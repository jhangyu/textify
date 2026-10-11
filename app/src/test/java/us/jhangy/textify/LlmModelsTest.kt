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
            assertTrue(m.key, sha.matches(m.revision))
            assertTrue(m.key, m.size > 0)
            assertEquals(m.key, "https://huggingface.co/${m.repo}/resolve/${m.revision}/${m.file}", m.url)
            assertFalse(m.key, "/resolve/main/" in m.url)
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
    fun threeModelsInOrderWithPinnedSizes() {
        assertEquals(listOf("hymt", "gemma", "qwen"), MODELS.map { it.key })
        assertEquals(2_588_147_712L, modelOf("gemma").size)
        assertEquals(971_293_456L, modelOf("qwen").size)
        assertEquals(listOf("qwen"), MODELS.filter { it.fp32Activations }.map { it.key })   // only Qwen needs fp32 GPU activations
    }

    @Test
    fun chatModelsTurnThinkingOff() {
        for (key in listOf("gemma", "qwen")) assertEquals(key, false, modelOf(key).context["enable_thinking"])
    }
}
