package us.jhangy.textify

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Mini frozen screenshot of the language card (spec §8.5, settings-final .pv): rows light up as Halo pills
 * for the selected scripts. XML children (by tag): `previewShot` (direct child) with `avatar` and rows
 * `zh`/`en`/`ja`/`ko`, each holding `text` and `tag` TextViews.
 */
class LanguagePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private class Row(val view: ViewGroup, val text: View?, val tag: View?, val bg: RowDrawable) {
        var lit = false
        var anim: ValueAnimator? = null
    }

    /** Row fill (r12) + its Halo pill outset 6dp x 3dp (CSS .pln .pill inset -3px -6px), drawn under the row's children. */
    private class RowDrawable(color: Int, private val corner: Float, private val padX: Float, private val padY: Float, private val painter: PillPainter) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        var pillAlpha = 0f
        var pillScale = 0.96f

        override fun onBoundsChange(bounds: Rect) {
            painter.setSize((bounds.width() + 2f * padX).roundToInt(), (bounds.height() + 2f * padY).roundToInt())
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawRoundRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), corner, corner, paint)
            if (pillAlpha <= 0f) return
            val saved = canvas.save()
            canvas.translate(b.left - padX, b.top - padY)
            painter.draw(canvas, pillAlpha, pillScale)
            canvas.restoreToCount(saved)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private val dp = resources.displayMetrics.density
    private val halo = EdgeGlowView(context).apply { recipe = HaloRecipe.PREVIEW_EDGE }
    private var shot: ViewGroup? = null
    private val rows = LinkedHashMap<String, Row>()
    private val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(0.05f, 0x1E1B4B) }   // .pv .shot::after

    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        val s = checkNotNull(findViewWithTag<ViewGroup>(TAG_SHOT)) { "LanguagePreviewView needs a direct child tagged '$TAG_SHOT'" }
        check(s.parent === this) { "'$TAG_SHOT' must be a direct child of LanguagePreviewView" }
        shot = s
        s.clipChildren = false
        s.clipToPadding = false
        s.background = CssGradientDrawable.solid(Color.WHITE, SHOT_CORNER_DP * dp)
        s.findViewWithTag<View>(TAG_AVATAR)?.background =
            CssGradientDrawable(intArrayOf(0xFFFFB27A.toInt(), 0xFFFF6F91.toInt()), null, 135f, -1f)
        for (key in KEYS) {
            val v = checkNotNull(s.findViewWithTag<ViewGroup>(key)) { "LanguagePreviewView: row '$key' missing" }
            v.clipChildren = false
            v.clipToPadding = false
            // preview pill: #A78BFA 20% -> #F472B6 16%, glow #A78BFA 28% r12, radius 12
            val painter = PillPainter(withAlpha(0.20f, 0xA78BFA), withAlpha(0.16f, 0xF472B6), withAlpha(0.28f, 0xA78BFA), 12f * dp, ROW_CORNER_DP * dp)
            val color = if (key in RIGHT) 0xFFE8EEFC.toInt() else 0xFFF1F3F7.toInt()
            val bg = RowDrawable(color, ROW_CORNER_DP * dp, PILL_OUT_X_DP * dp, PILL_OUT_Y_DP * dp, painter)
            v.background = bg
            val tag = v.findViewWithTag<View>(TAG_TAG)?.apply {
                // .pln .tag: white r8, color #7C3AED, box-shadow 0 1px 4px #4C1D95 12%
                background = GlowDrawable(CssGradientDrawable.solid(Color.WHITE, 8f * dp), withAlpha(0.12f, 0x4C1D95), 4f * dp, 1f * dp, 0f, 8f * dp)
                (this as? android.widget.TextView)?.setTextColor(0xFF7C3AED.toInt())
                alpha = 0f
            }
            val text = v.findViewWithTag<View>(TAG_TEXT)?.apply { alpha = UNLIT_TEXT }
            rows[key] = Row(v, text, tag, bg)
        }
        addView(halo, 0, LayoutParams(0, 0))
    }

    fun setScripts(scripts: Set<Script>, animate: Boolean) {
        // English lights for ANY selection: every model also reads Latin (settings-final header)
        val want = mapOf(
            "zh" to (Script.CHINESE in scripts),
            "en" to scripts.isNotEmpty(),
            "ja" to (Script.JAPANESE in scripts),
            "ko" to (Script.KOREAN in scripts),
        )
        rows.forEach { (k, r) -> if (want[k] != true && r.lit) light(r, false, 0L, animate) }
        val turningOn = rows.filter { (k, r) -> want[k] == true && !r.lit }.values.toList()
        val n = turningOn.size
        turningOn.forEachIndexed { i, r ->
            val delay = if (n > 1) (i * BOX_STAGGER / (n - 1)).roundToLong() else 0L
            light(r, true, delay, animate)
        }
    }

    private fun light(r: Row, on: Boolean, delay: Long, animate: Boolean) {
        r.lit = on
        r.anim?.cancel()
        val toA = if (on) 1f else 0f
        val toS = if (on) 1f else 0.96f
        val toText = if (on) 1f else UNLIT_TEXT
        if (!animate) {
            r.bg.pillAlpha = toA
            r.bg.pillScale = toS
            r.bg.invalidateSelf()
            r.tag?.alpha = toA
            r.text?.alpha = toText
            return
        }
        val a0 = r.bg.pillAlpha
        val s0 = r.bg.pillScale
        r.anim = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = delay
            duration = if (on) PILL_IN else PILL_OUT
            interpolator = if (on) Easing.EASE_OUT else Easing.EASE_IN
            addUpdateListener {
                val f = it.animatedValue as Float
                r.bg.pillAlpha = a0 + (toA - a0) * f
                r.bg.pillScale = s0 + (toS - s0) * f
                r.bg.invalidateSelf()
            }
            start()
        }
        r.tag?.animate()?.alpha(toA)?.setStartDelay(delay)?.setDuration(TEXT_FADE)?.setInterpolator(Easing.CSS_EASE)
        r.text?.animate()?.alpha(toText)?.setStartDelay(delay)?.setDuration(TEXT_FADE)?.setInterpolator(Easing.CSS_EASE)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        halo.measure(
            MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        halo.layout(0, 0, width, height)                   // .edge inset 0 of .pv (the view's padding is the 10dp band)
        rows.values.forEach { r ->
            val t = r.tag ?: return@forEach
            // .pln .tag: right -6px, top -9px of the row
            val rx = r.view.width + TAG_OUT_X_DP * dp
            val ty = -TAG_OUT_Y_DP * dp
            t.layout((rx - t.measuredWidth).roundToInt(), ty.roundToInt(), rx.roundToInt(), (ty + t.measuredHeight).roundToInt())
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val s = shot ?: return
        val r = SHOT_CORNER_DP * dp
        canvas.drawRoundRect(s.left.toFloat(), s.top.toFloat(), s.right.toFloat(), s.bottom.toFloat(), r, r, scrim)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        halo.showSettled()
    }

    override fun onDetachedFromWindow() {
        rows.values.forEach { it.anim?.cancel() }
        halo.clear()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val TAG_SHOT = "previewShot"
        const val TAG_AVATAR = "avatar"
        const val TAG_TEXT = "text"
        const val TAG_TAG = "tag"
        val KEYS = listOf("zh", "en", "ja", "ko")
        val RIGHT = setOf("en", "ko")              // .pln.me rows
        const val SHOT_CORNER_DP = 18f
        const val ROW_CORNER_DP = 12f
        const val PILL_OUT_X_DP = 6f
        const val PILL_OUT_Y_DP = 3f
        const val TAG_OUT_X_DP = 6f
        const val TAG_OUT_Y_DP = 9f
        const val UNLIT_TEXT = 0.5f
        const val PILL_IN = 260L
        const val PILL_OUT = 200L
        const val TEXT_FADE = 200L
        const val BOX_STAGGER = 140f
    }
}
