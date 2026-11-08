package com.clipcaptions.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mozilla's translation models (the ones Firefox uses): which language pairs exist, which are on the
 * phone, and downloading them, once, before captions start.
 *
 * Every model goes to or from English, so Spanish → French, say, uses two: Spanish → English and
 * English → French. Each pair is 15–40 MB on the phone.
 */
object MozModels {

    /** One Mozilla model, in Mozilla's language codes ("es", "zh-Hans"…). */
    data class Leg(val src: String, val trg: String) {
        val key: String get() = "$src-$trg"
    }

    class Files(val model: String, val srcVocab: String, val trgVocab: String, val lex: String?)

    private const val RECORDS =
        "https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/translations-models-v2/records"
    private const val CDN = "https://firefox-settings-attachments.cdn.mozilla.net/"
    // Clip Captions' own copy of the models (Mozilla asks apps not to rely on Firefox's servers),
    // kept up to date by .github/workflows/models.yml. Mozilla's servers are the fallback.
    private const val MIRROR = "https://github.com/controlclips/ClipCaptions/releases/download/models/"
    // Model versions this app's translator reads (3.x). A new major version needs a new app.
    private const val SUPPORTED_MAJOR = 3

    // Languages with a model into English, and from English, in Mozilla's codes. Checked against
    // Mozilla's list on 8 Nov 2026 (translations-models-v2).
    private val INTO_ENGLISH = MozCatalogue.INTO_ENGLISH
    private val FROM_ENGLISH = MozCatalogue.FROM_ENGLISH

    /** Mozilla's code for one of the app's language codes, or null if Mozilla has no model for it. */
    fun mozCode(code: String): String? {
        if (code == "en") return "en"
        val base = when (code) {
            "no" -> "nb"
            "iw" -> "he"
            "in" -> "id"
            "fil" -> "tl"
            // Simplified Chinese (mainland, and what the speech recognisers write).
            "zh" -> "zh-Hans"
            else -> code
        }
        val all = INTO_ENGLISH + FROM_ENGLISH
        return all.firstOrNull { it == base } ?: all.firstOrNull { it.substringBefore('-') == base }
    }

    /** The models needed for [source] → [target] (app codes), or null if Mozilla can't do it. */
    fun route(source: String, target: String): List<Leg>? {
        if (source == target) return null
        val from = mozCode(source) ?: return null
        val to = mozCode(target) ?: return null
        return when {
            from == "en" -> if (to in FROM_ENGLISH) listOf(Leg("en", to)) else null
            to == "en" -> if (from in INTO_ENGLISH) listOf(Leg(from, "en")) else null
            from in INTO_ENGLISH && to in FROM_ENGLISH -> listOf(Leg(from, "en"), Leg("en", to))
            else -> null
        }
    }

    /** True if Mozilla's translator can do [source] → [target] on this phone (downloaded or not). */
    fun covers(source: String, target: String): Boolean = MozTranslate.available && route(source, target) != null

    /** The route for [source] → [target] if every model it needs is on the phone, else null. */
    fun readyRoute(context: Context, source: String, target: String): List<Leg>? {
        if (!MozTranslate.available) return null
        val legs = route(source, target) ?: return null
        return legs.takeIf { it.all { leg -> isDownloaded(context, leg) } }
    }

    /** Every app language code whose translation into [target] is fully on the phone. */
    fun readyAppCodes(context: Context, target: String): Set<String> {
        if (!MozTranslate.available) return emptySet()
        val candidates = (INTO_ENGLISH + FROM_ENGLISH + "en").map { appCode(it) }.toSet()
        return candidates.filter { it != target && readyRoute(context, it, target) != null }.toSet()
    }

    /** The app's code for one of Mozilla's ("nb" → "no", "zh-Hans" → "zh"). */
    private fun appCode(moz: String): String = when (moz) {
        "nb" -> "no"
        else -> moz.substringBefore('-')
    }

    fun isDownloaded(context: Context, leg: Leg): Boolean = File(dir(context, leg), READY).exists()

