package us.jhangy.textify

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Bundle
import android.text.Layout
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.animation.doOnEnd
import androidx.core.widget.TextViewCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One OCR paragraph as an a2 "Halo" box with two modes (translation spec §1, §3).
 * Original: the paragraph's text is real, selectable and transparent, laid out line by line on the screenshot's
 * OCR line boxes, so long-press gives system handles + copy menu on the glyphs.
 * Translated: a solid card in the screenshot's edge colour hides the glyphs and shows the translation as real,
 * selectable text, fitted down to 10sp and truncated with a "⋯" mark below that.
 * Vertical CJK paragraph + CJK target: the translation is drawn as columns right to left (VerticalText.kt); the
 * TextView keeps the same text transparent so long-press select / copy still work (handles are not on the columns).
 * Tap is detected with GestureDetector: a selectable TextView is focusable in touch mode, so the first tap would
 * only take focus and never reach an OnClickListener.
 */
@SuppressLint("ViewConstructor")
class PillView(context: Context, val block: OcrBlock) : AppCompatTextView(context) {

    /** How the translated card appears / disappears. */
    enum class Reveal { NONE, TOP_DOWN, LEFT_RIGHT }

    /** Single tap. dismissOnly = a text selection was alive when the finger went down. */
    var onTap: ((pill: PillView, dismissOnly: Boolean) -> Unit)? = null

    /** Supplied by the activity: is any selection ActionMode alive right now? */
    var selectionActive: () -> Boolean = { false }

    /** Set by the activity while this pill's selection ActionMode is alive (a2 .box.selecting). */
    var selecting = false
        set(value) {
            field = value
            invalidate()
        }

    /** Translation shown (or still animating out); null once the original layout is back. */
    var translationText: String? = null
        private set

    /** True from showTranslation until showOriginal is called. */
    var showsTranslation = false
        private set

    /** The translation did not fit at 10sp: text cut to whole lines and the "⋯" mark drawn (spec §3). */
    var truncated = false
        private set

