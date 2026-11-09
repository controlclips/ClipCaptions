package com.clipcaptions.app

import java.util.Locale

/**
 * Gets captions into the shape the translators were trained on, and tidies what comes back.
 *
 * Speech recognisers often give plain lowercase words with no full stop ("so i went to the shop"),
 * especially Basic mode and the words still being spoken. Mozilla's and Google's translators learned
 * from written text, so a capital at the start and a full stop at the end of a finished line make
 * their output noticeably better and stop them treating a whole sentence as a fragment.
 *
 * Both translators pick one word at a time, and now and then get stuck repeating a phrase
 * ("I don't know, I don't know, I don't know…"). [tidy] keeps a repeat only as often as it was said.
 *
 * Contains no Android code so it can be tested on its own (tools/logic-tests).
 */
object TranslationText {

    private val spaces = Regex("""\s+""")

    // English "i" on its own, as Basic mode writes it ("i think", "i'm", "i've").
    private val loneEnglishI = Regex("""(?<![\p{L}\p{N}'’])i(?=$|[\s'’,.!?;:])""")

    // Languages that end a sentence with something other than ".", or with nothing at all.
    private val fullStops = mapOf(
        "zh" to "。", "ja" to "。", "yue" to "。",
        "hi" to "।", "mr" to "।", "ne" to "।", "bn" to "।", "sa" to "।",
    )
    private val noFullStop = setOf("th", "lo", "km", "my", "bo")

    private val wordMarks = setOf(
        CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK,
    )

    /**
     * The text to hand the translator. [finished] is true for a whole caption line, false for the
     * words still being spoken (those get no full stop: the sentence hasn't ended yet).
     */
    fun prepare(text: String, language: String, finished: Boolean): String {
        var t = text.replace(spaces, " ").trim()
        if (t.isEmpty()) return t
        val code = language.substringBefore('-').lowercase()

        if (code == "en") t = t.replace(loneEnglishI, "I")
        t = capitaliseFirst(t, code)

        if (finished) {
            val last = t.last()
            // Only after a word: a line cut at a comma carries on in the next line, so leave it open.
            // (Hindi and similar often end a word with a vowel sign, which counts as part of the word.)
            val endsWord = last.isLetterOrDigit() || last.category in wordMarks
            if (endsWord && code !in noFullStop) t += fullStops[code] ?: "."
        }
        return t
    }

    /** The translator's answer, with stuck repeats removed. [source] is what it was asked to translate. */
    fun tidy(translated: String, source: String): String {
        val t = translated.replace(spaces, " ").trim()
        if (t.isEmpty()) return t
        val allowed = maxOf(2, longestRepeat(words(source)))
        return collapseRepeats(words(t), allowed).joinToString(" ")
    }

    private fun capitaliseFirst(text: String, code: String): String {
        val i = text.indexOfFirst { it.isLetter() }
        if (i < 0 || !text[i].isLowerCase()) return text
        // Only the first word, allowing an opening mark before it ("¿qué…" → "¿Qué…"), not after a number.
        if (text.substring(0, i).any { it.isLetterOrDigit() }) return text
        val word = text.substring(i).takeWhile { !it.isWhitespace() }
        if (word.drop(1).any { it.isUpperCase() }) return text // "iPhone", "eBay": leave brand names alone
        val locale = if (code.isNotEmpty()) Locale(code) else Locale.ROOT
        return text.substring(0, i) + text[i].toString().uppercase(locale) + text.substring(i + 1)
    }

    private fun words(text: String) = text.split(' ').filter { it.isNotEmpty() }

    /** A word for comparing repeats: no capitals or punctuation. */
    private fun key(word: String) = word.lowercase().filter { it.isLetterOrDigit() }

    /** Most times any phrase of up to four words is said in a row ("no, no, no" is 3). */
    private fun longestRepeat(words: List<String>): Int {
        val keys = words.map(::key)
        var best = 1
        for (n in 1..4) {
            var i = 0
            while (i + 2 * n <= keys.size) {
                var run = 1
                while (i + (run + 1) * n <= keys.size && same(keys, i, i + run * n, n)) run++
                if (run > best) best = run
                i += if (run > 1) run * n else 1
            }
        }
        return best
    }

    /** Keeps at most [allowed] copies in a row of any phrase of up to four words. */
    private fun collapseRepeats(words: List<String>, allowed: Int): List<String> {
        var out = words
        for (n in 4 downTo 1) {
            val keys = out.map(::key)
            val kept = ArrayList<String>(out.size)
            var i = 0
            while (i < out.size) {
                var run = 1
                while (i + (run + 1) * n <= out.size && same(keys, i, i + run * n, n)) run++
                if (run > allowed) {
                    // Keep the allowed copies, then carry on after the last one.
                    for (j in i until i + allowed * n) kept += out[j]
                    // Let the last kept word end the way the final copy did ("know…" → "know.").
                    val lastEnd = out[i + run * n - 1]
                    val lastKept = kept.size - 1
                    val ending = lastEnd.takeLastWhile { !it.isLetterOrDigit() }
                    kept[lastKept] = kept[lastKept].trimEnd { !it.isLetterOrDigit() } + ending
                    i += run * n
                } else {
                    kept += out[i]
                    i++
                }
            }
            out = kept
        }
        return out
    }

    /** True if the [n] words at [a] match the [n] words at [b] (and aren't just punctuation). */
    private fun same(keys: List<String>, a: Int, b: Int, n: Int): Boolean {
        if (b + n > keys.size) return false
        var anyWord = false
        for (k in 0 until n) {
            if (keys[a + k] != keys[b + k]) return false
            if (keys[a + k].isNotEmpty()) anyWord = true
        }
        return anyWord
    }
}