    /** The files for [leg], or null if they aren't all on the phone. */
    fun files(context: Context, leg: Leg): Files? {
        val dir = dir(context, leg)
        if (!File(dir, READY).exists()) return null
        val model = File(dir, "model.bin")
        val shared = File(dir, "vocab.spm")
        val src = if (shared.exists()) shared else File(dir, "srcvocab.spm")
        val trg = if (shared.exists()) shared else File(dir, "trgvocab.spm")
        val lex = File(dir, "lex.bin")
        if (!model.exists() || !src.exists() || !trg.exists()) return null
        return Files(model.path, src.path, trg.path, lex.path.takeIf { lex.exists() })
    }

    /** Deletes [leg]'s files (when they turn out damaged), so they're downloaded afresh next time. */
    fun forget(context: Context, leg: Leg) {
        dir(context, leg).deleteRecursively()
    }

    /**
     * Downloads every model [legs] need that isn't on the phone yet. Blocking: call it off the main
     * thread. [onProgress] gets (model number, number of models, percent). Throws if it fails.
     */
    fun download(context: Context, legs: List<Leg>, onProgress: (Int, Int, Int) -> Unit) {
        val needed = legs.distinctBy { it.key }.filter { !isDownloaded(context, it) }
        needed.forEachIndexed { index, leg ->
            onProgress(index, needed.size, 0)
            downloadLeg(context, leg) { percent -> onProgress(index, needed.size, percent) }
        }
    }

    /** How many models [legs] still need to download. */
    fun missing(context: Context, legs: List<Leg>): Int = legs.distinctBy { it.key }.count { !isDownloaded(context, it) }

    // ---- Downloading ----

    private const val READY = "ready"
    private val FILE_TYPES = mapOf(
        "model" to "model.bin",
        "vocab" to "vocab.spm",
        "srcvocab" to "srcvocab.spm",
        "trgvocab" to "trgvocab.spm",
        "lex" to "lex.bin",
    )

    private class Item(
        val type: String,
        val version: List<Int>,
        val preRelease: Boolean,
        val architecture: String,
        val url: String,
        val size: Long,
        val sha256: String?,
    )

    private fun dir(context: Context, leg: Leg) = File(context.filesDir, "mozilla/${leg.key}")

    private fun downloadLeg(context: Context, leg: Leg, onPercent: (Int) -> Unit) {
        val mirrored = try {
            mirrorFiles(leg)
        } catch (e: Exception) {
            null
        }
        if (mirrored != null) {
            try {
                install(context, leg, mirrored, onPercent)
                return
            } catch (e: IOException) {
                // The copy couldn't be fetched: try Mozilla's servers instead.
            }
        }
        install(context, leg, mozillaFiles(leg), onPercent)
    }

    private fun install(context: Context, leg: Leg, items: Map<String, Item>, onPercent: (Int) -> Unit) {
        if (items["model"] == null || (items["vocab"] == null && (items["srcvocab"] == null || items["trgvocab"] == null))) {
            throw IOException("there's no complete model for ${leg.key}")
        }
        val dir = dir(context, leg)
        dir.deleteRecursively()
        dir.mkdirs()
        val total = items.values.sumOf { it.size.coerceAtLeast(1) }
        var done = 0L
        try {
            for (item in items.values) {
                val packed = File(dir, FILE_TYPES.getValue(item.type) + ".zst")
                Downloads.download(item.url, packed) { percent ->
                    onPercent(((done + item.size * percent / 100) * 100 / total).toInt())
                }
                val problem = MozTranslate.nativeUnpack(packed.path, File(dir, FILE_TYPES.getValue(item.type)).path, item.sha256)
                packed.delete()
                if (problem != null) throw IOException("the ${leg.key} ${item.type} file didn't unpack: $problem")
                done += item.size.coerceAtLeast(1)
            }
        } catch (e: IOException) {
            dir.deleteRecursively()
            throw e
        }
        File(dir, READY).writeText(items.getValue("model").version.joinToString("."))
        onPercent(100)
    }

    // The mirror's list of files, fetched once per run of the app.
    @Volatile
    private var mirrorList: JSONObject? = null

