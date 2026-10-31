package com.clipcaptions.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.math.abs

/**
 * Captures the sound other apps are playing (the same audio that goes to the speaker or to
 * headphones such as AirPods), turns speech into text on the phone, translates it and shows
 * it in the floating caption box. Nothing is recorded or saved.
 */
class CaptionService : Service() {

    companion object {
        const val ACTION_START = "com.clipcaptions.app.START"
        const val ACTION_STOP = "com.clipcaptions.app.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_MODEL_PATH = "modelPath"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_TARGET = "target"

        private const val CHANNEL_ID = "captions"
        private const val NOTIFICATION_ID = 7
        private const val SAMPLE_RATE = 16000
        private const val MAX_WORDS_PER_LINE = 16
        private const val SILENCE_WARNING_MS = 15_000L
        private const val SOUND_LEVEL = 400
        private val englishNoise = setOf("the", "a", "huh", "uh", "um", "hmm")

        @Volatile
        var isRunning = false
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var overlay: CaptionOverlay? = null
    private var translator: Translator? = null
    private var worker: Thread? = null

    @Volatile
    private var keepRunning = false
    private var sourceCode = "en"

    // Translation of the sentence still being spoken.
    private var liveGeneration = 0
    private var liveInFlight = false
    private var pendingLive: String? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            main.post { stopSelf() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent
        if (command == null || command.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = command.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(command, EXTRA_RESULT_DATA, Intent::class.java)
        val modelPath = command.getStringExtra(EXTRA_MODEL_PATH)

        // Android requires the foreground notification before the sound capture can begin.
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), foregroundTypes())
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY // Already running.
        if (resultCode != Activity.RESULT_OK || data == null || modelPath == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mp: MediaProjection? = try {
            getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            null
        }
        if (mp == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        mp.registerCallback(projectionCallback, main)
        projection = mp
        isRunning = true

        sourceCode = command.getStringExtra(EXTRA_SOURCE) ?: "en"
        val targetCode = command.getStringExtra(EXTRA_TARGET) ?: "en"
        translator = createTranslator(sourceCode, targetCode)

        val label = if (translator != null) {
            "${Languages.nameFor(sourceCode)} → ${Languages.nameFor(targetCode)}".uppercase()
        } else {
            "LIVE CAPTIONS · ${Languages.nameFor(sourceCode).uppercase()}"
        }
        val box = CaptionOverlay(this, translator != null, label) { stopSelf() }
        try {
            box.show()
        } catch (e: Exception) {
            // "Display over other apps" was switched off.
            stopSelf()
            return START_NOT_STICKY
        }
        overlay = box
        box.setStatus("Loading the speech model…")

        keepRunning = true
        worker = Thread({ captureLoop(mp, modelPath) }, "caption-capture").also { it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        keepRunning = false
        isRunning = false
        try {
            worker?.join(400)
        } catch (ignored: InterruptedException) {
        }
        worker = null
        projection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        projection = null
        overlay?.remove()
        overlay = null
        translator?.close()
        translator = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun foregroundTypes(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Live captions", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_caption)
            .setContentTitle("Captions are on")
            .setContentText("Reading the sound your phone plays. Nothing is recorded.")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createTranslator(source: String, target: String): Translator? {
        if (source == target) return null
        val from = TranslateLanguage.fromLanguageTag(source) ?: return null
        val to = TranslateLanguage.fromLanguageTag(target) ?: return null
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(from)
            .setTargetLanguage(to)
            .build()
        return Translation.getClient(options)
    }

    // ---- Runs on the capture thread ----

    @SuppressLint("MissingPermission") // RECORD_AUDIO is granted before the service starts.
    private fun captureLoop(mp: MediaProjection, modelPath: String) {
        var model: Model? = null
        var recognizer: Recognizer? = null
        var record: AudioRecord? = null
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val speechModel = Model(modelPath)
            model = speechModel
            val rec = Recognizer(speechModel, SAMPLE_RATE.toFloat())
            recognizer = rec

            // Media, game and untagged sound from other apps. Calls and notifications are left out.
            val config = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val audio = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuffer, SAMPLE_RATE * 2))
                .setAudioPlaybackCaptureConfig(config)
                .build()
            record = audio
            if (audio.state != AudioRecord.STATE_INITIALIZED) error("Android didn't share the phone's sound.")
            audio.startRecording()
            main.post { overlay?.setStatus("Listening… play a video") }

            val buffer = ByteArray(3200) // 0.1 s of 16 kHz, 16-bit mono sound.
            var lastSoundAt = SystemClock.elapsedRealtime()
            var silenceWarned = false
            var lastPartial = ""

            while (keepRunning) {
                val read = audio.read(buffer, 0, buffer.size)
                if (read < 0) error("sound capture stopped (code $read)")
                if (read == 0) continue

                val now = SystemClock.elapsedRealtime()
                if (peakLevel(buffer, read) > SOUND_LEVEL) {
                    lastSoundAt = now
                    if (silenceWarned) {
                        silenceWarned = false
                        main.post { overlay?.setHint(null) }
                    }
                } else if (!silenceWarned && now - lastSoundAt > SILENCE_WARNING_MS) {
                    silenceWarned = true
                    main.post {
                        overlay?.setHint(
                            "No sound is coming through. Play a video with the sound on. " +
                                "If one is playing and this message stays, that app may block sound capture.",
                        )
                    }
                }

                if (rec.acceptWaveForm(buffer, read)) {
                    lastPartial = ""
                    deliverFinal(JSONObject(rec.result).optString("text"))
                } else {
                    val partial = JSONObject(rec.partialResult).optString("partial")
                    if (partial != lastPartial) {
                        lastPartial = partial
                        val words = partial.split(' ').count { it.isNotBlank() }
                        if (words >= MAX_WORDS_PER_LINE) {
                            // Long runs without a pause (fast talkers, music): break the line here.
                            lastPartial = ""
                            deliverFinal(JSONObject(rec.finalResult).optString("text"))
                        } else {
                            main.post { onPartial(partial) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (keepRunning) {
                val reason = e.message ?: e.javaClass.simpleName
                main.post { overlay?.setHint("Captions stopped: $reason") }
            }
        } finally {
            try {
                record?.stop()
            } catch (ignored: Exception) {
            }
            record?.release()
            recognizer?.close()
            model?.close()
        }
    }

    private fun deliverFinal(raw: String) {
        val text = tidy(raw)
        if (text.isEmpty()) {
            main.post { onPartial("") }
        } else {
            main.post { onFinal(text) }
        }
    }

    private fun peakLevel(buffer: ByteArray, length: Int): Int {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()
            val level = abs(sample)
            if (level > peak) peak = level
            i += 2
        }
        return peak
    }

    private fun tidy(raw: String): String {
        var text = raw.trim()
        if (sourceCode in Languages.writtenWithoutSpaces) text = text.replace(" ", "")
        // The small English model hears lone filler words in music and background noise.
        if (sourceCode == "en" && text.lowercase() in englishNoise) return ""
        return text
    }

    // ---- Runs on the main thread ----

    private fun onPartial(raw: String) {
        val box = overlay ?: return
        val text = tidy(raw)
        if (text.isEmpty()) {
            liveGeneration++
            pendingLive = null
            box.setLive("", null)
            return
        }
        val tr = translator
        if (tr == null) {
            box.setLive(text, null)
            return
        }
        box.setLiveOriginal(text)
        pendingLive = text
        translateLive(tr)
    }

    /** Translates the sentence in progress, one request at a time, always using the newest words. */
    private fun translateLive(tr: Translator) {
        if (liveInFlight) return
        val text = pendingLive ?: return
        pendingLive = null
        liveInFlight = true
        val generation = liveGeneration
        tr.translate(text).addOnCompleteListener { task ->
            liveInFlight = false
            if (generation == liveGeneration && task.isSuccessful) {
                overlay?.setLiveTranslation(task.result)
            }
            if (translator === tr) translateLive(tr)
        }
    }

    private fun onFinal(text: String) {
        val box = overlay ?: return
        liveGeneration++
        pendingLive = null
        val id = box.addLine(text)
        val tr = translator ?: return
        tr.translate(text)
            .addOnSuccessListener { translated -> overlay?.setTranslation(id, translated) }
            .addOnFailureListener { overlay?.setTranslation(id, text) }
    }
}