    /** Paragraph is Translating: light shimmer over the original pill (prism-tide note 3). */
    var shimmering = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startShimmer() else stopShimmer()
        }

    /** Bottom panel open for this paragraph: the card glow pulses (prism-tide note 9). */
    var focused = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startFocus() else stopFocus()
        }

    private val dp = resources.displayMetrics.density
    private var painter: PillPainter? = null
    private var painterCorner = -1f
    private val card = CardPainter(resources)
    private var corner = 0f
    private var originalTextPx = 0f
    private var markLinePx = 0f
    private var vertical: VerticalLayout? = null          // non-null = translation drawn as vertical columns
    private val vPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlay = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val prismPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillPath = Path()
    private var overlayAlpha = 0f
    private var shineX = Float.NaN
    private var shimmerX = Float.NaN
    private var prismX = Float.NaN
    private var reveal = 0f                       // 0 = original look, 1 = card fully shown
    private var revealDir = Reveal.TOP_DOWN
    private var glowAlpha = GLOW_REST
    private var overlayAnim: ValueAnimator? = null
    private var shineAnim: ValueAnimator? = null
    private var shimmerAnim: ValueAnimator? = null
    private var prismAnim: ValueAnimator? = null
    private var revealAnim: ValueAnimator? = null
    private var focusAnim: ValueAnimator? = null
    private var dismissOnly = false

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onTap?.invoke(this@PillView, dismissOnly)
            return false
        }

        override fun onLongPress(e: MotionEvent) = press(false)

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            press(false)
            return false
        }
    })

    init {
        text = block.text
        setTextColor(Color.TRANSPARENT)
        highlightColor = withAlpha(0.26f, 0x7C3AED)
        setTextIsSelectable(true)
        includeFontPadding = false
        gravity = Gravity.START or Gravity.TOP
        val px = (PAD_X_DP * dp).roundToInt()
        val py = (PAD_Y_DP * dp).roundToInt()
        setPadding(px, py, px, py)
        applyOriginalLayout()
        alpha = 0f
        scaleX = 0.9f
        scaleY = 0.9f
    }

    /** Spec §1: keep the OCR line breaks; size and pitch from the OCR line boxes; squeeze glyphs to their widths. */
    private fun applyOriginalLayout() {
        val boxes = block.lines.map { it.toBox() }
        val m = lineMetrics(boxes, block.vertical)
        originalTextPx = m.textPx
        setHorizontallyScrolling(true)                    // never re-wrap: one TextView line per OCR line
        maxLines = Int.MAX_VALUE
        setLineSpacing(0f, 1f)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, m.textPx)
        TextViewCompat.setLineHeight(this, m.pitchPx.roundToInt())
        textScaleX = 1f
        val measured = block.text.split('\n').sumOf { paint.measureText(it).toDouble() }
        val target = boxes.sumOf { it.width }.toDouble()
        textScaleX = if (measured > 0.0) (target / measured).toFloat().coerceIn(0.5f, 2f) else 1f
        scrollTo(0, 0)
    }

    /** Spec §3: wrap inside the box; largest size from the original size (≥ 10sp) down to 10sp; truncate below that. */
    private fun applyTranslatedLayout(text: String) {
        val lp = layoutParams
        val innerH = max(1, lp.height - paddingTop - paddingBottom)
        val minPx = minPx()
        // measure with the TextView's own layout so fallback-font line heights match what is drawn (one path, API 26+)
        setHorizontallyScrolling(false)
        textScaleX = 1f
        setLineSpacing(0f, TR_SPACING)
        maxLines = Int.MAX_VALUE
        fun layoutAt(px: Float): Layout {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
            measure(
                View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            return layout
        }
        val fit = fitTextSize(max(originalTextPx, minPx), minPx, 1f, innerH) { layoutAt(it).height }
        truncated = fit.truncated
        markLinePx = 0f
        if (truncated) {
            val at10 = layoutAt(minPx)
            val line = (at10.getLineBottom(0) - at10.getLineTop(0)).toFloat()
            maxLines = max(1, (innerH / line).toInt())
            markLinePx = line + PAD_Y_DP * dp
        }
        setTextSize(TypedValue.COMPLEX_UNIT_PX, fit.textPx)
        ellipsize = null                                 // custom "⋯" mark instead of the system ellipsis (final pick)
        scrollTo(0, 0)
    }

    private fun minPx() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, MIN_SP, resources.displayMetrics)

    /** Vertical translation: columns fitted into the box (same size range as above), "︙" ends the last column when cut. */
    private fun applyVerticalLayout(text: String) {
        val lp = layoutParams
        val minPx = minPx()
        val v = layoutVertical(
            text, max(1, lp.width - paddingLeft - paddingRight), max(1, lp.height - paddingTop - paddingBottom),
            max(originalTextPx, minPx), minPx,
        )
        vertical = v
        truncated = v.truncated
        markLinePx = 0f                                  // the mark is in the columns, not the horizontal "⋯" strip
        setTextColor(Color.TRANSPARENT)                  // TextView text stays for selection; columns are drawn in onDraw
    }

    /** One glyph per em, top to bottom; columns right to left from the inner right edge. */
    private fun drawColumns(canvas: Canvas, v: VerticalLayout) {
        vPaint.set(paint)
        vPaint.color = card.textColor
        vPaint.textSize = v.textPx
        vPaint.textScaleX = 1f
        vPaint.textAlign = Paint.Align.CENTER
        val fm = vPaint.fontMetrics
        val dy = v.textPx / 2f - (fm.ascent + fm.descent) / 2f
        var x = width - paddingRight - v.textPx / 2f
        for (col in v.columns) {
            var y = paddingTop + dy
            var i = 0
            while (i < col.length) {
                val n = Character.charCount(col.codePointAt(i))
                canvas.drawText(col, i, i + n, x, y, vPaint)
                y += v.textPx
                i += n
            }
            x -= v.textPx * VCOL_PITCH
        }
    }

    /**
     * Shows [text] on a solid card of colour [bg] (spec §3). [reveal]: NONE = at once, TOP_DOWN = tide flip,
     * LEFT_RIGHT = arrive flip + prism sweep. Starts after [delayMs]. [vertical] = draw as vertical columns.
     */
    fun showTranslation(text: String, bg: Int, reveal: Reveal, delayMs: Long, vertical: Boolean) {
        translationText = text
        showsTranslation = true
        card.color = bg
        card.textColor = textColorFor(bg)
        setText(text)
        setTextColor(card.textColor)
        applyTranslatedLayout(text)
        this.vertical = null
        if (vertical) applyVerticalLayout(text)
        animateReveal(1f, reveal, delayMs, null)
        if (reveal == Reveal.LEFT_RIGHT) playPrism(delayMs)
    }

    /** Back to the transparent original (spec §3 toggle): the card clips away first, then the original layout returns. */
    fun showOriginal(reveal: Reveal, delayMs: Long) {
        if (!showsTranslation) return
        showsTranslation = false
        animateReveal(0f, reveal, delayMs) {
            translationText = null
            truncated = false
            markLinePx = 0f
            vertical = null
            setText(block.text)
            setTextColor(Color.TRANSPARENT)
            applyOriginalLayout()
        }
    }

    /** a2 boxIn: opacity 0 -> 1 and scale .9 -> 1 over 260 ms, cubic-bezier(.2,0,0,1), after [delayMs]. */
    fun playIn(delayMs: Long) {
        animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(delayMs).setDuration(BOX_IN).setInterpolator(Easing.EMPHASIZED)
    }

    /** a2 copy feedback: overlay flash 1 -> 0 (ease-out) and a white shine sweep, both over flash 380 ms. */
    fun playCopied() {
        animateOverlay(1f, 0L, Easing.EMPHASIZED)
        animateOverlay(0f, FLASH, Easing.EASE_OUT)
        val band = width * SHINE_BAND
        shineAnim?.cancel()
        shineAnim = ValueAnimator.ofFloat(-1.1f * band, width.toFloat()).apply {
            duration = FLASH
            interpolator = Easing.FAST_OUT_SLOW_IN
            addUpdateListener { shineX = it.animatedValue as Float; invalidate() }
            doOnEnd { shineX = Float.NaN; invalidate() }
            start()
        }
    }

    /** flip 240 (emphasized) of the clip fraction to [to]; a newer call replaces a running one without its end action. */
    private fun animateReveal(to: Float, how: Reveal, delayMs: Long, onEnd: (() -> Unit)?) {
        revealAnim?.let {
            it.removeAllListeners()
            it.cancel()
        }
        revealAnim = null
        if (how == Reveal.NONE) {
            reveal = to
            invalidate()
            onEnd?.invoke()
            return
        }
        revealDir = how
        revealAnim = ValueAnimator.ofFloat(reveal, to).apply {
            startDelay = delayMs
            duration = FLIP
            interpolator = Easing.EMPHASIZED
            addUpdateListener { reveal = it.animatedValue as Float; invalidate() }
            if (onEnd != null) doOnEnd { onEnd() }
            start()
        }
    }

    /** prism-tide arrive 320: diagonal-ish prism band from -110 % of the band to 160 % of the box. */
    private fun playPrism(delayMs: Long) {
        prismAnim?.cancel()
        prismAnim = ValueAnimator.ofFloat(-1.1f * BAND, 1.6f).apply {
            startDelay = delayMs
            duration = ARRIVE
            interpolator = Easing.FAST_OUT_SLOW_IN
            addUpdateListener { prismX = (it.animatedValue as Float) * width; invalidate() }
            doOnEnd { prismX = Float.NaN; invalidate() }
            start()
        }
    }

    /** prism-tide shimmer 1200 linear infinite: band from -110 % to 160 % of its own width. */
    private fun startShimmer() {
        shimmerAnim?.cancel()
        shimmerAnim = ValueAnimator.ofFloat(-1.1f, 1.6f).apply {
            duration = SHIMMER
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { shimmerX = (it.animatedValue as Float) * width * BAND; invalidate() }
            start()
        }
    }

    private fun stopShimmer() {
        shimmerAnim?.cancel()
        shimmerAnim = null
        shimmerX = Float.NaN
        invalidate()
    }

    /** prism-tide focusGlow: 1200 ms ease-in-out, alternate, infinite. */
    private fun startFocus() {
        focusAnim?.cancel()
        focusAnim = ValueAnimator.ofFloat(GLOW_REST, 1f).apply {
            duration = FOCUS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = Easing.EASE_IN_OUT
            addUpdateListener { glowAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopFocus() {
        focusAnim?.cancel()
        focusAnim = null
        glowAlpha = GLOW_REST
        invalidate()
    }

    private fun press(on: Boolean) {
        val s = if (on) 1.03f else 1f
        animate().scaleX(s).scaleY(s).setStartDelay(0L).setDuration(BOX_IN).setInterpolator(Easing.EMPHASIZED)
        animateOverlay(if (on) 0.65f else 0f, PRESS_FADE, Easing.CSS_EASE)
    }

    private fun animateOverlay(to: Float, ms: Long, curve: TimeInterpolator) {
        overlayAnim?.cancel()
        if (ms == 0L) {
            overlayAlpha = to
            invalidate()
            return
        }
        overlayAnim = ValueAnimator.ofFloat(overlayAlpha, to).apply {
            duration = ms
            interpolator = curve
            addUpdateListener { overlayAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility") // accessibility clicks are handled in performAccessibilityAction
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dismissOnly = selectionActive()
                if (!dismissOnly) press(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> press(false)
        }
        gestures.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_CLICK) {
            onTap?.invoke(this, false)
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        val fw = w.toFloat()
        val fh = h.toFloat()
        corner = min(CORNER_DP * dp, fh / 2f)                // prism-tide: radius = min(12dp, h/2)
        // a2 box: LinearGradient #A78BFA 20% -> #F472B6 16%, glow #A78BFA 28% r12
        // PillPainter bakes the corner in, so it is rebuilt only when the corner changes (h/2 < 12dp), not on every size change
        if (painter == null || painterCorner != corner) {
            painter = PillPainter(withAlpha(0.20f, 0xA78BFA), withAlpha(0.16f, 0xF472B6), withAlpha(0.28f, 0xA78BFA), GLOW_R_DP * dp, corner)
            painterCorner = corner
        }
        painter?.setSize(w, h)
        card.setSize(w, h, corner)
        pillPath.reset()
        pillPath.addRoundRect(0f, 0f, fw, fh, corner, corner, Path.Direction.CW)
        overlay.shader = LinearGradient(0f, 0f, fw, 0f, withAlpha(0.28f, 0x7C3AED), withAlpha(0.26f, 0xEC4899), Shader.TileMode.CLAMP)
        shine.shader = LinearGradient(
            0f, 0f, fw * SHINE_BAND, 0f,
            intArrayOf(withAlpha(0f, 0xFFFFFF), withAlpha(0.9f, 0xFFFFFF), withAlpha(0f, 0xFFFFFF)), null, Shader.TileMode.CLAMP,
        )
        val band = fw * BAND
        shimmerPaint.shader = LinearGradient(
            0f, 0f, band, 0f,
            intArrayOf(withAlpha(0f, 0xFFFFFF), withAlpha(0.7f, 0xFFFFFF), withAlpha(0.22f, 0xF472B6), withAlpha(0f, 0xFFFFFF)),
            floatArrayOf(0f, 0.45f, 0.6f, 1f), Shader.TileMode.CLAMP,
        )
        prismPaint.shader = LinearGradient(
            0f, 0f, band, 0f,
            intArrayOf(withAlpha(0f, 0xFFFFFF), withAlpha(0.7f, 0xFFFFFF), withAlpha(0.35f, 0xF472B6), withAlpha(0.3f, 0x67E8F9), withAlpha(0f, 0xFFFFFF)),
            floatArrayOf(0f, 0.35f, 0.55f, 0.7f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val saved = canvas.save()
        canvas.translate(scrollX.toFloat(), scrollY.toFloat())   // keep the box fixed while the text scrolls
        if (reveal < 1f) {
            painter?.draw(canvas, alpha = if (selecting) SELECTING else 1f, withGlow = !selecting)   // a2 .box.selecting: fill x.45, no glow
            if (!shimmerX.isNaN()) sweep(canvas, shimmerPaint, shimmerX, w * BAND)
        }
        if (reveal > 0f) {
            val clip = canvas.save()
            clipReveal(canvas, w, h, GLOW_CLIP_DP * dp)
            card.draw(canvas, glowAlpha)
            canvas.restoreToCount(clip)
        }
        if (!prismX.isNaN()) sweep(canvas, prismPaint, prismX, w * BAND)
        if (overlayAlpha > 0f) {
            val layer = canvas.saveLayerAlpha(0f, 0f, w, h, (overlayAlpha.coerceIn(0f, 1f) * 255f).roundToInt())
            canvas.drawRoundRect(0f, 0f, w, h, corner, corner, overlay)
            canvas.restoreToCount(layer)
        }
        if (!shineX.isNaN()) sweep(canvas, shine, shineX, w * SHINE_BAND)
        canvas.restoreToCount(saved)
        when {
            translationText == null -> super.onDraw(canvas)                   // glyphs + selection highlight
            reveal > 0f -> {                                                  // translated text only where the card is
                val clip = canvas.save()
                canvas.translate(scrollX.toFloat(), scrollY.toFloat())
                clipReveal(canvas, w, h, 0f)
                canvas.translate(-scrollX.toFloat(), -scrollY.toFloat())
                super.onDraw(canvas)
                canvas.translate(scrollX.toFloat(), scrollY.toFloat())
                if (markLinePx > 0f) card.drawMark(canvas, markLinePx)       // over the text, under the same clip
                vertical?.let { drawColumns(canvas, it) }
                canvas.restoreToCount(clip)
            }
        }
    }

    /** Clips to the revealed part of the card ([pad] lets the glow outside the box through); no clip at 1. */
    private fun clipReveal(canvas: Canvas, w: Float, h: Float, pad: Float) {
        if (reveal >= 1f) return
        if (revealDir == Reveal.LEFT_RIGHT) canvas.clipRect(-pad, -pad, w * reveal, h + pad)
        else canvas.clipRect(-pad, -pad, w + pad, h * reveal)
    }

    /** A horizontal band of [paint] (shader spans 0..[band]) at [x], clipped to the box outline. */
    private fun sweep(canvas: Canvas, paint: Paint, x: Float, band: Float) {
        val clip = canvas.save()
        canvas.clipPath(pillPath)
        canvas.translate(x, 0f)
        canvas.drawRect(0f, 0f, band, height.toFloat(), paint)
        canvas.restoreToCount(clip)
    }

    override fun onDetachedFromWindow() {
        overlayAnim?.cancel()
        shineAnim?.cancel()
        shimmerAnim?.cancel()
        prismAnim?.cancel()
        revealAnim?.cancel()
        focusAnim?.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        const val PAD_X_DP = 7f
        const val PAD_Y_DP = 3f
        private const val CORNER_DP = 12f
        private const val GLOW_R_DP = 12f
        private const val GLOW_CLIP_DP = 32f       // > card glow pad (blur 14dp + dy 4dp)
        private const val MIN_SP = 10f             // spec §3 minimum translation size
        private const val TR_SPACING = 1.1f        // ≈ mockup line-height 1.28 over the default font line height
        private const val BAND = 0.7f              // shimmer / prism band = 70 % of the box
        private const val GLOW_REST = 0.6f         // × 70 % lavender = 42 %
        private const val BOX_IN = 260L
        private const val FLASH = 380L
        private const val FLIP = 240L
        private const val ARRIVE = 320L
        private const val SHIMMER = 1200L
        private const val FOCUS = 1200L
        private const val PRESS_FADE = 140L
        private const val SHINE_BAND = 0.48f
        private const val SELECTING = 0.45f
    }
}
