package us.jhangy.textify

/*
 * Pure per-model specs for the on-device translation LLMs. No Android or LiteRT-LM imports, so they run on the JVM
 * test runner; Llm.kt is the one engine that runs whichever spec is active.
 */

/** Sampling settings for every conversation of a model. */
data class Sampler(val topK: Int, val topP: Double, val temperature: Double)

/**
 * One downloadable `.litertlm` model. [revision] + exact [size] pin the file: a resumed download can never mix two
 * versions (any other size is rejected). [key] is what Prefs stores; [title]/[badge]/[glyph] are the Settings row.
 * [context] = chat-template variables passed with every message (e.g. enable_thinking=false).
 * [fp32Activations] = run the GPU with fp32 activations (the runtime default is fp16, which garbles some models).
 */
class ModelSpec(
    val key: String,
    val title: String,
    val badge: String,
    val glyph: String,
    val repo: String,
    val file: String,
    val revision: String,
    val size: Long,
    val sampler: Sampler,
    val context: Map<String, Any>,
    val prompt: (String, TargetLang) -> String?,
    val clean: (String) -> String?,
    val supports: (String) -> Boolean,
    val fp32Activations: Boolean = false,
) {
    val url: String get() = "https://huggingface.co/$repo/resolve/$revision/$file"
}

/** General chat models: no thinking. Every ML Kit language is offered; an unnamed target gets a null prompt -> ML Kit. */
private val CHAT_CONTEXT: Map<String, Any> = mapOf("enable_thinking" to false)

/** Every selectable translation LLM, in Settings row order; the first is the default active model. */
val MODELS: List<ModelSpec> = listOf(
    ModelSpec(
        key = "hymt", title = "Hy-MT2", badge = "1.8B", glyph = "Hy",
        repo = "litert-community/Hy-MT2-1.8B", file = "Hy-MT2-1.8B_int8.litertlm",
        revision = "1ac9676f3d10c7f64835eb83026e6df2ec343d1f", size = 1_827_026_224L,
        sampler = Sampler(topK = 20, topP = 0.6, temperature = 0.7),   // Hy-MT2 model card
        context = emptyMap(),
        prompt = HyMtRules::prompt, clean = HyMtRules::clean, supports = HyMtRules::supports,
    ),
    // Generic file, the one the model README benchmarks on GPU (docs/logs/2026-10-11/gemma-pin.md).
    ModelSpec(
        key = "gemma", title = "Gemma 4", badge = "E2B", glyph = "G",
        repo = "litert-community/gemma-4-E2B-it-litert-lm", file = "gemma-4-E2B-it.litertlm",
        revision = "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1", size = 2_588_147_712L,
        sampler = Sampler(topK = 10, topP = 0.95, temperature = 0.3),  // ponytail: README gives none; low temperature for translation
        context = CHAT_CONTEXT,
        prompt = ChatRules::prompt, clean = ChatRules::clean, supports = { true },
    ),
    ModelSpec(
        key = "qwen", title = "Qwen3.8", badge = "2B", glyph = "Q",
        repo = "jhangyu/Qwen3.8-2B-Distill-litert-lm", file = "qwen3.8-2b-distill_q4.litertlm",
        revision = "92ae7e17c6409fc0fcbf519f1154089c6147237b", size = 971_293_456L,
        sampler = Sampler(topK = 10, topP = 0.95, temperature = 0.3),
        context = CHAT_CONTEXT,
        prompt = ChatRules::prompt, clean = ChatRules::clean, supports = { true },
        fp32Activations = true,   // Qwen3.5 hybrid graph prints "!!!!" with fp16 GPU activations (docs/logs/2026-10-11/qwen-gpu-debug.md)
    ),
)

/** Spec for a stored key; unknown or missing -> the first model. */
fun modelOf(key: String?): ModelSpec = MODELS.firstOrNull { it.key == key } ?: MODELS.first()
