package com.clipcaptions.app

/** Tidies Whisper's output and removes the phrases it tends to invent over music or silence. */
object WhisperText {

    private val nonSpeech = Regex("""\[[^\]]*]|\([^)]*\)|\*[^*]*\*|[♪♫♬]""")
    private val spaces = Regex("""\s+""")

    // Whisper learned these from subtitle files, and "hears" them in music and background noise.
    private val inventedPhrases = listOf(
        "thank you for watching",
        "thanks for watching",
        "thank you so much for watching",
        "please subscribe",
        "like and subscribe",
        "don't forget to subscribe",
        "subtitles by",
        "subtitled by",
        "captions by",
        "transcribed by",
        "translated by",
        "amara.org",
    )
    private val inventedOnTheirOwn = setOf("you", "thank you", "thanks", "bye", "okay")

    fun clean(raw: String): String {
        val text = raw.replace(nonSpeech, " ").replace(spaces, " ").trim()
        if (text.none { it.isLetterOrDigit() }) return ""
        val lower = text.lowercase()
        if (lower.trimEnd('.', '!', '?', ',', ' ') in inventedOnTheirOwn) return ""
        if (inventedPhrases.any { lower.contains(it) }) return ""
        return collapseRepeats(text)
    }

    /** Whisper sometimes gets stuck repeating a word ("no no no no no"); keep at most two in a row. */
    private fun collapseRepeats(text: String): String {
        val words = text.split(' ')
        val kept = ArrayList<String>(words.size)
        var previous = ""
        var run = 0
        for (word in words) {
            val key = word.lowercase().trim { !it.isLetterOrDigit() }
            if (key.isNotEmpty() && key == previous) {
                run++
            } else {
                previous = key
                run = 1
            }
            if (run <= 2) kept.add(word)
        }
        return kept.joinToString(" ")
    }
}
