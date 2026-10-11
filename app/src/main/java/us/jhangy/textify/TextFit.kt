package us.jhangy.textify

/*
 * Pure layout and colour math for paragraph boxes (translation spec §1, §3). No Android imports: runs on the
 * JVM test runner.
 */

/** Text size and line pitch (px) that put a paragraph's TextView lines onto the screenshot's OCR line boxes. */
internal data class LineMetrics(val textPx: Float, val pitchPx: Float)

// ponytail: glyph em ≈ 0.85 × OCR line-box height; calibration knob, tune on device if handles drift
internal const val TEXT_TO_BOX = 0.85f

/** [lines] top to bottom, non-empty. Size from the median line height; pitch = mean distance between line tops (1 line: its height). */
internal fun lineMetrics(lines: List<Box>): LineMetrics {
    require(lines.isNotEmpty())
    val heights = lines.map { it.height }.sorted()
    val median = heights[heights.size / 2].toFloat()
    val pitch = if (lines.size < 2) median else (lines.last().top - lines.first().top).toFloat() / (lines.size - 1)
    return LineMetrics(median * TEXT_TO_BOX, maxOf(pitch, 1f))
}
