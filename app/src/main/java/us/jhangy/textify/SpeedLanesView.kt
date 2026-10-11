package us.jhangy.textify

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.roundToInt

/** Parallel per-model lanes (spec §8.7): one row per selected script; bar width = illustrative finish time. */
class SpeedLanesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val labels = arrayOf<CharSequence>("", "", "")
    private val show = FloatArray(3)            // 0..1 presence per row (zh, ja, ko)
    private var widthFrac = WIDTHS[0]
    private var anim: ValueAnimator? = null
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        typeface = Typeface.DEFAULT_BOLD
        color = 0xFF6B5FA8.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(0.08f, 0x7C3AED) }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setLabels(zh: CharSequence, ja: CharSequence, ko: CharSequence) {
        labels[0] = zh
        labels[1] = ja
        labels[2] = ko
        invalidate()
    }

    fun setScripts(scripts: Set<Script>, animate: Boolean) {
        val target = FloatArray(3) { if (ORDER[it] in scripts) 1f else 0f }
        val targetW = WIDTHS[scripts.size.coerceIn(1, 3) - 1]
        anim?.cancel()
        if (!animate) {
            target.copyInto(show)
            widthFrac = targetW
            requestLayout()
            invalidate()
            return
        }
        val s0 = show.copyOf()
        val w0 = widthFrac
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = LANE_GROW
            interpolator = LANE_CURVE
            addUpdateListener {
                val f = it.animatedValue as Float
                for (i in 0 until 3) show[i] = s0[i] + (target[i] - s0[i]) * f
                widthFrac = w0 + (targetW - w0) * f
                requestLayout()
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = show.sum() * (ROW_DP + GAP_DP) * dp + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(h.roundToInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        var y = paddingTop.toFloat()
        val left = paddingLeft.toFloat()
        val trackL = left + (LABEL_W_DP + LABEL_GAP_DP) * dp
        val trackR = (width - paddingRight).toFloat()
        val th = TRACK_H_DP * dp
        val tr = th / 2f
        for (i in 0 until 3) {
            val s = show[i]
            if (s <= 0f) continue
            val rowH = ROW_DP * dp * s
            val cy = y + rowH / 2f
            val a = (s * 255f).roundToInt()
            val saved = canvas.save()
            canvas.clipRect(left, y, trackR, y + rowH)       // .lane overflow:hidden while the height animates
            label.alpha = a
            canvas.drawText(labels[i], 0, labels[i].length, left + LABEL_W_DP * dp / 2f, cy - (label.descent() + label.ascent()) / 2f, label)
            track.alpha = (0.08f * a).roundToInt()
            canvas.drawRoundRect(trackL, cy - th / 2f, trackR, cy + th / 2f, tr, tr, track)
            val barR = trackL + (trackR - trackL) * widthFrac
            bar.shader = LinearGradient(trackL, 0f, barR, 0f, COLORS[i][0], COLORS[i][1], Shader.TileMode.CLAMP)
            bar.alpha = a
            canvas.drawRoundRect(trackL, cy - th / 2f, barR, cy + th / 2f, tr, tr, bar)
            canvas.restoreToCount(saved)
            y += rowH + GAP_DP * dp * s
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        val ORDER = arrayOf(Script.CHINESE, Script.JAPANESE, Script.KOREAN)
        val WIDTHS = floatArrayOf(0.58f, 0.64f, 0.70f)          // 1 / 2 / 3 parallel models (illustrative, not measured)
        val COLORS = arrayOf(
            intArrayOf(0xFFA78BFA.toInt(), 0xFF818CF8.toInt()),   // zh
            intArrayOf(0xFFF472B6.toInt(), 0xFFFDBA74.toInt()),   // ja
            intArrayOf(0xFF67E8F9.toInt(), 0xFF818CF8.toInt()),   // ko
        )
        const val ROW_DP = 16f
        const val GAP_DP = 5f
        const val LABEL_W_DP = 18f
        const val LABEL_GAP_DP = 8f
        const val TRACK_H_DP = 12f
        const val LANE_GROW = 300L
        val LANE_CURVE = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    }
}
