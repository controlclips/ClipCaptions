import com.clipcaptions.app.SentenceJoiner

var failures = 0
fun check(name: String, ok: Boolean, detail: Any? = null) { println((if (ok) "PASS " else "FAIL ") + name + (if (!ok) "  -> $detail" else "")); if (!ok) failures++ }

fun main() {
    run {
        val j = SentenceJoiner()
        check("first half waits", j.add(1, "Ayer fui a la tienda y", "es", 0, 1000) == null)
        val w = j.add(2, "compré pan para mi madre.", "es", 0, 3000)
        check("second half gives the whole sentence", w?.text == "Ayer fui a la tienda y compré pan para mi madre.", w?.text)
        check("it replaces both lines", w?.lines == listOf(1, 2), w?.lines)
        check("next sentence starts clean", j.add(3, "Hola.", "es", 0, 4000) == null)
    }
    run {
        val j = SentenceJoiner()
        check("a whole sentence in one piece needs nothing", j.add(1, "Ich gehe nach Hause.", "de", 0, 0) == null)
    }
    run {
        val j = SentenceJoiner()
        j.add(1, "So I was thinking...", "en", 0, 0)
        val w = j.add(2, "...that we could go.", "en", 0, 1500)
        check("Whisper's ... joins are tidied", w?.text == "So I was thinking that we could go.", w?.text)
    }
    run {
        val j = SentenceJoiner()
        j.add(1, "我们今天", "zh", 0, 0)
        val w = j.add(2, "去公园吧。", "zh", 0, 1000)
        check("Chinese joined without a space", w?.text == "我们今天去公园吧。", w?.text)
    }
    run {
        val j = SentenceJoiner()
        j.add(1, "Ayer fui a la tienda y", "es", 0, 0)
        check("new video starts again", j.add(2, "bonjour à tous.", "fr", 1, 500) == null)
    }
    run {
        val j = SentenceJoiner()
        j.add(1, "Ayer fui a la tienda y", "es", 0, 0)
        check("long pause starts again", j.add(2, "compré pan.", "es", 0, 10_000) == null)
    }
    run {
        val j = SentenceJoiner()
        j.add(1, "Ayer fui a la tienda y", "es", 0, 0)
        check("other language starts again", j.add(2, "I bought bread.", "en", 0, 1000) == null)
    }
    run {
        val j = SentenceJoiner(maxWords = 10)
        j.add(1, "one two three four five six", "en", 0, 0)
        j.add(2, "seven eight nine ten eleven twelve", "en", 0, 1000)
        check("run-on sentence isn't joined", j.add(3, "the end.", "en", 0, 2000)?.lines != listOf(1, 2, 3))
    }
    run {
        val j = SentenceJoiner()
        j.add(1, "Ella dijo", "es", 0, 0)
        val w = j.add(2, "\"ya voy.\"", "es", 0, 1000)
        check("closing quote still ends the sentence", w?.text == "Ella dijo \"ya voy.\"", w?.text)
    }

    if (failures > 0) { println("$failures FAILED"); kotlin.system.exitProcess(1) } else println("ALL PASSED")
}
