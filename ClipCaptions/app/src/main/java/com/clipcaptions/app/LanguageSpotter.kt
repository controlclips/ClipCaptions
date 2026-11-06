package com.clipcaptions.app

/**
 * Works out which language a couple of seconds of speech are in, using Whisper's smallest model
 * on a short window. Takes a fraction of a second, so captions can start in the right language
 * instead of waiting for slower detection (Google's, or a bigger Whisper model's).
 * Not thread-safe: use each spotter from one thread at a time.
 */
class LanguageSpotter private constructor(
    private val handle: Long,
    private val ownsModel: Boolean,
    private val threads: Int,
    // The speech detector, so music and noise are left out before spotting (0 if there isn't one).
    private val vad: Long = 0L,
) {
    companion object {
        /** About 3.8 seconds of Whisper's 30-second window: plenty for spotting, and quick. */
        const val AUDIO_CONTEXT = 192

        /** Speech beyond this is ignored (the window above holds this much). */
        const val MAX_SAMPLES = 16000 * 37 / 10

        @Volatile
        private var codes: Array<String>? = null

        /** Loads the small model (and the speech detector, if given) for spotting. Blocking. Null if it couldn't be loaded. */
        fun load(modelPath: String, threads: Int, vadPath: String? = null): LanguageSpotter? {
            if (!WhisperLib.available) return null
            val handle = WhisperLib.nativeInit(modelPath)
            if (handle == 0L) return null
            if (!WhisperLib.nativePrepareDetect(handle, threads, AUDIO_CONTEXT)) {
                WhisperLib.nativeFree(handle)
                return null
            }
            val vad = vadPath?.let { WhisperLib.nativeInitVad(it) } ?: 0L
            return LanguageSpotter(handle, ownsModel = true, threads = threads, vad = vad)
        }

        /** Spots with a model that's already loaded for captions (when it's the small model anyway). */
        fun sharing(handle: Long, threads: Int): LanguageSpotter? =
            if (handle == 0L) null else LanguageSpotter(handle, ownsModel = false, threads = threads)
    }

    /**
     * The language of [samples] (16 kHz mono, -1..1). [allowed] limits the choice to these Whisper
     * codes, unless another language is clearly being spoken and [othersIfClear] is true. Empty = any.
     */
    fun detect(samples: FloatArray, allowed: Collection<String>, othersIfClear: Boolean): LanguageGuess? {
        val audio = if (samples.size > MAX_SAMPLES) samples.copyOf(MAX_SAMPLES) else samples
        val probs = WhisperLib.nativeDetectLanguage(handle, audio, threads) ?: return null
        val names = codes ?: WhisperLib.nativeLanguageCodes()?.also { codes = it } ?: return null
        return LanguagePick.pick(names, probs, allowed, if (othersIfClear) 0.8f else 2f)
    }

    /** The speech in [samples] with music and noise between words left out, or null with no speech detector. */
    fun speechOnly(samples: FloatArray): FloatArray? =
        if (vad == 0L) null else WhisperLib.nativeSpeechOnly(vad, samples)

    fun close() {
        if (ownsModel) WhisperLib.nativeFree(handle)
        if (vad != 0L) WhisperLib.nativeFreeVad(vad)
    }
}
