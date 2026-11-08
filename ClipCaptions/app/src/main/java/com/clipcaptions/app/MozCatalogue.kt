package com.clipcaptions.app

/**
 * The language pairs Mozilla publishes (translations-models-v2, model version 3), in Mozilla's codes.
 * Every model goes to or from English. Taken from Mozilla's list on 8 November 2026; the files
 * themselves are looked up and downloaded when needed, so newer versions of a pair are picked up.
 */
object MozCatalogue {

    /** Languages with a model into English. */
    val INTO_ENGLISH = setOf(
        "af", "ar", "az", "be", "bg", "bn", "bs", "ca", "cs", "da", "de", "el", "es", "et", "eu",
        "fa", "fi", "fr", "gl", "gu", "he", "hi", "hr", "hu", "id", "is", "it", "ja", "kn", "ko",
        "lt", "lv", "ml", "mr", "ms", "nb", "nl", "nn", "pl", "pt", "ro", "ru", "sk", "sl", "sq",
        "sr", "sv", "ta", "te", "th", "tl", "tr", "ug", "uk", "ur", "vi", "zh-Hans", "zh-Hant",
    )

    /** Languages with a model from English. */
    val FROM_ENGLISH = setOf(
        "af", "ar", "az", "be", "bg", "bn", "bs", "ca", "cs", "da", "de", "el", "es", "et", "eu",
        "fa", "fi", "fr", "gl", "gu", "he", "hi", "hr", "hu", "id", "is", "it", "ja", "kn", "ko",
        "lt", "lv", "ml", "mr", "ms", "nb", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sq", "sr",
        "sv", "ta", "te", "th", "tl", "tr", "ug", "uk", "ur", "vi", "zh-Hans", "zh-Hant",
    )
}
