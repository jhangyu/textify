package us.jhangy.textify

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.AnimationUtils
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * a2 "Halo" edge glow. One code path for API 26-36:
 * the blurred ring (halo + core) is rendered white into a 1/4-scale mask on a software canvas
 * (BlurMaskFilter is not supported on hardware canvases), then coloured by a rotating
 * SweepGradient through ComposeShader(sweep, mask, DST_IN).
 */
class EdgeGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density

    // animated state, same names as the a2 mockup's `g`
    private var a = 0f          // overall glow alpha
    private var mix = 0f        // 0 = activation look, 1 = settled residual look
    private var spin = 0f       // rotation progress in ms (advances slower once settled)
    private var t0 = 0L         // activation time, drives the bloom term
    private var last = 0L
    private var running = false
    private var alphaAnim: ValueAnimator? = null
    private var mixAnim: ValueAnimator? = null

    private var mask: Bitmap? = null
    private var maskCanvas: Canvas? = null
    private var maskShader: BitmapShader? = null
    private var sweep: SweepGradient? = null
    private var maskKey = -1L
    private val rotation = Matrix()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)   // bilinear upscale of the 1/4-scale mask
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val haloBlur = BlurMaskFilter(HALO_BLUR_DP * dp * MASK_SCALE, BlurMaskFilter.Blur.NORMAL)
    private val coreBlur = BlurMaskFilter(CORE_BLUR_DP * dp * MASK_SCALE, BlurMaskFilter.Blur.NORMAL)

    /** Bloom in from the bezel: alpha 0 -> 1 over glowIn, breathing, full-speed rotation. */
    fun activate() {
        t0 = AnimationUtils.currentAnimationTimeMillis()
        last = t0
        spin = 0f
        mix = 0f
        maskKey = -1L
        mixAnim?.cancel()
        alphaAnim?.cancel()
        alphaAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = GLOW_IN
            interpolator = EASE_OUT_CUBIC
            addUpdateListener { a = it.animatedValue as Float }
            start()
        }
        running = true
        postInvalidateOnAnimation()
    }

    /** OCR done: core fades out, halo settles to 26dp @ .22, rotation slows to 12%. */
    fun settle() {
        mixAnim?.cancel()
        mixAnim = ValueAnimator.ofFloat(mix, 1f).apply {
            duration = GLOW_SETTLE
            interpolator = EASE_IN_OUT_CUBIC
            addUpdateListener { mix = it.animatedValue as Float }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        // no recycle() of the old mask: the last recorded frame may still reference it; GC frees it
        val m = Bitmap.createBitmap(
            max(1, (w * MASK_SCALE).roundToInt()),
            max(1, (h * MASK_SCALE).roundToInt()),
            Bitmap.Config.ARGB_8888,
        )
        val c = Canvas(m)
        check(!c.isHardwareAccelerated) { "glow mask must be drawn on a software canvas (BlurMaskFilter)" }
        mask = m
        maskCanvas = c
        maskShader = null
        maskKey = -1L
        sweep = SweepGradient(w / 2f, h / 2f, COLORS, STOPS)
    }

    override fun onDraw(canvas: Canvas) {
        if (!running) return
        val m = mask ?: return
        val sw = sweep ?: return
        val now = AnimationUtils.currentAnimationTimeMillis()
        val dt = min(64L, now - last)
        last = now
        spin += dt * lerp(1f, RESIDUAL_SPIN, mix)
        if (a > 0.001f) {
            val breath = sin(spin / BREATH * 2.0 * PI).toFloat()
            val bloom = 1f - easeOut(min(1f, (now - t0) / BLOOM))
            renderMask(
                m,
                haloWidthDp = lerp(44f + 10f * breath + 70f * bloom, 26f, mix),
                haloAlpha = a * lerp(0.75f, 0.22f, mix),
                coreWidthDp = 12f + 4f * breath,
                coreAlpha = a * (1f - mix) * 0.9f,
            )
            rotation.setRotate(spin / GLOW_LOOP * 360f, width / 2f, height / 2f)
            sw.setLocalMatrix(rotation)
            // new ComposeShader each frame so the rotated sweep is never served from a cached native shader
            paint.shader = ComposeShader(sw, maskShader!!, PorterDuff.Mode.DST_IN)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
        postInvalidateOnAnimation()
    }

    /** Redraws the white blurred ring mask only when its quantized geometry/alpha changed. */
    private fun renderMask(m: Bitmap, haloWidthDp: Float, haloAlpha: Float, coreWidthDp: Float, coreAlpha: Float) {
        val hw = (haloWidthDp * 2f).roundToInt()                       // 0.5 dp steps
        val ha = (haloAlpha.coerceIn(0f, 1f) * 255f).roundToInt()
        val cw = (coreWidthDp * 2f).roundToInt()
        val ca = if (coreAlpha > 0.01f) (coreAlpha.coerceIn(0f, 1f) * 255f).roundToInt() else 0
        val key = (hw.toLong() shl 48) or (ha.toLong() shl 32) or (cw.toLong() shl 16) or ca.toLong()
        if (key == maskKey && maskShader != null) return
        maskKey = key

        val c = maskCanvas ?: return
        val s = MASK_SCALE
        val mw = m.width.toFloat()
        val mh = m.height.toFloat()
        val r = CORNER_DP * dp * s
        m.eraseColor(Color.TRANSPARENT)
        ring.maskFilter = haloBlur
        ring.strokeWidth = haloWidthDp * dp * s
        ring.alpha = ha
        c.drawRoundRect(0f, 0f, mw, mh, r, r, ring)
        if (ca > 0) {
            ring.maskFilter = coreBlur
            ring.strokeWidth = coreWidthDp * dp * s
            ring.alpha = ca
            c.drawRoundRect(0f, 0f, mw, mh, r, r, ring)
        }
        // new BitmapShader after every pixel change: never draw a stale snapshot of the mask
        maskShader = BitmapShader(m, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(width / mw, height / mh) })
        }
    }

    override fun onDetachedFromWindow() {
        alphaAnim?.cancel()
        mixAnim?.cancel()
        running = false
        super.onDetachedFromWindow()
    }

    companion object {
        // a2 header: TIMINGS and GLOW RECIPE
        private const val GLOW_IN = 350L
        private const val GLOW_SETTLE = 400L
        private const val BLOOM = 400f
        private const val GLOW_LOOP = 2400f
        private const val BREATH = 1200f
        private const val RESIDUAL_SPIN = 0.12f
        private const val HALO_BLUR_DP = 30f
        private const val CORE_BLUR_DP = 7f
        private const val CORNER_DP = 36f                 // a2 ring() default radius
        // ponytail: 1/4-scale mask keeps the per-frame software blur cheap; raise if the halo looks blocky
        private const val MASK_SCALE = 0.25f

        private val COLORS = longArrayOf(0xFFA78BFA, 0xFFF472B6, 0xFFFDBA74, 0xFF67E8F9, 0xFF818CF8, 0xFFA78BFA).map { it.toInt() }.toIntArray()
        private val STOPS = floatArrayOf(0f, 0.2f, 0.38f, 0.56f, 0.76f, 1f)

        private val EASE_OUT_CUBIC = TimeInterpolator { easeOut(it) }
        private val EASE_IN_OUT_CUBIC = TimeInterpolator { t ->
            if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f
        }

        private fun easeOut(t: Float) = 1f - (1f - t).pow(3)
        private fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t
    }
}
