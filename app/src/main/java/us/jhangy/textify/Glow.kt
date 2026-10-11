package us.jhangy.textify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Shared Halo painting helpers (spec §8.3). One code path for API 26-36: every glow is a blurred shape
 * rendered once per size with BlurMaskFilter on a software canvas, the technique PillView/EdgeGlowView use.
 */

internal fun withAlpha(alpha: Float, rgb: Int): Int = ((alpha * 255f).roundToInt() shl 24) or (rgb and 0xFFFFFF)

internal fun Context.dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

// ponytail: glow bitmaps at half resolution; they are soft blurs so the loss is invisible
internal const val GLOW_SCALE = 0.5f

internal fun glowPad(blurPx: Float, spreadPx: Float, dyPx: Float): Float = blurPx + max(0f, spreadPx) + abs(dyPx)

/**
 * CSS `box-shadow: 0 dy blur spread color` around a w×h round rect (cornerPx < 0 = h/2).
 * The bitmap covers the element padded by [glowPad] on every side at [GLOW_SCALE];
 * draw it into RectF(-pad, -pad, w + pad, h + pad). clearInside erases the element's own rect.
 */
internal fun glowBitmap(
    w: Float, h: Float, cornerPx: Float, blurPx: Float, color: Int,
    spreadPx: Float = 0f, dyPx: Float = 0f, clearInside: Boolean = true,
): Bitmap {
    val s = GLOW_SCALE
    val pad = glowPad(blurPx, spreadPx, dyPx)
    val bmp = Bitmap.createBitmap(
        max(1, ((w + 2f * pad) * s).roundToInt()),
        max(1, ((h + 2f * pad) * s).roundToInt()),
        Bitmap.Config.ARGB_8888,
    )
    val c = Canvas(bmp)
    val r = if (cornerPx < 0f) h / 2f else cornerPx
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        if (blurPx > 0f) maskFilter = BlurMaskFilter(blurPx * s, BlurMaskFilter.Blur.NORMAL)
    }
    val rs = max(0f, r + spreadPx) * s
    c.drawRoundRect(
        (pad - spreadPx) * s, (pad - spreadPx + dyPx) * s,
        (pad + w + spreadPx) * s, (pad + h + spreadPx + dyPx) * s, rs, rs, p,
    )
    if (clearInside) {
        p.maskFilter = null
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        c.drawRoundRect(pad * s, pad * s, (pad + w) * s, (pad + h) * s, r * s, r * s, p)
    }
    return bmp
}

/** Halo pill: horizontal LinearGradient fill + outer glow (CSS box-shadow 0 0 glowRadius glowColor). */
class PillPainter(
    private val fromColor: Int,
    private val toColor: Int,
    private val glowColor: Int,
    private val glowRadiusPx: Float,
    private val cornerPx: Float,
) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glowDst = RectF()
    private var glow: Bitmap? = null
    private var w = 0f
    private var h = 0f

    fun setSize(w: Int, h: Int) {
        this.w = w.toFloat()
        this.h = h.toFloat()
        if (w <= 0 || h <= 0) {
            glow = null
            return
        }
        fill.shader = LinearGradient(0f, 0f, this.w, 0f, fromColor, toColor, Shader.TileMode.CLAMP)
        glow = if (glowRadiusPx > 0f) glowBitmap(this.w, this.h, cornerPx, glowRadiusPx, glowColor) else null
        glowDst.set(-glowRadiusPx, -glowRadiusPx, this.w + glowRadiusPx, this.h + glowRadiusPx)
    }

    /** Draws into the box (0, 0, w, h) of the current canvas; alpha multiplies fill and glow; scale is about the centre. */
    fun draw(canvas: Canvas, alpha: Float = 1f, scale: Float = 1f, withGlow: Boolean = true) {
        if (w <= 0f || h <= 0f || alpha <= 0f) return
        val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        val saved = canvas.save()
        if (scale != 1f) canvas.scale(scale, scale, w / 2f, h / 2f)
        if (withGlow) glow?.let {
            glowPaint.alpha = a
            canvas.drawBitmap(it, null, glowDst, glowPaint)
        }
        val r = if (cornerPx < 0f) h / 2f else cornerPx
        fill.alpha = a
        canvas.drawRoundRect(0f, 0f, w, h, r, r, fill)
        canvas.restoreToCount(saved)
    }
}

/** Round rect filled with a CSS `linear-gradient(angleDeg, colors)` (0deg = up, clockwise). cornerPx < 0 = h/2. */
class CssGradientDrawable(
    private val colors: IntArray,
    private val positions: FloatArray?,
    private val angleDeg: Float,
    private val cornerPx: Float,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val radius: Float get() = if (cornerPx < 0f) rect.height() / 2f else cornerPx

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
        if (rect.isEmpty) return
        val t = Math.toRadians(angleDeg.toDouble())
        val dx = sin(t).toFloat()
        val dy = -cos(t).toFloat()
        val half = (abs(rect.width() * dx) + abs(rect.height() * dy)) / 2f
        val cx = rect.centerX()
        val cy = rect.centerY()
        paint.shader = LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half, colors, positions, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = paint.alpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radius)
    }

    companion object {
        fun solid(color: Int, cornerPx: Float) = CssGradientDrawable(intArrayOf(color, color), null, 180f, cornerPx)
    }
}

