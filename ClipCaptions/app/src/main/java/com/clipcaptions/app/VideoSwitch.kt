package com.clipcaptions.app

/**
 * Works out when you've moved on to another video (swiped in TikTok, Reels or Shorts, or the
 * app moved on by itself), from two signals:
 * - which media players are playing: feed apps usually play each video through its own player,
 *   so a different player starting means a different video;
 * - touches on the screen: a video starting again straight after a touch (a swipe, or tapping
 *   to resume) also counts, which covers apps that reuse a single player.
 *
 * Pausing never clears the captions, so you can stop a video to read them.
 * Contains no Android code so it can be tested on its own.
 */
class VideoSwitchLogic(private val touchWindowMs: Long = 2500L) {

    private var lastTouchAt: Long? = null
    private var playing: Set<Int> = emptySet()
    private var current: Int? = null

    fun onTouch(now: Long) {
        lastTouchAt = now
    }

    /** Called with the players playing right now. Returns true if a new video has started. */
    fun onPlayersChanged(players: Set<Int>, now: Long): Boolean {
        val started = players - playing
        playing = players
        if (started.isEmpty()) return false

        val previous = current
        val samePlayer = previous != null && previous in started
        current = if (samePlayer) previous else started.first()

        val touch = lastTouchAt
        val afterTouch = touch != null && now - touch in 0..touchWindowMs
        val differentPlayer = previous != null && !samePlayer
        return afterTouch || differentPlayer
    }
}

/**
 * Collects captured sound into chunks for Whisper. A chunk ends at a pause once it's long enough,
 * or, when someone talks without pausing, at the quietest moment near the maximum length, so
 * words aren't cut in half. Quiet stretches before speech are dropped (keeping a short lead-in).
 * Contains no Android code so it can be tested on its own.
 */
class SpeechChunker(
    private val sampleRate: Int = 16000,
    private val minChunkMs: Int = 1600,
    private val maxChunkMs: Int = 6000,
    private val pauseMs: Int = 300,
    private val leadInMs: Int = 300,
    private val searchBackMs: Int = 2000,
    private val loudLevel: Int = 400,
    // The first chunk of each video is cut sooner, so its first words (and its language) come quickly.
    private val firstMaxChunkMs: Int = maxChunkMs,
) {
    private val maxSamples = sampleRate * maxChunkMs / 1000
    private val firstMaxSamples = sampleRate * minOf(firstMaxChunkMs, maxChunkMs) / 1000
    private var firstOfVideo = true

    /** The most a chunk may hold right now. */
    private fun limit() = if (firstOfVideo) firstMaxSamples else maxSamples

    /** Another video: forget what's collected, and cut its first chunk sooner. */
    fun newVideo() {
        reset()
        firstOfVideo = true
    }
    private val buffer = FloatArray(maxSamples + sampleRate) // room for one extra block

    // Each block of audio added: where it starts in the buffer, its length and loudest sample.
    private val blockStart = ArrayList<Int>()
    private val blockLength = ArrayList<Int>()
    private val blockPeak = ArrayList<Int>()

    private var length = 0
    private var heardSound = false
    private var quietSamples = 0

    fun reset() {
        length = 0
        heardSound = false
        quietSamples = 0
        blockStart.clear()
        blockLength.clear()
        blockPeak.clear()
    }

    /** Adds a block of 16-bit samples (keep blocks short, around 0.1 s). Returns chunks ready for Whisper. */
    fun add(samples: ShortArray, count: Int): List<FloatArray> {
        val ready = ArrayList<FloatArray>(1)
        var offset = 0
        while (offset < count) {
            val n = minOf(count - offset, sampleRate / 10)
            addBlock(samples, offset, n, ready)
            offset += n
        }
        return ready
    }

    private fun addBlock(samples: ShortArray, offset: Int, n: Int, ready: MutableList<FloatArray>) {
        var peak = 0
        for (i in 0 until n) {
            val level = kotlin.math.abs(samples[offset + i].toInt())
            if (level > peak) peak = level
        }
        val loud = peak > loudLevel

        if (length + n > limit()) cutAtQuietestPoint(ready)

        for (i in 0 until n) buffer[length + i] = samples[offset + i] / 32768f
        blockStart.add(length)
        blockLength.add(n)
        blockPeak.add(peak)
        length += n

        if (loud) {
            heardSound = true
            quietSamples = 0
        } else {
            quietSamples += n
        }

        if (!heardSound) {
            // Nothing but quiet so far: keep only a short lead-in so soft starts aren't clipped.
            val keep = sampleRate * leadInMs / 1000
            if (length > keep * 2) dropFront(length - keep)
            return
        }

        val ms = length * 1000L / sampleRate
        val pausedMs = quietSamples * 1000L / sampleRate
        if (ms >= minChunkMs && pausedMs >= pauseMs) {
            ready.add(buffer.copyOf(length))
            firstOfVideo = false
            reset()
        } else if (length >= limit()) {
            cutAtQuietestPoint(ready)
        }
    }

    /** Sends everything up to the quietest block in the last stretch, and keeps the rest for the next chunk. */
    private fun cutAtQuietestPoint(ready: MutableList<FloatArray>) {
        if (length == 0) return
        if (!heardSound) {
            reset()
            return
        }
        firstOfVideo = false
        // Look for the quietest moment in the last part, but never in the first half.
        val searchFrom = length - minOf(sampleRate * searchBackMs / 1000, length / 2)
        var best = -1
        for (i in blockStart.indices) {
            if (blockStart[i] < searchFrom || i == 0) continue
            if (best < 0 || blockPeak[i] <= blockPeak[best]) best = i
        }
        if (best < 0) {
            ready.add(buffer.copyOf(length))
            reset()
            return
        }
        // Cut in the middle of the quietest block.
        val cut = blockStart[best] + blockLength[best] / 2
        ready.add(buffer.copyOf(cut))
        dropFront(cut)
    }

    /** Removes the first [count] samples, keeping the block records and speech state in step. */
    private fun dropFront(count: Int) {
        if (count <= 0) return
        System.arraycopy(buffer, count, buffer, 0, length - count)
        length -= count

        val starts = ArrayList<Int>()
        val lengths = ArrayList<Int>()
        val peaks = ArrayList<Int>()
        for (i in blockStart.indices) {
            val start = blockStart[i]
            val end = start + blockLength[i]
            if (end <= count) continue
            val newStart = maxOf(start, count) - count
            starts.add(newStart)
            lengths.add(end - count - newStart)
            peaks.add(blockPeak[i])
        }
        blockStart.clear(); blockStart.addAll(starts)
        blockLength.clear(); blockLength.addAll(lengths)
        blockPeak.clear(); blockPeak.addAll(peaks)

        // Recount from what's left: is there speech, and how long has it been quiet at the end?
        heardSound = blockPeak.any { it > loudLevel }
        quietSamples = 0
        for (i in blockPeak.indices.reversed()) {
            if (blockPeak[i] > loudLevel) break
            quietSamples += blockLength[i]
        }
    }
}

