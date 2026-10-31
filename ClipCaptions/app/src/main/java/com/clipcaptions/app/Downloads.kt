package com.clipcaptions.app

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Downloads {

    /**
     * Downloads [url] to [destination], reporting whole percentages. Writes to a temporary file
     * first, so an interrupted download never looks finished. Blocking: call it off the main thread.
     */
    fun download(url: String, destination: File, onProgress: (Int) -> Unit) {
        destination.parentFile?.mkdirs()
        val partial = File(destination.path + ".part")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("the download server answered $code")
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
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
            if (total > 0 && partial.length() != total) throw IOException("the download was cut short")
        } finally {
            connection.disconnect()
        }
        destination.delete()
        if (!partial.renameTo(destination)) throw IOException("the file couldn't be saved")
    }
}
