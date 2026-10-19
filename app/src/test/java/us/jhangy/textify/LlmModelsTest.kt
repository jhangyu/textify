package us.jhangy.textify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmModelsTest {

    private val sha = Regex("[0-9a-f]{40}")

    @Test
    fun allSpecsUseRevisionUrlAndExactSize() {
        for (m in MODELS) {
            assertTrue(m.key, sha.matches(m.pinned.revision))
            assertTrue(m.key, m.size > 0)
            assertEquals(m.key, "https://huggingface.co/${m.pinned.repo}/resolve/${m.pinned.revision}/${m.pinned.file}", m.pinned.url)
            assertFalse(m.key, "/resolve/main/" in m.pinned.url)
        }
    }

    @Test
    fun hyMt2IsPinnedAndDefault() {
        val hy = modelOf(null)
        assertEquals("hymt", hy.key)
        assertEquals(1_827_026_224L, hy.size)
    }

    @Test
    fun keysUniqueAndUnknownFallsBackToFirst() {
        assertEquals(MODELS.size, MODELS.map { it.key }.toSet().size)
        assertEquals(MODELS.first(), modelOf("gone"))
    }

    @Test
    fun twoModelsInOrderWithPinnedSizes() {
        assertEquals(listOf("hymt", "gemma"), MODELS.map { it.key })
        assertEquals(2_588_147_712L, modelOf("gemma").size)
    }

    @Test
    fun chatModelsTurnThinkingOff() {
        assertEquals(false, modelOf("gemma").context["enable_thinking"])
    }
}