/** [fill] plus a CSS box-shadow drawn outside its bounds (ancestors need clipChildren=false). */
class GlowDrawable(
    private val fill: Drawable,
    private val glowColor: Int,
    private val blurPx: Float,
    private val dyPx: Float,
    private val spreadPx: Float,
    private val cornerPx: Float,
) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private var glow: Bitmap? = null

    override fun onBoundsChange(bounds: Rect) {
        fill.bounds = bounds
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) {
            glow = null
            return
        }
        val pad = glowPad(blurPx, spreadPx, dyPx)
        glow = glowBitmap(w, h, cornerPx, blurPx, glowColor, spreadPx, dyPx, clearInside = true)
        dst.set(bounds.left - pad, bounds.top - pad, bounds.right + pad, bounds.bottom + pad)
    }

    override fun draw(canvas: Canvas) {
        glow?.let { canvas.drawBitmap(it, null, dst, paint) }
        fill.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        fill.alpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = paint.alpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getOutline(outline: Outline) {
        fill.getOutline(outline)
    }
}

/** One CSS `radial-gradient(rw rh at cx cy, color, transparent 70%)` spot; cx, cy are fractions of the bounds. */
data class MistSpot(val color: Int, val cx: Float, val cy: Float, val rwDp: Float, val rhDp: Float)

/** Mist page: solid base + elliptical radial spots (settings / onboarding backgrounds). */
class MistBackground(private val spots: List<MistSpot>, base: Int, private val density: Float) : Drawable() {
    private val basePaint = Paint().apply { color = base }
    private val paints = spots.map { Paint(Paint.DITHER_FLAG) }

    override fun onBoundsChange(bounds: Rect) {
        spots.forEachIndexed { i, s ->
            val cx = bounds.left + s.cx * bounds.width()
            val cy = bounds.top + s.cy * bounds.height()
            val rw = s.rwDp * density
            val rh = s.rhDp * density
            paints[i].shader = RadialGradient(
                cx, cy, rw, intArrayOf(s.color, s.color and 0x00FFFFFF), floatArrayOf(0f, 0.7f), Shader.TileMode.CLAMP,
            ).apply { setLocalMatrix(Matrix().apply { setScale(1f, rh / rw, cx, cy) }) }
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawRect(b, basePaint)
        paints.forEach { canvas.drawRect(b, it) }
    }

    override fun setAlpha(alpha: Int) {
        basePaint.alpha = alpha
        paints.forEach { it.alpha = alpha }
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        basePaint.colorFilter = colorFilter
        paints.forEach { it.colorFilter = colorFilter }
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Shared Halo surfaces (values: spec §3.1, §7.1, §7.2, §8.3; mockups settings-final / permission-2). */
object HaloStyle {
    val MIST: Int = 0xFFFAF5FF.toInt()

    /** permission-2 #sIntro */
    val ONBOARDING_SPOTS = listOf(
        MistSpot(withAlpha(0.30f, 0xA78BFA), 0.20f, 0.10f, 380f, 300f),
        MistSpot(withAlpha(0.22f, 0x67E8F9), 0.90f, 0.40f, 360f, 300f),
        MistSpot(withAlpha(0.20f, 0xFDBA74), 0.30f, 0.60f, 300f, 240f),
    )

    /** settings-final .settings */
    val SETTINGS_SPOTS = listOf(
        MistSpot(withAlpha(0.35f, 0xA78BFA), 0f, 0f, 360f, 300f),
        MistSpot(withAlpha(0.25f, 0xF472B6), 1f, 0.08f, 320f, 300f),
        MistSpot(withAlpha(0.28f, 0x67E8F9), 1f, 1f, 380f, 320f),
        MistSpot(withAlpha(0.28f, 0xFDBA74), 0f, 0.96f, 340f, 300f),
    )

    fun page(ctx: Context, spots: List<MistSpot>): Drawable = MistBackground(spots, MIST, ctx.resources.displayMetrics.density)

    /** CTA: linear-gradient(100deg, #7C3AED, #A855F7 55%, #EC4899), full-round, box-shadow 0 10px 26px #7C3AED 30%. */
    fun cta(ctx: Context): Drawable = GlowDrawable(
        CssGradientDrawable(intArrayOf(0xFF7C3AED.toInt(), 0xFFA855F7.toInt(), 0xFFEC4899.toInt()), floatArrayOf(0f, 0.55f, 1f), 100f, -1f),
        withAlpha(0.30f, 0x7C3AED), ctx.dpToPx(26f), ctx.dpToPx(10f), 0f, -1f,
    )

    /** Card: #FFFFFF 78% (#C7FFFFFF) r28, box-shadow 0 10px 24px -8px #7C3AED 12% (the 0 1px 2px 4% hairline layer is not drawn). */
    fun card(ctx: Context): Drawable {
        val r = ctx.dpToPx(28f)
        return GlowDrawable(
            CssGradientDrawable.solid(0xC7FFFFFF.toInt(), r),
            withAlpha(0.12f, 0x7C3AED), ctx.dpToPx(24f), ctx.dpToPx(10f), ctx.dpToPx(-8f), r,
        )
    }

    /** Denied chip: #FFFFFF 94% full-round, box-shadow 0 8px 24px #FDBA74 35%. */
    fun chip(ctx: Context): Drawable = GlowDrawable(
        CssGradientDrawable.solid(withAlpha(0.94f, 0xFFFFFF), -1f),
        withAlpha(0.35f, 0xFDBA74), ctx.dpToPx(24f), ctx.dpToPx(8f), 0f, -1f,
    )
}
