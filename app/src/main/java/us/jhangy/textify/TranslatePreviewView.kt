package us.jhangy.textify

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * Looping preview in the 「翻譯」 card (prism-tide .tpv, note 10): a chat bubble whose text is flipped into the
 * target language by a Halo band, then flipped back. One ValueAnimator (previewLoop 3200), software drawing.
 */
class TranslatePreviewView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val d = resources.displayMetrics.density
    private val bg = CssGradientDrawable(intArrayOf(withAlpha(0.14f, 0xA78BFA), withAlpha(0.12f, 0x67E8F9)), null, 160f, 24 * d)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(15f); color = 0xFF1D2330.toInt() }
    private val trText = Paint(text).apply { color = 0xFF111111.toInt() }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(12f); typeface = Typeface.DEFAULT_BOLD }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val srcPill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(0.75f, 0xFFFFFF) }
    private val dstPill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val underline = Paint(Paint.ANTI_ALIAS_FLAG)
    private val band = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.4f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = withAlpha(0.7f, 0x6B5FA8)
    }
    private val pill = PillPainter(withAlpha(0.20f, 0xA78BFA), withAlpha(0.16f, 0xF472B6), withAlpha(0.28f, 0xA78BFA), 12 * d, -1f)
    private val bubble = RectF()
    private val layer = RectF()
    private var shadow: Bitmap? = null
    private val shadowDst = RectF()
    private var source = ""
    private var translated = ""
    private var srcLabel = ""
    private var dstLabel = ""
    private var t = 0f
    private val loop = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = LOOP; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    /** Sample sentence and labels for target [key] (prism-tide PV map). */
    fun setTarget(key: String) {
        if (key == "en") { source = "9時にロビーで集合。"; translated = "Meet at the lobby at 9."; srcLabel = "日文" }
        else { source = "Meet at the lobby at 9."; translated = SAMPLES[key] ?: "${Languages.targetTitle(key)} · 範例譯文"; srcLabel = "英文" }
        dstLabel = Languages.targetTitle(key)
        layoutBubble()
        invalidate()
    }

    fun resume() { if (loop.isStarted) loop.resume() else loop.start() }

    fun pause() { loop.pause() }

    override fun onDetachedFromWindow() { loop.cancel(); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); layoutBubble() }

    /** Bubble (padding 14/9dp, max 300dp) + 12dp gap + language row, centred. */
    private fun layoutBubble() {
        if (width == 0 || height == 0) return
        bg.setBounds(0, 0, width, height)
        val bw = min(maxOf(text.measureText(source), text.measureText(translated)) + 28 * d, min(300 * d, width - 24 * d))
        val bh = text.fontSpacing + 18 * d
        val total = bh + 12 * d + small.fontSpacing + 6 * d
        val top = (height - total) / 2f
        bubble.set((width - bw) / 2f, top, (width + bw) / 2f, top + bh)
        layer.set(bubble.left + 6 * d, bubble.top + 5 * d, bubble.right - 6 * d, bubble.bottom - 5 * d)
        pill.setSize((bw - 16 * d).toInt(), (bh - 8 * d).toInt())
        val blur = 20 * d; val dy = 8 * d; val spread = -10 * d
        shadow = glowBitmap(bw, bh, 16 * d, blur, withAlpha(0.35f, 0x4C1D95), spread, dy)
        val pad = glowPad(blur, spread, dy)
        shadowDst.set(bubble.left - pad, bubble.top - pad, bubble.right + pad, bubble.bottom + pad)
        underline.shader = LinearGradient(layer.left + 8 * d, 0f, layer.right - 8 * d, 0f, HALO, null, Shader.TileMode.CLAMP)
        band.shader = LinearGradient(0f, 0f, bw * BAND, 0f, BAND_COLORS, BAND_STOPS, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        if (bubble.isEmpty) return
        bg.draw(canvas)
        shadow?.let { canvas.drawBitmap(it, null, shadowDst, null) }
        canvas.drawRoundRect(bubble, 16 * d, 16 * d, white)
        canvas.save()
        canvas.translate(bubble.left + 8 * d, bubble.top + 4 * d)          // a2 pill, inset 8/4dp
        pill.draw(canvas)
        canvas.restore()
        val baseline = bubble.centerY() - (text.ascent() + text.descent()) / 2f
        canvas.save()
        canvas.clipRect(bubble)
        canvas.drawText(source, bubble.left + 14 * d, baseline, text)
        val (from, to) = coverage(t)
        if (to > from) {                                                   // translated layer, revealed left to right
            canvas.save()
            canvas.clipRect(layer.left + from * layer.width(), bubble.top, layer.left + to * layer.width(), bubble.bottom)
            canvas.drawRoundRect(layer, 12 * d, 12 * d, white)
            canvas.drawText(translated, bubble.left + 14 * d, baseline, trText)
            canvas.drawRoundRect(layer.left + 8 * d, layer.bottom - 2.5f * d, layer.right - 8 * d, layer.bottom, 1.25f * d, 1.25f * d, underline)
            canvas.restore()
        }
        canvas.restore()
        bandPhase(t)?.let { (p, alpha) ->                                  // Halo band sweeping across the bubble
            val bw = bubble.width() * BAND
            band.alpha = (alpha * 255f).toInt()
            canvas.save()
            canvas.translate(bubble.left + (-0.9f + 3.2f * p) * bw, 0f)
            canvas.drawRect(0f, bubble.top - 16 * d, bw, bubble.bottom + 16 * d, band)
            canvas.restore()
        }
        drawLanguageRow(canvas, bubble.bottom + 12 * d)
    }

    /** Source pill (white 75 %) -> arrow -> target pill (gradient #A78BFA -> #F472B6), padding 10/3dp. */
    private fun drawLanguageRow(canvas: Canvas, top: Float) {
        val h = small.fontSpacing + 6 * d
        val sw = small.measureText(srcLabel) + 20 * d
        val dw = small.measureText(dstLabel) + 20 * d
        val gap = 8 * d
        val aw = 14 * d
        var x = (width - (sw + gap + aw + gap + dw)) / 2f
        val ty = top + h / 2f - (small.ascent() + small.descent()) / 2f
        canvas.drawRoundRect(x, top, x + sw, top + h, h / 2f, h / 2f, srcPill)
        small.color = 0xFF6B5FA8.toInt()
        canvas.drawText(srcLabel, x + 10 * d, ty, small)
        x += sw + gap
        val s = aw / 24f                                                   // arrow path M5 12h14 M13 6l6 6-6 6
        val cy = top + h / 2f
        canvas.drawPath(Path().apply {
            moveTo(x + 5 * s, cy); lineTo(x + 19 * s, cy)
            moveTo(x + 13 * s, cy - 6 * s); lineTo(x + 19 * s, cy); lineTo(x + 13 * s, cy + 6 * s)
        }, arrow)
        x += aw + gap
        dstPill.shader = LinearGradient(x, 0f, x + dw, 0f, 0xFFA78BFA.toInt(), 0xFFF472B6.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(x, top, x + dw, top + h, h / 2f, h / 2f, dstPill)
        small.color = Color.WHITE
        canvas.drawText(dstLabel, x + 10 * d, ty, small)
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    companion object {
        private const val LOOP = 3200L
        private const val BAND = 0.45f
        private val HALO = intArrayOf(0xFFA78BFA.toInt(), 0xFFF472B6.toInt(), 0xFFFDBA74.toInt(), 0xFF67E8F9.toInt())
        private val BAND_COLORS = intArrayOf(
            withAlpha(0f, 0xFFFFFF), withAlpha(0.45f, 0xA78BFA), withAlpha(0.5f, 0xF472B6), withAlpha(0.4f, 0x67E8F9), withAlpha(0f, 0xFFFFFF),
        )
        private val BAND_STOPS = floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f)
        private val SAMPLES = mapOf(
            "zh-Hant" to "九點在大廳見。", "zh-Hans" to "九点在大厅见。", "ja" to "9時にロビーで集合。",
            "ko" to "9시에 로비에서 만나요.", "fr" to "Rendez-vous au hall à 9 h.", "de" to "Treffen um 9 in der Lobby.",
            "es" to "Nos vemos en el vestíbulo a las 9.",
        )

        /** Covered fraction range [from, to] of the bubble at loop time [t] (prism-tide tflip keyframes). */
        private fun coverage(t: Float): Pair<Float, Float> = when {
            t < 0.36f -> 0f to 0f
            t < 0.485f -> 0f to (t - 0.36f) / 0.125f
            t < 0.86f -> 0f to 1f
            t < 0.96f -> (t - 0.86f) / 0.10f to 1f
            else -> 0f to 0f
        }

        /** Band progress 0..1 and alpha during the two sweeps (prism-tide tband), null otherwise. */
        private fun bandPhase(t: Float): Pair<Float, Float>? = when {
            t in 0.36f..0.485f -> ((t - 0.36f) / 0.125f).let { it to 1f - it }
            t in 0.86f..0.96f -> ((t - 0.86f) / 0.10f).let { it to 1f - it }
            else -> null
        }
    }
}
