import com.clipcaptions.app.SentenceJoiner

var failures = 0
fun check(name: String, ok: Boolean, detail: Any? = null) { println((if (ok) "PASS " else "FAIL ") + name + (if (!ok) "  -> $detail" else "")); if (!ok) failures++ }

fun main() {
    run {
        val j = SentenceJoiner()
        check("first piece waits", j.add(1, "Ayer fui a la tienda y", "es", 0, 1000) == null)
        val w = j.add(2, "compré pan para mi madre.", "es", 0, 3000)
        check("second piece joins the first", w?.text == "Ayer fui a la tienda y compré pan para mi madre.", w?.text)
        check("it replaces both lines", w?.lines == listOf(1, 2), w?.lines)
    }
    run {
        // Whisper puts a full stop on a piece cut mid-sentence: still joined, without the stop.
        val j = SentenceJoiner()
        j.add(1, "Ayer fui a la tienda.", "es", 0, 0)
        val w = j.add(2, "Compré pan.", "es", 0, 4000)
        check("cut-off full stop removed and joined", w?.text == "Ayer fui a la tienda compré pan.", w?.text)
    }
    run {
        // The group keeps growing while it's short.
        val j = SentenceJoiner()
        j.add(1, "So I was", "en", 0, 0)
        j.add(2, "thinking that", "en", 0, 1000)
        val w = j.add(3, "we could go.", "en", 0, 2000)
        check("three pieces together", w?.text == "So I was thinking that we could go." && w.lines == listOf(1, 2, 3), w?.text)
    }
    run {
        // A full stop after plenty of speech is a real sentence end.
        val j = SentenceJoiner()
        j.add(1, "Yesterday I went to the shop with my brother and we bought some bread.", "en", 0, 0)
        check("after a long sentence, the next starts fresh", j.add(2, "Then we went home.", "en", 0, 2000) == null)
    }
    run {
        val j = SentenceJoiner()
        j.add(1, "¿Dónde está?", "es", 0, 0)
        check("a question ends the group", j.add(2, "No lo sé.", "es", 0, 1000) == null)
    }
    run {
        val j = SentenceJoiner()
        j.add(1, "So I was thinking...", "en", 0, 0)
        val w = j.add(2, "...that we could go.", "en", 0, 1500)
        check("Whisper's ... joins are tidied", w?.text == "So I was thinking that we could go.", w?.text)
    }
    run {
        val j = SentenceJoiner()
        j.add(1, "Well.", "en", 0, 0)
        val w = j.add(2, "I think so.", "en", 0, 1000)
        check("English I keeps its capital", w?.text == "Well I think so.", w?.text)
    }
    run {
        val j = SentenceJoiner()
        j.add(1, "Ich habe", "de", 0, 0)
        val w = j.add(2, "Brot gekauft.", "de", 0, 1000)
        check("German nouns keep their capitals", w?.text == "Ich habe Brot gekauft.", w?.text)
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
        check("too long: the new piece starts a group", j.add(2, "seven eight nine ten eleven twelve", "en", 0, 1000) == null)
        val w = j.add(3, "the end", "en", 0, 2000)
        check("and the next piece joins that one", w?.lines == listOf(2, 3), w?.lines)
    }
    run {
        val j = SentenceJoiner()
        j.add(1, "Ella dijo", "es", 0, 0)
        val w = j.add(2, "\"ya voy.\"", "es", 0, 1000)
        check("quotes kept", w?.text == "Ella dijo \"ya voy.\"", w?.text)
    }

    if (failures > 0) { println("$failures FAILED"); kotlin.system.exitProcess(1) } else println("ALL PASSED")
}