/**
 * Picks the language of a video from Whisper's guesses and sticks to it: straight away when Whisper
 * is confident, otherwise once two chunks in a row agree. After that Whisper is told the language,
 * which is faster than detecting it and stops it switching mid-video.
 */
class LanguageLock(private val confidentAt: Float = 0.7f) {
    var locked: String? = null
        private set
    private var lastGuess = ""

    fun reset() {
        locked = null
        lastGuess = ""
    }

    /** Records Whisper's guess for a chunk that contained words, with its confidence (0–1, or -1 if unknown). */
    fun record(guess: String, confidence: Float) {
        if (locked != null || guess.isEmpty()) return
        if (confidence >= confidentAt || guess == lastGuess) locked = guess else lastGuess = guess
    }
}

/**
 * After a swipe, Google's recogniser keeps listening (restarting it would cost up to a second),
 * but the sentence it's building still starts with the old video's words. This drops them:
 * it remembers how much had been heard at the swipe and removes that much from what follows,
 * until the sentence finishes. Counts words, or characters for languages written without spaces.
 * Contains no Android code so it can be tested on its own.
 */
class LeftoverWords {
    private var latest = ""
    private var cutWords = 0
    private var cutChars = 0
    // How the old video's sentence began, to tell it apart from a new sentence.
    private var cutStart: List<String> = emptyList()
    private var cutStartChars = ""

    /** Call when you move on to another video. */
    fun cut() {
        val heard = latest.trim()
        if (heard.isEmpty()) return
        if (heard.contains(' ')) {
            val all = words(heard)
            cutWords = all.size
            cutStart = all.take(2).map { plain(it) }
            cutChars = 0
        } else {
            cutChars = heard.length
            cutStartChars = heard.take(2)
            cutWords = 0
        }
    }

    /** Words still being spoken; returns what should be shown. */
    fun onPartial(text: String): String {
        latest = text
        return strip(text)
    }

