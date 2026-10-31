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
) {
    private val maxSamples = sampleRate * maxChunkMs / 1000
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

        if (length + n > maxSamples) cutAtQuietestPoint(ready)

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
            reset()
        } else if (length >= maxSamples) {
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
        val searchFrom = length - sampleRate * searchBackMs / 1000
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
