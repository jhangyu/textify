package us.jhangy.textify

import kotlin.math.max
import kotlin.math.min

/** Integer rect in screenshot pixels. Pure Kotlin so merge and layout math run on the JVM test runner. */
internal data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * One recognised paragraph (ML Kit TextBlock) from one script model; [lines] = per-line boxes in reading order:
 * top to bottom, or for [vertical] CJK text the columns right to left.
 */
internal data class OcrCandidate(
    val text: String,
    val box: Box,
    val lines: List<Box>,
    val confidence: Float,
    val vertical: Boolean = false,
)

private fun isCjk(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
    Character.UnicodeScript.HANGUL -> true
    else -> false
}

/**
 * Orientation from geometry, CJK only (more CJK than other letters; Latin text is always horizontal). ML Kit has no
 * orientation flag, but a CJK "line" of ≥2 characters is wider than tall when horizontal and taller than wide when it
 * is a column. Lines vote by that; a block of only 1-char lines is vertical when its lines sit side by side.
 * ponytail: no cornerPoints/angle, rotated text is out of scope.
 */
internal fun isVertical(lineTexts: List<String>, lines: List<Box>): Boolean {
    val cps = lineTexts.joinToString("").codePoints().toArray().filter { Character.isLetter(it) }
    if (cps.count(::isCjk) * 2 <= cps.size) return false
    val long = lines.indices.filter { i -> lineTexts[i].count { !it.isWhitespace() } >= 2 }
    if (long.isNotEmpty()) return long.count { lines[it].height > lines[it].width } * 2 > long.size
    if (lines.size < 2) return false
    val xs = lines.map { (it.left + it.right) / 2 }
    val ys = lines.map { (it.top + it.bottom) / 2 }
    return xs.max() - xs.min() > ys.max() - ys.min()
}

/** A candidate from one TextBlock's lines; vertical columns are put in reading order (right to left) — ML Kit's line order there is undocumented. */
internal fun ocrCandidate(lineTexts: List<String>, lines: List<Box>, box: Box, confidence: Float): OcrCandidate {
    val vertical = isVertical(lineTexts, lines)
    val order = if (vertical) lines.indices.sortedByDescending { lines[it].right } else lines.indices.toList()
    return OcrCandidate(order.joinToString("\n") { lineTexts[it] }, box, order.map { lines[it] }, confidence, vertical)
}

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
    val (vertical, horizontal) = kept.partition { it.vertical }   // the two orientations never join each other
    return (joinParagraphs(horizontal) + joinParagraphs(vertical.map { it.turned() }).map { it.unturned() })
        .sortedWith(compareBy({ it.box.top }, { it.box.left }))
}

/**
 * Vertical text turned 90° so its columns become lines (x' = y, y' = -x): right-to-left columns read top-to-bottom,
 * so [joinParagraphs]/[continues] apply unchanged (column width = line height, top edge = left edge, next column = line below).
 */
private fun Box.turned() = Box(top, -right, bottom, -left)
private fun Box.unturned() = Box(-bottom, left, -top, right)
private fun OcrCandidate.turned() = copy(box = box.turned(), lines = lines.map { it.turned() })
private fun OcrCandidate.unturned() = copy(box = box.unturned(), lines = lines.map { it.unturned() })

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
private fun joinParagraphs(paragraphs: List<OcrCandidate>): List<OcrCandidate> {
    val out = ArrayList<OcrCandidate>(paragraphs.size)
    for (c in paragraphs.sortedWith(compareBy({ it.box.top }, { it.box.left }))) {
        val i = out.indexOfFirst { continues(it, c) }
        if (i < 0) { out += c; continue }
        val a = out[i]
        out[i] = a.copy(
            text = a.text + "\n" + c.text,
            box = Box(min(a.box.left, c.box.left), a.box.top, max(a.box.right, c.box.right), max(a.box.bottom, c.box.bottom)),
            lines = a.lines + c.lines,
        )
    }
    return out
}
