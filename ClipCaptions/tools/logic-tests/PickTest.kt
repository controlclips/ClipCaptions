import com.clipcaptions.app.LanguagePick
import com.clipcaptions.app.SpeechChunker

var failures = 0
fun check(name: String, ok: Boolean, detail: Any? = null) { println((if (ok) "PASS " else "FAIL ") + name + (if (!ok) "  -> $detail" else "")); if (!ok) failures++ }

fun main() {
    val codes = arrayOf("en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "it")
    fun probs(vararg pairs: Pair<String, Float>) = FloatArray(codes.size) { i -> pairs.firstOrNull { it.first == codes[i] }?.second ?: 0.001f }

    run {
        val g = LanguagePick.pick(codes, probs("es" to 0.55f, "pt" to 0.30f, "en" to 0.10f), listOf("en", "es"))
        check("best allowed language picked", g?.code == "es", g?.code)
        check("confidence is among the allowed ones", g != null && g.confidence > 0.8f, g?.confidence)
    }
    run {
        val g = LanguagePick.pick(codes, probs("pt" to 0.5f, "es" to 0.3f, "en" to 0.1f), listOf("en", "es"))
        check("unclear other language: stays within your languages", g?.code == "es", g?.code)
    }
    run {
        val g = LanguagePick.pick(codes, probs("ja" to 0.9f, "en" to 0.05f), listOf("en", "es"), clearAt = 0.8f)
        check("clearly another language: chosen anyway (Whisper mode)", g?.code == "ja", g?.code)
        val google = LanguagePick.pick(codes, probs("ja" to 0.9f, "en" to 0.05f), listOf("en", "es"), clearAt = 2f)
        check("Google mode never leaves your languages", google?.code == "en", google?.code)
    }
    run {
        val g = LanguagePick.pick(codes, probs("fr" to 0.7f), emptyList())
        check("no languages chosen: any", g?.code == "fr" && g.confidence == 0.7f, g?.code)
    }
    run {
        check("empty input", LanguagePick.pick(emptyArray(), FloatArray(0), emptyList()) == null)
        check("code mapping", LanguagePick.whisperCode("nb") == "no" && LanguagePick.whisperCode("fil") == "tl" && LanguagePick.whisperCode("es") == "es")
    }
    run {
        // Chunker: first chunk of a video is cut at 2 s even without a pause; later ones at 3.5 s.
        val c = SpeechChunker(sampleRate = 16000, minChunkMs = 1200, maxChunkMs = 3500, loudLevel = 400, firstMaxChunkMs = 2000)
        val loud = ShortArray(1600) { if (it % 2 == 0) 3000 else -3000 }
        val sizes = ArrayList<Int>()
        repeat(100) { for (ch in c.add(loud, loud.size)) sizes.add(ch.size) }
        check("first chunk at most 2 s", sizes.isNotEmpty() && sizes[0] <= 32000, sizes.take(3))
        check("later chunks at most 3.5 s", sizes.drop(1).all { it <= 56000 } && sizes.size >= 3, sizes.take(5))
        c.newVideo()
        val after = ArrayList<Int>()
        repeat(30) { for (ch in c.add(loud, loud.size)) after.add(ch.size) }
        check("after a swipe, first chunk is short again", after.isNotEmpty() && after[0] <= 32000, after.take(2))
    }
    if (failures > 0) { println("$failures FAILED"); kotlin.system.exitProcess(1) } else println("ALL PASSED")
}
