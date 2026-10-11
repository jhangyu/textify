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
import androidx.core.animation.doOnEnd
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Geometry/alpha of one Halo ring (spec §8.1). Halo width = (active + breath·b + bloom·bloomTerm)·(1-mix) + settled·mix,
 * halo alpha = a·lerp(alphaActive, alphaSettled, mix); core (skipped when coreDp == 0) width = core + coreBreath·b,
 * alpha a·(1-mix)·coreAlpha; spin speed lerp(1, residualSpin, mix); ring rect = (inset, inset, w-inset, h+extendBottom-inset).
 */
data class HaloRecipe(
    val cornerDp: Float,
    val insetDp: Float,
    val haloBlurDp: Float,
    val coreBlurDp: Float,
    val haloActiveDp: Float,
    val haloBreathDp: Float,
    val haloBloomDp: Float,
    val haloSettledDp: Float,
    val alphaActive: Float,
    val alphaSettled: Float,
    val coreDp: Float,
    val coreBreathDp: Float,
    val coreAlpha: Float,
    val residualSpin: Float,
    val extendBottomDp: Float,
) {
    companion object {
        /** a2 session halo (values unchanged from the capture-session implementation). */
        val SESSION = HaloRecipe(
            cornerDp = 36f, insetDp = 0f, haloBlurDp = 30f, coreBlurDp = 7f,
            haloActiveDp = 44f, haloBreathDp = 10f, haloBloomDp = 70f, haloSettledDp = 26f,
            alphaActive = 0.75f, alphaSettled = 0.22f, coreDp = 12f, coreBreathDp = 4f, coreAlpha = 0.9f,
            residualSpin = 0.12f, extendBottomDp = 0f,
        )

        /** permission-2 mini halo: ring((26+40·bloom)(1-mix)+16·mix, blur 18, a(.75-.45mix)), core ring(7, blur 4), rect H+40, r26. */
        val ONBOARDING_MINI = HaloRecipe(
            cornerDp = 26f, insetDp = 0f, haloBlurDp = 18f, coreBlurDp = 4f,
            haloActiveDp = 26f, haloBreathDp = 0f, haloBloomDp = 40f, haloSettledDp = 16f,
            alphaActive = 0.75f, alphaSettled = 0.30f, coreDp = 7f, coreBreathDp = 0f, coreAlpha = 0.9f,
            residualSpin = 1f, extendBottomDp = 40f,
        )

        /** settings-final mini halo: (22+34·bloom)(1-mix)+14·mix, blur 15, core 6 blur 3, rect H+30, r20. */
        val SETTINGS_MINI = HaloRecipe(
            cornerDp = 20f, insetDp = 0f, haloBlurDp = 15f, coreBlurDp = 3f,
            haloActiveDp = 22f, haloBreathDp = 0f, haloBloomDp = 34f, haloSettledDp = 14f,
            alphaActive = 0.75f, alphaSettled = 0.30f, coreDp = 6f, coreBreathDp = 0f, coreAlpha = 0.9f,
            residualSpin = 1f, extendBottomDp = 30f,
        )

        /**
         * Language preview residual edge: a 10dp band inside a r24 box (centre line inset 5dp, radius 24-5 = 19),
         * blur 12, alpha .22, one turn per 20000 ms (= glowLoop 2400 / .12).
         */
        val PREVIEW_EDGE = HaloRecipe(
            cornerDp = 19f, insetDp = 5f, haloBlurDp = 12f, coreBlurDp = 0f,
            haloActiveDp = 10f, haloBreathDp = 0f, haloBloomDp = 0f, haloSettledDp = 10f,
            alphaActive = 0.22f, alphaSettled = 0.22f, coreDp = 0f, coreBreathDp = 0f, coreAlpha = 0f,
            residualSpin = 0.12f, extendBottomDp = 0f,
        )
    }
}

/**
 * a2 "Halo" edge glow, parameterised by [recipe]. One code path for API 26-36:
 * the blurred ring (halo + core) is rendered white into a 1/4-scale mask on a software canvas
 * (BlurMaskFilter is not supported on hardware canvases), then coloured by a rotating
 * SweepGradient through ComposeShader(sweep, mask, DST_IN).
 */
class EdgeGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density

    /** Set before the first layout; default = the a2 session halo. */
    var recipe: HaloRecipe = HaloRecipe.SESSION
        set(value) {
            field = value
            haloBlur = blur(value.haloBlurDp)
            coreBlur = blur(value.coreBlurDp)
            maskKey = -1L
            invalidate()
        }

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
    private var haloBlur: BlurMaskFilter? = blur(recipe.haloBlurDp)
    private var coreBlur: BlurMaskFilter? = blur(recipe.coreBlurDp)

    private fun blur(rDp: Float): BlurMaskFilter? =
        if (rDp > 0f) BlurMaskFilter(rDp * dp * MASK_SCALE, BlurMaskFilter.Blur.NORMAL) else null

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

    /** Core fades out, halo settles to recipe.haloSettledDp @ alphaSettled, rotation slows to residualSpin. */
    fun settle() {
        mixAnim?.cancel()
        mixAnim = ValueAnimator.ofFloat(mix, 1f).apply {
            duration = GLOW_SETTLE
            interpolator = EASE_IN_OUT_CUBIC
            addUpdateListener { mix = it.animatedValue as Float }
            start()
        }
    }

    /** Overall alpha -> 0 over [ms] (ease-out cubic); stops animating when it reaches 0. */
    fun fadeOut(ms: Long) {
        alphaAnim?.cancel()
        alphaAnim = ValueAnimator.ofFloat(a, 0f).apply {
            duration = ms
            interpolator = EASE_OUT_CUBIC
            addUpdateListener { a = it.animatedValue as Float }
            doOnEnd { if (a <= 0.001f) running = false }
            start()
        }
    }

    /** Settled look at once (no bloom, no core), rotating at residualSpin. */
    fun showSettled() {
        alphaAnim?.cancel()
        mixAnim?.cancel()
        a = 1f
        mix = 1f
        val now = AnimationUtils.currentAnimationTimeMillis()
        t0 = now - BLOOM.toLong()
        last = now
        maskKey = -1L
        running = true
        postInvalidateOnAnimation()
    }

    /** Nothing drawn, no animation. */
    fun clear() {
        alphaAnim?.cancel()
        mixAnim?.cancel()
        a = 0f
        running = false
        invalidate()
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
        val r = recipe
        val now = AnimationUtils.currentAnimationTimeMillis()
        val dt = min(64L, now - last)
        last = now
        spin += dt * lerp(1f, r.residualSpin, mix)
        if (a > 0.001f) {
            val breath = sin(spin / BREATH * 2.0 * PI).toFloat()
            val bloom = 1f - easeOut(min(1f, (now - t0) / BLOOM))
            renderMask(
                m,
                haloWidthDp = (r.haloActiveDp + r.haloBreathDp * breath + r.haloBloomDp * bloom) * (1f - mix) + r.haloSettledDp * mix,
                haloAlpha = a * lerp(r.alphaActive, r.alphaSettled, mix),
                coreWidthDp = r.coreDp + r.coreBreathDp * breath,
                coreAlpha = if (r.coreDp > 0f) a * (1f - mix) * r.coreAlpha else 0f,
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
        val ca = if (coreAlpha > 0.01f) (coreAlpha.coerceIn(0f, 1f) * 255f).roundToInt() else 0
        val cw = if (ca > 0) (coreWidthDp * 2f).roundToInt() else 0  // invisible core: breathing must not dirty the mask
        val key = (hw.toLong() shl 48) or (ha.toLong() shl 32) or (cw.toLong() shl 16) or ca.toLong()
        if (key == maskKey && maskShader != null) return
        maskKey = key

        val c = maskCanvas ?: return
        val s = MASK_SCALE
        val mw = m.width.toFloat()
        val mh = m.height.toFloat()
        val rr = recipe.cornerDp * dp * s
        val ins = recipe.insetDp * dp * s
        val bottom = mh + recipe.extendBottomDp * dp * s - ins
        m.eraseColor(Color.TRANSPARENT)
        ring.maskFilter = haloBlur
        ring.strokeWidth = haloWidthDp * dp * s
        ring.alpha = ha
        c.drawRoundRect(ins, ins, mw - ins, bottom, rr, rr, ring)
        if (ca > 0) {
            ring.maskFilter = coreBlur
            ring.strokeWidth = coreWidthDp * dp * s
            ring.alpha = ca
            c.drawRoundRect(ins, ins, mw - ins, bottom, rr, rr, ring)
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
        // a2 header TIMINGS (shared by every recipe)
        private const val GLOW_IN = 350L
        private const val GLOW_SETTLE = 400L
        private const val BLOOM = 400f
        private const val GLOW_LOOP = 2400f
        private const val BREATH = 1200f
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
