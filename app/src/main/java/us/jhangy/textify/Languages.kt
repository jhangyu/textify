package us.jhangy.textify

import java.util.Locale

/** Display names, disc glyphs and disc colours for translation languages (prism-tide mockup LANGS). */
object Languages {
    private val TW: Locale = Locale.forLanguageTag("zh-TW")

    /** zh-TW name of an ML Kit code, e.g. "en" -> 英文, "ja" -> 日文, "zh" -> 中文. */
    fun zhName(code: String): String = Locale.forLanguageTag(code).getDisplayLanguage(TW).ifEmpty { code }

    /** Name in the language itself, first letter title-cased, e.g. "fr" -> Français, "ja" -> 日本語. */
    fun nativeName(code: String): String {
        val l = Locale.forLanguageTag(code)
        return l.getDisplayLanguage(l).ifEmpty { code }.replaceFirstChar { it.titlecase(l) }
    }

    /** Target sheet row title (and the value shown in the target row). */
    fun targetTitle(key: String): String = when (key) {
        TargetLang.ZH_HANT -> "繁體中文"
        TargetLang.ZH_HANS -> "简体中文"
        else -> nativeName(key)
    }

    /** Target sheet row subtitle. */
    fun targetSub(key: String): String = when (key) {
        TargetLang.ZH_HANT -> "中文（台灣用語）"
        TargetLang.ZH_HANS -> "簡體中文"
        else -> zhName(key)
    }

    /** Model row title: one Chinese model serves both Chinese targets. */
    fun modelTitle(code: String): String = if (code == "zh") "中文" else nativeName(code)

    fun modelSub(code: String): String = if (code == "zh") "繁體、簡體共用" else zhName(code)

    private val GLYPHS = mapOf(
        "zh" to "中", "en" to "A", "ja" to "あ", "ko" to "한", "th" to "ก", "ru" to "Ру", "ar" to "ع", "hi" to "हि",
        "el" to "Ελ", "he" to "א", "uk" to "Ук", "bg" to "Бг", "be" to "Бе", "mk" to "Мк", "ka" to "ქ", "fa" to "ف",
        "ur" to "ا", "bn" to "বা", "gu" to "ગુ", "kn" to "ಕ", "mr" to "म", "ta" to "த", "te" to "తె",
    )

    /** Disc glyph: script sample for non-Latin languages, otherwise the capitalised code ("fr" -> Fr). */
    fun glyph(code: String): String = GLYPHS[code] ?: code.replaceFirstChar { it.uppercaseChar() }

    private val FIXED = mapOf(
        "zh" to intArrayOf(0xFFA78BFA.toInt(), 0xFF818CF8.toInt()),
        "en" to intArrayOf(0xFF7C3AED.toInt(), 0xFFA78BFA.toInt()),
        "ja" to intArrayOf(0xFFF472B6.toInt(), 0xFFFDBA74.toInt()),
        "ko" to intArrayOf(0xFF67E8F9.toInt(), 0xFF818CF8.toInt()),
    )
    private val PAIRS = listOf(
        0xFFF472B6 to 0xFFA78BFA, 0xFF818CF8 to 0xFF67E8F9, 0xFFFDBA74 to 0xFFF472B6, 0xFF67E8F9 to 0xFFA78BFA,
        0xFFFDBA74 to 0xFFA78BFA, 0xFFA78BFA to 0xFF67E8F9, 0xFFF472B6 to 0xFF818CF8, 0xFF818CF8 to 0xFFF472B6,
        0xFF67E8F9 to 0xFFFDBA74, 0xFFFDBA74 to 0xFF67E8F9,
    )

    /** Two ARGB colours for the disc's 135° gradient (stable per code). */
    fun discColors(code: String): IntArray = FIXED[code] ?: PAIRS[Math.floorMod(code.hashCode(), PAIRS.size)]
        .let { (a, b) -> intArrayOf(a.toInt(), b.toInt()) }
}
