package com.clipcaptions.app

import android.os.Build
import java.io.File

/** Kotlin side of the whisper.cpp bridge in src/main/cpp/whisper_jni.c. */
object WhisperLib {

    /** True when the faster build (for phones with dot-product instructions) is the one loaded. */
    val isFastBuild: Boolean

    /** False if this phone can't load Whisper at all; the app then offers word-by-word mode only. */
    val available: Boolean

    init {
        val fast = CpuFeatures.supportsFastBuild() && tryLoad("clipwhisper_fast")
        isFastBuild = fast
        available = fast || tryLoad("clipwhisper")
    }

    private fun tryLoad(name: String): Boolean = try {
        System.loadLibrary(name)
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
     * [language] is a code such as "es" when the language is known, or "auto" to detect it.
     * [audioContext] is how much of Whisper's 30-second window to process (1500 = all).
     * Returns UTF-8 bytes (empty if no speech was found) or null on failure.
     */
    @JvmStatic
    external fun nativeTranscribe(
        handle: Long,
        samples: FloatArray,
        threads: Int,
        translate: Boolean,
        language: String,
        audioContext: Int,
    ): ByteArray?

    /** The language of the last chunk, such as "es", or "" if unknown. */
    @JvmStatic
    external fun nativeLanguage(handle: Long): String

    /** How sure Whisper was (0–1) of the language it detected in the last chunk, or -1 if it didn't detect one. */
    @JvmStatic
    external fun nativeLanguageConfidence(): Float

    /** Loads the speech detector. Returns 0 if it couldn't be loaded. */
    @JvmStatic
    external fun nativeInitVad(modelPath: String): Long

    @JvmStatic
    external fun nativeFreeVad(handle: Long)

    /** True if the chunk holds enough speech to be worth sending to Whisper (music and noise don't count). */
    @JvmStatic
    external fun nativeHasSpeech(handle: Long, samples: FloatArray): Boolean

    /** Sets the short window used for language spotting. Call once after loading a model for spotting. */
    @JvmStatic
    external fun nativePrepareDetect(handle: Long, threads: Int, audioContext: Int): Boolean

    /** How likely each language is for this speech, indexed like [nativeLanguageCodes]. Null on failure. */
    @JvmStatic
    external fun nativeDetectLanguage(handle: Long, samples: FloatArray, threads: Int): FloatArray?

    /** Whisper's language codes ("en", "es", …), in the order [nativeDetectLanguage] uses. */
    @JvmStatic
    external fun nativeLanguageCodes(): Array<String>?
}

object CpuFeatures {

    /**
     * The faster build needs ARMv8.2 dot-product and half-precision maths on every core.
     * Phones without them would crash on those instructions, so anything unclear counts as no.
     */
    fun supportsFastBuild(): Boolean {
        if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") return false
        val featureLines = try {
            File("/proc/cpuinfo").readLines().filter { it.trimStart().startsWith("Features") }
        } catch (e: Exception) {
            return false
        }
        if (featureLines.isEmpty()) return false
        val needed = listOf("asimddp", "fphp", "asimdhp")
        return featureLines.all { line ->
            val features = line.substringAfter(':').trim().split(Regex("\\s+")).toSet()
            needed.all { it in features }
        }
    }

    /** The fastest core's top speed in GHz, or 0 if the phone doesn't say. */
    fun maxFrequencyGhz(): Double {
        var maxKhz = 0L
        val cores = Runtime.getRuntime().availableProcessors()
        for (i in 0 until cores) {
            try {
                val khz = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
                if (khz > maxKhz) maxKhz = khz
            } catch (ignored: Exception) {
            }
        }
        return maxKhz / 1_000_000.0
    }
}