    /** [leg]'s files on Clip Captions' own copy, or null if it isn't there. */
    private fun mirrorFiles(leg: Leg): Map<String, Item>? {
        val list = mirrorList ?: JSONObject(fetch(MIRROR + "models.json")).also { mirrorList = it }
        val entries = list.optJSONObject("pairs")?.optJSONArray(leg.key) ?: return null
        val items = LinkedHashMap<String, Item>()
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            val type = entry.optString("type")
            if (type !in FILE_TYPES) continue
            val versionText = entry.optString("version")
            val version = versionText.split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
            if (version.firstOrNull() != SUPPORTED_MAJOR) return null
            items[type] = Item(
                type = type,
                version = version,
                preRelease = versionText.any { it.isLetter() },
                architecture = entry.optString("architecture"),
                url = MIRROR + entry.optString("asset"),
                size = entry.optLong("size", 0L),
                sha256 = entry.optString("sha256").takeIf { it.isNotEmpty() },
            )
        }
        return items.takeIf { it.isNotEmpty() }
    }

    /**
     * The files Mozilla's servers list for [leg]: the newest model meant for Android release builds,
     * and the vocabulary and word list that go with it. (The mirror picks the same ones.)
     */
    private fun mozillaFiles(leg: Leg): Map<String, Item> {
        val body = try {
            fetch("$RECORDS?sourceLanguage=${leg.src}&targetLanguage=${leg.trg}")
        } catch (e: IOException) {
            fetch(RECORDS)
        }
        val data = JSONObject(body).getJSONArray("data")
        val items = ArrayList<Item>()
        for (i in 0 until data.length()) {
            val record = data.optJSONObject(i) ?: continue
            if (record.optString("sourceLanguage") != leg.src || record.optString("targetLanguage") != leg.trg) continue
            val type = record.optString("fileType")
            if (type !in FILE_TYPES) continue
            if (!forAndroidRelease(record.optString("filter_expression"))) continue
            val attachment = record.optJSONObject("attachment") ?: continue
            val location = attachment.optString("location").takeIf { it.isNotEmpty() } ?: continue
            val versionText = record.optString("version")
            val version = versionText.split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
            if (version.firstOrNull() != SUPPORTED_MAJOR) continue
            items += Item(
                type = type,
                version = version,
                preRelease = versionText.any { it.isLetter() },
                architecture = record.optString("architecture"),
                url = CDN + location,
                size = attachment.optLong("size", 0L),
                sha256 = record.optString("decompressedHash").takeIf { it.isNotEmpty() },
            )
        }
        val newestFirst = Comparator<Item> { a, b ->
            if (a.preRelease != b.preRelease) (if (a.preRelease) 1 else -1) else compareVersions(b.version, a.version)
        }
        val model = items.filter { it.type == "model" }.sortedWith(newestFirst).firstOrNull() ?: return emptyMap()
        val chosen = linkedMapOf("model" to model)
        for (type in FILE_TYPES.keys - "model") {
            val candidates = items.filter { it.type == type }
            // The files made with the model (same version and size of model), else the newest.
            val match = candidates.filter { it.version == model.version && it.architecture == model.architecture }
                .sortedWith(newestFirst).firstOrNull()
                ?: candidates.sortedWith(newestFirst).firstOrNull()
                ?: continue
            chosen[type] = match
        }
        // A pair has either one shared vocabulary or one for each side, never both.
        if (chosen.containsKey("vocab")) {
            chosen.remove("srcvocab")
            chosen.remove("trgvocab")
        }
        return chosen
    }

    /**
     * Mozilla marks some files for Firefox Nightly only, or for desktop only. Keep the ones a release
     * build on Android would get.
     */
    private fun forAndroidRelease(expression: String): Boolean {
        val e = expression.replace('"', '\'').replace(" ", "")
        if (e.isEmpty()) return true
        if ("env.appinfo.OS!='Android'" in e) return false
        if ("env.channel" in e && "'release'" !in e) return false
        return true
    }

    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("Mozilla's model list answered $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
