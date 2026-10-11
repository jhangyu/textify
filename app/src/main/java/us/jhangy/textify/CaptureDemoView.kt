package us.jhangy.textify

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Geometry of one demo variant (spec §8.2; permission-2 = ONBOARDING, settings-final = SETTINGS). */
data class DemoSpec(
    val halo: HaloRecipe,
    val stageCornerDp: Float,
    val screenCornerDp: Float,
    val screenShadowBlurDp: Float,
    val screenShadowDyDp: Float,
    val msgCornerDp: Float,
    val bubbleDp: Float,
    val bubbleRightDp: Float,
    val bubbleTopDp: Float,
    val glyphDp: Float,
    val pillPadXDp: Float,
    val pillPadYDp: Float,
    val pulseDp: Float,
) {
    companion object {
        /** permission-2: .stagebox r34, .mini r26 shadow 0 18 40 -12, .m r12, .mb 36 @ right 6 top 176, glyph 20, .ln::before -5/-2, .gp 60. */
        val ONBOARDING = DemoSpec(HaloRecipe.ONBOARDING_MINI, stageCornerDp = 34f, screenCornerDp = 26f, screenShadowBlurDp = 40f, screenShadowDyDp = 18f, msgCornerDp = 12f, bubbleDp = 36f, bubbleRightDp = 6f, bubbleTopDp = 176f, glyphDp = 20f, pillPadXDp = 5f, pillPadYDp = 2f, pulseDp = 60f)

        /** settings-final: .stagebox r24, .mini r20 shadow 0 14 32 -12, .mm r10, .mb 30 @ right 5 top 84, glyph 17, .ln::before -4/-2, .gp 60. */
        val SETTINGS = DemoSpec(HaloRecipe.SETTINGS_MINI, stageCornerDp = 24f, screenCornerDp = 20f, screenShadowBlurDp = 32f, screenShadowDyDp = 14f, msgCornerDp = 10f, bubbleDp = 30f, bubbleRightDp = 5f, bubbleTopDp = 84f, glyphDp = 17f, pillPadXDp = 4f, pillPadYDp = 2f, pulseDp = 60f)
    }
}

/**
 * Looping miniature of a capture (bubble tap -> Halo ring -> line pills -> close), spec §8.2.
 * This view is the stage box. XML children (by tag): `demoScreen` (direct child; inside: `avatar`,
 * TextViews `line` / `line_me`) and `demoChip`. One 4800 ms ValueAnimator drives every element.
 */
class CaptureDemoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private class Pill(val index: Int, val rect: RectF, val painter: PillPainter)

    private val dp = resources.displayMetrics.density
    private var spec = DemoSpec.SETTINGS
    private val halo = EdgeGlowView(context)
    private var screen: View? = null
    private var chip: View? = null
    private val lines = ArrayList<TextView>()
    private val pills = ArrayList<Pill>()
    private val bubbleBox = RectF()
    private val stageClip = Path()

    private val bubbleBg: Drawable = ContextCompat.getDrawable(context, R.drawable.bubble_bg)!!.mutate()
    private val glyph: Drawable = ContextCompat.getDrawable(context, R.drawable.ic_scan)!!.mutate()
    private val ghostGlyph: Drawable = ContextCompat.getDrawable(context, R.drawable.ic_scan)!!.mutate().apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })   // CSS filter: grayscale(1)
    }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var p = 0f            // loop time, ms
    private var granted = true
    private var paused = false
    private var ghost = 0f        // 0 = live, 1 = ghost (denied)
    private var pulse = -1f       // current pulse progress 0..1, < 0 = none
    private var flashT = -1f      // return-flash progress 0..1, < 0 = none
    private var ghostAnim: ValueAnimator? = null
    private var pulseAnim: ValueAnimator? = null
    private var flashAnim: ValueAnimator? = null

    private val loop = ValueAnimator.ofFloat(0f, LOOP).apply {
        duration = LOOP.toLong()
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { onLoop(it.animatedValue as Float) }
    }

    init {
        clipChildren = false
        setWillNotDraw(false)
    }

    fun configure(spec: DemoSpec) {
        this.spec = spec
        halo.recipe = spec.halo
        // permission-2 / settings-final .stagebox: linear-gradient(160deg, #A78BFA 14%, #67E8F9 12%)
        background = CssGradientDrawable(intArrayOf(withAlpha(0.14f, 0xA78BFA), withAlpha(0.12f, 0x67E8F9)), null, 160f, spec.stageCornerDp * dp)
        val scr = checkNotNull(findViewWithTag<View>(TAG_SCREEN)) { "CaptureDemoView needs a direct child tagged '$TAG_SCREEN'" }
        check(scr.parent === this) { "'$TAG_SCREEN' must be a direct child of CaptureDemoView" }
        screen = scr
        val corner = spec.screenCornerDp * dp
        scr.background = GlowDrawable(
            CssGradientDrawable.solid(0xFFF3F5F8.toInt(), corner),
            withAlpha(0.30f, 0x4C1D95), spec.screenShadowBlurDp * dp, spec.screenShadowDyDp * dp, SCREEN_SPREAD_DP * dp, corner,
        )
        scr.findViewWithTag<View>(TAG_AVATAR)?.background =
            CssGradientDrawable(intArrayOf(0xFFFFB27A.toInt(), 0xFFFF6F91.toInt()), null, 135f, -1f)
        lines.clear()
        collectLines(scr)
        val mc = spec.msgCornerDp * dp
        lines.forEach { tv ->
            if (tv.tag == TAG_LINE_ME) {
                tv.background = CssGradientDrawable.solid(0xFF2F6BFF.toInt(), mc)
                tv.setTextColor(Color.WHITE)
            } else {
                tv.background = CssGradientDrawable.solid(Color.WHITE, mc)
                tv.setTextColor(0xFF1D2330.toInt())
            }
        }
        chip = findViewWithTag<View>(TAG_CHIP)?.also {
            it.background = HaloStyle.chip(context)
            it.alpha = ghost
        }
        if (halo.parent == null) addView(halo, indexOfChild(scr) + 1, LayoutParams(0, 0))
        requestLayout()
    }

    /** true = live loop; false = ghost frame + peach pulse x2, loop stopped. */
    fun setGranted(granted: Boolean, animate: Boolean) {
        val changed = this.granted != granted
        this.granted = granted
        val target = if (granted) 0f else 1f
        ghostAnim?.cancel()
        if (animate && changed) {
            ghostAnim = ValueAnimator.ofFloat(ghost, target).apply {
                duration = GHOST_SWAP
                addUpdateListener { setGhost(it.animatedValue as Float) }
                start()
            }
        } else {
            setGhost(target)
        }
        if (!granted) {
            loop.cancel()
            halo.clear()
            playPulse()
        }
        updateLoop()
    }

    fun pause() {
        paused = true
        updateLoop()
    }

    fun resume() {
        paused = false
        updateLoop()
    }

    /** Return-from-system-settings flash: ring #A78BFA 60% -> 0, spread 0 -> 14dp, 400 ms ease-out (drawn outside the stage). */
    fun flash() {
        flashAnim?.cancel()
        flashAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FLASH
            interpolator = Easing.EASE_OUT
            addUpdateListener { flashT = it.animatedValue as Float; invalidate() }
            doOnEnd { flashT = -1f; invalidate() }
            start()
        }
    }

    private fun setGhost(v: Float) {
        ghost = v
        chip?.alpha = v
        halo.alpha = 1f - v
        invalidate()
    }

    private fun playPulse() {
        pulseAnim?.cancel()
        pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE
            repeatCount = 1                       // CSS animation-iteration-count 2
            interpolator = Easing.EASE_OUT
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            doOnEnd { pulse = -1f; invalidate() }
            start()
        }
    }

    private fun updateLoop() {
        val run = granted && !paused && isAttachedToWindow
        when {
            run && !loop.isStarted -> {
                p = 0f
                loop.start()
            }
            run && loop.isPaused -> loop.resume()
            !run && loop.isStarted && !loop.isPaused -> {
                loop.pause()
                halo.clear()
            }
        }
    }

    private fun onLoop(t: Float) {
        val prev = p
        p = t
        if (crossed(prev, t, HALO_ON)) halo.activate()
        if (crossed(prev, t, HALO_SETTLE)) halo.settle()
        if (crossed(prev, t, HALO_OFF)) halo.fadeOut(HALO_OUT)
        invalidate()
    }

    /** True when loop time moved past [at] between two frames (handles the wrap at 4800). */
    private fun crossed(prev: Float, now: Float, at: Float) =
        if (now >= prev) at > prev && at <= now else at > prev || at <= now

    private fun collectLines(v: View) {
        if (v is TextView && (v.tag == TAG_LINE || v.tag == TAG_LINE_ME)) lines += v
        if (v is ViewGroup) for (i in 0 until v.childCount) collectLines(v.getChildAt(i))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val scr = screen ?: return
        halo.measure(
            MeasureSpec.makeMeasureSpec(scr.measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(scr.measuredHeight, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val scr = screen ?: return
        halo.layout(scr.left, scr.top, scr.right, scr.bottom)
        val b = spec.bubbleDp * dp
        val bx = scr.right - spec.bubbleRightDp * dp
        val by = scr.top + spec.bubbleTopDp * dp
        bubbleBox.set(bx - b, by, bx, by + b)
        val r = spec.stageCornerDp * dp
        stageClip.reset()
        stageClip.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, Path.Direction.CW)
        buildPills()
    }

    /** One pill per text-layout line of every `line` TextView, outset by pillPadX/Y (CSS .ln::before). */
    private fun buildPills() {
        pills.clear()
        val px = spec.pillPadXDp * dp
        val py = spec.pillPadYDp * dp
        lines.forEachIndexed { i, tv ->
            val layout = tv.layout ?: return@forEachIndexed
            var ox = 0f
            var oy = 0f
            var c: View = tv
            while (c !== this) {
                ox += c.left
                oy += c.top
                c = c.parent as View
            }
            ox += tv.totalPaddingLeft
            oy += tv.totalPaddingTop
            for (l in 0 until layout.lineCount) {
                val rect = RectF(
                    ox + layout.getLineLeft(l) - px, oy + layout.getLineTop(l) - py,
                    ox + layout.getLineRight(l) + px, oy + layout.getLineBottom(l) + py,
                )
                // mini pills: LinearGradient #A78BFA 26% -> #F472B6 22%, glow #A78BFA 30% r8, full-round
                val painter = PillPainter(withAlpha(0.26f, 0xA78BFA), withAlpha(0.22f, 0xF472B6), withAlpha(0.30f, 0xA78BFA), PILL_GLOW_DP * dp, -1f)
                painter.setSize(rect.width().roundToInt(), rect.height().roundToInt())
                pills += Pill(i, rect, painter)
            }
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.clipPath(stageClip)                 // CSS .stagebox overflow:hidden
        super.dispatchDraw(canvas)
        canvas.restoreToCount(saved)
        drawFlash(canvas)
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        val scr = screen
        if (child === halo && scr != null) {
            val saved = canvas.save()
            canvas.clipRect(scr.left, scr.top, scr.right, scr.bottom)   // the CSS canvas lives inside .mini
            val r = super.drawChild(canvas, child, drawingTime)
            drawBubble(canvas)
            canvas.restoreToCount(saved)
            return r
        }
        val r = super.drawChild(canvas, child, drawingTime)
        if (child === scr) drawPills(canvas)
        return r
    }

    private fun drawPills(canvas: Canvas) {
        val live = 1f - ghost
        if (live <= 0f) return
        for (pl in pills) {
            val q = ((p - pl.index * PILL_STAGGER) % LOOP + LOOP) % LOOP
            val a: Float
            val s: Float
            when {
                q < PILL_IN_AT -> { a = 0f; s = 0.9f }
                q < PILL_IN_AT + PILL_FADE -> { val f = (q - PILL_IN_AT) / PILL_FADE; a = f; s = 0.9f + 0.1f * f }
                q < PILL_OUT_AT -> { a = 1f; s = 1f }
                q < PILL_OUT_AT + PILL_FADE -> { a = 1f - (q - PILL_OUT_AT) / PILL_FADE; s = 1f }
                else -> { a = 0f; s = 1f }
            }
            if (a * live <= 0f) continue
            val saved = canvas.save()
            canvas.translate(pl.rect.left, pl.rect.top)
            pl.painter.draw(canvas, a * live, s)
            canvas.restoreToCount(saved)
        }
    }

    private fun drawBubble(canvas: Canvas) {
        val live = 1f - ghost
        val cx = bubbleBox.centerX()
        val cy = bubbleBox.centerY()
        val br = bubbleBox.width() / 2f
        if (live > 0f) {
            val (ba, bs) = bubbleFrame(p)
            if (ba > 0f) drawBubbleAt(canvas, bubbleBg, glyph, live * ba, bs)
            val (da, ds) = dotFrame(p)
            if (da > 0f) {
                dot.color = withAlpha(0.32f * da * live, 0x786E96)   // rgba(120,110,150,.32)
                canvas.drawCircle(cx, cy, br * ds, dot)
            }
        }
        if (ghost > 0f) {
            // .gb: white disc + grayscale glyph, opacity .45
            val layer = canvas.saveLayerAlpha(bubbleBox, (GHOST_ALPHA * ghost * 255f).roundToInt())
            canvas.drawCircle(cx, cy, br, white)
            placeGlyph(ghostGlyph)
            ghostGlyph.draw(canvas)
            canvas.restoreToCount(layer)
        }
        if (pulse >= 0f) {
            // .gp: radial-gradient(circle, peach 0 @45%, peach 55% @62%, peach 0 @75%) on a 60dp disc,
            // CSS default extent farthest-corner = r·√2; opacity .9 -> 0, scale .8 -> 1.3
            val s = 0.8f + 0.5f * pulse
            val r = spec.pulseDp * dp / 2f * s
            pulsePaint.shader = RadialGradient(
                cx, cy, r * sqrt(2f),
                intArrayOf(withAlpha(0f, 0xFDBA74), withAlpha(0.55f, 0xFDBA74), withAlpha(0f, 0xFDBA74)),
                floatArrayOf(0.45f, 0.62f, 0.75f), Shader.TileMode.CLAMP,
            )
            pulsePaint.alpha = (0.9f * (1f - pulse) * 255f).roundToInt()
            canvas.drawCircle(cx, cy, r, pulsePaint)
        }
    }

    private fun drawBubbleAt(canvas: Canvas, bg: Drawable, g: Drawable, alpha: Float, scale: Float) {
        val saved = canvas.save()
        canvas.scale(scale, scale, bubbleBox.centerX(), bubbleBox.centerY())
        val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        bg.setBounds(bubbleBox.left.roundToInt(), bubbleBox.top.roundToInt(), bubbleBox.right.roundToInt(), bubbleBox.bottom.roundToInt())
        bg.alpha = a
        bg.draw(canvas)
        placeGlyph(g)
        g.alpha = a
        g.draw(canvas)
        canvas.restoreToCount(saved)
    }

    private fun placeGlyph(g: Drawable) {
        val half = spec.glyphDp * dp / 2f
        val cx = bubbleBox.centerX()
        val cy = bubbleBox.centerY()
        g.setBounds((cx - half).roundToInt(), (cy - half).roundToInt(), (cx + half).roundToInt(), (cy + half).roundToInt())
    }

    private fun drawFlash(canvas: Canvas) {
        if (flashT < 0f) return
        val spread = FLASH_SPREAD_DP * dp * flashT
        if (spread <= 0f) return
        flashPaint.strokeWidth = spread
        flashPaint.color = withAlpha(0.6f * (1f - flashT), 0xA78BFA)
        val h = spread / 2f
        val r = spec.stageCornerDp * dp + h
        canvas.drawRoundRect(-h, -h, width + h, height + h, r, r, flashPaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateLoop()
    }

    override fun onDetachedFromWindow() {
        loop.cancel()
        ghostAnim?.cancel()
        pulseAnim?.cancel()
        flashAnim?.cancel()
        halo.clear()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val TAG_SCREEN = "demoScreen"
        const val TAG_CHIP = "demoChip"
        const val TAG_AVATAR = "avatar"
        const val TAG_LINE = "line"
        const val TAG_LINE_ME = "line_me"

        // permission-2 / settings-final demo loop (ms; keyframe % x 4800)
        const val LOOP = 4800f
        const val HALO_ON = 1150f          // haloIn 350 from 1150
        const val HALO_SETTLE = 1900f      // haloSettle 400 from 1900
        const val HALO_OFF = 3800f         // sessionOut 288 from 3800
        const val HALO_OUT = 288L
        const val PILL_IN_AT = 1488f       // 31%
        const val PILL_OUT_AT = 3792f      // 79%
        const val PILL_FADE = 288f         // 6% = pills 288 each
        const val PILL_STAGGER = 72f
        const val GHOST_SWAP = 300L
        const val PULSE = 400L             // peachPulse 400 x2
        const val FLASH = 400L
        const val FLASH_SPREAD_DP = 14f
        const val GHOST_ALPHA = 0.45f
        const val PILL_GLOW_DP = 8f
        const val SCREEN_SPREAD_DP = -12f


        private fun seg(t: Float, from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)

        /** CSS @keyframes mb: (alpha, scale). 20% 960, 21% 1008 (press .9), 23% 1104, 27% 1296 (bubbleOut 192), 85% 4080, 91% 4368 (bubbleIn 288). */
        fun bubbleFrame(t: Float): Pair<Float, Float> = when {
            t < 960f -> 1f to 1f
            t < 1008f -> 1f to (1f - 0.1f * seg(t, 960f, 1008f))
            t < 1104f -> 1f to (0.9f + 0.1f * seg(t, 1008f, 1104f))
            t < 1296f -> seg(t, 1104f, 1296f).let { (1f - it) to (1f - 0.3f * it) }
            t < 4080f -> 0f to 0.7f
            t < 4368f -> seg(t, 4080f, 4368f).let { it to (0.7f + 0.3f * it) }
            else -> 1f to 1f
        }

        /** CSS @keyframes mt: (alpha, scale). 18% 864, 21% 1008, 26% 1248. */
        fun dotFrame(t: Float): Pair<Float, Float> = when {
            t < 864f -> 0f to 1.6f
            t < 1008f -> seg(t, 864f, 1008f).let { it to (1.6f - 0.8f * it) }
            t < 1248f -> (1f - seg(t, 1008f, 1248f)) to 0.8f
            else -> 0f to 0.8f
        }
    }
}
