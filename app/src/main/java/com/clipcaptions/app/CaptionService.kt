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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Captures the sound other apps are playing (the same audio that goes to the speaker or to
 * headphones such as AirPods), turns speech into text on the phone, translates it and shows
 * it in the floating caption box. Nothing is recorded or saved.
 *
 * Two modes:
 * - Auto: Whisper detects the language of each chunk of speech (a few seconds behind).
 * - Live: Vosk writes the chosen language word by word.
 */
class CaptionService : Service() {

    companion object {
        const val ACTION_START = "com.clipcaptions.app.START"
        const val ACTION_STOP = "com.clipcaptions.app.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_MODE = "mode"
        const val EXTRA_TARGET = "target"

        // Live mode
        const val EXTRA_MODEL_PATH = "modelPath"
        const val EXTRA_SOURCE = "source"

        // Auto mode
        const val EXTRA_WHISPER_MODEL = "whisperModel"
        const val EXTRA_WHISPER_FALLBACK = "whisperFallback"
        const val EXTRA_VAD_PATH = "vadPath"

        const val MODE_AUTO = "auto"
        const val MODE_LIVE = "live"

        private const val CHANNEL_ID = "captions"
        private const val NOTIFICATION_ID = 7
        private const val SAMPLE_RATE = 16000
        private const val MAX_WORDS_PER_LINE = 16
        private const val SILENCE_WARNING_MS = 15_000L
        private const val SOUND_LEVEL = 400
        private val englishNoise = setOf("the", "a", "huh", "uh", "um", "hmm")

        // Auto mode sends speech to Whisper in chunks, cut at a pause where possible.
        private const val MIN_CHUNK_MS = 2500
        private const val MAX_CHUNK_MS = 7000
        private const val PAUSE_MS = 400

        private const val NO_SOUND_HINT =
            "No sound is coming through. Play a video with the sound on. " +
                "If one is playing and this message stays, that app may block sound capture."

        @Volatile
        var isRunning = false
    }

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var overlay: CaptionOverlay? = null
    private var captureThread: Thread? = null
    private var whisperThread: Thread? = null

    @Volatile
    private var keepRunning = false
    private var targetCode = "en"

    // Live mode
    private var sourceCode = "en"
    private var translator: Translator? = null
    private var liveGeneration = 0
    private var liveInFlight = false
    private var pendingLive: String? = null

    // Auto mode
    @Volatile
    private var translateToEnglish = false
    private var lastLanguage = ""
    private val translators = HashMap<String, Translator>()
    private val readyLanguages = HashSet<String>()
    private val pendingChunks = ArrayBlockingQueue<FloatArray>(1)
    private val droppedChunks = AtomicInteger(0)

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
        val autoMode = command.getStringExtra(EXTRA_MODE) == MODE_AUTO
        val voskModel = command.getStringExtra(EXTRA_MODEL_PATH)
        val whisperModel = command.getStringExtra(EXTRA_WHISPER_MODEL)

