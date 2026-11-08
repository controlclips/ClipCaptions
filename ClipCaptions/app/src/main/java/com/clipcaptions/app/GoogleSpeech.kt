package com.clipcaptions.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.system.Os
import androidx.annotation.RequiresApi
import java.io.IOException
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Google's speech recognition that's already on the phone (the on-device recogniser, the same
 * family as Live Caption). Free, runs on the phone, and writes words as they're spoken.
 * Needs Android 13 or newer, which is when apps became able to hand it sound other than the mic.
 */
object GoogleSpeech {

    private const val PREFS = "google_speech"
    private const val KEY_APP_SOUND = "reads_app_sound" // "yes" or "no", once checked on this phone
    private const val KEY_DOWNLOADED = "downloaded" // Languages the phone confirmed downloading.
    private const val KEY_REQUESTED = "requested" // Android 13: asked for, without confirmation.

    /** What the phone offers. Languages are IETF tags such as "en-GB". */
    sealed class Support {
        data class Ready(val installed: List<String>, val downloadable: List<String>) : Support()
        data class Unavailable(val reason: String) : Support()
    }

    fun displayName(tag: String): String = Locale.forLanguageTag(tag).getDisplayName(Locale.ENGLISH).ifEmpty { tag }

    /**
     * "en-GB" -> "en", matching the translator's language codes. Some recognisers name languages
     * differently (Mandarin "cmn", Norwegian Bokmål "nb", Filipino "fil"), which the translator
     * wouldn't recognise, so those captions were never translated.
     */
    fun codeOf(tag: String): String {
        val code = Locale.forLanguageTag(tag).language
        return when (code) {
            "iw" -> "he"
            "in" -> "id"
            "cmn", "yue", "wuu" -> "zh"
            "nb", "nn" -> "no"
            "fil" -> "tl"
            else -> code
        }
    }

