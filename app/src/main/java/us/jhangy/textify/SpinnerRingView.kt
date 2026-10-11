package us.jhangy.textify

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Halo spinner (prism-tide note 7): 300° arc with a SweepGradient transparent -> lavender -> pink -> peach ->
 * aqua -> transparent, one turn per 900 ms. Used by the translate button, the 下載中… chip and Settings rows.
 */
class SpinnerRingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** Stroke width in px (default 3dp). */
    var strokePx: Float = 3f * resources.displayMetrics.density
        set(value) {
            field = value
            paint.strokeWidth = value
            rebuild()
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()
    private var angle = 0f
    private val spin: ValueAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = SPIN
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { angle = it.animatedValue as Float; invalidate() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    private fun rebuild() {
        if (width == 0 || height == 0) return
        val half = strokePx / 2f
        oval.set(half, half, width - half, height - half)
        paint.shader = SweepGradient(width / 2f, height / 2f, COLORS, STOPS)
    }

    override fun onDraw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.rotate(angle, width / 2f, height / 2f)
        canvas.drawArc(oval, 0f, ARC, false, paint)
        canvas.restoreToCount(saved)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncSpin()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isAttachedToWindow) syncSpin()      // also called from the View constructor, before `spin` exists
    }

    override fun onDetachedFromWindow() {
        spin.cancel()
        super.onDetachedFromWindow()
    }

    /** Turn only while visible on screen. */
    private fun syncSpin() {
        if (isShown) {
            if (!spin.isStarted) spin.start()
        } else {
            spin.cancel()
        }
    }

    companion object {
        private const val SPIN = 900L
        private const val ARC = 300f
        private val COLORS = intArrayOf(
            0x00A78BFA, 0xFFA78BFA.toInt(), 0xFFF472B6.toInt(), 0xFFFDBA74.toInt(), 0xFF67E8F9.toInt(), 0x0067E8F9,
        )
        private val STOPS = floatArrayOf(0f, 60f / 360f, 150f / 360f, 220f / 360f, 290f / 360f, 300f / 360f)
    }
}
