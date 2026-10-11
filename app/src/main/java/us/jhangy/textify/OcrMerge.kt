package us.jhangy.textify

import kotlin.math.max
import kotlin.math.min

/** Integer rect in screenshot pixels. Pure Kotlin so merge and layout math run on the JVM test runner. */
internal data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** One recognised paragraph (ML Kit TextBlock) from one script model; [lines] = per-line boxes, top to bottom. */
internal data class OcrCandidate(
    val text: String,
    val box: Box,
    val lines: List<Box>,
    val confidence: Float,
)

/** Paragraph confidence = mean of its lines' Text.Line.getConfidence() (blocks have none); 0 without lines. */
internal fun paragraphConfidence(lineConfidences: List<Float>): Float =
    if (lineConfidences.isEmpty()) 0f else lineConfidences.sum() / lineConfidences.size

/** Intersection over union of two integer rects; 0 when they do not overlap or the union is empty. */
internal fun iou(a: Box, b: Box): Float {
    val iw = min(a.right, b.right) - max(a.left, b.left)
    val ih = min(a.bottom, b.bottom) - max(a.top, b.top)
    if (iw <= 0 || ih <= 0) return 0f
    val inter = iw.toLong() * ih
    val areaA = a.width.toLong() * a.height
    val areaB = b.width.toLong() * b.height
    val union = areaA + areaB - inter
    return if (union <= 0L) 0f else inter.toFloat() / union
}

/** Share of [a]'s own area covered by [b]; 0 when they do not overlap or [a] is empty. */
internal fun coverage(a: Box, b: Box): Float {
    val iw = min(a.right, b.right) - max(a.left, b.left)
    val ih = min(a.bottom, b.bottom) - max(a.top, b.top)
    val areaA = a.width.toLong() * a.height
    if (iw <= 0 || ih <= 0 || areaA <= 0L) return 0f
    return iw.toLong() * ih / areaA.toFloat()
}

private const val IOU_LIMIT = 0.5f
private const val COVER_LIMIT = 0.6f

/**
 * Merges the paragraphs of several recognizers run on the same screenshot (translation spec §1): highest
 * confidence first (ties: longer text, then input order); a paragraph overlapping an already kept one with
 * IoU > 0.5, or covered ≥ 0.6 of its own area by it, is dropped. Equal confidences (e.g. models that report 0) fall through to "longer text wins".
 * Then paragraphs that continue one another (see [continues]) are joined, so a wrapped sentence is translated whole.
 * Result sorted top-to-bottom, then left-to-right.
 */
internal fun mergeOcr(candidates: List<OcrCandidate>): List<OcrCandidate> {
    val ranked = candidates.sortedWith(
        compareByDescending<OcrCandidate> { it.confidence }.thenByDescending { it.text.length },
    )   // sortedWith is stable: input order breaks remaining ties
    val kept = ArrayList<OcrCandidate>(ranked.size)
    for (c in ranked) if (kept.none { iou(it.box, c.box) > IOU_LIMIT || coverage(c.box, it.box) >= COVER_LIMIT }) kept += c
    return joinParagraphs(kept.sortedWith(compareBy({ it.box.top }, { it.box.left })))
}

private const val MAX_GAP = 0.6f        // vertical gap / line height; ordinary line spacing is 0.2-0.5, paragraph breaks ≥ ~0.8
private const val MAX_INDENT = 2f       // left-edge shift / line height
private const val MAX_HEIGHT_RATIO = 1.3f   // headings vs body text differ more than this
private const val MIN_X_OVERLAP = 0.5f  // share of the narrower box's width
private const val SHORT_TAIL = 3f       // max width / line height of a 1-2 char last line

private fun lineHeight(c: OcrCandidate): Float = c.lines.map { it.height }.average().toFloat()

private fun centerY(b: Box): Float = (b.top + b.bottom) / 2f

/**
 * True when [b] reads as the continuation of [a]: directly below, similar line height, same column.
 * ML Kit line boxes hug the ink, so a 1-2 char last line ("on.", "了。") is shorter than [a]'s lines and starts lower;
 * such a narrow single line is judged by [a]'s line height and the line-centre pitch instead of its own height.
 */
private fun continues(a: OcrCandidate, b: OcrCandidate): Boolean {
    val ha = lineHeight(a)
    val hb = lineHeight(b)
    if (ha <= 0f || hb <= 0f) return false
    val tail = b.lines.size == 1 && hb < ha && b.box.width <= SHORT_TAIL * ha
    val h = if (tail) ha else min(ha, hb)
    if (!tail && max(ha, hb) / h > MAX_HEIGHT_RATIO) return false
    val gap = b.box.top - a.box.bottom
    if (gap < -0.2f * h) return false                                   // overlapped, e.g. side by side
    if (tail) {
        if (centerY(b.box) - centerY(a.lines.last()) > (1 + MAX_GAP) * h) return false   // = gap ≤ MAX_GAP·h at full height
    } else if (gap > MAX_GAP * h) return false
    if (kotlin.math.abs(a.box.left - b.box.left) > MAX_INDENT * h) return false
    val overlap = min(a.box.right, b.box.right) - max(a.box.left, b.box.left)
    return overlap >= MIN_X_OVERLAP * min(a.box.width, b.box.width)
}

/** Greedy top-down chaining: each paragraph is appended to the first group whose last paragraph it continues. ponytail: O(n²). */
private fun joinParagraphs(sorted: List<OcrCandidate>): List<OcrCandidate> {
    val out = ArrayList<OcrCandidate>(sorted.size)
    for (c in sorted) {
        val i = out.indexOfFirst { continues(it, c) }
        if (i < 0) { out += c; continue }
        val a = out[i]
        out[i] = OcrCandidate(
            a.text + "\n" + c.text,
            Box(min(a.box.left, c.box.left), a.box.top, max(a.box.right, c.box.right), max(a.box.bottom, c.box.bottom)),
            a.lines + c.lines,
            a.confidence,
        )
    }
    return out.sortedWith(compareBy({ it.box.top }, { it.box.left }))
}
