package com.clipcaptions.app

/**
 * Any language mode hears speech in short pieces (a few seconds each), so a sentence is often split
 * across two or three caption lines and each half is translated on its own, which reads badly.
 *
 * Each piece is still shown and translated the moment it's heard (speed first). This keeps track of
 * the pieces of the sentence in progress, and when a piece ends the sentence, hands back the whole
 * sentence so it can be translated again in one go and quietly replace the halves on screen.
 *
 * Contains no Android code so it can be tested on its own (tools/logic-tests).
 */
class SentenceJoiner(
    // Pieces further apart than this are treated as separate sentences.
    private val maxGapMs: Long = 3000L,
    // A "sentence" longer than this is a run-on; give up joining it, so translating stays quick.
    private val maxWords: Int = 40,
    private val maxChars: Int = 80,
    private val maxPieces: Int = 4,
) {
    /** A whole sentence to translate again: the caption lines it replaces, and its words. */
    class Whole(val lines: List<Int>, val text: String)

    private class Piece(val line: Int, val text: String)

    private val open = ArrayList<Piece>()
    private var language = ""
    private var video = -1
    private var lastAt = 0L

    fun reset() {
        open.clear()
        language = ""
        video = -1
        lastAt = 0L
    }

    /**
     * A piece heard in [language] during [video], shown as caption line [line] at time [at] (ms).
     * Returns the whole sentence when this piece finishes one that was split, otherwise null.
     */
    fun add(line: Int, text: String, language: String, video: Int, at: Long): Whole? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (language != this.language || video != this.video || at - lastAt > maxGapMs) {
            open.clear()
            this.language = language
            this.video = video
        }
        lastAt = at
        open += Piece(line, t)

        if (!endsSentence(t)) {
            if (open.size > maxPieces || tooLong()) {
                // Too long to be worth joining: start again from this piece.
                open.clear()
                open += Piece(line, t)
            }
            return null
        }
        val whole = if (open.size >= 2 && !tooLong()) Whole(open.map { it.line }, join()) else null
        open.clear()
        return whole
    }

    private fun tooLong(): Boolean {
        val text = join()
        return if (writtenWithoutSpaces()) text.length > maxChars else text.split(' ').size > maxWords
    }

    private fun join(): String {
        val separator = if (writtenWithoutSpaces()) "" else " "
        return open.mapIndexed { i, piece ->
            var p = piece.text
            // Whisper marks a piece cut mid-sentence with "..." at the end, and the next with "..." at the start.
            if (i < open.lastIndex) p = p.trimEnd { it == '.' || it == '…' || it.isWhitespace() }.trimEnd(',').trimEnd()
            if (i > 0) p = p.trimStart { it == '.' || it == '…' || it.isWhitespace() }
            p
        }.filter { it.isNotEmpty() }.joinToString(separator)
    }

    private fun writtenWithoutSpaces() = language.substringBefore('-') in noSpaces

    private companion object {
        val noSpaces = setOf("zh", "ja", "yue", "th", "lo", "km", "my", "bo")
        val enders = setOf('.', '!', '?', '。', '！', '？', '।', '؟', '։', '።')

        /** True if [text] ends a sentence. A trailing "..." means the speaker carries on, so it doesn't. */
        fun endsSentence(text: String): Boolean {
            val t = text.trimEnd { it.isWhitespace() || it == '"' || it == '”' || it == '»' || it == ')' || it == '」' }
            if (t.isEmpty()) return false
            if (t.endsWith("...") || t.endsWith("…") || t.endsWith("。。。")) return false
            return t.last() in enders
        }
    }
}
