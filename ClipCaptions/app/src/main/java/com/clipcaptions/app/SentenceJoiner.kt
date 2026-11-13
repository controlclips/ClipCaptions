package com.clipcaptions.app

/**
 * Speech arrives in short pieces, split wherever the speaker pauses for breath (every mode does this:
 * Instant sends a "segment" at each pause, Any language cuts the sound every few seconds, Basic
 * finishes at each pause). Translated one by one, each piece loses what came before it, and the
 * result reads like word-for-word nonsense.
 *
 * Each piece is still shown and translated the moment it's heard (speed first). This groups the
 * pieces that belong together, and each time a piece joins a group hands back the group's words so
 * they can be translated together, with their context, and replace the separate lines on screen.
 *
 * Punctuation is only a hint: Whisper puts a full stop on almost every piece, even one cut mid-
 * sentence. So a full stop only ends a group once it already holds a decent amount of speech;
 * "?" and "!" always end one.
 *
 * Contains no Android code so it can be tested on its own (tools/logic-tests).
 */
class SentenceJoiner(
    // Pieces further apart than this are treated as separate (Any language pieces are up to 6 s long).
    private val maxGapMs: Long = 7000L,
    // A group is kept to about a sentence or two, so translating it stays quick and it fits the box.
    private val maxWords: Int = 30,
    private val maxChars: Int = 60,
    // A full stop after this much speech is taken as a real sentence end.
    private val settledWords: Int = 12,
    private val settledChars: Int = 24,
) {
    /** Words to translate together: the caption lines they replace, and the text. */
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
     * Returns its group's words when the piece joined earlier ones, otherwise null.
     */
    fun add(line: Int, text: String, language: String, video: Int, at: Long): Whole? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (language != this.language || video != this.video || at - lastAt > maxGapMs) {
            open.clear()
            this.language = language
            this.video = video
        } else if (open.isNotEmpty()) {
            val previous = open.last().text
            if (endsQuestionOrExclamation(previous) || (endsWithFullStop(previous) && settled())) open.clear()
        }
        lastAt = at
        open += Piece(line, t)
        if (tooLong()) {
            // Too much for one go: this piece starts the next group.
            open.clear()
            open += Piece(line, t)
        }
        return if (open.size >= 2) Whole(open.map { it.line }, join()) else null
    }

    private fun size(text: String) = if (withoutSpaces()) text.length else text.split(' ').count { it.isNotEmpty() }

    private fun settled() = size(join()) >= if (withoutSpaces()) settledChars else settledWords

    private fun tooLong() = size(join()) > if (withoutSpaces()) maxChars else maxWords

    private fun join(): String {
        val separator = if (withoutSpaces()) "" else " "
        val code = language.substringBefore('-')
        return open.mapIndexed { i, piece ->
            var p = piece.text
            if (i < open.lastIndex && !endsQuestionOrExclamation(p)) {
                // A full stop or "..." where a piece was cut mid-sentence would make the translator
                // treat the halves as two sentences again.
                p = p.trimEnd { it == '.' || it == '…' || it == '。' || it == '।' || it.isWhitespace() }
            }
            if (i > 0) {
                p = p.trimStart { it == '.' || it == '…' || it.isWhitespace() }
                if (!endsQuestionOrExclamation(open[i - 1].text)) p = lowerFirst(p, code)
            }
            p
        }.filter { it.isNotEmpty() }.joinToString(separator)
    }

    private fun withoutSpaces() = language.substringBefore('-') in noSpaces

    private companion object {
        val noSpaces = setOf("zh", "ja", "yue", "th", "lo", "km", "my", "bo")
        // German writes every noun with a capital, so its capitals carry meaning.
        val keepCapitals = setOf("de", "lb")
        val fullStops = setOf('.', '。', '।', '։', '።')
        val otherEnds = setOf('!', '?', '！', '？', '؟')
        val closers = setOf('"', '”', '»', ')', '」', '’', '\'')

        fun trimmedEnd(text: String) = text.trimEnd { it.isWhitespace() || it in closers }

        fun endsQuestionOrExclamation(text: String) = trimmedEnd(text).lastOrNull()?.let { it in otherEnds } ?: false

        /** A plain full stop. "..." means the speaker carries on, so it doesn't count. */
        fun endsWithFullStop(text: String): Boolean {
            val t = trimmedEnd(text)
            if (t.isEmpty() || t.endsWith("..") || t.endsWith("…")) return false
            return t.last() in fullStops
        }

        /** "Compré" → "compré" when it carries on a sentence, but not "I", names in capitals, or German. */
        fun lowerFirst(text: String, code: String): String {
            if (code in keepCapitals || text.isEmpty() || !text[0].isUpperCase()) return text
            val word = text.takeWhile { it.isLetter() || it == '\'' || it == '’' }
            if (word.length <= 1 && code == "en") return text // "I", "I'm"
            if (word == "I" || word.startsWith("I'") || word.startsWith("I’")) return text
            if (word.drop(1).any { it.isUpperCase() }) return text // "NASA", "McDonald"
            return text[0].lowercase() + text.substring(1)
        }
    }
}
