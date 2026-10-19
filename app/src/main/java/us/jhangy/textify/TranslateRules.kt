package us.jhangy.textify

/*
 * Pure translation rules (translation spec §2, §3, final picks). No Android imports: everything
 * here runs on the JVM test runner; Translate.kt feeds it language-id results.
 */

/** Per-paragraph translation state (spec §2). */
sealed class ParagraphState {
    data object Translating : ParagraphState()
    data object Downloading : ParagraphState()
    data object WaitingWifi : ParagraphState()
    data object Unsupported : ParagraphState()
    data object Failed : ParagraphState()
    data object NotNeeded : ParagraphState()

    /** [source] = language code of the detected source language ("zh" for the OpenCC-only route). */
    data class Done(val text: String, val source: String) : ParagraphState()
}

/** Target language. [key] is what Prefs stores; [code] = language code of the target; [openCc] = convert the result with OpenCC s2twp. */
data class TargetLang(val key: String, val code: String, val openCc: Boolean) {
    companion object {
        const val ZH_HANT = "zh-Hant"
        const val ZH_HANS = "zh-Hans"
        const val DEFAULT = ZH_HANT

        /** 繁體中文 = zh + OpenCC; 简体中文 = zh as is; any other key is a language code. */
        fun of(key: String): TargetLang = when (key) {
            ZH_HANT -> TargetLang(key, "zh", true)
            ZH_HANS -> TargetLang(key, "zh", false)
            else -> TargetLang(key, key, false)
        }

        private val OTHERS = (
            "af ar be bg bn ca cs cy da de el eo es et fa fi fr ga gl gu he hi hr ht hu id is it ka kn lt lv mk mr ms mt nl no pl pt " +
                "ro ru sk sl sq sv sw ta te th tl tr uk ur vi"
            ).split(' ')

        /** Target list: zh-Hant, zh-Hans, then en, ja, ko, then the other codes alphabetically (the codes ML Kit translation offered, so stored keys stay valid). */
        val keys: List<String> = listOf(ZH_HANT, ZH_HANS, "en", "ja", "ko") + OTHERS.sorted()
    }
}

/** What to do with one paragraph. */
sealed class Route {
    data object NotNeeded : Route()
    data object OpenCcOnly : Route()
    data object Unsupported : Route()
    /** The active LLM is not downloaded yet: start its download, the next press translates. */
    data object NeedsModel : Route()
    /** Translate with the active LLM; [source] = detected language code. */
    data class Llm(val source: String) : Route()
}

/** language-id tag for "undetermined" (LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG). */
const val UNDETERMINED = "und"

/**
 * [tag] = language-id tag ("und" when undetermined or identification failed, romanized tags such as "ja-Latn"
 * are treated as undetermined). [active] translates only when it supports both languages; [downloaded] = model keys.
 * A GPU start failure surfaces as a failed translation, not as another route: there is no fallback engine.
 */
internal fun route(tag: String, target: TargetLang, active: ModelSpec, downloaded: Set<String>): Route {
    val detected = LANGUAGE_ALIASES[tag] ?: tag
    return when {
        detected == UNDETERMINED || detected.endsWith("-Latn") -> Route.NotNeeded
        detected == target.code -> if (target.openCc) Route.OpenCcOnly else Route.NotNeeded
        !active.supports(detected) || !active.supports(target.code) -> Route.Unsupported
        active.key !in downloaded -> Route.NeedsModel
        else -> Route.Llm(detected)
    }
}

/** Legacy language-id tags -> the codes used everywhere else (the aliases ML Kit's TranslateLanguage.fromLanguageTag applied). */
private val LANGUAGE_ALIASES = mapOf("iw" to "he", "in" to "id", "nb" to "no")

/** Final pick: an OpenCC-only paragraph whose conversion changes nothing keeps the original look. */
internal fun openCcOnlyResult(original: String, converted: String): ParagraphState =
    if (converted == original) ParagraphState.NotNeeded else ParagraphState.Done(converted, "zh")

private val NO_SPACE_SOURCES = setOf("zh", "ja")

/** Text for language-id ([source] null) and the LLM: OCR line breaks dropped for zh/ja, one space otherwise. */
internal fun flatten(text: String, source: String?): String =
    text.replace("\n", if (source in NO_SPACE_SOURCES) "" else " ")

/** Status chip kinds in a paragraph corner (spec §3). */
enum class Chip { DOWNLOADING, WAITING_WIFI, UNSUPPORTED, FAILED }

internal fun chipFor(state: ParagraphState?): Chip? = when (state) {
    ParagraphState.Downloading -> Chip.DOWNLOADING
    ParagraphState.WaitingWifi -> Chip.WAITING_WIFI
    ParagraphState.Unsupported -> Chip.UNSUPPORTED
    ParagraphState.Failed -> Chip.FAILED
    else -> null
}

/** Final pick: the translate button spins only while at least one paragraph is Translating. */
internal fun anyTranslating(states: Collection<ParagraphState?>): Boolean = states.any { it == ParagraphState.Translating }

/** Paragraphs a translate press (re)starts: never run, waiting for the model download, or failed (spec §2, §5). */
internal fun needsRun(state: ParagraphState?): Boolean =
    state == null || state == ParagraphState.Downloading || state == ParagraphState.WaitingWifi || state == ParagraphState.Failed