        // Android requires the foreground notification before the sound capture can begin.
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), foregroundTypes())
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY // Already running.
        val modelReady = if (autoMode) whisperModel != null else voskModel != null
        if (resultCode != Activity.RESULT_OK || data == null || !modelReady) {
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
        targetCode = command.getStringExtra(EXTRA_TARGET) ?: "en"

        if (autoMode && whisperModel != null) {
            translateToEnglish = targetCode == "en"
            val label = "AUTO-DETECT → ${Languages.nameFor(targetCode).uppercase()}"
            if (!showOverlay(label)) return START_NOT_STICKY
            val fallback = command.getStringExtra(EXTRA_WHISPER_FALLBACK)
            val vad = command.getStringExtra(EXTRA_VAD_PATH)
            keepRunning = true
            whisperThread = Thread({ whisperLoop(whisperModel, fallback, vad) }, "caption-whisper").also { it.start() }
            captureThread = Thread({ captureForWhisper(mp) }, "caption-capture").also { it.start() }
        } else if (voskModel != null) {
            sourceCode = command.getStringExtra(EXTRA_SOURCE) ?: "en"
            translator = createTranslator(sourceCode, targetCode)
            val label = if (translator != null) {
                "${Languages.nameFor(sourceCode)} → ${Languages.nameFor(targetCode)}".uppercase()
            } else {
                "LIVE CAPTIONS · ${Languages.nameFor(sourceCode).uppercase()}"
            }
            if (!showOverlay(label)) return START_NOT_STICKY
            keepRunning = true
            captureThread = Thread({ captureForVosk(mp, voskModel) }, "caption-capture").also { it.start() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        keepRunning = false
        isRunning = false
        try {
            captureThread?.join(400)
        } catch (ignored: InterruptedException) {
        }
        captureThread = null
        // The Whisper thread finishes its current chunk and frees the model by itself.
        whisperThread = null
        projection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        projection = null
        overlay?.remove()
        overlay = null
        translator?.close()
        translator = null
        translators.values.forEach { it.close() }
        translators.clear()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun showOverlay(label: String): Boolean {
        val box = CaptionOverlay(this, label) { stopSelf() }
        return try {
            box.show()
            overlay = box
            box.setStatus("Loading the speech model…")
            true
        } catch (e: Exception) {
            // "Display over other apps" was switched off.
            stopSelf()
            false
        }
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

    // ---- Sound capture (capture thread) ----

    /** Media, game and untagged sound from other apps. Calls and notifications are left out. */
    @SuppressLint("MissingPermission") // RECORD_AUDIO is granted before the service starts.
    private fun openPlaybackCapture(mp: MediaProjection): AudioRecord {
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
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audio = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer, SAMPLE_RATE * 2))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            error("Android didn't share the phone's sound.")
        }
        return audio
    }

    /** Warns in the caption box when no sound has come through for a while. */
    private inner class SilenceWatcher {
        private var lastSoundAt = SystemClock.elapsedRealtime()
        private var warned = false

        fun onAudio(loud: Boolean) {
            val now = SystemClock.elapsedRealtime()
            if (loud) {
                lastSoundAt = now
                if (warned) {
                    warned = false
                    main.post { overlay?.setHint(null) }
                }
            } else if (!warned && now - lastSoundAt > SILENCE_WARNING_MS) {
                warned = true
                main.post { overlay?.setHint(NO_SOUND_HINT) }
            }
        }
    }

    private fun reportStop(e: Exception) {
        if (!keepRunning) return
        val reason = e.message ?: e.javaClass.simpleName
        main.post { overlay?.setHint("Captions stopped: $reason") }
    }

    // ---- Auto mode: Whisper ----

    private fun captureForWhisper(mp: MediaProjection) {
        var record: AudioRecord? = null
        try {
            val audio = openPlaybackCapture(mp)
            record = audio
            audio.startRecording()
            val silence = SilenceWatcher()
            val buffer = ShortArray(SAMPLE_RATE / 10) // 0.1 s
            val maxSamples = SAMPLE_RATE * MAX_CHUNK_MS / 1000
            val chunk = FloatArray(maxSamples)
            var length = 0
            var heardSound = false
            var quietMs = 0

            while (keepRunning) {
                val read = audio.read(buffer, 0, buffer.size)
                if (read < 0) error("sound capture stopped (code $read)")
                if (read == 0) continue

                var peak = 0
                for (i in 0 until read) {
                    val level = abs(buffer[i].toInt())
                    if (level > peak) peak = level
                }
                val loud = peak > SOUND_LEVEL
                silence.onAudio(loud)

                if (length + read > maxSamples) {
                    if (heardSound) submitChunk(chunk.copyOf(length))
                    length = 0
                    heardSound = false
                    quietMs = 0
                }
                for (i in 0 until read) chunk[length + i] = buffer[i] / 32768f
                length += read
                if (loud) {
                    heardSound = true
                    quietMs = 0
                } else {
                    quietMs += read * 1000 / SAMPLE_RATE
                }

                val chunkMs = length * 1000 / SAMPLE_RATE
                if (!heardSound) {
                    // Only quiet so far: keep the last moment, so speech that starts softly isn't clipped.
                    if (chunkMs >= 1000) {
                        val keep = SAMPLE_RATE * 3 / 10
                        System.arraycopy(chunk, length - keep, chunk, 0, keep)
                        length = keep
                    }
                } else if ((chunkMs >= MIN_CHUNK_MS && quietMs >= PAUSE_MS) || chunkMs >= MAX_CHUNK_MS) {
                    submitChunk(chunk.copyOf(length))
                    length = 0
                    heardSound = false
                    quietMs = 0
                }
            }
        } catch (e: Exception) {
            reportStop(e)
        } finally {
            try {
                record?.stop()
            } catch (ignored: Exception) {
            }
            record?.release()
        }
    }

    /** Hands a chunk to Whisper. If Whisper is still busy with an older one, the older one is dropped. */
    private fun submitChunk(samples: FloatArray) {
        if (!pendingChunks.offer(samples)) {
            pendingChunks.poll()
            droppedChunks.incrementAndGet()
            pendingChunks.offer(samples)
        }
    }

    private fun whisperLoop(modelPath: String, fallbackPath: String?, vadPath: String?) {
        var handle = WhisperLib.nativeInit(modelPath)
        var onFallback = false
        if (handle == 0L && fallbackPath != null) {
            handle = WhisperLib.nativeInit(fallbackPath)
            onFallback = true
        }
        if (handle == 0L) {
            main.post {
                overlay?.setHint(
                    "Auto-detect couldn't start on this phone. Open Clip Captions and choose the language yourself instead.",
                )
            }
            return
        }
        // Without the speech detector Whisper still works; it just also runs on music-only chunks.
        val vad = if (vadPath != null) WhisperLib.nativeInitVad(vadPath) else 0L
        main.post { overlay?.setStatus("Listening… play a video") }

        val threads = DeviceProfile.threads()
        var slowScore = 0
        var previousText = ""
        try {
            while (keepRunning) {
                val chunk = pendingChunks.poll(250, TimeUnit.MILLISECONDS) ?: continue
                val started = SystemClock.elapsedRealtime()
                if (vad != 0L && !WhisperLib.nativeHasSpeech(vad, chunk)) {
                    slowScore = (slowScore - 1).coerceAtLeast(0)
                    continue
                }
                val bytes = WhisperLib.nativeTranscribe(handle, chunk, threads, translateToEnglish)
                val elapsed = SystemClock.elapsedRealtime() - started
                if (!keepRunning) break

                val language = WhisperLib.nativeLanguage(handle)
                val text = if (bytes == null) "" else WhisperText.clean(String(bytes, Charsets.UTF_8))
                if (text.isNotEmpty() && text != previousText) {
                    previousText = text
                    main.post { onAutoText(text, language) }
                }

                // If this phone can't keep up, move to the faster model.
                val audioMs = chunk.size * 1000L / SAMPLE_RATE
                val dropped = droppedChunks.getAndSet(0)
                slowScore = when {
                    dropped > 0 -> slowScore + 2
                    elapsed > audioMs * 85 / 100 -> slowScore + 1
                    else -> (slowScore - 1).coerceAtLeast(0)
                }
                if (slowScore >= 4 && !onFallback && fallbackPath != null) {
                    val faster = WhisperLib.nativeInit(fallbackPath)
                    if (faster != 0L) {
                        WhisperLib.nativeFree(handle)
                        handle = faster
                        onFallback = true
                        slowScore = 0
                        main.post { overlay?.flashHint("Switched to the faster speech model so captions keep up.") }
                    }
                }
            }
        } catch (e: Exception) {
            reportStop(e)
        } finally {
            WhisperLib.nativeFree(handle)
            if (vad != 0L) WhisperLib.nativeFreeVad(vad)
        }
    }

    /** Main thread: shows a chunk of Whisper text, translating it when needed. */
    private fun onAutoText(text: String, language: String) {
        val box = overlay ?: return
        if (language.isNotEmpty() && language != lastLanguage) {
            lastLanguage = language
            box.setLabel(autoLabel(language))
        }

        // Whisper already translated into English, or it's already in the chosen language.
        if (translateToEnglish || language.isEmpty() || language == targetCode) {
            box.addLine(text, false)
            return
        }
        val tr = translatorFor(language)
        if (tr == null) {
            // A language the translator doesn't cover: show the words as heard.
            box.addLine(text, false)
            return
        }
        if (language !in readyLanguages) {
            box.flashHint("Getting ${Languages.nameFor(language)} translation ready (first time only)…")
        }
        val id = box.addLine(text, true)
        tr.downloadModelIfNeeded()
            .onSuccessTask { tr.translate(text) }
            .addOnSuccessListener { translated ->
                readyLanguages.add(language)
                overlay?.setTranslation(id, translated)
            }
            .addOnFailureListener { overlay?.setTranslation(id, text) }
    }

    private fun translatorFor(language: String): Translator? {
        translators[language]?.let { return it }
        val created = createTranslator(language, targetCode) ?: return null
        translators[language] = created
        return created
    }

    private fun autoLabel(language: String): String {
        val heard = Languages.nameFor(language)
        val label = if (language == targetCode) "$heard · auto" else "$heard → ${Languages.nameFor(targetCode)} · auto"
        return label.uppercase()
    }

    // ---- Live mode: Vosk ----

    private fun captureForVosk(mp: MediaProjection, modelPath: String) {
        var model: Model? = null
        var recognizer: Recognizer? = null
        var record: AudioRecord? = null
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val speechModel = Model(modelPath)
            model = speechModel
            val rec = Recognizer(speechModel, SAMPLE_RATE.toFloat())
            recognizer = rec

            val audio = openPlaybackCapture(mp)
            record = audio
            audio.startRecording()
            main.post { overlay?.setStatus("Listening… play a video") }

            val silence = SilenceWatcher()
            val buffer = ByteArray(3200) // 0.1 s of 16 kHz, 16-bit mono sound.
            var lastPartial = ""

            while (keepRunning) {
                val read = audio.read(buffer, 0, buffer.size)
                if (read < 0) error("sound capture stopped (code $read)")
                if (read == 0) continue
                silence.onAudio(peakLevel(buffer, read) > SOUND_LEVEL)

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
            reportStop(e)
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

    private fun onPartial(raw: String) {
        val box = overlay ?: return
        val text = tidy(raw)
        if (text.isEmpty()) {
            liveGeneration++
            pendingLive = null
            box.setLive("", false)
            return
        }
        val tr = translator
        if (tr == null) {
            box.setLive(text, false)
            return
        }
        box.setLive(text, true)
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
        val tr = translator
        val id = box.addLine(text, tr != null)
        if (tr == null) return
        tr.translate(text)
            .addOnSuccessListener { translated -> overlay?.setTranslation(id, translated) }
            .addOnFailureListener { overlay?.setTranslation(id, text) }
    }
}
