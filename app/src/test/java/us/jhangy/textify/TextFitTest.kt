package us.jhangy.textify

import org.junit.Assert.assertEquals
import org.junit.Test

class TextFitTest {

    @Test
    fun singleLineUsesItsHeight() {
        val m = lineMetrics(listOf(Box(0, 0, 100, 20)))
        assertEquals(20f * TEXT_TO_BOX, m.textPx, 1e-4f)
        assertEquals(20f, m.pitchPx, 1e-4f)
    }

    @Test
    fun pitchIsMeanTopDistance() {
        // tops 0, 30, 60; heights 20, 24, 20 -> median height 20, pitch 30
        val m = lineMetrics(listOf(Box(0, 0, 100, 20), Box(0, 30, 90, 54), Box(0, 60, 80, 80)))
        assertEquals(20f * TEXT_TO_BOX, m.textPx, 1e-4f)
        assertEquals(30f, m.pitchPx, 1e-4f)
    }

    @Test
    fun equalTopLinesFallBackToLineWidth() {
        // two vertical columns side by side: tops equal, height 200, width 20 -> size from the width, not the height
        val m = lineMetrics(listOf(Box(0, 0, 20, 200), Box(30, 0, 50, 200)))
        assertEquals(20f * TEXT_TO_BOX, m.textPx, 1e-4f)
        assertEquals(20f, m.pitchPx, 1e-4f)
    }

    @Test
    fun fitPicksLargestThatFits() {
        // layout height = 10 × size; 150 px available -> 15 px fits, 16 px does not
        assertEquals(Fit(15f, false), fitTextSize(20f, 10f, 1f, 150) { (it * 10).toInt() })
    }

    @Test
    fun fitTruncatesAtMinimum() {
        assertEquals(Fit(10f, true), fitTextSize(20f, 10f, 1f, 50) { (it * 10).toInt() })
    }

    @Test
    fun fitNeverGoesBelowMinimum() {
        // original text smaller than 10sp: start at the minimum
        assertEquals(Fit(10f, false), fitTextSize(8f, 10f, 1f, 200) { (it * 10).toInt() })
    }

    @Test
    fun averageIgnoresAlpha() {
        assertEquals(0xFF7F007F.toInt(), averageColor(intArrayOf(0x00FF0000, 0xFF0000FF.toInt())))
        assertEquals(0xFFFFFFFF.toInt(), averageColor(IntArray(0)))
    }

    @Test
    fun textColorPicksHigherContrast() {
        assertEquals(TEXT_DARK, textColorFor(0xFFFFFFFF.toInt()))
        assertEquals(TEXT_DARK, textColorFor(0xFFFAF5FF.toInt()))
        assertEquals(TEXT_LIGHT, textColorFor(0xFF1E1B4B.toInt()))
        assertEquals(TEXT_LIGHT, textColorFor(0xFF000000.toInt()))
    }

    @Test
    fun edgePointsStayInsideBitmap() {
        // box 0..10 grown by 2 px, clamped to an 8×8 bitmap -> rect 0..7: top/bottom x = 0, 4; sides y = 4
        val pts = edgePoints(Box(0, 0, 10, 10), out = 2, step = 4, w = 8, h = 8)
        assertEquals(6, pts.size)
        assertEquals(true, pts.all { (x, y) -> x in 0..7 && y in 0..7 })
    }
}
