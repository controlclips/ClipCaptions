package com.clipcaptions.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A phrase saved from the caption box: the words as heard, their translation, and the languages. */
data class Phrase(
    val original: String,
    val translation: String?,
    val language: String,
    val target: String,
    val savedAt: Long,
)

/** The phrases you've saved with ★, kept in a small file on the phone. */
object PhraseBook {

    private const val FILE_NAME = "phrases.json"
    private const val MAX_PHRASES = 2000

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    /** Newest first. */
    @Synchronized
    fun all(context: Context): List<Phrase> {
        val f = file(context)
        if (!f.isFile) return emptyList()
        return try {
            val array = JSONArray(f.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Phrase(
                    original = o.optString("original"),
                    translation = if (o.has("translation")) o.optString("translation") else null,
                    language = o.optString("language"),
                    target = o.optString("target"),
                    savedAt = o.optLong("savedAt"),
                )
            }.sortedByDescending { it.savedAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun count(context: Context): Int = all(context).size

    /** Saves a phrase. Returns false if it's the same as the one just saved (a double tap). */
    @Synchronized
    fun add(context: Context, phrase: Phrase): Boolean {
        val existing = all(context)
        val newest = existing.firstOrNull()
        if (newest != null && newest.original == phrase.original) return false
        write(context, (listOf(phrase) + existing).take(MAX_PHRASES))
        return true
    }

    @Synchronized
    fun remove(context: Context, savedAt: Long) {
        write(context, all(context).filter { it.savedAt != savedAt })
    }

    /** All the phrases as plain text, for sharing or copying into a notes or flashcard app. */
    fun asText(phrases: List<Phrase>): String = phrases.joinToString("\n\n") { p ->
        val language = Languages.nameFor(p.language)
        if (p.translation.isNullOrBlank() || p.translation == p.original) {
            "${p.original}  ($language)"
        } else {
            "${p.translation}\n${p.original}  ($language)"
        }
    }

    private fun write(context: Context, phrases: List<Phrase>) {
        val array = JSONArray()
        for (p in phrases) {
            array.put(
                JSONObject()
                    .put("original", p.original)
                    .put("language", p.language)
                    .put("target", p.target)
                    .put("savedAt", p.savedAt)
                    .apply { if (p.translation != null) put("translation", p.translation) },
            )
        }
        // Write to a spare file first, so a phone switching off mid-save can't lose the list.
        val f = file(context)
        val spare = File(f.parentFile, "$FILE_NAME.new")
        spare.writeText(array.toString())
        if (!spare.renameTo(f)) {
            f.writeText(array.toString())
            spare.delete()
        }
    }
}
