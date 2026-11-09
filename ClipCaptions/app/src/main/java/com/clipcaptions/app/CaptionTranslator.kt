package com.clipcaptions.app

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Translates captions from one language into another. Uses Mozilla's translator when its files for
 * the pair are on the phone (much better), otherwise Google's ML Kit packs. Results arrive on the
 * main thread, like ML Kit's.
 */
class CaptionTranslator private constructor(
    private val context: Context,
    val source: String,
    val target: String,
    private val legs: List<MozModels.Leg>?,
    private val mlkitSupported: Boolean,
) {
    private var mlkit: Translator? = null

    /** True when Mozilla's translator does the work. */
    val usesMozilla: Boolean get() = legs != null

    /**
     * Translates [text]. [finished] is false for the words still being spoken, so they aren't given a
     * full stop yet (see [TranslationText]).
     */
    fun translate(text: String, finished: Boolean = true): Task<String> {
        val input = TranslationText.prepare(text, source, finished)
        if (input.isEmpty()) return Tasks.forResult(text)
        return translateRaw(input).continueWith { task ->
            // Fails here too if the translation failed, so callers' failure handling still runs.
            val out = TranslationText.tidy(task.result, input)
            if (out.isEmpty()) throw IllegalStateException("empty translation")
            out
        }
    }

    private fun translateRaw(text: String): Task<String> {
        val route = legs ?: return mlkitTranslate(text)
        if (!mlkitSupported) return MozEngines.translate(context, route, text)
        // If Mozilla's files turn out to be unusable, fall back to ML Kit rather than show nothing.
        return MozEngines.translate(context, route, text).continueWithTask { task ->
            if (task.isSuccessful) task else mlkitTranslate(text)
        }
    }

    /**
     * Makes sure the translation is on the phone. Mozilla's files are only ever downloaded up front
     * (from the main screen), never while you watch, so this only fetches ML Kit packs.
     */
    fun downloadModelIfNeeded(): Task<Void> {
        if (legs != null) return Tasks.forResult<Void>(null)
        val tr = mlkitClient() ?: return Tasks.forException(IllegalStateException("not translatable"))
        return tr.downloadModelIfNeeded()
    }

    fun close() {
        mlkit?.close()
        mlkit = null
    }

    private fun mlkitTranslate(text: String): Task<String> {
        val tr = mlkitClient() ?: return Tasks.forException(IllegalStateException("not translatable"))
        return tr.translate(text)
    }

    private fun mlkitClient(): Translator? {
        mlkit?.let { return it }
        if (!mlkitSupported) return null
        val from = TranslateLanguage.fromLanguageTag(source) ?: return null
        val to = TranslateLanguage.fromLanguageTag(target) ?: return null
        val options = TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build()
        return Translation.getClient(options).also { mlkit = it }
    }

    companion object {
        /** A translator for [source] → [target], or null if neither translator covers the pair. */
        fun create(context: Context, source: String, target: String): CaptionTranslator? {
            if (source == target) return null
            val app = context.applicationContext
            val mlkit = TranslateLanguage.fromLanguageTag(source) != null &&
                TranslateLanguage.fromLanguageTag(target) != null
            val legs = MozModels.readyRoute(app, source, target)
            if (legs == null && !mlkit) return null
            return CaptionTranslator(app, source, target, legs, mlkit)
        }
    }
}
