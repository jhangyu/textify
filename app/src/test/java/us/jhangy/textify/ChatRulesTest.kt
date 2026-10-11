package us.jhangy.textify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRulesTest {

    private fun prompt(key: String, text: String = "こんにちは") = ChatRules.prompt(text, TargetLang.of(key))!!

    @Test
    fun promptNamesTargetLanguage() {
        assertTrue(prompt(TargetLang.ZH_HANT).contains("into Traditional Chinese"))
        assertTrue(prompt(TargetLang.ZH_HANS).contains("into Simplified Chinese"))
        assertTrue(prompt("en").contains("into English"))
        assertTrue(prompt("ko").contains("into Korean"))
        assertTrue(prompt("fr").contains("into French"))
    }

    @Test
    fun promptForbidsExplanationAndEndsWithSource() {
        val p = prompt("en", "hello")
        assertTrue(p.contains("only the translation"))
        assertTrue(p.contains("no quotes"))
        assertTrue(p.endsWith("\n\nhello"))
    }

    @Test
    fun unknownTargetHasNoPrompt() {
        assertNull(ChatRules.prompt("x", TargetLang.of("zz")))
        assertNull(ChatRules.prompt("x", TargetLang.of("")))
    }

    @Test
    fun cleanTrimsWhitespace() {
        assertEquals("你好", ChatRules.clean("\n  你好 \n"))
    }

    @Test
    fun cleanStripsEmptyThinkBlock() {
        assertEquals("你好", ChatRules.clean("<think>\n\n</think>\n\n你好"))
        assertEquals("你好", ChatRules.clean("<think></think>你好"))
    }

    @Test
    fun cleanStripsNormalThinkBlock() {
        assertEquals("你好", ChatRules.clean("<think>The user wants...\nokay</think>\n你好"))
    }

    @Test
    fun cleanStripsUnterminatedLeadingThink() {
        assertNull(ChatRules.clean("<think>still reasoning, never closed"))
        // closing tag without opener: the template already supplied "<think>", so the reasoning is the head
        assertEquals("你好", ChatRules.clean("reasoning...\n</think>\n你好"))
    }

    @Test
    fun cleanEmptyIsNull() {
        assertNull(ChatRules.clean("  \n"))
        assertNull(ChatRules.clean("<think>x</think>"))
    }
}
