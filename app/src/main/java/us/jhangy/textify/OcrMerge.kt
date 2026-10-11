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

private const val IOU_LIMIT = 0.5f

/**
 * Merges the paragraphs of several recognizers run on the same screenshot (translation spec §1): highest
 * confidence first (ties: longer text, then input order); a paragraph overlapping an already kept one with
 * IoU > 0.5 is dropped. Equal confidences (e.g. models that report 0) fall through to "longer text wins".
 * Result sorted top-to-bottom, then left-to-right.
 */
internal fun mergeOcr(candidates: List<OcrCandidate>): List<OcrCandidate> {
    val ranked = candidates.sortedWith(
        compareByDescending<OcrCandidate> { it.confidence }.thenByDescending { it.text.length },
    )   // sortedWith is stable: input order breaks remaining ties
    val kept = ArrayList<OcrCandidate>(ranked.size)
    for (c in ranked) if (kept.none { iou(it.box, c.box) > IOU_LIMIT }) kept += c
    return kept.sortedWith(compareBy({ it.box.top }, { it.box.left }))
}
