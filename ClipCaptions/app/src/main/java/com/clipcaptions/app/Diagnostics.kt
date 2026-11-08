package com.clipcaptions.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Starts the crash catcher as soon as the app starts. */
class ClipCaptionsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.watchForCrashes(this)
    }
}

/**
 * Remembers recent problems and the last crash on this phone, and puts together a report the
 * user can send, since apps installed outside Google Play don't send crash reports by themselves.
 * Nothing is sent unless the user chooses to share the report.
 */
object Diagnostics {

    private const val PREFS = "diagnostics"
    private const val KEY_PROBLEMS = "problems"
    private const val KEY_CRASH = "crash"
    private const val KEY_STOPPED_FOR_BATTERY = "stopped_for_battery"
    private const val MAX_PROBLEMS = 10

    /** Where reports go. Empty: the user picks how to send it (email, WhatsApp, …) in the share sheet. */
    const val SUPPORT_EMAIL = ""

    /** Records a problem the user saw, such as "Captions stopped: …". */
    fun note(context: Context, problem: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val earlier = prefs.getString(KEY_PROBLEMS, "").orEmpty().lines().filter { it.isNotBlank() }
        val all = (earlier + "${stamp()}  ${problem.replace('\n', ' ')}").takeLast(MAX_PROBLEMS)
        prefs.edit().putString(KEY_PROBLEMS, all.joinToString("\n")).apply()
    }

    /** Captions turned themselves off to save battery; the main screen says so next time it opens. */
    fun markStoppedForBattery(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_STOPPED_FOR_BATTERY, true).apply()
    }

    /** True once after captions turned themselves off to save battery. */
    fun takeStoppedForBattery(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stopped = prefs.getBoolean(KEY_STOPPED_FOR_BATTERY, false)
        if (stopped) prefs.edit().remove(KEY_STOPPED_FOR_BATTERY).apply()
        return stopped
    }

    /** Saves the details of a crash before the app closes, so they can go in the next report. */
    fun watchForCrashes(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashSaver) return
        Thread.setDefaultUncaughtExceptionHandler(CrashSaver(app, previous))
    }

    private class CrashSaver(
        private val app: Context,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            try {
                val trace = error.stackTraceToString().lines().take(30).joinToString("\n")
                // commit(), not apply(): the app is about to close.
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_CRASH, "${stamp()} on ${thread.name}\n$trace").commit()
            } catch (ignored: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The report: a space for the user to describe what happened, then details about this phone and the app. */
    fun report(context: Context, settings: List<String>): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val manager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
        val version = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else 0})"
        } catch (e: Exception) {
            "?"
        }
        val speechBuild = when {
            !WhisperLib.available -> "not available"
            WhisperLib.isFastBuild -> "fast"
            else -> "standard"
        }
        val lines = ArrayList<String>()
        lines += "What happened? (please describe it here)"
        lines += ""
        lines += ""
        lines += "---- Details (nothing personal) ----"
        lines += "Clip Captions $version"
        lines += "Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        lines += "Processor: ${Build.SUPPORTED_ABIS.joinToString()}, ${Runtime.getRuntime().availableProcessors()} cores, " +
            "speech engine build: $speechBuild"
        lines += String.format(Locale.UK, "Memory: %.1f GB", memory.totalMem / (1024.0 * 1024.0 * 1024.0))
        lines += "Offline translator: Mozilla ${MozTranslate.backend}"
        lines += settings
        val problems = prefs.getString(KEY_PROBLEMS, "").orEmpty()
        lines += ""
        lines += "Recent problems:"
        lines += problems.ifBlank { "none" }
        val crash = prefs.getString(KEY_CRASH, null)
        lines += ""
        lines += "Last crash:"
        lines += crash ?: "none"
        return lines.joinToString("\n")
    }

    private fun stamp(): String = SimpleDateFormat("d MMM HH:mm", Locale.UK).format(Date())
}
