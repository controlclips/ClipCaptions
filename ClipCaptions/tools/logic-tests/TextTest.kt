import com.clipcaptions.app.TranslationText

var failures = 0
fun check(name: String, ok: Boolean, detail: Any? = null) { println((if (ok) "PASS " else "FAIL ") + name + (if (!ok) "  -> $detail" else "")); if (!ok) failures++ }

fun main() {
    fun p(t: String, lang: String = "en", finished: Boolean = true) = TranslationText.prepare(t, lang, finished)

    // Before translating
    check("capital and full stop on a finished line", p("so i went to the shop") == "So I went to the shop.", p("so i went to the shop"))
    check("English lone i fixed", p("i think i'm right and i've said so") == "I think I'm right and I've said so.", p("i think i'm right and i've said so"))
    check("i inside words left alone", p("this is it") == "This is it.", p("this is it"))
    check("only English gets the i fix", p("i ragazzi", "it") == "I ragazzi.", p("i ragazzi", "it"))
    check("words still being spoken get no full stop", p("so i went", finished = false) == "So I went", p("so i went", finished = false))
    check("existing punctuation kept", p("¿qué pasa?", "es") == "¿Qué pasa?", p("¿qué pasa?", "es"))
    check("line cut at a comma stays open", p("honestly, I didn't expect that,") == "Honestly, I didn't expect that,", p("honestly, I didn't expect that,"))
    check("brand names left alone", p("iPhone is great") == "iPhone is great.", p("iPhone is great"))
    check("Chinese full stop", p("我们走吧", "zh") == "我们走吧。", p("我们走吧", "zh"))
    check("Hindi full stop", p("मैं घर जा रहा हूँ", "hi") == "मैं घर जा रहा हूँ।", p("मैं घर जा रहा हूँ", "hi"))
    check("Thai gets no full stop", p("ไปกันเถอะ", "th") == "ไปกันเถอะ", p("ไปกันเถอะ", "th"))
    check("Turkish capital i", p("istanbul güzel", "tr") == "İstanbul güzel.", p("istanbul güzel", "tr"))
    check("spaces tidied", p("  hello    there ") == "Hello there.", p("  hello    there "))
    check("empty stays empty", p("   ") == "")
    check("region code works", p("hola amigo", "es-ES") == "Hola amigo.", p("hola amigo", "es-ES"))

    // After translating
    fun t(out: String, src: String) = TranslationText.tidy(out, src)
    check("stuck phrase cut to two", t("I don't know, I don't know, I don't know, I don't know.", "No sé.") == "I don't know, I don't know.",
        t("I don't know, I don't know, I don't know, I don't know.", "No sé."))
    check("stuck word cut to two", t("No no no no no no.", "No.") == "No no.", t("No no no no no no.", "No."))
    check("repeats that were said are kept", t("No, no, no, no!", "¡No, no, no, no!") == "No, no, no, no!", t("No, no, no, no!", "¡No, no, no, no!"))
    check("normal sentence untouched", t("The man said that that was fine.", "El hombre dijo que eso estaba bien.") == "The man said that that was fine.",
        t("The man said that that was fine.", "El hombre dijo que eso estaba bien."))
    check("two copies allowed", t("Very very good.", "Muy muy bien.") == "Very very good.", t("Very very good.", "Muy muy bien."))
    check("output spaces tidied", t("  Hello   there. ", "Hola.") == "Hello there.", t("  Hello   there. ", "Hola."))

    if (failures > 0) { println("$failures FAILED"); kotlin.system.exitProcess(1) } else println("ALL PASSED")
}
