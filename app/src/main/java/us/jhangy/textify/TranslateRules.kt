package us.jhangy.textify

/*
 * Pure translation rules (translation spec §2, §3, §4, final picks). No Android or ML Kit imports: everything
 * here runs on the JVM test runner; Translate.kt feeds it ML Kit results.
 */

/** Per-paragraph translation state (spec §2). */
sealed class ParagraphState {
    data object Translating : ParagraphState()
    data object Downloading : ParagraphState()
    data object WaitingWifi : ParagraphState()
    data object Unsupported : ParagraphState()
    data object Failed : ParagraphState()
    data object NotNeeded : ParagraphState()

    /** [source] = ML Kit code of the detected source language ("zh" for the OpenCC-only route). */
    data class Done(val text: String, val source: String) : ParagraphState()
}

/** Target language. [key] is what Prefs stores; [mlkit] = ML Kit target code; [openCc] = convert the result with OpenCC s2twp. */
data class TargetLang(val key: String, val mlkit: String, val openCc: Boolean) {
    companion object {
        const val ZH_HANT = "zh-Hant"
        const val ZH_HANS = "zh-Hans"
        const val DEFAULT = ZH_HANT

        /** 繁體中文 = ML Kit zh + OpenCC; 简体中文 = ML Kit zh as is; any other key is an ML Kit code. */
        fun of(key: String): TargetLang = when (key) {
            ZH_HANT -> TargetLang(key, "zh", true)
            ZH_HANS -> TargetLang(key, "zh", false)
            else -> TargetLang(key, key, false)
        }

        /** Target list (spec §2): zh-Hant, zh-Hans, then en, ja, ko, then the other ML Kit codes alphabetically. */
        fun keys(mlkitLanguages: Collection<String>): List<String> {
            val rest = mlkitLanguages.filter { it != "zh" }
            val first = listOf("en", "ja", "ko").filter { it in rest }
            return listOf(ZH_HANT, ZH_HANS) + first + (rest - first.toSet()).sorted()
        }

        /** Model list order for the pre-download sheet: the same order, one "zh" model for both Chinese targets. */
        fun modelKeys(mlkitLanguages: Collection<String>): List<String> =
            keys(mlkitLanguages).filter { it != ZH_HANS }.map { of(it).mlkit }
    }
}

/** What to do with one paragraph (spec §2 step 2). */
sealed class Route {
    data object NotNeeded : Route()
    data object OpenCcOnly : Route()
    data object Unsupported : Route()
    data class MlKit(val source: String, val openCc: Boolean) : Route()
}

/** Translation engine for one paragraph. */
enum class Engine { LLM, MLKIT }

/**
 * The [active] LLM only when its model is downloaded, its GPU engine has not failed, and it supports both languages;
 * else ML Kit. [downloaded] / [gpuFailed] = model keys, so another model's state never counts.
 */
internal fun engineFor(
    source: String,
    target: TargetLang,
    active: ModelSpec,
    downloaded: Set<String>,
    gpuFailed: Set<String>,
): Engine =
    if (active.key in downloaded && active.key !in gpuFailed && active.supports(source) && active.supports(target.mlkit)) Engine.LLM
    else Engine.MLKIT

/** ML Kit language-id tag for "undetermined" (LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG). */
const val UNDETERMINED = "und"

/**
 * [detected] = language-id tag ("und" when undetermined or identification failed, spec §5; romanized tags
 * such as "ja-Latn" are treated as undetermined);
 * [mlkitSource] = TranslateLanguage.fromLanguageTag(detected), null when ML Kit cannot translate it.
 */
internal fun route(detected: String, mlkitSource: String?, target: TargetLang): Route = when {
    detected == UNDETERMINED || detected.endsWith("-Latn") -> Route.NotNeeded
    mlkitSource == null -> Route.Unsupported
    mlkitSource == target.mlkit -> if (target.openCc) Route.OpenCcOnly else Route.NotNeeded
    else -> Route.MlKit(mlkitSource, target.openCc)
}

/** Final pick: an OpenCC-only paragraph whose conversion changes nothing keeps the original look. */
internal fun openCcOnlyResult(original: String, converted: String): ParagraphState =
    if (converted == original) ParagraphState.NotNeeded else ParagraphState.Done(converted, "zh")

private val NO_SPACE_SOURCES = setOf("zh", "ja")

/** Text for language-id ([source] null) and ML Kit translate: OCR line breaks dropped for zh/ja, one space otherwise. */
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

/** Paragraphs a translate press (re)starts: never run, waiting for Wi-Fi, or failed (spec §2, §5). */
internal fun needsRun(state: ParagraphState?): Boolean =
    state == null || state == ParagraphState.WaitingWifi || state == ParagraphState.Failed

/** Settings model row states (spec §4). */
enum class ModelState { BUILTIN, DONE, DOWNLOADING, WAITING_WIFI }

/** Model rows: English first (built into ML Kit), then every other known model by code; downloading > done > waiting. */
internal fun modelRows(downloaded: Set<String>, downloading: Set<String>, waiting: Set<String>): List<Pair<String, ModelState>> {
    val others = (downloaded + downloading + waiting - "en").sorted()
    return listOf("en" to ModelState.BUILTIN) + others.map { code ->
        code to when (code) {
            in downloading -> ModelState.DOWNLOADING
            in downloaded -> ModelState.DONE
            else -> ModelState.WAITING_WIFI
        }
    }
}
