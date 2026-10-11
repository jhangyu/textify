package us.jhangy.textify

import android.animation.ArgbEvaluator
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Session-style language toggle pill (spec §8.6). XML children (by tag): TextViews `glyph` and `sub`. */
class LanguageToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var checked: Boolean = false
        private set

    private val dp = resources.displayMetrics.density
    private val corner = CORNER_DP * dp
    private val off = CssGradientDrawable.solid(withAlpha(0.07f, 0x7C3AED), corner)
    private val on = GlowDrawable(
        CssGradientDrawable(intArrayOf(0xFF7C3AED.toInt(), 0xFFF472B6.toInt()), null, 135f, corner),
        withAlpha(0.30f, 0xF472B6), 18f * dp, 0f, 0f, corner,
    )
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val argb = ArgbEvaluator()
    private var glyph: TextView? = null
    private var sub: TextView? = null
    private var onLevel = 0f
    private var flash = 0f
    private var fillAnim: ValueAnimator? = null
    private var rubberAnim: ObjectAnimator? = null
    private var flashAnim: ValueAnimator? = null

    init {
        setWillNotDraw(false)
        clipChildren = false
        isClickable = true
        isFocusable = true
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        glyph = findViewWithTag(TAG_GLYPH)
        sub = findViewWithTag(TAG_SUB)
        applyColors()
    }

    fun setChecked(value: Boolean, animate: Boolean) {
        checked = value
        val target = if (value) 1f else 0f
        fillAnim?.cancel()
        if (!animate) {
            onLevel = target
            applyColors()
        } else {
            fillAnim = ValueAnimator.ofFloat(onLevel, target).apply {
                duration = TOGGLE_FILL
                interpolator = Easing.EASE_OUT
                addUpdateListener { onLevel = it.animatedValue as Float; applyColors() }
                start()
            }
        }
    }

    /** Last-one deselect: rubber 1 -> .92 -> 1.04 -> 1 (360), flash ring #F472B6 0 -> 55% @35% spread 7dp -> 0 (380), haptic. */
    fun reject() {
        rubberAnim?.cancel()
        val kf = arrayOf(
            Keyframe.ofFloat(0f, 1f),
            Keyframe.ofFloat(0.30f, 0.92f).apply { interpolator = Easing.EASE_OUT },
            Keyframe.ofFloat(0.65f, 1.04f).apply { interpolator = Easing.EASE_OUT },
            Keyframe.ofFloat(1f, 1f).apply { interpolator = Easing.EASE_OUT },
        )
        rubberAnim = ObjectAnimator.ofPropertyValuesHolder(
            this,
            PropertyValuesHolder.ofKeyframe(View.SCALE_X, *kf),
            PropertyValuesHolder.ofKeyframe(View.SCALE_Y, *kf.map { it.clone() }.toTypedArray()),
        ).apply { duration = SHAKE; start() }
        flashAnim?.cancel()
        flashAnim = ValueAnimator.ofPropertyValuesHolder(
            PropertyValuesHolder.ofKeyframe(
                "f",
                Keyframe.ofFloat(0f, 0f),
                Keyframe.ofFloat(0.35f, 1f).apply { interpolator = Easing.EASE_OUT },
                Keyframe.ofFloat(1f, 0f).apply { interpolator = Easing.EASE_OUT },
            ),
        ).apply {
            duration = REJECT_FLASH
            addUpdateListener { flash = it.animatedValue as Float; invalidate() }
            start()
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun applyColors() {
        glyph?.setTextColor(argb.evaluate(onLevel, withAlpha(0.60f, 0x1E1B4B), Color.WHITE) as Int)
        sub?.setTextColor(argb.evaluate(onLevel, withAlpha(0.55f, 0x1E1B4B), Color.WHITE) as Int)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        off.setBounds(0, 0, w, h)
        on.setBounds(0, 0, w, h)
    }

    override fun onDraw(canvas: Canvas) {
        off.draw(canvas)
        if (onLevel > 0f) {
            on.alpha = (onLevel * 255f).roundToInt()
            on.draw(canvas)
        }
        if (flash > 0f) {
            val spread = FLASH_SPREAD_DP * dp * flash
            flashPaint.strokeWidth = spread
            flashPaint.color = withAlpha(0.55f * flash, 0xF472B6)
            val h = spread / 2f
            canvas.drawRoundRect(-h, -h, width + h, height + h, corner + h, corner + h, flashPaint)
        }
    }

    /** CSS .pl:active scale(.97) (no transition). */
    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        if (rubberAnim?.isRunning == true) return
        val s = if (pressed) 0.97f else 1f
        scaleX = s
        scaleY = s
    }

    override fun getAccessibilityClassName(): CharSequence = CheckBox::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isCheckable = true
        @Suppress("DEPRECATION")
        info.isChecked = checked
    }

    override fun onDetachedFromWindow() {
        fillAnim?.cancel()
        rubberAnim?.cancel()
        flashAnim?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val TAG_GLYPH = "glyph"
        const val TAG_SUB = "sub"
        const val CORNER_DP = 24f
        const val FLASH_SPREAD_DP = 7f
        const val TOGGLE_FILL = 240L
        const val SHAKE = 360L
        const val REJECT_FLASH = 380L
    }
}
