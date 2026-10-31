package com.clipcaptions.app

/** Kotlin side of the whisper.cpp bridge in src/main/cpp/whisper_jni.c. */
object WhisperLib {

    /** False if this phone can't load the Whisper library; the app then offers word-by-word mode only. */
    val available: Boolean = try {
        System.loadLibrary("clipwhisper")
        true
    } catch (e: Throwable) {
        false
    }

    /** Loads a model file. Returns 0 if it couldn't be loaded (for example, not enough memory). */
    @JvmStatic
    external fun nativeInit(modelPath: String): Long

    @JvmStatic
    external fun nativeFree(handle: Long)

    /**
     * Turns 16 kHz mono audio (-1..1) into text, or into English text when [translate] is true.
     * Returns UTF-8 bytes (empty if no speech was found) or null on failure.
     */
    @JvmStatic
    external fun nativeTranscribe(handle: Long, samples: FloatArray, threads: Int, translate: Boolean): ByteArray?

    /** The language detected in the last chunk, such as "es", or "" if unknown. */
    @JvmStatic
    external fun nativeLanguage(handle: Long): String

    /** Loads the speech detector. Returns 0 if it couldn't be loaded. */
    @JvmStatic
    external fun nativeInitVad(modelPath: String): Long

    @JvmStatic
    external fun nativeFreeVad(handle: Long)

    /** True if the chunk holds enough speech to be worth sending to Whisper (music and noise don't count). */
    @JvmStatic
    external fun nativeHasSpeech(handle: Long, samples: FloatArray): Boolean
}
