package com.clipcaptions.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/** A downloadable file for auto-detect mode. */
data class WhisperFile(val label: String, val fileName: String, val url: String)

/** The accuracy setting shown in the app. */
enum class WhisperSpeed(val key: String, val label: String) {
    AUTOMATIC("auto", "Automatic (best this phone can keep up with)"),
    FAST("fast", "Fast (31 MB)"),
    BETTER("accurate", "Better (57 MB)"),
    BEST("best", "Best (181 MB, recent phones only)"),
    ;

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: AUTOMATIC
    }
}

/**
 * What to download, and the speech models to use in order: the first one to start with, then
 * smaller ones to step down to if the phone can't keep up.
 */
data class WhisperPlan(val models: List<WhisperFile>) {
    // The small model is always included: it spots each video's language quickly.
    val downloads: List<WhisperFile> get() = (models + WhisperModels.fast + WhisperModels.speechDetector).distinct()
}

object WhisperModels {

    private const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    /** About 31 MB. Quick enough for older and budget phones. */
    val fast = WhisperFile("fast speech model", "ggml-tiny-q5_1.bin", MODEL_URL + "ggml-tiny-q5_1.bin")

    /** About 57 MB. Noticeably more accurate; needs a reasonably recent phone. */
    val better = WhisperFile("better speech model", "ggml-base-q5_1.bin", MODEL_URL + "ggml-base-q5_1.bin")

    /** About 181 MB. The most accurate; needs a powerful phone. */
    val best = WhisperFile("best speech model", "ggml-small-q5_1.bin", MODEL_URL + "ggml-small-q5_1.bin")

    /** Under 1 MB. Tells speech apart from music and noise, so Whisper only works on speech. */
    val speechDetector = WhisperFile(
        "speech detector",
        "ggml-silero-v5.1.2.bin",
        "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin",
    )

    fun file(context: Context, item: WhisperFile) = File(File(context.filesDir, "whisper"), item.fileName)

    fun isDownloaded(context: Context, item: WhisperFile): Boolean {
        val f = file(context, item)
        return f.isFile && f.length() > 0
    }

    /** Downloads the file if needed and returns it. Blocking: call it off the main thread. */
    fun ensure(context: Context, item: WhisperFile, onProgress: (Int) -> Unit): File {
        val f = file(context, item)
        if (!isDownloaded(context, item)) Downloads.download(item.url, f, onProgress)
        return f
    }

    fun plan(context: Context, speed: WhisperSpeed): WhisperPlan = when (speed) {
        WhisperSpeed.FAST -> WhisperPlan(listOf(fast))
        WhisperSpeed.BETTER -> WhisperPlan(listOf(better, fast))
        WhisperSpeed.BEST -> WhisperPlan(listOf(best, better))
        WhisperSpeed.AUTOMATIC -> when (DeviceProfile.tier(context)) {
            DeviceTier.HIGH -> WhisperPlan(listOf(best, better))
            DeviceTier.MID -> WhisperPlan(listOf(better, fast))
            DeviceTier.LOW -> WhisperPlan(listOf(fast))
        }
    }
}

enum class DeviceTier { LOW, MID, HIGH }

object DeviceProfile {

    fun tier(context: Context): DeviceTier {
        val manager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        val ramGb = memory.totalMem / (1024.0 * 1024.0 * 1024.0)
        val cores = Runtime.getRuntime().availableProcessors()
        val is64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()

        if (manager.isLowRamDevice || !is64Bit || cores < 6 || ramGb < 3.5) return DeviceTier.LOW
        // Flagship-class: plenty of memory, a fast top core, and the faster Whisper build.
        val fastCpu = WhisperLib.isFastBuild && CpuFeatures.maxFrequencyGhz() >= 2.8
        if (ramGb >= 7.0 && cores >= 8 && fastCpu) return DeviceTier.HIGH
        return DeviceTier.MID
    }

    /**
     * Half the cores, at most 4. More threads than free cores makes Whisper dramatically slower,
     * because its threads wait for each other while the video player is using the rest.
     */
    fun threads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
}
