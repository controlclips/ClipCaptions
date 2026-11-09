import com.clipcaptions.app.LeftoverWords

var failures = 0
fun check(name: String, ok: Boolean, detail: Any? = null) { println((if (ok) "PASS " else "FAIL ") + name + (if (!ok) "  -> $detail" else "")); if (!ok) failures++ }

fun main() {
    run {
        val l = LeftoverWords()
        check("no swipe: words pass through", l.onPartial("hello this is") == "hello this is")
        l.onPartial("hello this is the old video")
        l.cut() // swipe
        check("right after swipe: old words hidden", l.onPartial("hello this is the old video") == "")
        val p = l.onPartial("hello this is the old video hola amigos")
        check("new video's words shown straight away", p == "hola amigos", p)
        val f = l.onFinal("Hello, this is the old video. Hola amigos.")
        check("finished sentence keeps only the new words", f == "Hola amigos.", f)
        check("next sentence starts clean", l.onPartial("qué tal") == "qué tal")
    }
    run {
        val l = LeftoverWords()
        l.cut()
        check("swipe during silence changes nothing", l.onPartial("bonjour") == "bonjour")
    }
    run {
        val l = LeftoverWords()
        l.onPartial("the end of the old one")
        l.cut()
        val f = l.onFinal("The end of the old one.")
        check("old sentence finishing after the swipe shows nothing", f == "", f)
        check("then the new video shows normally", l.onPartial("hallo zusammen") == "hallo zusammen")
    }
    run {
        val l = LeftoverWords()
        l.onPartial("first video words")
        l.cut()
        l.onPartial("first video words second")
        l.cut() // quick second swipe
        val p = l.onPartial("first video words second third video")
        check("two quick swipes: only the newest video's words", p == "third video", p)
    }
    run {
        val l = LeftoverWords()
        l.onPartial("こんにちは")
        l.cut()
        val p = l.onPartial("こんにちは元気ですか")
        check("Japanese (no spaces): old characters dropped", p == "元気ですか", p)
    }
    run {
        val l = LeftoverWords()
        l.onPartial("one two three four")
        l.cut()
        check("recogniser revises to fewer words: nothing old leaks", l.onPartial("one two tree") == "")
        check("...and new words appear once spoken", l.onPartial("one two three four five") == "five")
    }
    run {
        val l = LeftoverWords()
        l.onPartial("this is the end of the old video and it goes on a bit")
        l.cut() // swipe; Google starts a new sentence without finishing the old one
        val p = l.onPartial("hola")
        check("new sentence after a swipe shows straight away", p == "hola", p)
        val p2 = l.onPartial("hola amigos que tal")
        check("and keeps showing", p2 == "hola amigos que tal", p2)
    }
    run {
        val l = LeftoverWords()
        l.onPartial("this is the old video")
        l.cut()
        val same = l.onPartial("This, is the old video")
        check("same sentence still hidden", same == "", same)
        val more = l.onPartial("This, is the old video bonjour")
        check("capitals/punctuation don't count as a new sentence", more == "bonjour", more)
    }
    run {
        val l = LeftoverWords()
        l.onPartial("我们今天去")
        l.cut()
        val p = l.onPartial("你好")
        check("no spaces: new sentence shows", p == "你好", p)
    }
    if (failures > 0) { println("$failures FAILED"); kotlin.system.exitProcess(1) } else println("ALL PASSED")
}
