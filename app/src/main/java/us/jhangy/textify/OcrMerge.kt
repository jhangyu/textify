package us.jhangy.textify

import kotlin.math.max
import kotlin.math.min

/** Integer rect in screenshot pixels. Pure Kotlin so merge and layout math run on the JVM test runner. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** One recognised character (ML Kit Text.Symbol) and its box. */
data class OcrChar(val text: String, val box: Box)

/**
 * One recognised paragraph (ML Kit TextBlock) from one script model; [lines] = per-line boxes in reading order:
 * top to bottom, or for [vertical] CJK text the columns right to left. [chars] = its characters, for [regroup].
 */
internal data class OcrCandidate(
    val text: String,
    val box: Box,
    val lines: List<Box>,
    val confidence: Float,
    val vertical: Boolean = false,
    val chars: List<OcrChar> = emptyList(),
)

private fun isCjk(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
    Character.UnicodeScript.HANGUL -> true
    else -> false
}

/** More CJK than other letters. */
private fun mostlyCjk(text: String): Boolean {
    val cps = text.codePoints().toArray().filter { Character.isLetter(it) }
    return cps.count(::isCjk) * 2 > cps.size
}

/**
 * Orientation from geometry, CJK only (more CJK than other letters; Latin text is always horizontal). ML Kit has no
 * orientation flag, but a CJK "line" of ≥2 characters is wider than tall when horizontal and taller than wide when it
 * is a column. Lines vote by that; a block of only 1-char lines is vertical when its lines sit side by side.
 * ponytail: no cornerPoints/angle, rotated text is out of scope.
 */
internal fun isVertical(lineTexts: List<String>, lines: List<Box>): Boolean {
    if (!mostlyCjk(lineTexts.joinToString(""))) return false
    val long = lines.indices.filter { i -> lineTexts[i].count { !it.isWhitespace() } >= 2 }
    if (long.isNotEmpty()) return long.count { lines[it].height > lines[it].width } * 2 > long.size
    if (lines.size < 2) return false
    val xs = lines.map { (it.left + it.right) / 2 }
    val ys = lines.map { (it.top + it.bottom) / 2 }
    return xs.max() - xs.min() > ys.max() - ys.min()
}

/** A candidate from one TextBlock's lines; vertical columns are put in reading order (right to left) — ML Kit's line order there is undocumented. */
internal fun ocrCandidate(
    lineTexts: List<String>, lines: List<Box>, box: Box, confidence: Float, chars: List<OcrChar> = emptyList(),
): OcrCandidate {
    val vertical = isVertical(lineTexts, lines)
    val order = if (vertical) lines.indices.sortedByDescending { lines[it].right } else lines.indices.toList()
    return OcrCandidate(order.joinToString("\n") { lineTexts[it] }, box, order.map { lines[it] }, confidence, vertical, chars)
}

private const val DUP_COVER = 0.5f   // a character covered this much (either box's own area) by a kept one is a duplicate
private const val SAME_LINE = 0.6f   // max centre offset from a line's mean centre, in character sizes

/**
 * Re-reads [chars] as lines: [vertical] = columns right to left, top to bottom inside a column; else rows top to
 * bottom, left to right inside a row. A character overlapping an earlier kept one (≥ [DUP_COVER]) is dropped.
 * Returns the text ("\n" between lines) and the line boxes in reading order.
 * ponytail: 1-D clustering of centres against the median character size; rotated or wildly uneven text is out of scope.
 */
internal fun regroup(chars: List<OcrChar>, vertical: Boolean): Pair<String, List<Box>> {
    val kept = ArrayList<OcrChar>(chars.size)
    for (c in chars) if (kept.none { max(coverage(c.box, it.box), coverage(it.box, c.box)) >= DUP_COVER }) kept += c
    if (kept.isEmpty()) return "" to emptyList()
    val size = kept.map { max(it.box.width, it.box.height) }.sorted()[kept.size / 2]
    fun across(c: OcrChar) = if (vertical) -(c.box.left + c.box.right) / 2f else (c.box.top + c.box.bottom) / 2f
    fun along(c: OcrChar) = if (vertical) c.box.top + c.box.bottom else c.box.left + c.box.right
    val groups = ArrayList<MutableList<OcrChar>>()
    for (c in kept.sortedBy(::across)) {
        val g = groups.lastOrNull()
        if (g != null && across(c) - g.map(::across).average() <= SAME_LINE * size) g += c else groups += mutableListOf(c)
    }
    val lines = groups.map { g -> g.sortedBy(::along) }
    return lines.joinToString("\n") { l -> l.joinToString("") { it.text } } to lines.map { l ->
        Box(l.minOf { it.box.left }, l.minOf { it.box.top }, l.maxOf { it.box.right }, l.maxOf { it.box.bottom })
    }
}

/**
 * Paragraphs a flip of paragraph [i] pulls in (ML Kit can split one bubble, e.g. a lone last character): every other
 * mostly-CJK paragraph whose box lies within one median character size of [i]'s box (gap on both axes).
 * [boxes]/[texts] = all paragraphs, [chars] = paragraph [i]'s characters.
 * ponytail: direct neighbours of [i]'s box only, no chains; a bubble split into far-apart pieces stays split.
 */
internal fun flipNeighbours(i: Int, boxes: List<Box>, texts: List<String>, chars: List<OcrChar>): List<Int> {
    if (chars.isEmpty()) return emptyList()
    val size = charSize(chars)
    val a = boxes[i]
    return boxes.indices.filter { j ->
        val b = boxes[j]
        j != i && mostlyCjk(texts[j]) &&
            max(a.left - b.right, b.left - a.right) <= size && max(a.top - b.bottom, b.top - a.bottom) <= size
    }
}

