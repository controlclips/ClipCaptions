package com.clipcaptions.app

import java.util.Locale

/** A language the app can hear, with the offline Vosk speech model used for it. */
data class SpokenLanguage(val name: String, val code: String, val voskModel: String)

/** A language the captions can be shown in. Codes are ML Kit translation codes. */
data class CaptionLanguage(val name: String, val code: String)

object Languages {

    val spoken = listOf(
        SpokenLanguage("English", "en", "vosk-model-small-en-us-0.15"),
        SpokenLanguage("Spanish", "es", "vosk-model-small-es-0.42"),
        SpokenLanguage("French", "fr", "vosk-model-small-fr-0.22"),
        SpokenLanguage("German", "de", "vosk-model-small-de-0.15"),
        SpokenLanguage("Italian", "it", "vosk-model-small-it-0.22"),
        SpokenLanguage("Portuguese", "pt", "vosk-model-small-pt-0.3"),
        SpokenLanguage("Polish", "pl", "vosk-model-small-pl-0.22"),
        SpokenLanguage("Dutch", "nl", "vosk-model-small-nl-0.22"),
        SpokenLanguage("Russian", "ru", "vosk-model-small-ru-0.22"),
        SpokenLanguage("Ukrainian", "uk", "vosk-model-small-uk-v3-small"),
        SpokenLanguage("Turkish", "tr", "vosk-model-small-tr-0.3"),
        SpokenLanguage("Hindi", "hi", "vosk-model-small-hi-0.22"),
        SpokenLanguage("Persian", "fa", "vosk-model-small-fa-0.5"),
        SpokenLanguage("Chinese (Mandarin)", "zh", "vosk-model-small-cn-0.22"),
        SpokenLanguage("Japanese", "ja", "vosk-model-small-ja-0.22"),
        SpokenLanguage("Korean", "ko", "vosk-model-small-ko-0.22"),
        SpokenLanguage("Vietnamese", "vi", "vosk-model-small-vn-0.4"),
    )

    val caption = listOf(
        CaptionLanguage("English", "en"),
        CaptionLanguage("Welsh", "cy"),
        CaptionLanguage("Spanish", "es"),
        CaptionLanguage("French", "fr"),
        CaptionLanguage("German", "de"),
        CaptionLanguage("Italian", "it"),
        CaptionLanguage("Portuguese", "pt"),
        CaptionLanguage("Polish", "pl"),
        CaptionLanguage("Dutch", "nl"),
        CaptionLanguage("Russian", "ru"),
        CaptionLanguage("Ukrainian", "uk"),
        CaptionLanguage("Turkish", "tr"),
        CaptionLanguage("Hindi", "hi"),
        CaptionLanguage("Urdu", "ur"),
        CaptionLanguage("Arabic", "ar"),
        CaptionLanguage("Persian", "fa"),
        CaptionLanguage("Chinese", "zh"),
        CaptionLanguage("Japanese", "ja"),
        CaptionLanguage("Korean", "ko"),
        CaptionLanguage("Vietnamese", "vi"),
    )

    /** The speech models for these languages put a space between every word, which the written language doesn't use. */
    val writtenWithoutSpaces = setOf("zh", "ja")

    /** A readable name for a language code, including the ~99 languages auto-detect can recognise. */
    fun nameFor(code: String): String =
        caption.firstOrNull { it.code == code }?.name
            ?: spoken.firstOrNull { it.code == code }?.name
            ?: Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifEmpty { code }
}
