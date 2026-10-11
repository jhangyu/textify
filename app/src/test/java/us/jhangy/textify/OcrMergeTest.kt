package us.jhangy.textify

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrMergeTest {

    private fun c(text: String, l: Int, t: Int, r: Int, b: Int, conf: Float) =
        OcrCandidate(text, Box(l, t, r, b), listOf(Box(l, t, r, b)), conf)

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
        // small box ranks first: IoU = 50 / 100 = 0.5 (not > 0.5), big box own-area coverage 0.5 (< 0.6) -> both kept
        val out = mergeOcr(listOf(c("a", 0, 0, 10, 10, 0.1f), c("b", 0, 0, 10, 5, 0.9f)))
        assertEquals(setOf("a", "b"), out.map { it.text }.toSet())
    }

    @Test
    fun disjointParagraphsBothKept() {
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

    @Test
    fun winnerKeepsItsLines() {
        val twoLines = OcrCandidate("a\nb", Box(0, 0, 10, 20), listOf(Box(0, 0, 10, 10), Box(0, 10, 10, 20)), 0.9f)
        val out = mergeOcr(listOf(c("x", 0, 0, 10, 20, 0.1f), twoLines))
        assertEquals(listOf(twoLines), out)
    }

    @Test
    fun paragraphConfidenceIsLineMean() {
        assertEquals(0.5f, paragraphConfidence(listOf(0.25f, 0.75f)), 1e-6f)
        assertEquals(0f, paragraphConfidence(emptyList()), 0f)
    }

    @Test
    fun smallBoxMostlyInsideKeptOneIsDropped() {
        // IoU = 25 / 100 = 0.25 but 100% of the small box is covered
        val out = mergeOcr(listOf(c("big", 0, 0, 10, 10, 0.9f), c("small", 0, 0, 5, 5, 0.4f)))
        assertEquals(listOf("big"), out.map { it.text })
    }

    @Test
    fun touchingSideBySideBoxesBothKept() {
        val out = mergeOcr(listOf(c("l", 0, 0, 10, 10, 0.9f), c("r", 10, 0, 20, 10, 0.4f)))
        assertEquals(listOf("l", "r"), out.map { it.text })
    }
}
