package us.jhangy.textify

/*
 * Pure Hy-MT2 rules: prompt, language names, output cleanup. No Android or LiteRT-LM imports, so they run on the
 * JVM test runner; the Hy-MT2 spec in LlmModels.kt hands them to the engine (Llm.kt).
 */
internal object HyMtRules {
    /** ML Kit code -> English language name of the Hy-MT2 language table (model card "Supported Languages"). */
    private val names = mapOf(
        "zh" to "Chinese", "en" to "English", "fr" to "French", "pt" to "Portuguese", "es" to "Spanish",
        "ja" to "Japanese", "tr" to "Turkish", "ru" to "Russian", "ar" to "Arabic", "ko" to "Korean",
        "th" to "Thai", "it" to "Italian", "de" to "German", "vi" to "Vietnamese", "ms" to "Malay",
        "id" to "Indonesian", "tl" to "Filipino", "hi" to "Hindi", "pl" to "Polish", "cs" to "Czech",
        "nl" to "Dutch", "km" to "Khmer", "my" to "Burmese", "fa" to "Persian", "gu" to "Gujarati",
        "ur" to "Urdu", "te" to "Telugu", "mr" to "Marathi", "he" to "Hebrew", "bn" to "Bengali",
        "ta" to "Tamil", "uk" to "Ukrainian", "bo" to "Tibetan", "kk" to "Kazakh", "mn" to "Mongolian",
        "ug" to "Uyghur",
    )

    fun supports(mlkitCode: String): Boolean = mlkitCode in names

    /** Target name for the prompt; both Chinese targets name their script (the model card lists "Traditional Chinese" explicitly). */
    fun targetName(target: TargetLang): String? = when (target.key) {
        TargetLang.ZH_HANT -> "Traditional Chinese"
        TargetLang.ZH_HANS -> "Simplified Chinese"   // ponytail: the card names plain "Chinese"; stating the script keeps Hans output from drifting to Hant
        else -> names[target.mlkit]
    }

    /** Official default-translation prompt (English variant, no system prompt). Null = target not in the Hy-MT2 set. */
    fun prompt(text: String, target: TargetLang): String? {
        val name = targetName(target) ?: return null
        return "Translate the following text into $name. Note that you should only output the translated result without any additional explanation:\n\n$text"
    }

    private val specialToken = Regex("<[|｜][^<>]{1,40}[|｜]>")
    private const val ECHO = "Translate the following text into"

    /** Trims, drops special tokens and a stray echo of the instruction (up to its blank line). Null = nothing left. */
    fun clean(raw: String): String? {
        var s = raw.replace(specialToken, "").trim()
        if (s.startsWith(ECHO)) s = s.substringAfter("\n\n", "").trim()
        return s.ifEmpty { null }
    }
}