    /** False once this phone's recogniser has been found to ignore app sound (it would use the mic). */
    fun readsAppSound(context: Context): Boolean? =
        when (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_APP_SOUND, null)) {
            "yes" -> true
            "no" -> false
            else -> null
        }

    internal fun recordAppSound(context: Context, works: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_APP_SOUND, if (works) "yes" else "no").apply()
    }

    /** Lets the phone be checked again (for example after a system update). */
    fun forgetAppSoundCheck(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_APP_SOUND).apply()
    }

    private fun tagSet(context: Context, key: String): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(key, emptySet()).orEmpty()

    private fun addTag(context: Context, key: String, tag: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = HashSet(prefs.getStringSet(key, emptySet()).orEmpty())
        if (all.add(tag)) prefs.edit().putStringSet(key, all).apply()
    }

    /** True if this language was already downloaded or asked for, so it isn't requested again. */
    fun alreadyFetched(context: Context, tag: String): Boolean =
        tag in tagSet(context, KEY_DOWNLOADED) || tag in tagSet(context, KEY_REQUESTED)

    /**
     * Main thread. Downloads Google's speech packs for [tags], one at a time, skipping any already
     * on the phone or already asked for. [progress] gets (tag, number done, total); [done] gets the
     * tags that couldn't be downloaded.
     */
    fun downloadAll(context: Context, tags: List<String>, progress: (String, Int, Int) -> Unit, done: (List<String>) -> Unit) {
        val needed = tags.distinct().filter { !alreadyFetched(context, it) }
        val failed = ArrayList<String>()
        fun next(index: Int) {
            if (index >= needed.size) {
                done(failed)
                return
            }
            val tag = needed[index]
            progress(tag, index, needed.size)
            download(context, tag) { problem ->
                if (problem != null) failed += tag
                next(index + 1)
            }
        }
        next(0)
    }

    /** Main thread. Finds out whether Google's on-phone recogniser can be used, and with which languages. */
    fun checkSupport(context: Context, done: (Support) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            done(Support.Unavailable("It needs Android 13 or newer."))
            return
        }
        checkSupport33(context, done)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun checkSupport33(context: Context, done: (Support) -> Unit) {
        if (readsAppSound(context) == false) {
            done(Support.Unavailable("This phone's speech recognition can't listen to app sound."))
            return
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            done(Support.Unavailable("This phone doesn't have the speech recognition it needs."))
            return
        }
        val recognizer = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (e: Exception) {
            done(Support.Unavailable("The phone's speech recognition couldn't start."))
            return
        }
        val main = Handler(Looper.getMainLooper())
        var answered = false
        fun finish(result: Support) {
            if (answered) return
            answered = true
            recognizer.destroy()
            done(result)
        }
        // Some phones never answer the language check; don't wait forever.
        main.postDelayed({ finish(Support.Ready(emptyList(), emptyList())) }, 6000L)
        try {
            recognizer.checkRecognitionSupport(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
                context.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        // Some phones are slow to list a pack they've just downloaded; trust their confirmation.
                        val supported = support.supportedOnDeviceLanguages + support.pendingOnDeviceLanguages +
                            support.installedOnDeviceLanguages
                        val confirmed = tagSet(context, KEY_DOWNLOADED).filter { it in supported }
                        val installed = (support.installedOnDeviceLanguages + confirmed).distinct()
                        val downloadable = (support.supportedOnDeviceLanguages + support.pendingOnDeviceLanguages)
                            .distinct().filter { it !in installed }
                        finish(Support.Ready(installed, downloadable))
                    }

                    override fun onError(error: Int) {
                        finish(Support.Ready(emptyList(), emptyList()))
                    }
                },
            )
        } catch (e: Exception) {
            finish(Support.Ready(emptyList(), emptyList()))
        }
    }

    /**
     * Main thread. Asks the phone to download Google's speech pack for [tag]. [done] gets null on
     * success, or a reason it couldn't be done. On Android 13 the phone gives no progress, so
     * [done] is called straight away with null and the download carries on in the background.
     */
    fun download(context: Context, tag: String, done: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            done("Needs Android 13 or newer.")
            return
        }
        val recognizer = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (e: Exception) {
            done("Google's on-device speech recognition couldn't start.")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            recognizer.triggerModelDownload(
                intent,
                context.mainExecutor,
                object : ModelDownloadListener {
                    override fun onProgress(completedPercent: Int) {}
                    override fun onSuccess() {
                        recognizer.destroy()
                        addTag(context, KEY_DOWNLOADED, tag)
                        done(null)
                    }
                    override fun onScheduled() {
                        recognizer.destroy()
                        addTag(context, KEY_REQUESTED, tag) // Don't ask again; the phone has it queued.
                        done("The phone will download it when it's on Wi-Fi.")
                    }
                    override fun onError(error: Int) {
                        recognizer.destroy()
                        done("The phone couldn't download it (error $error).")
                    }
                },
            )
        } else {
            recognizer.triggerModelDownload(intent)
            recognizer.destroy()
            // Android 13 doesn't report back; remember it was asked for so it isn't asked again.
            addTag(context, KEY_REQUESTED, tag)
            done(null)
        }
    }
}

