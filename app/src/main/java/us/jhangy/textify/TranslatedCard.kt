package us.jhangy.textify

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import kotlin.math.max
import kotlin.math.roundToInt

/** Average screenshot colour along [box] (screenshot px), 2 px outside it, every 4 px (prism-tide note 4); recycled -> white. */
internal fun edgeColor(shot: Bitmap, box: Rect): Int {
    if (shot.isRecycled) return Color.WHITE
    val points = edgePoints(box.toBox(), EDGE_OUT_PX, EDGE_STEP_PX, shot.width, shot.height)
    return averageColor(IntArray(points.size) { shot.getPixel(points[it].first, points[it].second) })
}

private const val EDGE_OUT_PX = 2
private const val EDGE_STEP_PX = 4

/**
 * Translated paragraph card (translation spec §3, prism-tide header): opaque [color] fill, outer glow #A78BFA 42 %
 * blur 14dp + #7C3AED 16 % dy 4dp blur 14dp (software bitmaps, one path for API 26+), bottom Halo underline
 * 2.5dp at 90 %, and the truncation "⋯" mark of proposal-1 (fade in the card colour + "⋯" at 75 % text colour).
 */
internal class CardPainter(res: Resources) {
    private val density = res.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val underline = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, MARK_SP, res.displayMetrics)
        textAlign = Paint.Align.RIGHT
        letterSpacing = MARK_SPACING
    }
    private val cardPath = Path()
    private val lavenderDst = RectF()
    private val violetDst = RectF()
    private var lavender: Bitmap? = null
    private var violet: Bitmap? = null
    private var w = 0f
    private var h = 0f
    private var r = 0f

    /** Card fill: the sampled screenshot colour (opaque). */
    var color: Int = Color.WHITE
        set(value) {
            field = value
            fill.color = value
        }

    /** Text colour of the card (textColorFor(color)); the "⋯" mark uses it at 75 %. */
    var textColor: Int = TEXT_DARK

    fun setSize(w: Int, h: Int, cornerPx: Float) {
        this.w = w.toFloat()
        this.h = h.toFloat()
        r = cornerPx
        cardPath.reset()
        if (w <= 0 || h <= 0) {
            lavender = null
            violet = null
            return
        }
        cardPath.addRoundRect(0f, 0f, this.w, this.h, r, r, Path.Direction.CW)
        val blur = GLOW_DP * density
        val dy = DY_DP * density
        // lavender built at 70 % so glowAlpha 0.6 = the resting 42 % and 1.0 = the panel focus peak
        lavender = glowBitmap(this.w, this.h, r, blur, withAlpha(0.70f, 0xA78BFA))
        val lp = glowPad(blur, 0f, 0f)
        lavenderDst.set(-lp, -lp, this.w + lp, this.h + lp)
        violet = glowBitmap(this.w, this.h, r, blur, withAlpha(0.16f, 0x7C3AED), dyPx = dy)
        val vp = glowPad(blur, 0f, dy)
        violetDst.set(-vp, -vp, this.w + vp, this.h + vp)
        val inset = max(UNDERLINE_INSET_DP * density, r)
        underline.shader = LinearGradient(inset, 0f, this.w - inset, 0f, HALO, null, Shader.TileMode.CLAMP)
        underline.alpha = (UNDERLINE_ALPHA * 255f).roundToInt()
    }

    /**
     * Draws into (0, 0, w, h) of the current canvas. [glowAlpha] multiplies the lavender glow (0.6 rest, 1.0 focus);
     * The truncation mark is drawn separately by [drawMark] after the text.
     */
    fun draw(canvas: Canvas, glowAlpha: Float) {
        if (w <= 0f || h <= 0f) return
        lavender?.let {
            glowPaint.alpha = (glowAlpha.coerceIn(0f, 1f) * 255f).roundToInt()
            canvas.drawBitmap(it, null, lavenderDst, glowPaint)
        }
        violet?.let {
            glowPaint.alpha = 255
            canvas.drawBitmap(it, null, violetDst, glowPaint)
        }
        canvas.drawRoundRect(0f, 0f, w, h, r, r, fill)
        val inset = max(UNDERLINE_INSET_DP * density, r)
        val uh = UNDERLINE_DP * density
        canvas.drawRoundRect(inset, h - uh, w - inset, h, uh / 2f, uh / 2f, underline)
    }

    /** [areaH] = height of the "⋯" mark area (one text line + bottom padding). proposal-1 .mark: padding 0 6dp 0 18dp, fade transparent -> card colour at 45 %, "⋯" 13sp bold at 75 %. */
    fun drawMark(canvas: Canvas, areaH: Float) {
        val right = w - MARK_END_DP * density
        val left = right - mark.measureText(MARK) - MARK_START_DP * density
        val top = h - areaH
        fade.shader = LinearGradient(
            left, 0f, w, 0f, intArrayOf(color and 0x00FFFFFF, color, color), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        val saved = canvas.save()
        canvas.clipPath(cardPath)
        canvas.drawRect(left, top, w, h, fade)
        mark.color = textColor
        mark.alpha = (MARK_ALPHA * 255f).roundToInt()
        val fm = mark.fontMetrics
        canvas.drawText(MARK, right, top + areaH / 2f - (fm.ascent + fm.descent) / 2f, mark)
        canvas.restoreToCount(saved)
    }

    companion object {
        private const val GLOW_DP = 14f
        private const val DY_DP = 4f
        private const val UNDERLINE_DP = 2.5f
        private const val UNDERLINE_INSET_DP = 6f
        private const val UNDERLINE_ALPHA = 0.9f
        private const val MARK = "⋯"
        private const val MARK_SP = 13f
        private const val MARK_SPACING = 0.08f          // CSS letter-spacing 1px at 13px
        private const val MARK_ALPHA = 0.75f
        private const val MARK_END_DP = 6f
        private const val MARK_START_DP = 18f
        private val HALO = intArrayOf(0xFFA78BFA.toInt(), 0xFFF472B6.toInt(), 0xFFFDBA74.toInt(), 0xFF67E8F9.toInt())
    }
}
