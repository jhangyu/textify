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
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.animation.doOnEnd
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One OCR line as an a2 "Halo" pill. The text is real, selectable and transparent, sized to
 * roughly cover the glyphs in the screenshot, so long-press gives system handles + copy menu.
 * Tap is detected with GestureDetector: a selectable TextView is focusable in touch mode, so the
 * first tap would only take focus and never reach an OnClickListener.
 */
@SuppressLint("ViewConstructor")
class PillView(context: Context, val line: OcrLine) : AppCompatTextView(context) {

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

    private val dp = resources.displayMetrics.density
    // a2 box: LinearGradient #A78BFA 20% -> #F472B6 16%, glow #A78BFA 28% r12, radius = h/2
    private val painter = PillPainter(withAlpha(0.20f, 0xA78BFA), withAlpha(0.16f, 0xF472B6), withAlpha(0.28f, 0xA78BFA), GLOW_R_DP * dp, -1f)
    private val overlay = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillPath = Path()
    private var overlayAlpha = 0f
    private var shineX = Float.NaN
    private var overlayAnim: ValueAnimator? = null
    private var shineAnim: ValueAnimator? = null
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
        text = line.text
        setTextColor(Color.TRANSPARENT)
        highlightColor = withAlpha(0.26f, 0x7C3AED)
        setTextIsSelectable(true)
        setSingleLine(true)
        maxLines = 1
        includeFontPadding = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        val px = (PAD_X_DP * dp).roundToInt()
        val py = (PAD_Y_DP * dp).roundToInt()
        setPadding(px, py, px, py)
        setAutoSizeTextTypeUniformWithConfiguration(4, max(5, line.box.height()), 1, TypedValue.COMPLEX_UNIT_PX)
        alpha = 0f
        scaleX = 0.9f
        scaleY = 0.9f
    }

    /** a2 boxIn: opacity 0 -> 1 and scale .9 -> 1 over 260 ms, cubic-bezier(.2,0,0,1), after [delayMs]. */
    fun playIn(delayMs: Long) {
        animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(delayMs).setDuration(BOX_IN).setInterpolator(EMPHASIZED)
    }

    /** a2 copy feedback: overlay flash 1 -> 0 (ease-out) and a white shine sweep, both over flash 380 ms. */
    fun playCopied() {
        animateOverlay(1f, 0L, EMPHASIZED)
        animateOverlay(0f, FLASH, EASE_OUT)
        val band = width * SHINE_BAND
        shineAnim?.cancel()
        shineAnim = ValueAnimator.ofFloat(-1.1f * band, width.toFloat()).apply {
            duration = FLASH
            interpolator = SHINE_CURVE
            addUpdateListener { shineX = it.animatedValue as Float; invalidate() }
            doOnEnd { shineX = Float.NaN; invalidate() }
            start()
        }
    }

    private fun press(on: Boolean) {
        val s = if (on) 1.03f else 1f
        animate().scaleX(s).scaleY(s).setStartDelay(0L).setDuration(BOX_IN).setInterpolator(EMPHASIZED)
        animateOverlay(if (on) 0.65f else 0f, PRESS_FADE, CSS_EASE)
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
        painter.setSize(w, h)
        val fw = w.toFloat()
        val fh = h.toFloat()
        val r = fh / 2f
        pillPath.reset()
        pillPath.addRoundRect(0f, 0f, fw, fh, r, r, Path.Direction.CW)
        overlay.shader = LinearGradient(0f, 0f, fw, 0f, withAlpha(0.28f, 0x7C3AED), withAlpha(0.26f, 0xEC4899), Shader.TileMode.CLAMP)
        val band = fw * SHINE_BAND
        shine.shader = LinearGradient(
            0f, 0f, band, 0f,
            intArrayOf(withAlpha(0f, 0xFFFFFF), withAlpha(0.9f, 0xFFFFFF), withAlpha(0f, 0xFFFFFF)), null, Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        val saved = canvas.save()
        canvas.translate(scrollX.toFloat(), scrollY.toFloat())   // keep the pill fixed while the text scrolls
        painter.draw(canvas, alpha = if (selecting) SELECTING else 1f, withGlow = !selecting)   // a2 .box.selecting: fill x.45, no glow
        if (overlayAlpha > 0f) {
            val layer = canvas.saveLayerAlpha(0f, 0f, w, h, (overlayAlpha.coerceIn(0f, 1f) * 255f).roundToInt())
            canvas.drawRoundRect(0f, 0f, w, h, r, r, overlay)
            canvas.restoreToCount(layer)
        }
        if (!shineX.isNaN()) {
            val clip = canvas.save()
            canvas.clipPath(pillPath)
            canvas.translate(shineX, 0f)
            canvas.drawRect(0f, 0f, w * SHINE_BAND, h, shine)
            canvas.restoreToCount(clip)
        }
        canvas.restoreToCount(saved)
        super.onDraw(canvas)   // transparent glyphs + selection highlight
    }

    override fun onDetachedFromWindow() {
        overlayAnim?.cancel()
        shineAnim?.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        const val PAD_X_DP = 7f
        const val PAD_Y_DP = 3f
        private const val GLOW_R_DP = 12f
        private const val BOX_IN = 260L
        private const val FLASH = 380L
        private const val PRESS_FADE = 140L
        private const val SHINE_BAND = 0.48f
        private const val SELECTING = 0.45f
        private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)      // a2 boxIn
        private val CSS_EASE = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)  // CSS "ease" (pressing overlay)
        private val EASE_OUT = PathInterpolator(0f, 0f, 0.58f, 1f)       // CSS "ease-out" (copied flash)
        private val SHINE_CURVE = PathInterpolator(0.4f, 0f, 0.2f, 1f)   // a2 shine
    }
}
