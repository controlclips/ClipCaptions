import com.clipcaptions.app.LineSplitter
import com.clipcaptions.app.SpeedStats

var failures = 0
fun check(name: String, ok: Boolean, detail: Any? = null) { println((if (ok) "PASS " else "FAIL ") + name + (if (!ok) "  -> $detail" else "")); if (!ok) failures++ }

fun main() {
    run {
        val s = LineSplitter(10, 3, 16, 5)
        val a = s.onPartial("so I went to the shop")
        check("short sentence stays live", a.finished.isEmpty() && a.live == "so I went to the shop", a.live)
        val long = "so I went to the shop yesterday and the man behind the counter said"
        val b = s.onPartial(long)
        check("long sentence: first ten words become a line", b.finished == listOf("so I went to the shop yesterday and the man"), b.finished)
        check("rest stays live", b.live == "behind the counter said", b.live)
        val c = s.onPartial("$long hello")
        check("line isn't repeated", c.finished.isEmpty() && c.live == "behind the counter said hello", c.live)
        val f = s.onFinal("$long hello to me.")
        check("final keeps only what wasn't shown", f == "behind the counter said hello to me.", f)
        check("next sentence starts clean", s.onPartial("next one").live == "next one")
    }
    run {
        val s = LineSplitter(10, 3, 16, 5)
        val t = "honestly, I didn't expect that at all, but then my friend told me the whole story"
        val r = s.onPartial(t)
        check("breaks after punctuation when it can", r.finished == listOf("honestly, I didn't expect that at all,"), r.finished)
        check("live after punctuation break", r.live == "but then my friend told me the whole story", r.live)
    }
    run {
        val s = LineSplitter(10, 3, 16, 5)
        val words = (1..40).joinToString(" ") { "w$it" }
        val r = s.onPartial(words)
        check("very long guess: several lines at once", r.finished.size == 3, r.finished)
        check("never more than 12 words live", r.live.split(" ").size in 3..12, r.live)
        val shorter = s.onPartial((1..20).joinToString(" ") { "w$it" })
        check("guess suddenly shorter: treated as a new sentence, nothing hidden", shorter.finished == listOf((1..10).joinToString(" ") { "w$it" }) && shorter.live == (11..20).joinToString(" ") { "w$it" }, shorter.finished.toString() + " | " + shorter.live)
    }
    run {
        val s = LineSplitter(10, 3, 16, 5)
        val zh = "我今天去了商店然后店员跟我说了一些很有意思的事情真的"
        val r = s.onPartial(zh)
        check("no spaces: splits by characters", r.finished.size == 1 && r.finished[0].length == 16, r.finished)
        check("characters joined back up", (r.finished.joinToString("") + r.live) == zh)
        val f = s.onFinal(zh + "。")
        check("final, no spaces", f == zh.substring(16) + "。", f)
    }
    run {
        val s = LineSplitter(10, 3, 16, 5)
        check("empty partial", s.onPartial("  ").live == "")
        check("empty final", s.onFinal("") == "")
    }
    run {
        val st = SpeedStats()
        check("no samples yet", st.wordsMedian() == null)
        listOf(300L, 900L, 500L, 20_000L, -5L).forEach { st.addWords(it) }
        check("median ignores nonsense", st.wordsMedian() == 500L, st.wordsMedian())
        st.addTranslation(200)
        check("translation median", st.translationMedian() == 200L)
    }
    run {
        val s = LineSplitter()
        val r = s.onPartial("Hola amigos. ¿Qué tal estáis? Hoy vamos")
        check("whole sentences become lines (short ones join the next)", r.finished == listOf("Hola amigos. ¿Qué tal estáis?"), r.finished)
        check("the next sentence stays live", r.live == "Hoy vamos", r.live)
        val f = s.onFinal("Hola amigos. ¿Qué tal estáis? Hoy vamos a la playa.")
        check("the last sentence is translated whole at the end", f == "Hoy vamos a la playa.", f)
    }
    run {
        val s = LineSplitter()
        check("a sentence isn't finished until the next one begins", s.onPartial("Ich bin heute sehr müde").finished.isEmpty())
        val r = s.onPartial("Ich bin heute sehr müde. Gehen wir")
        check("then it's a line", r.finished == listOf("Ich bin heute sehr müde.") && r.live == "Gehen wir", r.finished.toString() + " | " + r.live)
    }
    run {
        val s = LineSplitter()
        val r = s.onPartial((1..34).joinToString(" ") { "w" })
        check("no sentence end: cut at 30 words", r.finished.size == 1 && r.finished[0].split(" ").size == 30, r.finished)
        check("33 words stay live", LineSplitter().onPartial((1..33).joinToString(" ") { "w" }).finished.isEmpty())
    }
    run {
        val s = LineSplitter()
        val r = s.onPartial("今天天气很好。我们去公园吧")
        check("no spaces: whole sentences", r.finished == listOf("今天天气很好。") && r.live == "我们去公园吧", r.finished.toString() + " | " + r.live)
    }
    if (failures > 0) { println("$failures FAILED"); kotlin.system.exitProcess(1) } else println("ALL PASSED")
}