/**
 * Feeds the captured sound to Google's on-phone recogniser and reports what it hears.
 *
 * The sound goes through a pipe in a continuous ("segmented") session that lasts until the pipe is
 * closed. Swiping to another video doesn't restart it (that would cost up to a second): the box
 * clears and the old video's leftover words are dropped instead. A new session starts only after
 * something ends one.
 *
 * Safety check: if a recogniser can't take app sound, Android has it open the microphone instead.
 * So the app checks that the recogniser really is reading the pipe. If it isn't, the session is
 * stopped within a couple of seconds, nothing it heard is shown, and the phone is marked as not
 * supported.
 *
 * All methods except [feed] must be called on the main thread. Events arrive on the main thread.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class GoogleCaptioner(
    private val context: Context,
    /** The language to listen for, such as "es-ES". */
    private val languageTag: String,
    /** Android 14+: other downloaded languages to switch to automatically, or empty. */
    private val switchLanguages: List<String>,
    private val events: Events,
    /**
     * Whether Google itself detects which of [switchLanguages] is spoken. That's slow (often several
     * seconds), so the app spots the language itself when it can and calls [switchTo] instead.
     */
    private var googleDetects: Boolean = true,
) {

    interface Events {
        /** Words still being spoken (replace the previous partial text). */
        fun onPartial(text: String, languageTag: String)

        /** A finished sentence. */
        fun onFinal(text: String, languageTag: String)

        /** Google can't continue; [message] says why in plain words. */
        fun onProblem(message: String)

        /** A new listening session began, so the next words start a new sentence. */
        fun onSessionStarted() {}
    }

    companion object {
        private const val F_SETPIPE_SZ = 1031
        private const val F_GETPIPE_SZ = 1032
        private const val SMALL_PIPE = 4096
        private const val DEFAULT_PIPE = 65536
        private const val BLOCKED_LIMIT_MS = 2000L
        private const val CHECK_FOR_MS = 8000L

        // Sound kept while Google restarts between sentences (20 blocks of 0.05 s = 1 second),
        // then passed to the next session so the first words of the next sentence aren't lost.
        private const val GAP_BLOCKS = 20

        // The last 3 seconds of sound, so a switch to another language can replay the video's start.
        private const val RECENT_BLOCKS = 60

        // A new session may take this long to catch up on replayed sound; after that, at most
        // a second of sound waits, so captions can't drift behind the video.
        private const val CATCH_UP_MS = 4000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val leftover = LeftoverWords()
    private var recognizer: SpeechRecognizer? = null
    private var currentLanguage = languageTag
    private var stopped = false
    private var paused = false
    private var sessionNumber = 0

    @Volatile
    private var session: Session? = null

    // Sound that arrived while no session was listening, and the last few seconds of sound.
    // Both guarded by [gap].
    private val gap = ArrayDeque<ByteArray>()
    private var gapLimit = GAP_BLOCKS
    private val recent = ArrayDeque<ByteArray>()
    private var blocksSinceVideo = 0

    /** The language being listened for now, such as "es-ES". */
    val language: String get() = currentLanguage

    // Restart limits: a phone that keeps failing gets reported instead of retried for ever.
    private val failureTimes = ArrayDeque<Long>()
    private val quietRestartTimes = ArrayDeque<Long>()

    /** One recognition session: a pipe, and a thread that writes the sound into it. */
    private inner class Session(val number: Int) {
        val readSide: ParcelFileDescriptor
        private val writeSide: ParcelFileDescriptor
        val capacity: Int
        // Sound waiting for the recogniser (see [offer] for how much is kept).
        val queue = LinkedBlockingQueue<ByteArray>()
        val written = AtomicLong(0)
        val startedAt = SystemClock.elapsedRealtime()

        @Volatile
        var blockedSince = 0L

        @Volatile
        var verified = GoogleSpeech.readsAppSound(context) == true

        @Volatile
        var open = true

        var readSideClosed = false
        var ended = false
        private val writer: Thread

        init {
            val pipe = ParcelFileDescriptor.createPipe()
            readSide = pipe[0]
            writeSide = pipe[1]
            // A small pipe lets the safety check tell quickly whether the recogniser is reading it.
            capacity = try {
                Os.fcntlInt(writeSide.fileDescriptor, F_SETPIPE_SZ, SMALL_PIPE)
                Os.fcntlInt(writeSide.fileDescriptor, F_GETPIPE_SZ, 0)
            } catch (e: Exception) {
                DEFAULT_PIPE
            }
            writer = Thread({ writeLoop() }, "google-speech-writer").apply {
                isDaemon = true
                start()
            }
        }

        private fun writeLoop() {
            val out = ParcelFileDescriptor.AutoCloseOutputStream(writeSide)
            try {
                while (open) {
                    val block = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    blockedSince = SystemClock.elapsedRealtime()
                    out.write(block)
                    blockedSince = 0L
                    val total = written.addAndGet(block.size.toLong())
                    // More written than the pipe holds means the recogniser has been reading it.
                    if (!verified && total > capacity + 6400) {
                        verified = true
                        main.post { onVerified(number) }
                    }
                }
            } catch (e: IOException) {
                // The recogniser closed its end: the session is over.
            } finally {
                blockedSince = 0L
                try {
                    out.close()
                } catch (ignored: IOException) {
                }
            }
        }

        fun closeReadSide() {
            if (readSideClosed) return
            readSideClosed = true
            try {
                readSide.close()
            } catch (ignored: IOException) {
            }
        }

        /** Ends the sound; the recogniser then finishes the session. */
        fun close() {
            open = false
            queue.clear()
            closeReadSide()
        }
    }

    fun start() {
        recognizer = createRecognizer() ?: run {
            events.onProblem("Google's on-device speech recognition couldn't start.")
            return
        }
        startSession()
    }

    /** Any thread. A block of 16 kHz, 16-bit mono sound. */
    fun feed(bytes: ByteArray, length: Int) {
        if (stopped) return
        val block = bytes.copyOf(length)
        synchronized(gap) {
            recent.addLast(block)
            while (recent.size > RECENT_BLOCKS) recent.removeFirst()
            if (blocksSinceVideo < RECENT_BLOCKS) blocksSinceVideo++
            val s = session
            if (s != null && s.open) {
                offer(s, block)
            } else {
                // Between sessions: keep it, to hand to the next session.
                gap.addLast(block)
                while (gap.size > gapLimit) gap.removeFirst()
            }
        }
    }

    /** If the recogniser falls behind, drop the oldest sound rather than lag behind the video. */
    private fun offer(s: Session, block: ByteArray) {
        val catchingUp = SystemClock.elapsedRealtime() - s.startedAt < CATCH_UP_MS
        val limit = if (catchingUp) RECENT_BLOCKS + GAP_BLOCKS else GAP_BLOCKS
        while (s.queue.size >= limit) s.queue.poll()
        s.queue.offer(block)
    }

    /**
     * Main thread. Listen for another language from now on, starting again from the beginning of
     * this video (up to 3 seconds back) so its first words are recognised in the right language.
     */
    fun switchTo(tag: String) {
        if (stopped || paused || tag == currentLanguage) return
        currentLanguage = tag
        synchronized(gap) {
            val replay = recent.toList().takeLast(minOf(blocksSinceVideo, RECENT_BLOCKS))
            val s = session
            session = null
            if (s != null) {
                s.ended = true
                s.close()
            }
            gap.clear()
            gap.addAll(replay)
            gapLimit = RECENT_BLOCKS + GAP_BLOCKS
        }
        // A fresh recogniser, so nothing from the old language's session can arrive late.
        recognizer?.destroy()
        recognizer = createRecognizer()
        if (recognizer == null) {
            stopped = true
            events.onProblem("Google's on-device speech recognition couldn't start.")
            return
        }
        main.postDelayed({ if (!stopped && !paused && session == null) startSession() }, 30L)
    }

    /**
     * Main thread. Stops listening to save battery while nothing is playing. Sound keeps coming in
     * (the last second is kept), so [resume] doesn't miss the first words.
     */
    fun pause() {
        if (stopped || paused) return
        paused = true
        endSession(cancel = true)
        // A fresh recogniser on resume, so nothing from before the pause can arrive late.
        recognizer?.destroy()
        recognizer = null
    }

    /** Main thread. Sound is playing again: start listening straight away. */
    fun resume() {
        if (stopped || !paused) return
        paused = false
        recognizer = createRecognizer()
        if (recognizer == null) {
            stopped = true
            events.onProblem("Google's on-device speech recognition couldn't start.")
            return
        }
        if (session == null) startSession()
    }

    /** Main thread. The app couldn't spot languages itself after all: let Google detect them. */
    fun letGoogleDetect() {
        googleDetects = true
    }

    /** Another video: keep listening, but drop the words that belonged to the old one. */
    fun newVideo() {
        if (stopped) return
        leftover.cut()
        // Sound kept between sessions belongs to the old video.
        synchronized(gap) {
            gap.clear()
            blocksSinceVideo = 0
        }
    }

    fun stop() {
        stopped = true
        synchronized(gap) { gap.clear() }
        endSession(cancel = true)
        recognizer?.destroy()
        recognizer = null
    }

    private fun createRecognizer(): SpeechRecognizer? = try {
        SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { it.setRecognitionListener(listener) }
    } catch (e: Exception) {
        null
    }

    @SuppressLint("MissingPermission")
    private fun startSession() {
        if (stopped || paused) return
        val rec = recognizer ?: return
        val s = try {
            Session(++sessionNumber)
        } catch (e: Exception) {
            events.onProblem("Couldn't pass the sound to Google's speech recognition.")
            return
        }
        // Hand over the sound heard while no session was listening, in order, then go live.
        synchronized(gap) {
            while (gap.isNotEmpty()) offer(s, gap.removeFirst())
            gapLimit = GAP_BLOCKS
            session = s
        }
        leftover.reset()
        events.onSessionStarted()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Punctuation and capitals, so sentences can be translated whole (the translator does far
            // better with "¿Dónde está? Vamos." than with an unpunctuated run of words). Trailing
            // punctuation is left off words still being spoken, so a sentence ends only once the next
            // one has begun.
            putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_LATENCY)
            putExtra(RecognizerIntent.EXTRA_HIDE_PARTIAL_TRAILING_PUNCTUATION, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, s.readSide)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            // Keep listening until the sound is closed, reporting each sentence as it finishes.
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            if (googleDetects && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && switchLanguages.size > 1) {
                val allowed = ArrayList(switchLanguages)
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, allowed)
                // Captions must keep up with fast scrolling, so switch language as quickly as possible.
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_QUICK_RESPONSE)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, allowed)
            }
        }
        try {
            rec.startListening(intent)
        } catch (e: Exception) {
            endSession(cancel = false)
            restartAfterFailure()
            return
        }
        scheduleSafetyCheck(s)
    }

    /** Checks every second that the recogniser is reading the sound and not the microphone. */
    private fun scheduleSafetyCheck(s: Session) {
        main.postDelayed({
            if (session !== s || !s.open || stopped) return@postDelayed
            if (s.verified) return@postDelayed
            val blocked = s.blockedSince
            if (blocked != 0L && SystemClock.elapsedRealtime() - blocked > BLOCKED_LIMIT_MS) {
                // The pipe is full and nothing is reading it: the recogniser isn't using the app sound.
                GoogleSpeech.recordAppSound(context, false)
                stopped = true
                endSession(cancel = true)
                events.onProblem("This phone's Google speech recognition can't listen to app sound.")
                return@postDelayed
            }
            if (SystemClock.elapsedRealtime() - s.startedAt < CHECK_FOR_MS || s.written.get() == 0L) {
                scheduleSafetyCheck(s)
            }
        }, 1000L)
    }

    private fun onVerified(number: Int) {
        if (GoogleSpeech.readsAppSound(context) != true) GoogleSpeech.recordAppSound(context, true)
        if (session?.number == number) failureTimes.clear()
    }

    private fun endSession(cancel: Boolean) {
        val s = session ?: return
        session = null
        s.ended = true
        s.close()
        if (cancel) {
            try {
                recognizer?.cancel()
            } catch (ignored: Exception) {
            }
        }
    }

    /** The recogniser ended a session normally (or after a short silence); start the next one. */
    private fun restartQuietly() {
        if (stopped) return
        val now = SystemClock.elapsedRealtime()
        quietRestartTimes.addLast(now)
        while (quietRestartTimes.isNotEmpty() && now - quietRestartTimes.first() > 10_000L) quietRestartTimes.removeFirst()
        if (quietRestartTimes.size > 12) {
            stopped = true
            events.onProblem("Google's speech recognition keeps stopping on this phone.")
            return
        }
        // The sound in between is kept (see feed), so restarting quickly loses nothing.
        main.postDelayed({ if (!stopped && !paused && session == null) startSession() }, 50L)
    }

    /** Something went wrong; start again with a fresh recogniser, unless it keeps happening. */
    private fun restartAfterFailure() {
        if (stopped) return
        val now = SystemClock.elapsedRealtime()
        failureTimes.addLast(now)
        while (failureTimes.isNotEmpty() && now - failureTimes.first() > 30_000L) failureTimes.removeFirst()
        if (failureTimes.size >= 5) {
            stopped = true
            events.onProblem("Google's speech recognition keeps failing on this phone.")
            return
        }
        recognizer?.destroy()
        recognizer = createRecognizer()
        main.postDelayed({ if (!stopped && !paused && session == null) startSession() }, 400L)
    }

    private fun texts(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()

    private fun showable(): Boolean = session?.verified == true

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            // The recogniser has its copy of the pipe now. Closing ours means the sound
            // thread notices if the recogniser lets go of it.
            session?.closeReadSide()
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val heard = texts(partialResults)
            if (heard.isEmpty()) return
            val text = leftover.onPartial(heard)
            if (text.isNotEmpty() && showable()) events.onPartial(text, currentLanguage)
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            val heard = texts(segmentResults)
            if (heard.isEmpty()) {
                // The sentence ended with nothing to show; the next one starts clean.
                leftover.reset()
                return
            }
            val text = leftover.onFinal(heard)
            if (text.isNotEmpty() && showable()) events.onFinal(text, currentLanguage)
        }

        override fun onResults(results: Bundle?) {
            // Recognisers without continuous sessions end after each sentence.
            val heard = texts(results)
            val text = if (heard.isEmpty()) "" else leftover.onFinal(heard)
            if (heard.isEmpty()) leftover.reset()
            if (text.isNotEmpty() && showable()) events.onFinal(text, currentLanguage)
            if (session != null) {
                endSession(cancel = false)
                restartQuietly()
            }
        }

        override fun onEndOfSegmentedSession() {
            if (session != null) {
                endSession(cancel = false)
                restartQuietly()
            }
        }

        override fun onLanguageDetection(results: Bundle) {
            val detected = results.getString(SpeechRecognizer.DETECTED_LANGUAGE) ?: return
            val switched = results.getInt(SpeechRecognizer.LANGUAGE_SWITCH_RESULT, 0) ==
                SpeechRecognizer.LANGUAGE_SWITCH_RESULT_SUCCEEDED
            val confident = results.getInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL, 0) >=
                SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_CONFIDENT
            if (switched || (confident && detected in switchLanguages)) currentLanguage = detected
        }

        override fun onError(error: Int) {
            if (stopped || paused) return
            val hadSession = session != null
            endSession(cancel = false)
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restartQuietly()
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    main.postDelayed({ if (!stopped && !paused && session == null) startSession() }, 500L)
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    stopped = true
                    events.onProblem(
                        "${GoogleSpeech.displayName(currentLanguage)} isn't downloaded for Google speech on this phone. " +
                            "Download it in Clip Captions.",
                    )
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    stopped = true
                    events.onProblem("Android didn't let Google's speech recognition run here.")
                }
                else -> if (hadSession || error != SpeechRecognizer.ERROR_CLIENT) restartAfterFailure()
            }
        }
    }
}
