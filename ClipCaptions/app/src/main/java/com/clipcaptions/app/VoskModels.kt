package com.clipcaptions.app

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** Downloads and stores the offline speech models (one per spoken language, 30–90 MB each). */
object VoskModels {

    private const val BASE_URL = "https://alphacephei.com/vosk/models/"
    private const val READY_MARKER = ".ready"

    private fun folder(context: Context, name: String) = File(File(context.filesDir, "vosk"), name)

    /** The model's folder if it has already been downloaded, otherwise null. */
    fun installed(context: Context, name: String): File? {
        val dir = folder(context, name)
        if (!File(dir, READY_MARKER).exists()) return null
        return findModelRoot(dir)
    }

    /** Downloads and unpacks a model. Blocking: call it off the main thread. */
    fun download(context: Context, name: String, onProgress: (Int) -> Unit): File {
        val zip = File(context.cacheDir, "$name.zip")
        val connection = URL("$BASE_URL$name.zip").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("the download server answered $code")
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(zip).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        done += count
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }

        val staging = File(File(context.filesDir, "vosk"), "$name.partial")
        staging.deleteRecursively()
        staging.mkdirs()
        val stagingPath = staging.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(staging, entry.name)
                if (!out.canonicalPath.startsWith(stagingPath)) throw IOException("the download was damaged")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        zip.delete()

        val target = folder(context, name)
        target.deleteRecursively()
        if (!staging.renameTo(target)) throw IOException("the model couldn't be saved")
        val root = findModelRoot(target) ?: throw IOException("the model files were incomplete")
        File(target, READY_MARKER).writeText("ok")
        return root
    }

    /** The zip wraps the model in a folder; the model root is the folder that holds "am". */
    private fun findModelRoot(dir: File): File? {
        var level = listOf(dir)
        repeat(3) {
            for (candidate in level) {
                if (File(candidate, "am").isDirectory) return candidate
            }
            level = level.flatMap { parent -> parent.listFiles()?.filter { it.isDirectory } ?: emptyList() }
        }
        return null
    }
}