/** Median character size (longer side of each character box); [chars] must not be empty. */
private fun charSize(chars: List<OcrChar>): Int = chars.map { max(it.box.width, it.box.height) }.sorted()[chars.size / 2]

private const val SHORT_LINE = 12   // ponytail: longest line (chars) still re-read when horizontal; a wider crop squashed to 224×224 reads badly. Tune on real pages.

/**
 * manga-ocr re-read trigger (manga-ocr contract decision 1): [text] has kana and is [vertical] or its longest line
 * has ≤ [SHORT_LINE] characters. Long horizontal Japanese (app UI, prose) and kana-less text stay ML Kit only.
 */
internal fun mangaTrigger(text: String, vertical: Boolean): Boolean =
    text.any { it in '\u3040'..'\u30FF' } &&
        (vertical || text.split('\n').maxOf { l -> l.count { !it.isWhitespace() } } <= SHORT_LINE)

/** One manga-ocr re-read: paragraph [index] plus the [absorbed] ones; [box] = their union, [crop] = it padded. */
internal data class MangaCrop(val index: Int, val absorbed: List<Int>, val box: Box, val crop: Box)

private const val CROP_PAD = 0.5f   // crop padding, in median character sizes (desktop bench: 0 broke a read, 0.5 = 1.0)

/**
 * Crops to re-read in a [width]×[height] screenshot: each [mangaTrigger] paragraph (in list order, not yet absorbed)
 * joined with its [flipNeighbours] that also trigger, padded by [CROP_PAD] median character sizes, clamped to the image.
 * Without characters the size is the box's short side per line.
 */
internal fun mangaCrops(paragraphs: List<OcrCandidate>, width: Int, height: Int): List<MangaCrop> {
    val boxes = paragraphs.map { it.box }
    val texts = paragraphs.map { it.text }
    val used = BooleanArray(paragraphs.size)
    val out = ArrayList<MangaCrop>()
    paragraphs.forEachIndexed { i, p ->
        if (used[i] || !mangaTrigger(p.text, p.vertical)) return@forEachIndexed
        val absorbed = flipNeighbours(i, boxes, texts, p.chars).filter { !used[it] && mangaTrigger(texts[it], paragraphs[it].vertical) }
        used[i] = true
        absorbed.forEach { used[it] = true }
        val box = (absorbed.map { boxes[it] }).fold(p.box) { a, b ->
            Box(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
        }
        val size = if (p.chars.isNotEmpty()) charSize(p.chars) else min(p.box.width, p.box.height) / max(1, p.lines.size)
        val pad = Math.round(CROP_PAD * size)
        val crop = Box(max(0, box.left - pad), max(0, box.top - pad), min(width, box.right + pad), min(height, box.bottom + pad))
        out += MangaCrop(i, absorbed, box, crop)
    }
    return out
}

/**
 * Applies the re-reads: [read] = manga-ocr text of a crop, null = failed (paragraph and its neighbours keep ML Kit's
 * text). A re-read paragraph becomes one line over the union box, vertical when taller than wide, without characters
 * (so a double tap does not flip it); absorbed paragraphs disappear. Result sorted top, then left, like [mergeOcr].
 */
internal fun applyMangaReads(paragraphs: List<OcrCandidate>, crops: List<MangaCrop>, read: (Box) -> String?): List<OcrCandidate> {
    val out = paragraphs.toMutableList<OcrCandidate?>()
    for (c in crops) {
        val text = read(c.crop) ?: continue
        out[c.index] = paragraphs[c.index].copy(
            text = text, box = c.box, lines = listOf(c.box), vertical = c.box.height > c.box.width, chars = emptyList(),
        )
        c.absorbed.forEach { out[it] = null }
    }
    return out.filterNotNull().sortedWith(compareBy({ it.box.top }, { it.box.left }))
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
    val (vertical, horizontal) = foldIntoVertical(kept).partition { it.vertical }   // the two orientations never join each other
    return (joinParagraphs(horizontal) + joinParagraphs(vertical.map { it.turned() }).map { it.unturned() })
        .sortedWith(compareBy({ it.box.top }, { it.box.left }))
}

/**
 * ML Kit can read a multi-column vertical bubble row-wise, or return a correct column plus row-wise fragments over it:
 * a horizontal CJK paragraph whose box overlaps a vertical one is joined into it and the characters re-read as columns
 * ([regroup] drops the duplicated ones).
 */
private fun foldIntoVertical(paragraphs: List<OcrCandidate>): List<OcrCandidate> {
    val out = paragraphs.toMutableList()
    for (h in paragraphs) {
        if (h.vertical || h.chars.isEmpty() || !mostlyCjk(h.text)) continue
        val i = out.indexOfFirst { it.vertical && it.chars.isNotEmpty() && coverage(h.box, it.box) > 0f }
        if (i < 0) continue
        val v = out[i]
        val chars = v.chars + h.chars
        val (text, lines) = regroup(chars, vertical = true)
        val box = Box(min(v.box.left, h.box.left), min(v.box.top, h.box.top), max(v.box.right, h.box.right), max(v.box.bottom, h.box.bottom))
        out[i] = v.copy(text = text, box = box, lines = lines, chars = chars)
        out.remove(h)
    }
    return out
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
            chars = a.chars + c.chars,
        )
    }
    return out
}
