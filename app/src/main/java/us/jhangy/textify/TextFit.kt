package us.jhangy.textify

/*
 * Pure layout and colour math for paragraph boxes (translation spec §1, §3). No Android imports: runs on the
 * JVM test runner.
 */

/** Text size and line pitch (px) that put a paragraph's TextView lines onto the screenshot's OCR line boxes. */
internal data class LineMetrics(val textPx: Float, val pitchPx: Float)

// ponytail: glyph em ≈ 0.85 × OCR line-box height; calibration knob, tune on device if handles drift
internal const val TEXT_TO_BOX = 0.85f

/**
 * [lines] top to bottom, non-empty. Size from the median line height; pitch = mean distance between line tops (1 line: its height).
 * [vertical] (lines are columns): size from the narrowest column, pitch = one glyph line.
 */
internal fun lineMetrics(lines: List<Box>, vertical: Boolean = false): LineMetrics {
    require(lines.isNotEmpty())
    val heights = lines.map { it.height }.sorted()
    val median = heights[heights.size / 2].toFloat()
    val pitch = if (lines.size < 2) median else (lines.last().top - lines.first().top).toFloat() / (lines.size - 1)
    // ponytail: tops almost equal = side-by-side lines (vertical ja/zh columns): the height is a column, not a glyph
    if (vertical || (lines.size > 1 && pitch < median * 0.5f)) {
        val textPx = minOf(median, lines.minOf { it.width }.toFloat()) * TEXT_TO_BOX
        return LineMetrics(textPx, textPx / TEXT_TO_BOX)
    }
    return LineMetrics(median * TEXT_TO_BOX, maxOf(pitch, 1f))
}

/** Fitted text size (px) and whether the text still overflows at the minimum (spec §3 truncation). */
internal data class Fit(val textPx: Float, val truncated: Boolean)

/**
 * Largest size from max(maxPx, minPx) down to [minPx] in [stepPx] steps whose layout height ([heightAt]) fits
 * [availPx]; truncated = even [minPx] overflows (spec §3: auto-size down to 10sp, then truncate).
 */
internal fun fitTextSize(maxPx: Float, minPx: Float, stepPx: Float, availPx: Int, heightAt: (Float) -> Int): Fit {
    var px = maxOf(maxPx, minPx)
    while (px > minPx) {
        if (heightAt(px) <= availPx) return Fit(px, false)
        px = maxOf(minPx, px - stepPx)
    }
    return Fit(minPx, heightAt(minPx) > availPx)
}

/** Mean colour of ARGB [pixels], alpha ignored, result opaque; opaque white when empty. */
internal fun averageColor(pixels: IntArray): Int {
    if (pixels.isEmpty()) return 0xFFFFFFFF.toInt()
    var r = 0L
    var g = 0L
    var b = 0L
    for (p in pixels) {
        r += (p shr 16) and 0xFF
        g += (p shr 8) and 0xFF
        b += p and 0xFF
    }
    val n = pixels.size
    return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
}

internal const val TEXT_DARK: Int = 0xFF111111.toInt()
internal const val TEXT_LIGHT: Int = 0xFFFFFFFF.toInt()

/** Spec §3: black-ish (#111111) or white text, whichever has the higher WCAG contrast against [bg] (as in the mockup). */
internal fun textColorFor(bg: Int): Int {
    val l = luminance(bg)
    val vsWhite = 1.05 / (l + 0.05)
    val vsBlack = (l + 0.05) / 0.05
    return if (vsWhite > vsBlack) TEXT_LIGHT else TEXT_DARK
}

private fun luminance(c: Int): Double {
    fun ch(v: Int): Double {
        val s = v / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
}

/** Sample points every [step] px on the edge of [box] grown by [out] px, clamped to a [w]×[h] bitmap (prism-tide note 4). */
internal fun edgePoints(box: Box, out: Int, step: Int, w: Int, h: Int): List<Pair<Int, Int>> {
    if (w <= 0 || h <= 0) return emptyList()
    val l = (box.left - out).coerceIn(0, w - 1)
    val r = (box.right - 1 + out).coerceIn(0, w - 1)
    val t = (box.top - out).coerceIn(0, h - 1)
    val b = (box.bottom - 1 + out).coerceIn(0, h - 1)
    val pts = ArrayList<Pair<Int, Int>>()
    var x = l
    while (x <= r) {
        pts += x to t
        pts += x to b
        x += step
    }
    var y = t + step
    while (y < b) {
        pts += l to y
        pts += r to y
        y += step
    }
    return pts
}
