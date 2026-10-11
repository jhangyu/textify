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
}