    /** A finished sentence; returns what should be shown. The next sentence starts clean. */
    fun onFinal(text: String): String {
        val shown = strip(text)
        reset()
        return shown
    }

    fun reset() {
        latest = ""
        cutWords = 0
        cutChars = 0
        cutStart = emptyList()
        cutStartChars = ""
    }

    private fun strip(text: String): String {
        val t = text.trim()
        return when {
            cutWords > 0 -> {
                val all = words(t)
                if (startsDifferently(all)) {
                    // The recogniser has begun a new sentence (the new video's) without saying the old
                    // one had finished: nothing in it belongs to the old video.
                    cutWords = 0
                    cutStart = emptyList()
                    t
                } else if (all.size <= cutWords) {
                    ""
                } else {
                    all.drop(cutWords).joinToString(" ")
                }
            }
            cutChars > 0 -> {
                val n = minOf(cutStartChars.length, t.length)
                if (n > 0 && t.substring(0, n) != cutStartChars.substring(0, n)) {
                    cutChars = 0
                    cutStartChars = ""
                    t
                } else if (t.length <= cutChars) {
                    ""
                } else {
                    t.substring(cutChars).trim()
                }
            }
            else -> t
        }
    }

    private fun startsDifferently(all: List<String>): Boolean {
        val n = minOf(cutStart.size, all.size)
        for (i in 0 until n) if (plain(all[i]) != cutStart[i]) return true
        return false
    }

    /** A word without capitals or punctuation, as recognisers add those when a sentence finishes. */
    private fun plain(word: String) = word.lowercase().filter { it.isLetterOrDigit() }

    private fun words(text: String) = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
}

/**
 * Breaks what's being said into caption lines a sentence at a time, so each line is translated once
 * and whole: the translator does much better with complete sentences than with fragments. A
 * sentence counts as finished once the next one has begun (the recogniser's punctuation shows where).
 * When someone talks for a long time without a sentence ending, a line is cut at [lineWords] (at a
 * comma if there is one), so translating doesn't slow down as the run grows. The last few words stay
 * "live", because the recogniser may still change them. Counts words, or characters for languages
 * written without spaces. Contains no Android code so it can be tested on its own.
 */
class LineSplitter(
    private val lineWords: Int = 30,
    private val liveWords: Int = 4,
    private val lineChars: Int = 45,
    private val liveChars: Int = 6,
    // A "sentence" shorter than this ("Oh. Yes.") waits to join the next one.
    private val minSentenceWords: Int = 3,
    private val minSentenceChars: Int = 4,
) {
    /** Lines that are finished now, and the words still being spoken. */
    class Split(val finished: List<String>, val live: String)

    private var doneWords = 0
    private var doneChars = 0

    fun reset() {
        doneWords = 0
        doneChars = 0
    }

    /** Words still being spoken (the recogniser's whole guess for the sentence so far). */
    fun onPartial(text: String): Split {
        val t = text.trim()
        if (t.isEmpty()) return Split(emptyList(), "")
        return if (t.contains(' ')) splitWords(t) else splitChars(t)
    }

    /** The sentence is finished: returns the part not already shown as a line. The next one starts clean. */
    fun onFinal(text: String): String {
        val t = text.trim()
        val rest = when {
            t.isEmpty() -> ""
            t.contains(' ') -> words(t).drop(doneWords).joinToString(" ")
            else -> if (doneChars >= t.length) "" else t.substring(doneChars).trim()
        }
        reset()
        return rest
    }

    private fun splitWords(t: String): Split {
        val all = words(t)
        // Shorter than what's already been shown: the recogniser started a new sentence without
        // saying the last one had finished. Start again rather than hide the new words.
        if (doneWords > all.size) doneWords = 0
        val finished = ArrayList<String>()
        while (true) {
            // A finished sentence: one that ends before the last word (so the next one has begun).
            var end = -1
            for (i in doneWords + minSentenceWords - 1 until all.size - 1) {
                if (all[i].last() in sentenceEnd) {
                    end = i + 1
                    break
                }
            }
            if (end < 0 && all.size - doneWords >= lineWords + liveWords) {
                // A long run with no sentence ending: cut it, at a comma near the end if there is one.
                end = doneWords + lineWords
                for (i in end - 1 downTo doneWords + lineWords / 2) {
                    if (all[i].last() in breakAfter) {
                        end = i + 1
                        break
                    }
                }
            }
            if (end < 0) break
            finished += all.subList(doneWords, end).joinToString(" ")
            doneWords = end
        }
        return Split(finished, all.drop(doneWords).joinToString(" "))
    }

    private fun splitChars(t: String): Split {
        if (doneChars > t.length) doneChars = 0
        val finished = ArrayList<String>()
        while (true) {
            var end = -1
            for (i in doneChars + minSentenceChars - 1 until t.length - 1) {
                if (t[i] in sentenceEnd) {
                    end = i + 1
                    break
                }
            }
            if (end < 0 && t.length - doneChars >= lineChars + liveChars) {
                end = doneChars + lineChars
                for (i in end - 1 downTo doneChars + lineChars / 2) {
                    if (t[i] in breakAfter) {
                        end = i + 1
                        break
                    }
                }
            }
            if (end < 0) break
            finished += t.substring(doneChars, end).trim()
            doneChars = end
        }
        return Split(finished, t.substring(doneChars).trim())
    }

    private fun words(text: String) = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

    private companion object {
        val sentenceEnd = setOf('.', '!', '?', '…', '。', '！', '？')
        val breakAfter = setOf('.', ',', '!', '?', ';', ':', '。', '，', '！', '？', '、', '；')
    }
}

