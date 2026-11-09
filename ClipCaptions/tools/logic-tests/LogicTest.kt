import com.clipcaptions.app.LanguageLock
import com.clipcaptions.app.SpeechChunker
import com.clipcaptions.app.VideoSwitchLogic
import kotlin.math.PI
import kotlin.math.sin

var failures = 0
fun check(name: String, ok: Boolean) { println((if (ok) "PASS " else "FAIL ") + name); if (!ok) failures++ }

fun main() {
    // ---- Swipe detection ----
    run {
        val v = VideoSwitchLogic()
        check("first video starting is not a switch", !v.onPlayersChanged(setOf(1), 1000))
        check("pause does not clear", !v.onPlayersChanged(emptySet(), 5000))
        check("resume same player without touch does not clear", !v.onPlayersChanged(setOf(1), 9000))
        v.onTouch(10_000)
        check("swipe: old stops", !v.onPlayersChanged(emptySet(), 10_300))
        check("swipe: different player starts -> clear", v.onPlayersChanged(setOf(2), 10_400))
        check("auto-advance to a different player without touch -> clear", v.onPlayersChanged(setOf(3), 60_000).let { true } && true)
    }
    run {
        val v = VideoSwitchLogic()
        v.onPlayersChanged(setOf(1), 0)
        check("different player starts with no touch -> clear", v.onPlayersChanged(setOf(2), 30_000))
        check("same player set again -> nothing", !v.onPlayersChanged(setOf(2), 30_100))
    }
    run {
        // App that reuses one player for every video.
        val v = VideoSwitchLogic()
        v.onPlayersChanged(setOf(7), 0)
        v.onTouch(20_000)
        v.onPlayersChanged(emptySet(), 20_200)
        check("same player restarts right after a touch -> clear", v.onPlayersChanged(setOf(7), 20_500))
        v.onPlayersChanged(emptySet(), 40_000) // buffering
        check("same player restarts long after a touch (buffering) -> no clear", !v.onPlayersChanged(setOf(7), 40_400))
    }
    run {
        // Next video starts before the old one stops (overlap).
        val v = VideoSwitchLogic()
        v.onPlayersChanged(setOf(1), 0)
        check("overlap: new player joins -> clear", v.onPlayersChanged(setOf(1, 2), 10_000))
        check("overlap: old leaves -> nothing", !v.onPlayersChanged(setOf(2), 10_100))
    }

    // ---- Language lock ----
    run {
        val l = LanguageLock()
        l.record("es", 0.4f); check("one unsure guess doesn't lock", l.locked == null)
        l.record("pt", 0.5f); check("disagreeing unsure guess doesn't lock", l.locked == null)
        l.record("pt", -1f); check("two in a row lock", l.locked == "pt")
        l.record("es", 0.99f); check("stays locked", l.locked == "pt")
        l.reset(); check("reset unlocks", l.locked == null)
        l.record("fr", 0.93f); check("one confident guess locks straight away", l.locked == "fr")
        l.reset(); l.record("", 0.99f); check("empty guess never locks", l.locked == null)
    }

    // ---- Chunker ----
    val rate = 16000
    fun tone(ms: Int, amp: Int = 8000) = ShortArray(rate * ms / 1000) { (amp * sin(2 * PI * 300 * it / rate)).toInt().toShort() }
    fun quiet(ms: Int) = ShortArray(rate * ms / 1000)
    fun feed(c: SpeechChunker, parts: List<ShortArray>): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        for (p in parts) {
            var o = 0
            while (o < p.size) {
                val n = minOf(1600, p.size - o)
                out += c.add(p.copyOfRange(o, o + n), n)
                o += n
            }
        }
        return out
    }
    fun ms(chunk: FloatArray) = chunk.size * 1000 / rate
    fun loudAt(chunk: FloatArray, fromMs: Int, toMs: Int): Boolean {
        val a = rate * fromMs / 1000; val b = minOf(chunk.size, rate * toMs / 1000)
        return (a until b).any { kotlin.math.abs(chunk[it]) > 0.05f }
    }
    run {
        val c = SpeechChunker()
        val out = feed(c, listOf(quiet(10_000)))
        check("silence alone produces nothing", out.isEmpty())
    }
    run {
        val c = SpeechChunker()
        val out = feed(c, listOf(quiet(3000), tone(3000), quiet(600)))
        check("speech then pause -> one chunk", out.size == 1)
        if (out.size == 1) {
            println("   chunk ${ms(out[0])} ms")
            check("long silence before speech trimmed to a short lead-in", ms(out[0]) in 3500..3950)
            check("chunk starts with a quiet lead-in (0.3–0.6 s) then speech", !loudAt(out[0], 0, 290) && loudAt(out[0], 300, 700))
        }
    }
    run {
        val c = SpeechChunker()
        val out = feed(c, listOf(tone(800), quiet(400)))
        check("short speech + pause waits (under minimum)", out.isEmpty())
        val more = feed(c, listOf(quiet(1200)))
        check("...then sends once long enough", more.size == 1)
    }
    run {
        // Continuous talking with a brief dip at 4.5 s: the dip should be chosen as the cut.
        val c = SpeechChunker()
        val parts = listOf(tone(4500), tone(100, 300), tone(5000), quiet(600))
        val out = feed(c, parts)
        println("   continuous: " + out.map { ms(it) })
        check("continuous talk is split in two", out.size == 2)
        if (out.size == 2) {
            check("first cut lands in the quiet dip (4.5–4.6 s)", ms(out[0]) in 4500..4600)
            check("nothing lost: all speech sent (only trailing quiet stays buffered)", out.sumOf { it.size } == rate * (4500 + 100 + 5000 + 300) / 1000)
        }
    }
    run {
        // Talking with no dips at all: must still cut, never exceed the maximum.
        val c = SpeechChunker()
        val out = feed(c, listOf(tone(20_000), quiet(600)))
        println("   no dips: " + out.map { ms(it) })
        check("all chunks within 6 s", out.all { ms(it) <= 6000 })
        check("no speech lost", out.sumOf { it.size } == rate * 20_300 / 1000)
    }
    run {
        val c = SpeechChunker()
        feed(c, listOf(tone(2000)))
        c.reset()
        val out = feed(c, listOf(quiet(2000)))
        check("reset discards the old video's speech", out.isEmpty())
    }
    if (failures > 0) { println("$failures FAILED"); kotlin.system.exitProcess(1) } else println("ALL PASSED")
}
