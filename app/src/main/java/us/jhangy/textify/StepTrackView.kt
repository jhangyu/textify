package us.jhangy.textify

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/** 2-node progress track 顯示泡泡 -> 擷取畫面 (spec §8.4). Height 24dp; gradient-node glows draw outside (parents: clipChildren=false). */
class StepTrackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class State { CURRENT, DENIED, GRANTED }

    private val dp = resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12.5f); typeface = Typeface.DEFAULT_BOLD; color = 0xFF6B5FA8.toInt()   // weight 600 -> bold
    }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12f); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRest = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(0.12f, 0x7C3AED) }
    private val trackFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val check: Drawable = ContextCompat.getDrawable(context, R.drawable.ic_check)!!.mutate()
    private val nodePx = NODE_DP * dp
    // CURRENT node 1: 135deg #A78BFA -> #F472B6, glow #F472B6 45% r12; GRANTED node 2: 135deg #F472B6 -> #FDBA74, glow #FDBA74 55% r12
    private val gradCurrent = GlowDrawable(CssGradientDrawable(intArrayOf(0xFFA78BFA.toInt(), 0xFFF472B6.toInt()), null, 135f, -1f), withAlpha(0.45f, 0xF472B6), 12f * dp, 0f, 0f, -1f)
    private val gradCapture = GlowDrawable(CssGradientDrawable(intArrayOf(0xFFF472B6.toInt(), 0xFFFDBA74.toInt()), null, 135f, -1f), withAlpha(0.55f, 0xFDBA74), 12f * dp, 0f, 0f, -1f)

    private var first: CharSequence = ""
    private var second: CharSequence = ""
    private var from = State.CURRENT
    private var state = State.CURRENT
    private var mixT = 1f            // 0 = draw `from`, 1 = draw `state`
    private var fill = 0f            // track fill fraction
    private var mixAnim: ValueAnimator? = null
    private var fillAnim: ValueAnimator? = null

    // x positions, computed in place()
    private var n1x = 0f
    private var l1x = 0f
    private var tx0 = 0f
    private var tx1 = 0f
    private var n2x = 0f
    private var l2x = 0f

    fun setLabels(first: CharSequence, second: CharSequence) {
        this.first = first
        this.second = second
        place()
        invalidate()
    }

    fun setState(state: State, animate: Boolean) {
        mixAnim?.cancel()
        fillAnim?.cancel()
        from = if (mixT >= 0.5f) this.state else from
        this.state = state
        val target = if (state == State.GRANTED) 1f else 0f
        if (!animate) {
            mixT = 1f
            fill = target
            invalidate()
            return
        }
        mixT = 0f
        mixAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = NODE_CHECK
            interpolator = Easing.CSS_EASE
            addUpdateListener { mixT = it.animatedValue as Float; invalidate() }
            start()
        }
        fillAnim = ValueAnimator.ofFloat(fill, target).apply {
            duration = TRACK_FILL
            interpolator = Easing.EMPHASIZED
            addUpdateListener { fill = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (nodePx + paddingTop + paddingBottom).roundToInt()
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(h, heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = place()

    /** [node 24][7][label][10][track][10][node 24][7][label] — CSS .track gap 10, .node gap 7. */
    private fun place() {
        n1x = paddingLeft.toFloat()
        l1x = n1x + nodePx + 7f * dp
        tx0 = l1x + label.measureText(first, 0, first.length) + 10f * dp
        val l2w = label.measureText(second, 0, second.length)
        l2x = width - paddingRight - l2w
        n2x = l2x - 7f * dp - nodePx
        tx1 = n2x - 10f * dp
        trackFill.shader = LinearGradient(tx0, 0f, tx1, 0f, intArrayOf(0xFFA78BFA.toInt(), 0xFFF472B6.toInt(), 0xFFFDBA74.toInt()), null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val cy = paddingTop + nodePx / 2f
        val lb = cy - (label.descent() + label.ascent()) / 2f
        canvas.drawText(first, 0, first.length, l1x, lb, label)
        canvas.drawText(second, 0, second.length, l2x, lb, label)
        // track: 6dp, r3, rest #7C3AED 12%, fill revealed by clip width (permission-2 note "ValueAnimator on clip width")
        val th = 3f * dp
        if (tx1 > tx0) {
            canvas.drawRoundRect(tx0, cy - th, tx1, cy + th, th, th, trackRest)
            if (fill > 0f) {
                val saved = canvas.save()
                canvas.clipRect(tx0, cy - th, tx0 + (tx1 - tx0) * fill, cy + th)
                canvas.drawRoundRect(tx0, cy - th, tx1, cy + th, th, th, trackFill)
                canvas.restoreToCount(saved)
            }
        }
        if (mixT < 1f) {
            drawNode(canvas, 1, from, n1x, cy, 1f - mixT)
            drawNode(canvas, 2, from, n2x, cy, 1f - mixT)
        }
        drawNode(canvas, 1, state, n1x, cy, mixT)
        drawNode(canvas, 2, state, n2x, cy, mixT)
    }

    private fun drawNode(canvas: Canvas, index: Int, s: State, x: Float, cy: Float, alpha: Float) {
        if (alpha <= 0f) return
        val a = (alpha * 255f).roundToInt()
        val l = x.roundToInt()
        val t = (cy - nodePx / 2f).roundToInt()
        val r = nodePx / 2f
        var textColor = 0xFF4C1D95.toInt()
        var showCheck = false
        when {
            index == 1 && s == State.CURRENT -> { gradCurrent.setBounds(l, t, l + nodePx.roundToInt(), t + nodePx.roundToInt()); gradCurrent.alpha = a; gradCurrent.draw(canvas); textColor = 0xFFFFFFFF.toInt() }
            index == 1 && s == State.DENIED -> { solid.color = withAlpha(0.30f, 0xFDBA74); solid.alpha = (0.30f * a).roundToInt(); canvas.drawCircle(x + r, cy, r, solid); textColor = 0xFF9A3412.toInt() }
            index == 1 && s == State.GRANTED -> { solid.color = withAlpha(0.16f, 0x7C3AED); solid.alpha = (0.16f * a).roundToInt(); canvas.drawCircle(x + r, cy, r, solid); showCheck = true }
            index == 2 && s == State.GRANTED -> { gradCapture.setBounds(l, t, l + nodePx.roundToInt(), t + nodePx.roundToInt()); gradCapture.alpha = a; gradCapture.draw(canvas); textColor = 0xFFFFFFFF.toInt() }
            else -> { solid.color = withAlpha(0.12f, 0x7C3AED); solid.alpha = (0.12f * a).roundToInt(); canvas.drawCircle(x + r, cy, r, solid) }
        }
        if (showCheck) {
            val h = CHECK_DP * dp / 2f
            check.setBounds((x + r - h).roundToInt(), (cy - h).roundToInt(), (x + r + h).roundToInt(), (cy + h).roundToInt())
            check.alpha = a
            check.draw(canvas)
        } else {
            num.color = textColor
            num.alpha = (((textColor ushr 24) / 255f) * a).roundToInt()
            canvas.drawText(if (index == 1) "1" else "2", x + r, cy - (num.descent() + num.ascent()) / 2f, num)
        }
    }

    override fun onDetachedFromWindow() {
        mixAnim?.cancel()
        fillAnim?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val NODE_DP = 24f
        const val CHECK_DP = 14f
        const val TRACK_FILL = 400L
        const val NODE_CHECK = 260L
    }
}
