package com.clipcaptions.app

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import java.io.File
import java.util.concurrent.Executors

/**
 * Mozilla's offline translator (the one built into Firefox), run on the phone through a small
 * native library (translator/ in the project). Much better than ML Kit's packs, and just as private:
 * nothing leaves the phone.
 *
 * Two copies of the library ship for 64-bit phones: one using the newer CPUs' fast int8 maths
 * (i8mm), one that runs on every 64-bit phone. The right one is picked here, before loading,
 * because the fast one would crash a phone without i8mm.
 */
object MozTranslate {

    private const val TAG = "MozTranslate"

    /** True once the library is loaded. False on phones it can't run on: ML Kit is used there. */
    val available: Boolean by lazy { load() }

    /** Which maths the loaded library uses ("i8mm+neon64", "neon64", "avx2", …), for problem reports. */
    val backend: String by lazy {
        if (!available) "unavailable" else try { nativeBackend() } catch (t: Throwable) { "unknown" }
    }

    private fun load(): Boolean {
        val abi = (if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS)
            .firstOrNull() ?: return false
        val features = cpuFeatures()
        val library = when (abi) {
            "arm64-v8a" -> if (features.isNotEmpty() && features.all { "i8mm" in it }) "cctranslate_i8mm" else "cctranslate"
            "armeabi-v7a" -> if (features.any { "neon" in it || "asimd" in it }) "cctranslate" else null
            "x86_64" -> if (features.isNotEmpty() && features.all { "avx2" in it }) "cctranslate" else null
            else -> null
        } ?: return false
        return try {
            System.loadLibrary(library)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "couldn't load $library", t)
            false
        }
    }

    /** Each CPU core's feature words from /proc/cpuinfo ("Features" on ARM, "flags" on x86). */
    private fun cpuFeatures(): List<Set<String>> = try {
        File("/proc/cpuinfo").readLines()
            .filter { line ->
                val key = line.substringBefore(':').trim()
                key == "Features" || key == "flags"
            }
            .map { it.substringAfter(':').trim().split(Regex("\\s+")).toSet() }
    } catch (e: Exception) {
        emptyList()
    }

    // ---- Native calls (translator/src/lib.rs) ----

    /** Loads one language pair. Throws if the files are damaged. Returns a handle for the other calls. */
    @JvmStatic external fun nativeLoad(model: String, srcVocab: String, trgVocab: String, lex: String?): Long

    @JvmStatic external fun nativeTranslate(handle: Long, text: String): String?

    @JvmStatic external fun nativeFree(handle: Long)

    /** Unpacks a downloaded model file, checking its fingerprint. Returns null when it worked, else the problem. */
    @JvmStatic external fun nativeUnpack(input: String, output: String, sha256: String?): String?

    @JvmStatic external fun nativeBackend(): String
}

/**
 * The loaded language pairs, shared by the main screen (which loads them while Android's sharing
 * question is on screen) and the caption service. Everything native runs on one thread, in order,
 * so a pair is never used and freed at the same time.
 */
object MozEngines {

    private const val TAG = "MozEngines"

    private val thread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "translate").apply { priority = Thread.NORM_PRIORITY + 1 }
    }

    // "es-en" -> native handle. Only touched on [thread].
    private val loaded = HashMap<String, Long>()
    private val broken = HashSet<String>()

    /** Translates [text] through [legs] (one pair, or two through English). Result on the main thread. */
    fun translate(context: Context, legs: List<MozModels.Leg>, text: String): Task<String> {
        val app = context.applicationContext
        val result = TaskCompletionSource<String>()
        thread.execute {
            try {
                var current = text
                for (leg in legs) {
                    val handle = handleFor(app, leg)
                    if (handle == 0L) throw IllegalStateException("${leg.key} isn't usable")
                    current = MozTranslate.nativeTranslate(handle, current)
                        ?: throw IllegalStateException("no translation")
                }
                result.setResult(current)
            } catch (e: Exception) {
                result.setException(e)
            }
        }
        return result.task
    }

    /** Loads [legs] now, so the first caption is translated at full speed. */
    fun preload(context: Context, legs: Collection<MozModels.Leg>) {
        val app = context.applicationContext
        val wanted = legs.distinctBy { it.key }
        if (wanted.isEmpty()) return
        thread.execute { wanted.forEach { handleFor(app, it) } }
    }

    /** Frees every loaded pair (when captions stop), giving the memory back. */
    fun releaseAll() {
        thread.execute {
            for (handle in loaded.values) MozTranslate.nativeFree(handle)
            loaded.clear()
        }
    }

    // On [thread] only.
    private fun handleFor(context: Context, leg: MozModels.Leg): Long {
        loaded[leg.key]?.let { return it }
        if (leg.key in broken || !MozTranslate.available) return 0L
        val files = MozModels.files(context, leg) ?: return 0L
        return try {
            val started = System.currentTimeMillis()
            val handle = MozTranslate.nativeLoad(files.model, files.srcVocab, files.trgVocab, files.lex)
            Log.i(TAG, "loaded ${leg.key} in ${System.currentTimeMillis() - started} ms (${MozTranslate.backend})")
            loaded[leg.key] = handle
            handle
        } catch (t: Throwable) {
            // Damaged files: forget them so they're downloaded again next time, and use ML Kit meanwhile.
            Log.w(TAG, "couldn't load ${leg.key}", t)
            broken += leg.key
            MozModels.forget(context, leg)
            0L
        }
    }
}
