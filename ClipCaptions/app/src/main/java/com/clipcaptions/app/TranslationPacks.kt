package com.clipcaptions.app

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel

/**
 * Keeps track of which translation packs (Google ML Kit, about 30 MB each) are on the phone, so
 * each one is downloaded once and captions never wait for a check that's already been done.
 */
object TranslationPacks {

    private const val PREFS = "translation_packs"
    private const val KEY_READY = "ready"

    /** Language codes (such as "es") whose packs are known to be on the phone. Instant: no waiting. */
    fun known(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_READY, emptySet()).orEmpty()

    fun remember(context: Context, codes: Collection<String>) {
        if (codes.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = HashSet(prefs.getStringSet(KEY_READY, emptySet()).orEmpty())
        if (all.addAll(codes)) prefs.edit().putStringSet(KEY_READY, all).apply()
    }

    /** Asks ML Kit which packs are actually downloaded, and remembers them. */
    fun refresh(context: Context, done: (Set<String>) -> Unit) {
        val app = context.applicationContext
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                val codes = models.map { it.language }.toSet()
                // Replace rather than add, in case a pack was deleted in the phone's settings.
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_READY, codes).apply()
                done(codes)
            }
            .addOnFailureListener { done(known(app)) }
    }

    /**
     * Downloads the packs for [codes] that aren't on the phone yet, one at a time.
     * [progress] gets (language code, number done, total). [done] gets the codes that failed.
     */
    fun download(
        context: Context,
        codes: List<String>,
        progress: (String, Int, Int) -> Unit,
        done: (List<String>) -> Unit,
    ) {
        val app = context.applicationContext
        refresh(app) { present ->
            val needed = codes.distinct().filter { it !in present && TranslateLanguage.fromLanguageTag(it) != null }
            val failed = ArrayList<String>()
            fun next(index: Int) {
                if (index >= needed.size) {
                    done(failed)
                    return
                }
                val code = needed[index]
                progress(code, index, needed.size)
                val model = TranslateRemoteModel.Builder(TranslateLanguage.fromLanguageTag(code)!!).build()
                RemoteModelManager.getInstance().download(model, DownloadConditions.Builder().build())
                    .addOnSuccessListener {
                        remember(app, listOf(code))
                        next(index + 1)
                    }
                    .addOnFailureListener {
                        failed += code
                        next(index + 1)
                    }
            }
            next(0)
        }
    }
}
