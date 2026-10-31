package com.clipcaptions.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/** A downloadable file for auto-detect mode. */
data class WhisperFile(val label: String, val fileName: String, val url: String)

/** The speed setting shown in the app. */
enum class WhisperSpeed(val key: String, val label: String) {
    AUTOMATIC("auto", "Automatic (suits this phone)"),
    FASTER("fast", "Faster"),
    MORE_ACCURATE("accurate", "More accurate"),
    ;

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: AUTOMATIC
    }
}

/** What to download, which model to start with, and which to drop to if the phone can't keep up. */
data class WhisperPlan(val files: List<WhisperFile>, val start: WhisperFile, val fallback: WhisperFile?)

object WhisperModels {

    private const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    /** About 31 MB. Quick enough for older and budget phones. */
    val fast = WhisperFile("fast speech model", "ggml-tiny-q5_1.bin", MODEL_URL + "ggml-tiny-q5_1.bin")

    /** About 57 MB. Noticeably more accurate; needs a reasonably recent phone. */
    val accurate = WhisperFile("accurate speech model", "ggml-base-q5_1.bin", MODEL_URL + "ggml-base-q5_1.bin")

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
        WhisperSpeed.FASTER -> WhisperPlan(listOf(fast, speechDetector), fast, null)
        WhisperSpeed.MORE_ACCURATE -> WhisperPlan(listOf(accurate, speechDetector), accurate, null)
        WhisperSpeed.AUTOMATIC ->
            if (DeviceProfile.canRunAccurateModel(context)) {
                WhisperPlan(listOf(accurate, fast, speechDetector), accurate, fast)
            } else {
                WhisperPlan(listOf(fast, speechDetector), fast, null)
            }
    }
}

object DeviceProfile {

    /** Recent phones with enough memory and cores start on the accurate model. */
    fun canRunAccurateModel(context: Context): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        val ramGb = memory.totalMem / (1024.0 * 1024.0 * 1024.0)
        val cores = Runtime.getRuntime().availableProcessors()
        val is64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        return !manager.isLowRamDevice && is64Bit && cores >= 6 && ramGb >= 3.5
    }

    /**
     * Half the cores, at most 4. More threads than free cores makes Whisper dramatically slower,
     * because its threads wait for each other while the video player is using the rest.
     */
    fun threads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
}
