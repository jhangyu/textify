package us.jhangy.textify

/*
 * Pure layout for vertical (top-to-bottom, columns right-to-left) translations of vertical CJK paragraphs.
 * No Android imports: runs on the JVM test runner. PillView only draws the result.
 */

/** Fitted size, columns in reading order (right to left) and whether text was cut ("︙" ends the last column). */
internal data class VerticalLayout(val textPx: Float, val columns: List<String>, val truncated: Boolean)

/** Column pitch as a multiple of the text size (glyph em + gap). */
internal const val VCOL_PITCH = 1.2f

/** Vertical drawing only for Chinese / Japanese / Korean targets. */
internal fun verticalTarget(t: TargetLang): Boolean = t.mlkit == "zh" || t.mlkit == "ja" || t.mlkit == "ko"

private const val VERTICAL_MARK = '︙'

// ponytail: Unicode vertical presentation forms; fonts without them show tofu — switch to the OpenType "vert" feature if seen
private val VERTICAL_FORMS = mapOf(
    '、' to '︑', '。' to '︒', '，' to '︐', '：' to '︓', '；' to '︔', '！' to '︕', '？' to '︖',
    '「' to '﹁', '」' to '﹂', '『' to '﹃', '』' to '﹄', '（' to '︵', '）' to '︶', '(' to '︵', ')' to '︶',
    '【' to '︻', '】' to '︼', '《' to '︽', '》' to '︾', '〈' to '︿', '〉' to '﹀', '〔' to '︹', '〕' to '︺',
    '｛' to '︷', '｝' to '︸', 'ー' to '︱', '—' to '︱', '…' to '︙', '‥' to '︰',
)

/** [s] with common CJK punctuation replaced by its vertical presentation form; other characters unchanged. */
internal fun verticalForms(s: String): String = buildString(s.length) { for (c in s) append(VERTICAL_FORMS[c] ?: c) }

/** [text] broken into columns of at most [rows] glyphs (code points); "\n" starts a new column. */
internal fun verticalColumns(text: String, rows: Int): List<String> {
    val cols = ArrayList<String>()
    for (para in text.split('\n')) {
        val col = StringBuilder()
        var n = 0
        var i = 0
        while (i < para.length) {
            val cp = para.codePointAt(i)
            if (n == rows) {
                cols += col.toString()
                col.clear()
                n = 0
            }
            col.appendCodePoint(cp)
            n++
            i += Character.charCount(cp)
        }
        cols += col.toString()
    }
    return cols
}

/**
 * Spec §3 for vertical text: largest size from max(maxPx, minPx) down to [minPx] (1 px steps) whose columns fit
 * [innerW]; one glyph = one em of [innerH]. Below the minimum, keep the columns that fit and end the last with "︙".
 */
internal fun layoutVertical(text: String, innerW: Int, innerH: Int, maxPx: Float, minPx: Float): VerticalLayout {
    val v = verticalForms(text)
    fun columnsAt(px: Float) = verticalColumns(v, maxOf(1, (innerH / px).toInt()))
    val fit = fitTextSize(maxPx, minPx, 1f, innerW) { px -> Math.ceil((columnsAt(px).size * px * VCOL_PITCH).toDouble()).toInt() }
    val cols = columnsAt(fit.textPx)
    if (!fit.truncated) return VerticalLayout(fit.textPx, cols, false)
    val rows = maxOf(1, (innerH / fit.textPx).toInt())
    val kept = cols.take(maxOf(1, (innerW / (fit.textPx * VCOL_PITCH)).toInt())).toMutableList()
    val last = kept.last()
    kept[kept.size - 1] =
        if (last.codePointCount(0, last.length) < rows) last + VERTICAL_MARK
        else last.substring(0, last.offsetByCodePoints(last.length, -1)) + VERTICAL_MARK
    return VerticalLayout(fit.textPx, kept, true)
}
