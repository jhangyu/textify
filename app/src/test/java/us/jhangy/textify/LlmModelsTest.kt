package us.jhangy.textify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmModelsTest {

    private val sha = Regex("[0-9a-f]{40}")

    @Test
    fun pinnedSpecsUseRevisionUrlAndExactSize() {
        for (m in MODELS.filter { it.pinned }) {
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
        assertTrue(hy.pinned)
        assertEquals(1_827_026_224L, hy.size)
    }

    @Test
    fun keysUniqueAndUnknownFallsBackToFirst() {
        assertEquals(MODELS.size, MODELS.map { it.key }.toSet().size)
        assertEquals(MODELS.first(), modelOf("gone"))
    }

    @Test
    fun threeModelsGemmaPinnedQwenNotDownloadableWhileUnpinned() {
        assertEquals(listOf("hymt", "gemma", "qwen"), MODELS.map { it.key })
        assertEquals(2_588_147_712L, modelOf("gemma").size)
        assertTrue(modelOf("gemma").pinned)
        val qwen = modelOf("qwen")
        assertTrue(qwen.pinned || qwen.size == 0L)   // flips to pinned once TODO(qwen-pin) is filled
    }

    @Test
    fun chatModelsTurnThinkingOff() {
        for (key in listOf("gemma", "qwen")) assertEquals(key, false, modelOf(key).context["enable_thinking"])
    }
}
