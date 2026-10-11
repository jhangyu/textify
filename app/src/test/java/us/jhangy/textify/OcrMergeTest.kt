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

    private fun p(text: String, l: Int, t: Int, r: Int, lineH: Int, n: Int): OcrCandidate {
        val lines = List(n) { Box(l, t + it * (lineH + 6), r, t + it * (lineH + 6) + lineH) }
        return OcrCandidate(text, Box(l, t, r, lines.last().bottom), lines, 0.9f)
    }

    @Test
    fun sentenceWrappedAcrossThreeBlocksIsMergedInReadingOrder() {
        // line height 20, line gap 6; slight indent on 2nd, ragged right edges
        val out = mergeOcr(listOf(
            p("third.", 100, 152, 180, 20, 1),
            p("first line of the", 100, 100, 400, 20, 1),
            p("sentence continues", 108, 126, 380, 20, 1),
        ))
        assertEquals(1, out.size)
        assertEquals("first line of the\nsentence continues\nthird.", out[0].text)
        assertEquals(Box(100, 100, 400, 172), out[0].box)
        assertEquals(3, out[0].lines.size)
    }

    @Test
    fun separateParagraphsStayApart() {
        val larger = mergeOcr(listOf(p("one", 100, 100, 400, 20, 1), p("two", 100, 160, 400, 20, 1)))   // gap 40 = 2 lines
        assertEquals(2, larger.size)
        val otherColumn = mergeOcr(listOf(p("one", 100, 100, 300, 20, 1), p("two", 600, 126, 800, 20, 1)))
        assertEquals(2, otherColumn.size)
        val heading = mergeOcr(listOf(p("TITLE", 100, 100, 400, 40, 1), p("body", 100, 146, 400, 20, 1)))
        assertEquals(2, heading.size)
    }
    // ML Kit line boxes hug the ink: a 1-2 char last line ("on.", "了。") is shorter than the paragraph's lines and its
    // top sits lower, so its own height must not decide whether it continues the paragraph.
    private fun tail(text: String, l: Int, t: Int, r: Int, b: Int) = OcrCandidate(text, Box(l, t, r, b), listOf(Box(l, t, r, b)), 0.9f)

    @Test
    fun latinShortLastLineIsJoined() {
        // body lines h20 pitch 26 (ascender..descender); tail "on." x-height only: top 158, bottom 169 (h11)
        val out = mergeOcr(listOf(p("the sentence goes\nall the way", 100, 100, 400, 20, 2), tail("on.", 100, 158, 130, 169)))
        assertEquals(listOf("the sentence goes\nall the way\non."), out.map { it.text })
    }

    @Test
    fun cjkShortLastLineIsJoined() {
        // tail "了。": "。" sits low, ML Kit box h12 starting 4px below the line top
        val out = mergeOcr(listOf(p("第一行文字很長很長\n第二行也很長很長", 100, 100, 400, 20, 2), tail("了。", 100, 156, 136, 168)))
        assertEquals(1, out.size)
        assertEquals("第一行文字很長很長\n第二行也很長很長\n了。", out[0].text)
    }

    @Test
    fun cjkTwoFullHeightCharsLastLineIsJoined() {
        val out = mergeOcr(listOf(p("第一行\n第二行", 100, 100, 400, 20, 2), tail("完成", 100, 152, 140, 172)))
        assertEquals(1, out.size)
    }

    @Test
    fun shortLineFarBelowStaysApart() {
        // same tail one blank line lower: a separate paragraph, not a wrap
        val out = mergeOcr(listOf(p("one\ntwo", 100, 100, 400, 20, 2), tail("了。", 100, 182, 136, 194)))
        assertEquals(2, out.size)
    }
}
