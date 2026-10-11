package us.jhangy.textify

import kotlin.math.max
import kotlin.math.min

/** One recognised line from one script model; box in screenshot pixels. Pure Kotlin so the merge runs on the JVM test runner. */
internal data class OcrCandidate(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float,
)

/** Intersection over union of two integer rects; 0 when they do not overlap or the union is empty. */
internal fun iou(a: OcrCandidate, b: OcrCandidate): Float {
    val iw = min(a.right, b.right) - max(a.left, b.left)
    val ih = min(a.bottom, b.bottom) - max(a.top, b.top)
    if (iw <= 0 || ih <= 0) return 0f
    val inter = iw.toLong() * ih
    val areaA = (a.right - a.left).toLong() * (a.bottom - a.top)
    val areaB = (b.right - b.left).toLong() * (b.bottom - b.top)
    val union = areaA + areaB - inter
    return if (union <= 0L) 0f else inter.toFloat() / union
}

private const val IOU_LIMIT = 0.5f

/**
 * Merges the lines of several recognizers run on the same screenshot (spec §6.2): highest confidence first
 * (ties: longer text, then input order); a line overlapping an already kept one with IoU > 0.5 is dropped.
 * Equal confidences (e.g. models that report 0) fall through to "longer text wins".
 * Result sorted top-to-bottom, then left-to-right.
 */
internal fun mergeOcr(candidates: List<OcrCandidate>): List<OcrCandidate> {
    val ranked = candidates.sortedWith(
        compareByDescending<OcrCandidate> { it.confidence }.thenByDescending { it.text.length },
    )   // sortedWith is stable: input order breaks remaining ties
    val kept = ArrayList<OcrCandidate>(ranked.size)
    for (c in ranked) if (kept.none { iou(it, c) > IOU_LIMIT }) kept += c
    return kept.sortedWith(compareBy({ it.top }, { it.left }))
}
