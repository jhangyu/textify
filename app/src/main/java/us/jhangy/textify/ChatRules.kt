package us.jhangy.textify

import java.util.Locale

/*
 * Pure rules for general chat models (Gemma): prompt and output cleanup. Same shape as HyMtRules, so the generic
 * engine calls either. No Android or LiteRT-LM imports.
 */
internal object ChatRules {
    /** English language name for the prompt; both Chinese targets name their script. Null = unknown code. */
    private fun targetName(target: TargetLang): String? = when (target.key) {
        TargetLang.ZH_HANT -> "Traditional Chinese"
        TargetLang.ZH_HANS -> "Simplified Chinese"
        else -> Locale(target.code).getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotEmpty() && it != target.code }
    }

    /** Single user turn; thinking is switched off at the template level (extraContext enable_thinking=false), not here. */
    fun prompt(text: String, target: TargetLang): String? {
        val name = targetName(target) ?: return null
        return "Translate the following text into $name. Output only the translation: no quotes, no notes, no explanation, no preamble.\n\n$text"
    }

    /**
     * Keeps only what follows the last `</think>` (covers empty and normal blocks, and a closer whose opener the chat
     * template supplied), drops an unterminated leading `<think>`, trims. Null = nothing left.
     */
    fun clean(raw: String): String? {
        var s = raw.substringAfterLast("</think>").trim()
        if (s.startsWith("<think>")) s = ""   // never closed: all reasoning, no answer
        return s.ifEmpty { null }
    }
}
