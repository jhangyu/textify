package us.jhangy.textify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerticalTextTest {

    @Test
    fun onlyCjkTargetsDrawVertically() {
        assertTrue(verticalTarget(TargetLang.of(TargetLang.ZH_HANT)))
        assertTrue(verticalTarget(TargetLang.of(TargetLang.ZH_HANS)))
        assertTrue(verticalTarget(TargetLang.of("ja")))
        assertTrue(verticalTarget(TargetLang.of("ko")))
        assertFalse(verticalTarget(TargetLang.of("en")))
        assertFalse(verticalTarget(TargetLang.of("fr")))
    }

    @Test
    fun punctuationMapsToVerticalForms() {
        assertEquals("︑︒︐﹁﹂﹃﹄︵︶︱︙︖︕︓︔", verticalForms("、。，「」『』（）ー…？！：；"))
        assertEquals("漢字かなAb1", verticalForms("漢字かなAb1"))   // everything else unchanged
    }

    @Test
    fun columnsBreakEveryRowsGlyphsAndAtNewlines() {
        assertEquals(listOf("一二三", "四五", "六"), verticalColumns("一二三四五\n六", 3))
        assertEquals(listOf("一二三", "四"), verticalColumns("一二三四", 3))
        assertEquals(listOf("一二三"), verticalColumns("一二三", 3))   // exact fit: no empty column after
    }

    @Test
    fun surrogatePairIsOneGlyph() {
        assertEquals(listOf("𠀋一", "二"), verticalColumns("𠀋一二", 2))
    }

    @Test
    fun fitPicksLargestSizeWhoseColumnsFitTheWidth() {
        // 12 glyphs, box 100 high; px 20 -> 5 rows -> 3 columns × 20 × 1.2 = 72 ≤ 80 wide
        // px 21 -> 4 rows -> 3 columns × 21 × 1.2 = 75.6 ≤ 80; px 25 -> 4 rows -> 90 > 80; px 24 -> 4 rows -> 86.4 > 80;
        // px 22 -> 4 rows -> 79.2 ≤ 80 -> 22 is the largest
        val v = layoutVertical("一二三四五六七八九十百千", innerW = 80, innerH = 100, maxPx = 30f, minPx = 10f)
        assertEquals(22f, v.textPx, 1e-4f)
        assertFalse(v.truncated)
        assertEquals(listOf("一二三四", "五六七八", "九十百千"), v.columns)
    }

    @Test
    fun overflowAtMinimumTruncatesWithVerticalMark() {
        // px 10, box 30 high -> 3 rows; 30 wide -> 2 columns of pitch 12; 9 glyphs need 3 columns
        val v = layoutVertical("一二三四五六七八九", innerW = 30, innerH = 30, maxPx = 20f, minPx = 10f)
        assertEquals(10f, v.textPx, 1e-4f)
        assertTrue(v.truncated)
        assertEquals(listOf("一二三", "四五︙"), v.columns)
    }

    @Test
    fun shortLastKeptColumnGetsMarkAppended() {
        // newline makes the second column short; third column dropped -> mark appended to the short one
        val v = layoutVertical("一二三\n四\n五六", innerW = 30, innerH = 30, maxPx = 10f, minPx = 10f)
        assertTrue(v.truncated)
        assertEquals(listOf("一二三", "四︙"), v.columns)
    }

    @Test
    fun layoutAppliesVerticalForms() {
        val v = layoutVertical("「好。」", innerW = 100, innerH = 100, maxPx = 20f, minPx = 10f)
        assertEquals(listOf("﹁好︒﹂"), v.columns)
    }
}
