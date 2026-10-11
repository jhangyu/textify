package us.jhangy.textify

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrMergeTest {

    private fun c(text: String, l: Int, t: Int, r: Int, b: Int, conf: Float) = OcrCandidate(text, l, t, r, b, conf)

    @Test
    fun overlapAboveHalfKeepsHigherConfidence() {
        // IoU = 60 / 100 = 0.6
        val out = mergeOcr(listOf(c("low", 0, 0, 10, 10, 0.4f), c("high", 0, 0, 10, 6, 0.9f)))
        assertEquals(listOf("high"), out.map { it.text })
    }

    @Test
    fun equalConfidenceKeepsLongerText() {
        val out = mergeOcr(listOf(c("ab", 0, 0, 10, 10, 0.5f), c("abcd", 1, 0, 10, 10, 0.5f)))
        assertEquals(listOf("abcd"), out.map { it.text })
    }

    @Test
    fun iouExactlyHalfKeepsBoth() {
        // IoU = 50 / 100 = 0.5 -> not > 0.5 -> both kept
        val out = mergeOcr(listOf(c("a", 0, 0, 10, 10, 0.9f), c("b", 0, 0, 10, 5, 0.1f)))
        assertEquals(setOf("a", "b"), out.map { it.text }.toSet())
    }

    @Test
    fun disjointLinesBothKept() {
        val out = mergeOcr(listOf(c("a", 0, 0, 10, 10, 0.9f), c("b", 0, 20, 10, 30, 0.9f)))
        assertEquals(2, out.size)
    }

    @Test
    fun resultSortedTopThenLeft() {
        val out = mergeOcr(
            listOf(
                c("bottom", 0, 50, 10, 60, 0.9f),
                c("topRight", 40, 0, 50, 10, 0.1f),
                c("topLeft", 0, 0, 10, 10, 0.5f),
            ),
        )
        assertEquals(listOf("topLeft", "topRight", "bottom"), out.map { it.text })
    }
}
