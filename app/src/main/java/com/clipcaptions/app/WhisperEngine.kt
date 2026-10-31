package com.clipcaptions.app

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Loads the Whisper model ahead of time. Loading starts while Android's screen-share question is
 * on screen, so captions begin as soon as you agree instead of after a pause.
 *
 * Under the Automatic setting it also times each model once on this phone and skips models that
 * would make captions lag, rather than guessing from the phone's specs.
 */
object WhisperEngine {

    /**
     * How much of Whisper's 30-second window to process: 768 of 1500 frames (about 15 s).
     * Our chunks are at most 6 s, and this makes each chunk roughly twice as fast.
     */
    const val AUDIO_CONTEXT = 768

    /** A model slower than this on the 4-second timing test would make captions lag on this phone. */
    private const val SPEED_BUDGET_MS = 800L

    private const val SAMPLE_RATE = 16000
    private const val PREFS = "whisper_speed"

    /** What to load: speech models from most to least accurate, the speech detector, and whether Automatic chooses. */
    data class Request(val models: List<String>, val vadPath: String?, val automatic: Boolean)

    /** A loaded model ready for captioning. Whoever takes it must [close] it. */
    class Session internal constructor(
        val request: Request,
        level: Int,
        handle: Long,
        val vad: Long,
        private val context: Context,
    ) {
        /** Index into the request's models: higher means smaller and faster. */
        var level = level
            private set
        var handle = handle
            private set

        /** Swaps to the next smaller model. Returns false if there isn't one or it won't load. */
        fun stepDown(): Boolean {
            if (level >= request.models.lastIndex) return false
            if (request.automatic) markSlow(context, request.models[level])
            val next = WhisperLib.nativeInit(request.models[level + 1])
            if (next == 0L) return false
            warmUp(next)
            WhisperLib.nativeFree(handle)
            handle = next
            level++
            return true
        }

        fun close() {
            if (handle != 0L) WhisperLib.nativeFree(handle)
            if (vad != 0L) WhisperLib.nativeFreeVad(vad)
            handle = 0L
        }
    }

    private val lock = Any()
    private val loader = Executors.newSingleThreadExecutor()
    private var preloaded: Session? = null

    /** Starts loading in the background. */
    fun preload(context: Context, request: Request) {
        val app = context.applicationContext
        loader.execute {
            synchronized(lock) {
                if (preloaded?.request == request) return@synchronized
                preloaded?.close()
                preloaded = open(app, request)
            }
        }
    }

    /** Frees a preloaded model that won't be used (for example, sharing was cancelled). */
    fun discardPreload() {
        loader.execute {
            synchronized(lock) {
                preloaded?.close()
                preloaded = null
            }
        }
    }

    /** Returns the preloaded model, or loads one now. Blocking. Null if no model could be loaded. */
    fun take(context: Context, request: Request): Session? {
        // Let a preload that's already under way finish, so the model is never loaded twice.
        try {
            loader.submit {}.get(90, TimeUnit.SECONDS)
        } catch (ignored: Exception) {
        }
        synchronized(lock) {
            val ready = preloaded
            preloaded = null
            if (ready != null && ready.request == request) return ready
            ready?.close()
            return open(context.applicationContext, request)
        }
    }

    /** Forgets the timing tests, so Automatic chooses again (after changing the accuracy setting). */
    fun forgetSpeedTests(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun open(context: Context, request: Request): Session? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = request.models.lastIndex
        var level = 0
        if (request.automatic) {
            // Skip models already found too slow here; the smallest is always kept as a last resort.
            while (level < last && prefs.getBoolean(slowKey(request.models[level]), false)) level++
        }
        while (level <= last) {
            val path = request.models[level]
            val handle = WhisperLib.nativeInit(path)
            if (handle == 0L) {
                level++
                continue
            }
            warmUp(handle)
            if (request.automatic && level < last && !prefs.contains(timeKey(path))) {
                val ms = timeChunk(handle)
                prefs.edit().putLong(timeKey(path), ms).putBoolean(slowKey(path), ms > SPEED_BUDGET_MS).apply()
                if (ms > SPEED_BUDGET_MS) {
                    WhisperLib.nativeFree(handle)
                    level++
                    continue
                }
            }
            val vad = request.vadPath?.let { WhisperLib.nativeInitVad(it) } ?: 0L
            return Session(request, level, handle, vad, context)
        }
        return null
    }

    /**
     * Runs Whisper once on a moment of near-silence. Gets the first-run costs out of the way, and
     * sets the shorter window that language detection then also uses.
     */
    private fun warmUp(handle: Long) {
        WhisperLib.nativeTranscribe(handle, quietSound(1000), DeviceProfile.threads(), false, "en", AUDIO_CONTEXT)
    }

    private fun timeChunk(handle: Long): Long {
        val sound = quietSound(4000)
        val started = SystemClock.elapsedRealtime()
        WhisperLib.nativeTranscribe(handle, sound, DeviceProfile.threads(), false, "en", AUDIO_CONTEXT)
        return SystemClock.elapsedRealtime() - started
    }

    private fun quietSound(ms: Int): FloatArray {
        val random = Random(7)
        return FloatArray(SAMPLE_RATE * ms / 1000) { (random.nextFloat() - 0.5f) * 0.004f }
    }

    private fun markSlow(context: Context, path: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(slowKey(path), true).apply()
    }

    private fun slowKey(path: String) = "slow_" + File(path).name
    private fun timeKey(path: String) = "ms_" + File(path).name
}
