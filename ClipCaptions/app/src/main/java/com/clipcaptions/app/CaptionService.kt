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
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioPlaybackConfiguration
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
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Captures the sound other apps are playing (the same audio that goes to the speaker or to
 * headphones such as AirPods), turns speech into text on the phone, translates it and shows
 * it in the floating caption box. Nothing is recorded or saved.
 *
 * Three modes, all free and on the phone:
 * - Google: the phone's own Google speech recognition writes the words as they're spoken.
 *   Falls back to Auto if Google can't run.
 * - Auto: Whisper detects the language of each video (captions a few seconds behind).
 * - Live: Vosk writes the chosen language word by word (for phones without Google's recogniser).
 *
 * In all of them, moving on to another video clears the captions and starts afresh.
 */
class CaptionService : Service() {

    companion object {
        const val ACTION_START = "com.clipcaptions.app.START"
        const val ACTION_STOP = "com.clipcaptions.app.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_MODE = "mode"
        const val EXTRA_TARGET = "target"
        // Translation codes of the languages you watch: their translators are warmed up before the first video.
        const val EXTRA_WATCHED = "watched"

        // Live mode
        const val EXTRA_MODEL_PATH = "modelPath"
        const val EXTRA_SOURCE = "source"

        // Auto mode: speech models in order of preference, then the speech detector.
        const val EXTRA_WHISPER_MODELS = "whisperModels"
        const val EXTRA_VAD_PATH = "vadPath"
        const val EXTRA_AUTOMATIC_SPEED = "automaticSpeed"

        // Google mode: the language to listen for, and (Android 14+) others to switch to automatically.
        const val EXTRA_GOOGLE_LANGUAGE = "googleLanguage"
        const val EXTRA_GOOGLE_SWITCH = "googleSwitch"

        // Whisper's smallest model, used to spot each video's language quickly (Google with several
        // languages, and Whisper mode).
        const val EXTRA_SPOTTER_MODEL = "spotterModel"

        // Whisper mode: the languages you watch (Whisper codes), to choose between when spotting.
        // Empty means any language.
        const val EXTRA_WATCHED_WHISPER = "watchedWhisper"

        // Speech collected before spotting a video's language: enough to be reliable, short enough to be quick.
        private const val SPOT_SAMPLES = 16000 * 18 / 10 // 1.8 seconds

        const val MODE_AUTO = "auto"
        const val MODE_LIVE = "live"
        const val MODE_GOOGLE = "google"

        private const val CHANNEL_ID = "captions"
        private const val NOTIFICATION_ID = 7
        private const val SAMPLE_RATE = 16000
        private const val MAX_WORDS_PER_LINE = 16
        private const val SILENCE_WARNING_MS = 15_000L
        private const val SOUND_LEVEL = 400

        // After a switch to another video, skip this much sound: it can still hold the old video.
        private const val SWITCH_SKIP_SAMPLES = SAMPLE_RATE / 4

        private val englishNoise = setOf("the", "a", "huh", "uh", "um", "hmm")
        private val playerId = Regex("""piid:(\d+)""")

        private const val NO_SOUND_HINT =
            "No sound is coming through. Play a video with the sound on. " +
                "If one is playing and this message stays, that app may block sound capture."

        @Volatile
        var isRunning = false

        // Where the last session's speed readout is kept for the app's main screen.
        const val SPEED_PREFS = "speed"
    }

    /** A piece of speech for Whisper, tagged with the video it came from. */
    private class Chunk(val samples: FloatArray, val video: Int)

    /** What Whisper made of a chunk. */
    private class WhisperResult(val text: String, val language: String, val translatedByWhisper: Boolean, val video: Int)

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var overlay: CaptionOverlay? = null
    private var captureThread: Thread? = null
    private var whisperThread: Thread? = null

    @Volatile
    private var keepRunning = false
    private var autoMode = false
    private var googleMode = false
    private var targetCode = "en"

    // Google mode
    @Volatile
    private var google: GoogleCaptioner? = null
    @Volatile
    private var engineFailed = false
    private var fallbackRequest: WhisperEngine.Request? = null
    private var googleLanguage = ""
    private var liveTranslator: Translator? = null
    // Long sentences are shown as short lines, each translated once (see LineSplitter).
    private val splitter = LineSplitter()
    private var liveText = ""
    private var liveLanguage = ""
    // The live translation on screen, and the words it's for, so a finished sentence can reuse it.
    private var liveTranslatedFrom = ""
    private var liveTranslation: String? = null
    private var pendingLiveAt = 0L

    // Google with several languages: the app spots each video's language and tells Google.
    @Volatile
    private var spotter: LanguageSpotter? = null
    private val spotterThread = Executors.newSingleThreadExecutor()
    private var googleTags: List<String> = emptyList()
    private var spotterPath: String? = null
    private var watchedWhisper: List<String> = emptyList()

    // Speed readout: when people started speaking (set by the capture thread), and the delays seen.
    private val speed = SpeedStats()
    @Volatile
    private var speechStartedAt = 0L
    @Volatile
    private var timeNextSpeech = true

    // Bumped every time you move on to another video; work from an older video is thrown away.
    private val video = AtomicInteger(0)
    private val switchLogic = VideoSwitchLogic()
    private var audioManager: AudioManager? = null

    // Live mode
    private var sourceCode = "en"
    private var translator: Translator? = null
    private var liveGeneration = 0
    private var liveInFlight = false
    private var pendingLive: String? = null

    // Auto mode
    private var lastLanguage = ""
    private val translators = HashMap<String, Translator>()
    // Languages whose translation is on the phone. Read by the Whisper thread too.
    private val readyLanguages: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    // Languages whose translation is downloading in the background right now.
    private val downloading = HashSet<String>()
    private val pendingChunks = ArrayBlockingQueue<Chunk>(1)
    private val droppedChunks = AtomicInteger(0)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            main.post { stopSelf() }
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            onPlayersChanged(configs)
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
        val mode = command.getStringExtra(EXTRA_MODE)
        val wantsAuto = mode == MODE_AUTO
        val wantsGoogle = mode == MODE_GOOGLE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val googleTag = command.getStringExtra(EXTRA_GOOGLE_LANGUAGE)
        val voskModel = command.getStringExtra(EXTRA_MODEL_PATH)
        val whisperModels = command.getStringArrayExtra(EXTRA_WHISPER_MODELS)?.toList().orEmpty()

        // Android requires the foreground notification before the sound capture can begin.
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), foregroundTypes())
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY // Already running.
        val modelReady = when {
            wantsGoogle -> !googleTag.isNullOrEmpty()
            wantsAuto -> whisperModels.isNotEmpty()
            else -> voskModel != null
        }
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
        spotterPath = command.getStringExtra(EXTRA_SPOTTER_MODEL)
        watchedWhisper = command.getStringArrayListExtra(EXTRA_WATCHED_WHISPER).orEmpty()
        loadTranslationPacks()
        val watched = command.getStringArrayListExtra(EXTRA_WATCHED).orEmpty()
        autoMode = wantsAuto

        if (wantsGoogle && googleTag != null) {
            googleMode = true
            googleLanguage = GoogleSpeech.codeOf(googleTag)
            // On-phone Whisper captions to fall back to, if their files are already on the phone.
            fallbackRequest = if (whisperModels.isNotEmpty()) {
                WhisperEngine.Request(
                    whisperModels,
                    command.getStringExtra(EXTRA_VAD_PATH),
                    command.getBooleanExtra(EXTRA_AUTOMATIC_SPEED, true),
                )
            } else {
                null
            }
            if (!showOverlay(googleLabel(googleLanguage))) return START_NOT_STICKY
            overlay?.setStatus("Listening… play a video")
            val switch = command.getStringArrayListExtra(EXTRA_GOOGLE_SWITCH).orEmpty()
            googleTags = switch
            // With several languages, spot each video's language here (a fraction of a second) rather
            // than wait for Google's own detection, which can take several seconds.
            val path = spotterPath
            val spotHere = switch.size > 1 && path != null && WhisperLib.available
            val captioner = GoogleCaptioner(this, googleTag, switch, googleEvents, googleDetects = !spotHere)
            google = captioner
            if (spotHere && path != null) {
                spotterThread.execute {
                    val loaded = LanguageSpotter.load(path, spotterThreads())
                    if (loaded == null) {
                        main.post { google?.letGoogleDetect() }
                    } else if (!keepRunning) {
                        loaded.close()
                    } else {
                        spotter = loaded
                    }
                }
            }
            keepRunning = true
            captioner.start()
            captureThread = Thread({ captureForGoogle(mp) }, "caption-capture").also { it.start() }
        } else if (wantsAuto) {
            if (!showOverlay(autoStartLabel())) return START_NOT_STICKY
            val request = WhisperEngine.Request(
                whisperModels,
                command.getStringExtra(EXTRA_VAD_PATH),
                command.getBooleanExtra(EXTRA_AUTOMATIC_SPEED, true),
            )
            keepRunning = true
            whisperThread = Thread({ whisperLoop(request) }, "caption-whisper").also { it.start() }
            captureThread = Thread({ captureForWhisper(mp) }, "caption-capture").also { it.start() }
        } else if (voskModel != null) {
            sourceCode = command.getStringExtra(EXTRA_SOURCE) ?: "en"
            translator = createTranslator(sourceCode, targetCode)
            liveTranslator = translator
            val label = if (translator != null) {
                "${Languages.nameFor(sourceCode)} → ${Languages.nameFor(targetCode)}".uppercase()
            } else {
                "LIVE CAPTIONS · ${Languages.nameFor(sourceCode).uppercase()}"
            }
            if (!showOverlay(label)) return START_NOT_STICKY
            keepRunning = true
            captureThread = Thread({ captureForVosk(mp, voskModel) }, "caption-capture").also { it.start() }
        }

        warmUpTranslators(watched)

        // Watch which media players start, to spot moves to another video.
        val am = getSystemService(AudioManager::class.java)
        am.registerAudioPlaybackCallback(playbackCallback, main)
        audioManager = am
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        keepRunning = false
        isRunning = false
        saveSpeed()
        audioManager?.unregisterAudioPlaybackCallback(playbackCallback)
        audioManager = null
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
        google?.stop()
        google = null
        // The spotter is freed on its own thread, after any spotting under way.
        spotterThread.execute {
            spotter?.close()
            spotter = null
        }
        spotterThread.shutdown()
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
        val box = CaptionOverlay(
            this,
            label,
            onClose = { stopSelf() },
            onScreenTouched = { switchLogic.onTouch(SystemClock.elapsedRealtime()) },
        )
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

    // ---- Spotting a move to another video (main thread) ----

    private fun onPlayersChanged(configs: List<AudioPlaybackConfiguration>) {
        val players = HashSet<Int>()
        for (config in configs) {
            val usage = config.audioAttributes.usage
            if (usage != AudioAttributes.USAGE_MEDIA &&
                usage != AudioAttributes.USAGE_GAME &&
                usage != AudioAttributes.USAGE_UNKNOWN
            ) {
                continue
            }
            // Android doesn't offer the player's id directly, but includes it in the description.
            val id = playerId.find(config.toString())?.groupValues?.get(1)?.toIntOrNull() ?: config.hashCode()
            players.add(id)
        }
        if (switchLogic.onPlayersChanged(players, SystemClock.elapsedRealtime())) onNewVideo()
    }

    private fun onNewVideo() {
        val box = overlay ?: return
        // Google first, so nothing from the old video's session can arrive after the captions clear.
        google?.newVideo()
        video.incrementAndGet()
        box.clearCaptions()
        // Time how long the new video's first words take to appear.
        speechStartedAt = 0L
        timeNextSpeech = true
        if (googleMode) {
            liveGeneration++
            pendingLive = null
            splitter.reset()
            liveText = ""
            forgetLiveTranslation()
        } else if (autoMode) {
            lastLanguage = ""
            pendingChunks.clear()
            box.setLabel(autoStartLabel())
        } else {
            liveGeneration++
            pendingLive = null
        }
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

    // ---- Google mode: the phone's own speech recognition ----

    private val googleEvents = object : GoogleCaptioner.Events {
        override fun onPartial(text: String, languageTag: String) {
            if (googleMode) onGooglePartial(text, GoogleSpeech.codeOf(languageTag))
        }

        override fun onFinal(text: String, languageTag: String) {
            if (googleMode) onGoogleFinal(text, GoogleSpeech.codeOf(languageTag))
        }

        override fun onProblem(message: String) {
            onEngineProblem(message)
        }

        override fun onSessionStarted() {
            if (googleMode) onGoogleSessionStarted()
        }
    }

    private fun captureForGoogle(mp: MediaProjection) {
        var record: AudioRecord? = null
        try {
            val audio = openPlaybackCapture(mp)
            record = audio
            audio.startRecording()
            val silence = SilenceWatcher()
            // 0.05 s blocks: the less sound waits here, the sooner Google hears it.
            val buffer = ByteArray(1600)

            var quietMs = 1000L
            val spot = SpotCollector()

            // No pause after a swipe: the old video's leftover words are dropped by the recogniser
            // side instead, so the new video's first words aren't cut off.
            while (keepRunning && !engineFailed) {
                val read = audio.read(buffer, 0, buffer.size)
                if (read < 0) error("sound capture stopped (code $read)")
                if (read == 0) continue
                val loud = peakLevel(buffer, read) > SOUND_LEVEL
                silence.onAudio(loud)
                google?.feed(buffer, read)
                if (spotter != null) spot.add(buffer, read, loud)

                // For the speed readout: sound starting after a pause (or after a swipe) is when
                // people start speaking; the clock stops when their first words appear.
                if (loud) {
                    if ((quietMs >= 600 || timeNextSpeech) && speechStartedAt == 0L) {
                        speechStartedAt = SystemClock.elapsedRealtime()
                        timeNextSpeech = false
                    }
                    quietMs = 0
                } else {
                    quietMs += read * 1000L / (SAMPLE_RATE * 2)
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
            if (engineFailed) main.post { startOnPhoneFallback() }
        }
    }

    /**
     * Capture thread: collects the start of each video's speech, then spots its language on the
     * spotter's thread. Once per video.
     */
    private inner class SpotCollector {
        private val samples = FloatArray(SPOT_SAMPLES)
        private var length = 0
        private var forVideo = -1
        private var done = false

        fun add(buffer: ByteArray, read: Int, loud: Boolean) {
            val now = video.get()
            if (now != forVideo) {
                forVideo = now
                length = 0
                done = false
            }
            if (done || (length == 0 && !loud)) return // Wait for the sound to start.
            var i = 0
            while (i + 1 < read && length < samples.size) {
                val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                samples[length++] = sample / 32768f
                i += 2
            }
            if (length < samples.size) return
            done = true
            val speech = samples.copyOf()
            val videoNumber = forVideo
            try {
                spotterThread.execute { spotGoogleLanguage(speech, videoNumber) }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                // Captions are stopping.
            }
        }
    }

    /** Spotter thread: which of your languages this video is in. */
    private fun spotGoogleLanguage(speech: FloatArray, videoNumber: Int) {
        val sp = spotter ?: return
        if (videoNumber != video.get()) return
        val allowed = googleTags.map { LanguagePick.whisperCode(GoogleSpeech.codeOf(it)) }
        val guess = sp.detect(speech, allowed, othersIfClear = false) ?: return
        main.post { onGoogleLanguageSpotted(guess, videoNumber) }
    }

    /** Main thread: if the video isn't in the language Google is listening for, switch straight away. */
    private fun onGoogleLanguageSpotted(guess: LanguageGuess, videoNumber: Int) {
        if (!googleMode || videoNumber != video.get() || guess.confidence < 0.6f) return
        val captioner = google ?: return
        val tag = googleTags.firstOrNull { LanguagePick.whisperCode(GoogleSpeech.codeOf(it)) == guess.code } ?: return
        if (tag == captioner.language) return
        // The words shown so far were heard in the wrong language: start this video again.
        captioner.switchTo(tag)
        overlay?.clearCaptions()
        splitter.reset()
        liveGeneration++
        pendingLive = null
        liveText = ""
        forgetLiveTranslation()
        updateGoogleLabel(GoogleSpeech.codeOf(tag))
    }

    /** Spotting shares the phone with the video and Google, so it uses at most two cores. */
    private fun spotterThreads() = DeviceProfile.threads().coerceAtMost(2)

    private fun onGooglePartial(text: String, language: String) {
        val box = overlay ?: return
        noteWordsAppeared()
        updateGoogleLabel(language)
        val split = splitter.onPartial(text)
        if (split.finished.isNotEmpty()) {
            // Someone's talking without pausing: the start of the sentence becomes its own line,
            // translated once, so translating doesn't get slower as the sentence grows.
            liveGeneration++
            pendingLive = null
            forgetLiveTranslation()
            // A burst of words at once (the recogniser catching up) is old news by the time it
            // arrives: show only the newest line rather than flood the box.
            showLine(box, split.finished.last(), language)
        }
        showGoogleLive(box, split.live, language)
    }

    /** The words still being spoken, translated as they come in. */
    private fun showGoogleLive(box: CaptionOverlay, text: String, language: String) {
        liveText = text
        liveLanguage = language
        if (text.isEmpty() || language == targetCode) {
            box.setLive(text, false)
            return
        }
        val tr = translatorFor(language)
        if (tr == null || language !in readyLanguages) {
            // No translator for this language, or its translation is still downloading in the background:
            // show the words as heard rather than wait.
            box.setLive(text, false)
            if (tr != null) prepareTranslation(language, tr)
            return
        }
        box.setLive(text, true)
        pendingLive = text
        pendingLiveAt = SystemClock.elapsedRealtime()
        liveTranslator = tr
        translateLive(tr)
    }

    private fun onGoogleFinal(text: String, language: String) {
        val box = overlay ?: return
        noteWordsAppeared()
        updateGoogleLabel(language)
        finishGoogleSentence(box, splitter.onFinal(text), language)
    }

    /**
     * Shows the end of a sentence as a line. If the live translation on screen is for the same words,
     * it's used as it is; otherwise it stays up until the new translation is ready, so there's no gap.
     */
    private fun finishGoogleSentence(box: CaptionOverlay, rest: String, language: String) {
        val shown = liveTranslation
        val exact = if (shown != null && sameWords(rest, liveTranslatedFrom)) shown else null
        liveGeneration++
        pendingLive = null
        liveText = ""
        forgetLiveTranslation()
        if (rest.isEmpty()) {
            box.setLive("", false)
            return
        }
        showLine(box, rest, language, exact, shown)
    }

    /** Google began a new session. If the last one stopped mid-sentence, keep what was on screen. */
    private fun onGoogleSessionStarted() {
        val box = overlay
        val unfinished = liveText
        splitter.reset()
        if (box != null && unfinished.isNotEmpty()) finishGoogleSentence(box, unfinished, liveLanguage)
    }

    private fun forgetLiveTranslation() {
        liveTranslatedFrom = ""
        liveTranslation = null
    }

    /** The same words, ignoring capitals and punctuation (a finished sentence usually adds both). */
    private fun sameWords(a: String, b: String): Boolean {
        fun plain(t: String) = t.lowercase().replace(Regex("[\\p{P}\\p{S}]"), "").split(Regex("\\s+")).filter { it.isNotEmpty() }
        return plain(a) == plain(b)
    }

    /** Speed readout: words have appeared, so stop the clock started when people began speaking. */
    private fun noteWordsAppeared() {
        val started = speechStartedAt
        if (started == 0L) return
        speechStartedAt = 0L
        speed.addWords(SystemClock.elapsedRealtime() - started)
    }

    /** Keeps this session's typical delays for the app's main screen. */
    private fun saveSpeed() {
        val words = speed.wordsMedian() ?: return
        getSharedPreferences(SPEED_PREFS, MODE_PRIVATE).edit()
            .putString("mode", if (googleMode) MODE_GOOGLE else if (autoMode) MODE_AUTO else MODE_LIVE)
            .putLong("words_ms", words)
            .putLong("translation_ms", speed.translationMedian() ?: -1L)
            .putInt("samples", speed.samples())
            .apply()
    }

    private fun updateGoogleLabel(language: String) {
        if (language.isNotEmpty() && language != googleLanguage) {
            googleLanguage = language
            overlay?.setLabel(googleLabel(language))
        }
    }

    private fun googleLabel(language: String): String = captionLabel(language, "google")

    /** "SPANISH → ENGLISH · GOOGLE", noting when that translation is still downloading. */
    private fun captionLabel(language: String, engine: String): String {
        val heard = Languages.nameFor(language)
        val label = when {
            language == targetCode -> "$heard · $engine"
            language in downloading -> "$heard → ${Languages.nameFor(targetCode)} · translation downloading"
            else -> "$heard → ${Languages.nameFor(targetCode)} · $engine"
        }
        return label.uppercase()
    }

    /** Main thread: updates the box's label after a translation starts or finishes downloading. */
    private fun refreshLabel() {
        val box = overlay ?: return
        when {
            googleMode && googleLanguage.isNotEmpty() -> box.setLabel(googleLabel(googleLanguage))
            autoMode && lastLanguage.isNotEmpty() -> box.setLabel(autoLabel(lastLanguage))
        }
    }

    /** Main thread: the on-phone Google engine can't continue, so switch to Whisper if it's available. */
    private fun onEngineProblem(message: String) {
        if (engineFailed) return
        engineFailed = true
        google?.stop()
        google = null
        val box = overlay ?: return
        if (fallbackRequest != null && WhisperLib.available) {
            box.flashHint("$message Using Whisper on this phone instead.")
        } else {
            box.setHint("$message Choose another option in Clip Captions.")
        }
    }

    /** Main thread, after the Google engine's sound capture has stopped. */
    private fun startOnPhoneFallback() {
        val request = fallbackRequest ?: return
        val mp = projection ?: return
        if (!keepRunning || !WhisperLib.available) return
        googleMode = false
        autoMode = true
        overlay?.let {
            it.clearCaptions()
            it.setLabel(autoStartLabel())
            it.setStatus("Loading the speech model…")
        }
        whisperThread = Thread({ whisperLoop(request) }, "caption-whisper").also { it.start() }
        captureThread = Thread({ captureForWhisper(mp) }, "caption-capture").also { it.start() }
    }

    // ---- Auto mode: Whisper ----

    private fun captureForWhisper(mp: MediaProjection) {
        var record: AudioRecord? = null
        try {
            val audio = openPlaybackCapture(mp)
            record = audio
            audio.startRecording()
            val silence = SilenceWatcher()
            // Short chunks, and a shorter first one per video: captions come sooner and a few words at a
            // time, instead of a long wait and then a wall of text.
            val chunker = SpeechChunker(
                sampleRate = SAMPLE_RATE,
                minChunkMs = 1200,
                maxChunkMs = 3500,
                loudLevel = SOUND_LEVEL,
                firstMaxChunkMs = 2000,
            )
            val buffer = ShortArray(SAMPLE_RATE / 10) // 0.1 s
            var currentVideo = video.get()
            var skip = 0

            while (keepRunning) {
                val read = audio.read(buffer, 0, buffer.size)
                if (read < 0) error("sound capture stopped (code $read)")
                if (read == 0) continue

                var peak = 0
                for (i in 0 until read) {
                    val level = abs(buffer[i].toInt())
                    if (level > peak) peak = level
                }
                silence.onAudio(peak > SOUND_LEVEL)

                val nowVideo = video.get()
                if (nowVideo != currentVideo) {
                    // Another video: drop the old one's sound, including the moment of the switch.
                    currentVideo = nowVideo
                    chunker.newVideo()
                    skip = SWITCH_SKIP_SAMPLES
                }
                if (skip > 0) {
                    skip -= read
                    continue
                }
                for (samples in chunker.add(buffer, read)) submitChunk(Chunk(samples, currentVideo))
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
    private fun submitChunk(chunk: Chunk) {
        if (!pendingChunks.offer(chunk)) {
            pendingChunks.poll()
            droppedChunks.incrementAndGet()
            pendingChunks.offer(chunk)
        }
    }

    private fun whisperLoop(request: WhisperEngine.Request) {
        // Usually already loaded while Android's screen-share question was on screen.
        val session = WhisperEngine.take(this, request)
        if (session == null) {
            main.post {
                overlay?.setHint(
                    "Auto-detect couldn't start on this phone. Open Clip Captions and choose the language yourself instead.",
                )
            }
            return
        }
        if (!keepRunning) {
            session.close()
            return
        }
        main.post { overlay?.setStatus("Listening… play a video") }

        val threads = DeviceProfile.threads()
        // Each video's language is spotted with the small model on a short window, so the main model
        // only writes the words (detecting with the main model costs about as much again).
        val path = spotterPath
        val wsSpotter = when {
            path == null -> null
            path == request.models.getOrNull(session.level) -> LanguageSpotter.sharing(session.handle, threads)
            else -> LanguageSpotter.load(path, threads)
        }
        // Choose between the languages you watch, and the one you read.
        val allowed = if (watchedWhisper.isEmpty()) emptyList() else (watchedWhisper + LanguagePick.whisperCode(targetCode)).distinct()
        val languageLock = LanguageLock()
        var lockVideo = -1
        var slowScore = 0
        var previousText = ""
        try {
            while (keepRunning) {
                val chunk = pendingChunks.poll(250, TimeUnit.MILLISECONDS) ?: continue
                if (chunk.video != video.get()) continue // From a video you've already moved on from.
                if (chunk.video != lockVideo) {
                    lockVideo = chunk.video
                    languageLock.reset()
                    previousText = ""
                }

                val started = SystemClock.elapsedRealtime()
                if (session.vad != 0L && !WhisperLib.nativeHasSpeech(session.vad, chunk.samples)) {
                    slowScore = (slowScore - 1).coerceAtLeast(0)
                    continue
                }

                // Once a video's language is settled, Whisper is told it rather than detecting it every chunk.
                // Until then, the small model spots it for each chunk.
                var spotted: LanguageGuess? = null
                val language = languageLock.locked ?: wsSpotter?.detect(chunk.samples, allowed, othersIfClear = true)?.also {
                    spotted = it
                    languageLock.record(it.code, it.confidence)
                }?.code
                // Whisper writes the words in their own language and ML Kit translates them, which is more
                // accurate with these model sizes. Whisper translates into English itself for languages ML Kit
                // can't translate, and for ones whose translation is still downloading, so nothing waits for it.
                val whisperTranslates = language != null && targetCode == "en" && language != "en" &&
                    (TranslateLanguage.fromLanguageTag(language) == null || language !in readyLanguages)
                val bytes = WhisperLib.nativeTranscribe(
                    session.handle,
                    chunk.samples,
                    threads,
                    whisperTranslates,
                    language ?: "auto",
                    audioContextFor(chunk.samples.size),
                )
                val elapsed = SystemClock.elapsedRealtime() - started
                if (!keepRunning) break

                val heard = language ?: WhisperLib.nativeLanguage(session.handle)
                val confidence = spotted?.confidence ?: WhisperLib.nativeLanguageConfidence()
                val text = if (bytes == null) "" else WhisperText.clean(String(bytes, Charsets.UTF_8))
                if (text.isNotEmpty() && language == null) languageLock.record(heard, confidence)
                if (text.isNotEmpty() && text != previousText) {
                    previousText = text
                    val result = WhisperResult(text, heard, whisperTranslates, chunk.video)
                    main.post { onAutoText(result) }
                }

                // If captions are lagging, step down to a smaller, faster model.
                val audioMs = chunk.samples.size * 1000L / SAMPLE_RATE
                val dropped = droppedChunks.getAndSet(0)
                slowScore = when {
                    dropped > 0 -> slowScore + 2
                    elapsed > audioMs * 65 / 100 -> slowScore + 1
                    else -> (slowScore - 1).coerceAtLeast(0)
                }
                if (slowScore >= 4 && session.stepDown()) {
                    slowScore = 0
                    main.post { overlay?.flashHint("Switched to a faster speech model so captions keep up.") }
                }
            }
        } catch (e: Exception) {
            reportStop(e)
        } finally {
            wsSpotter?.close()
            session.close()
        }
    }

    /**
     * How much of Whisper's 30-second window to process for a chunk: the chunk plus a margin. Short
     * chunks are then much quicker than with a fixed window.
     */
    private fun audioContextFor(samples: Int): Int {
        val frames = samples / 320 + 128 // 50 frames per second, plus a margin
        val rounded = (frames + 63) / 64 * 64
        return rounded.coerceIn(256, WhisperEngine.AUDIO_CONTEXT)
    }

    /** Main thread: shows what Whisper heard, translating it when needed. */
    private fun onAutoText(result: WhisperResult) {
        if (result.video != video.get()) return
        val box = overlay ?: return
        val language = result.language
        if (language.isNotEmpty() && language != lastLanguage) {
            lastLanguage = language
            box.setLabel(autoLabel(language))
        }

        val text = result.text
        if (result.translatedByWhisper || language.isEmpty()) {
            box.addLine(text, false)
            // Whisper stood in because this language's translation isn't on the phone: fetch it quietly.
            if (result.translatedByWhisper) translatorFor(language)?.let { prepareTranslation(language, it) }
            return
        }
        showLine(box, text, language)
    }

    /**
     * Shows a finished sentence heard in [language], translated into the caption language when
     * needed (Google and Auto modes).
     */
    private fun showLine(
        box: CaptionOverlay,
        text: String,
        language: String,
        exact: String? = null,
        provisional: String? = null,
    ) {
        if (language == targetCode) {
            box.addLine(text, false)
            return
        }
        val tr = translatorFor(language)
        if (tr == null) {
            // A language the translator doesn't cover: show the words as heard.
            box.addLine(text, false)
            return
        }
        if (language in readyLanguages) {
            val id = box.addLine(text, true)
            if (exact != null) {
                // Already translated while it was being spoken.
                box.setTranslation(id, exact)
                return
            }
            if (provisional != null) box.setTranslation(id, provisional)
            val askedAt = SystemClock.elapsedRealtime()
            tr.translate(text)
                .addOnSuccessListener { translated ->
                    if (provisional == null) speed.addTranslation(SystemClock.elapsedRealtime() - askedAt)
                    overlay?.setTranslation(id, translated)
                }
                .addOnFailureListener { if (provisional == null) overlay?.setTranslation(id, text) }
            return
        }
        // A language you didn't tick, heard for the first time: show the words as heard straight away,
        // and fetch its translation in the background. Nothing waits for the download.
        box.addLine(text, false)
        prepareTranslation(language, tr)
    }

    /**
     * Downloads a language's translation in the background, once. Captions carry on meanwhile; the
     * box's label says it's on its way, rather than a message covering the words.
     */
    private fun prepareTranslation(language: String, tr: Translator) {
        if (language in readyLanguages || !downloading.add(language)) return
        refreshLabel()
        tr.downloadModelIfNeeded()
            .addOnSuccessListener {
                downloading.remove(language)
                markTranslationReady(language)
                warmUp(tr)
                refreshLabel()
            }
            .addOnFailureListener {
                // No connection, say: try again the next time this language is heard.
                downloading.remove(language)
                refreshLabel()
            }
    }

    /**
     * The first translation after starting is slow while the phone loads the language into memory.
     * Doing that now, for the languages you watch, means the first caption is translated at full speed.
     */
    private fun warmUpTranslators(watched: List<String>) {
        if (!googleMode && !autoMode) {
            translator?.let { if (sourceCode in readyLanguages || sourceCode == "en") warmUp(it) }
            return
        }
        for (language in watched.distinct()) {
            if (language == targetCode || language !in readyLanguages) continue
            translatorFor(language)?.let { warmUp(it) }
        }
    }

    private fun warmUp(tr: Translator) {
        tr.translate("Hello").addOnFailureListener { }
    }

    /**
     * Translation packs already on the phone are ready straight away, so captions don't wait or
     * say "first time only" on every start. The list is remembered, then checked with ML Kit.
     */
    private fun loadTranslationPacks() {
        addReadyPacks(TranslationPacks.known(this))
        TranslationPacks.refresh(this) { codes -> addReadyPacks(codes) }
    }

    private fun addReadyPacks(codes: Set<String>) {
        // A language is ready when both its pack and the caption language's pack are on the phone.
        // English is built in.
        if (targetCode == "en" || targetCode in codes) {
            readyLanguages.addAll(codes)
            readyLanguages.add("en")
        }
    }

    private fun markTranslationReady(language: String) {
        readyLanguages.add(language)
        TranslationPacks.remember(this, listOf(language, targetCode))
    }

    private fun translatorFor(language: String): Translator? {
        translators[language]?.let { return it }
        val created = createTranslator(language, targetCode) ?: return null
        translators[language] = created
        return created
    }

    private fun autoStartLabel() = "AUTO-DETECT → ${Languages.nameFor(targetCode).uppercase()}"

    private fun autoLabel(language: String): String = captionLabel(language, "auto")

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
            var currentVideo = video.get()
            var skip = 0

            while (keepRunning) {
                val read = audio.read(buffer, 0, buffer.size)
                if (read < 0) error("sound capture stopped (code $read)")
                if (read == 0) continue
                silence.onAudio(peakLevel(buffer, read) > SOUND_LEVEL)

                val nowVideo = video.get()
                if (nowVideo != currentVideo) {
                    // Another video: forget the half-heard sentence from the old one.
                    currentVideo = nowVideo
                    rec.reset()
                    lastPartial = ""
                    skip = SWITCH_SKIP_SAMPLES * 2 // bytes
                }
                if (skip > 0) {
                    skip -= read
                    continue
                }

                if (rec.acceptWaveForm(buffer, read)) {
                    lastPartial = ""
                    deliverFinal(JSONObject(rec.result).optString("text"), currentVideo)
                } else {
                    val partial = JSONObject(rec.partialResult).optString("partial")
                    if (partial != lastPartial) {
                        lastPartial = partial
                        val words = partial.split(' ').count { it.isNotBlank() }
                        if (words >= MAX_WORDS_PER_LINE) {
                            // Long runs without a pause (fast talkers, music): break the line here.
                            lastPartial = ""
                            deliverFinal(JSONObject(rec.finalResult).optString("text"), currentVideo)
                        } else {
                            val forVideo = currentVideo
                            main.post { if (forVideo == video.get()) onPartial(partial) }
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

    private fun deliverFinal(raw: String, forVideo: Int) {
        val text = tidy(raw)
        main.post {
            if (forVideo != video.get()) return@post
            if (text.isEmpty()) onPartial("") else onFinal(text)
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
        val askedAt = pendingLiveAt
        tr.translate(text).addOnCompleteListener { task ->
            liveInFlight = false
            if (generation == liveGeneration && task.isSuccessful) {
                liveTranslatedFrom = text
                liveTranslation = task.result
                if (askedAt != 0L) speed.addTranslation(SystemClock.elapsedRealtime() - askedAt)
                overlay?.setLiveTranslation(task.result)
            }
            if (liveTranslator === tr) translateLive(tr)
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