/**
 * Typical delays during a captions session, in milliseconds: from people starting to speak to their
 * words appearing, and from words appearing to their translation. Shown in the app afterwards, so a
 * slow phone can be told apart from a slow step. Contains no Android code so it can be tested on its own.
 */
class SpeedStats(private val keep: Int = 200) {
    private val words = ArrayList<Long>()
    private val translations = ArrayList<Long>()

    @Synchronized
    fun addWords(ms: Long) = add(words, ms)

    @Synchronized
    fun addTranslation(ms: Long) = add(translations, ms)

    @Synchronized
    fun wordsMedian(): Long? = median(words)

    @Synchronized
    fun translationMedian(): Long? = median(translations)

    @Synchronized
    fun samples(): Int = words.size

    private fun add(list: ArrayList<Long>, ms: Long) {
        if (ms < 0 || ms > 10_000) return // Not a real measurement.
        list.add(ms)
        if (list.size > keep) list.removeAt(0)
    }

    private fun median(list: List<Long>): Long? {
        if (list.isEmpty()) return null
        val sorted = list.sorted()
        return sorted[sorted.size / 2]
    }
}

/** A language guess: a Whisper code such as "es", and how sure (0–1) among the languages considered. */
class LanguageGuess(val code: String, val confidence: Float)

/**
 * Picks a language from Whisper's likelihoods. Limiting the choice to the languages you watch makes
 * spotting far more reliable on a couple of seconds of speech. Contains no Android code.
 */
object LanguagePick {

    /**
     * [codes] and [probs] are Whisper's language codes and their likelihoods, in the same order.
     * [allowed] is the set to choose from (empty = any). A language outside it is still chosen when
     * its likelihood is at least [clearAt] (pass more than 1 to never do that).
     */
    fun pick(codes: Array<String>, probs: FloatArray, allowed: Collection<String>, clearAt: Float = 0.8f): LanguageGuess? {
        val n = minOf(codes.size, probs.size)
        if (n == 0) return null
        var best = 0
        for (i in 1 until n) if (probs[i] > probs[best]) best = i
        if (allowed.isEmpty() || codes[best] in allowed) {
            val inSet = if (allowed.isEmpty()) 1f else (0 until n).filter { codes[it] in allowed }.sumOf { probs[it].toDouble() }.toFloat()
            return LanguageGuess(codes[best], if (inSet > 0f) (probs[best] / inSet).coerceAtMost(1f) else probs[best])
        }
        if (probs[best] >= clearAt) return LanguageGuess(codes[best], probs[best])
        var pick = -1
        var sum = 0f
        for (i in 0 until n) {
            if (codes[i] !in allowed) continue
            sum += probs[i]
            if (pick < 0 || probs[i] > probs[pick]) pick = i
        }
        if (pick < 0) return LanguageGuess(codes[best], probs[best])
        return LanguageGuess(codes[pick], if (sum > 0f) probs[pick] / sum else 0f)
    }

    /** The Whisper code for one of the app's language codes ("nb" → "no", "fil" → "tl", …). */
    fun whisperCode(code: String): String = when (code) {
        "nb", "nn" -> "no"
        "fil" -> "tl"
        "jv" -> "jw"
        "cmn", "yue" -> "zh"
        "iw" -> "he"
        "in" -> "id"
        else -> code
    }
}
