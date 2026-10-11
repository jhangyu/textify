package us.jhangy.textify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HyMtRulesTest {

    private fun prompt(key: String, text: String = "こんにちは") = HyMtRules.prompt(text, TargetLang.of(key))!!

    @Test
    fun promptNamesTraditionalChineseExplicitly() {
        assertTrue(prompt(TargetLang.ZH_HANT).startsWith("Translate the following text into Traditional Chinese."))
    }

    @Test
    fun promptNamesSimplifiedChineseAndEnglish() {
        assertTrue(prompt(TargetLang.ZH_HANS).contains("into Simplified Chinese."))
        assertTrue(prompt("en").contains("into English."))
        assertTrue(prompt("ko").contains("into Korean."))
    }

    @Test
    fun promptEndsWithBlankLineThenSource() {
        assertTrue(prompt("en", "hello").endsWith("without any additional explanation:\n\nhello"))
    }

    @Test
    fun unsupportedTargetHasNoPrompt() {
        assertNull(HyMtRules.prompt("x", TargetLang.of("sw")))
    }

    @Test
    fun supportsHy2LanguageSetOnly() {
        listOf("zh", "ja", "ko", "en", "fr").forEach { assertTrue(it, HyMtRules.supports(it)) }
        listOf("sw", "af", "zh-Hant", "").forEach { assertFalse(it, HyMtRules.supports(it)) }
    }

    @Test
    fun cleanTrimsWhitespace() {
        assertEquals("你好", HyMtRules.clean("\n  你好 \n"))
    }

    @Test
    fun cleanDropsSpecialTokens() {
        assertEquals("你好", HyMtRules.clean("你好<｜hy_place▁holder▁no▁2｜>"))
        assertEquals("你好", HyMtRules.clean("你好<|hy_place_holder_no_2|>"))
    }

    @Test
    fun cleanDropsPromptEcho() {
        val echo = "Translate the following text into English. Note that you should only output the translated result without any additional explanation:\n\nHello"
        assertEquals("Hello", HyMtRules.clean(echo))
    }

    @Test
    fun cleanEmptyIsNull() {
        assertNull(HyMtRules.clean("  \n"))
        assertNull(HyMtRules.clean("<|x|>"))
    }
}
