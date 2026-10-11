package us.jhangy.textify

import android.graphics.Color
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import us.jhangy.textify.databinding.ActivitySessionBinding

/**
 * Bottom panel for a truncated translated box (translation spec §3, prism-tide .psheet / note 9): original on top,
 * translation below, one copy button each. In-window views (no dialog window, so "tap outside closes" works over
 * the session); opaque 96 % white with a software glow, no blur (final pick).
 */
internal class TranslationPanel(private val b: ActivitySessionBinding, private val copy: (String) -> Unit) {

    var isOpen = false
        private set

    private val d = b.root.resources.displayMetrics.density
    private var source: PillView? = null

    init {
        b.panel.background = GlowDrawable(
            CssGradientDrawable.solid(0xF5FFFFFF.toInt(), 28 * d), withAlpha(0.28f, 0x4C1D95), 48 * d, 18 * d, 0f, 28 * d,
        )
        b.panelGrip.background = CssGradientDrawable(intArrayOf(0xFFA78BFA.toInt(), 0xFFF472B6.toInt(), 0xFFFDBA74.toInt()), null, 90f, 2 * d)
        b.panelLang.background = CssGradientDrawable.solid(withAlpha(0.08f, 0x7C3AED), -1f)
        b.panelOrigBlock.background = CssGradientDrawable.solid(withAlpha(0.90f, 0xFAF5FF), 20 * d)
        b.panelTrBlock.background = CssGradientDrawable(intArrayOf(withAlpha(0.12f, 0xA78BFA), withAlpha(0.08f, 0xF472B6)), null, 135f, 20 * d)
        for ((button, ok) in listOf(b.copyOrig to b.copyOrigOk, b.copyTr to b.copyTrOk)) {
            button.background = GlowDrawable(CssGradientDrawable.solid(Color.WHITE, -1f), withAlpha(0.16f, 0x7C3AED), 10 * d, 3 * d, 0f, -1f)
            ok.background = CssGradientDrawable(intArrayOf(0xFF7C3AED.toInt(), 0xFFEC4899.toInt()), null, 135f, -1f)
        }
        b.panelScrim.setOnClickListener { close() }
        b.copyOrig.setOnClickListener { copied(b.panelOrig.text.toString(), b.copyOrigOk, b.copyOrigIcon, b.copyOrigText, R.string.panel_copy_original) }
        b.copyTr.setOnClickListener { copied(b.panelTr.text.toString(), b.copyTrOk, b.copyTrIcon, b.copyTrText, R.string.panel_copy_translation) }
    }

    /** panelIn 300: translationY 40dp -> 0 + alpha (decelerate), scrim fades in; [pill] glows while open. */
    fun open(pill: PillView, original: String, translation: String, langLabel: String) {
        source?.focused = false
        source = pill
        pill.focused = true
        b.panelOrig.text = original
        b.panelTr.text = translation
        b.panelLang.text = langLabel
        b.panelScroll.scrollTo(0, 0)
        b.panelScrim.animate().cancel()
        b.panel.animate().cancel()
        if (!isOpen) {
            b.panelScrim.alpha = 0f
            b.panel.alpha = 0f
            b.panel.translationY = PANEL_DY * d
        }
        isOpen = true
        b.panelScrim.visibility = View.VISIBLE
        b.panel.visibility = View.VISIBLE
        b.panelScrim.animate().alpha(1f).setDuration(PANEL_IN).setInterpolator(Easing.EASE_OUT)
        b.panel.animate().translationY(0f).alpha(1f).setDuration(PANEL_IN).setInterpolator(Easing.DECELERATE)
    }

    /** panelOut 240 (accelerate / ease-in), then GONE. */
    fun close() {
        if (!isOpen) return
        isOpen = false
        source?.focused = false
        source = null
        b.panelScrim.animate().alpha(0f).setDuration(PANEL_OUT).setInterpolator(Easing.EASE_IN)
            .withEndAction { b.panelScrim.visibility = View.GONE }
        b.panel.animate().translationY(PANEL_DY * d).alpha(0f).setDuration(PANEL_OUT).setInterpolator(Easing.ACCELERATE)
            .withEndAction { b.panel.visibility = View.GONE }
    }

    /** copyOk 200: gradient fades in with a white check and 已複製; back after copyOkHold 1400. */
    private fun copied(text: String, ok: View, icon: ImageView, label: TextView, idleText: Int) {
        copy(text)
        ok.animate().cancel()
        ok.animate().alpha(1f).setDuration(COPY_OK)
        icon.setImageResource(R.drawable.ic_check)
        icon.setColorFilter(Color.WHITE)
        label.setText(R.string.panel_copied)
        label.setTextColor(Color.WHITE)
        (label.tag as? Runnable)?.let { label.removeCallbacks(it) }
        val revert = Runnable {
            ok.animate().alpha(0f).setDuration(COPY_OK)
            icon.setImageResource(R.drawable.ic_copy)
            icon.clearColorFilter()
            label.setText(idleText)
            label.setTextColor(PLUM)
        }
        label.tag = revert
        label.postDelayed(revert, COPY_OK + COPY_OK_HOLD)
    }

    companion object {
        private const val PANEL_IN = 300L
        private const val PANEL_OUT = 240L
        private const val COPY_OK = 200L
        private const val COPY_OK_HOLD = 1400L
        private const val PANEL_DY = 40f
        private val PLUM = 0xFF4C1D95.toInt()
    }
}
