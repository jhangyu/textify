package us.jhangy.textify

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Per-paragraph status chip (translation spec §3, prism-tide .chip / note 6). Not clickable: taps reach the box. */
@SuppressLint("ViewConstructor")
class StatusChipView(context: Context) : LinearLayout(context) {

    /** Kind currently shown (or fading in); null when hidden. */
    var chip: Chip? = null
        private set

    private val d = resources.displayMetrics.density
    private val spinner = SpinnerRingView(context).apply { strokePx = 2f * d }
    private val icon = ImageView(context)
    private val label = TextView(context).apply {
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        maxLines = 1
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding((6 * d).roundToInt(), 0, (8 * d).roundToInt(), 0)
        addView(spinner, LayoutParams((11 * d).roundToInt(), (11 * d).roundToInt()))
        addView(icon, LayoutParams((12 * d).roundToInt(), (12 * d).roundToInt()))
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = (4 * d).roundToInt() })
        isClickable = false
        alpha = 0f
        visibility = GONE
    }

    /** chipIn 220 (alpha, translationY 4dp -> 0, scale .9 -> 1, overshoot) after [delayMs]; null = chipOut 160 then GONE. */
    fun show(kind: Chip?, delayMs: Long = 0L) {
        if (kind == chip) return
        chip = kind
        animate().cancel()
        if (kind == null) {
            animate().alpha(0f).setStartDelay(delayMs).setDuration(CHIP_OUT).setInterpolator(Easing.EASE_IN)
                .withEndAction { visibility = GONE }
            return
        }
        bind(kind)
        visibility = VISIBLE
        if (alpha == 0f) {
            translationY = 4 * d
            scaleX = 0.9f
            scaleY = 0.9f
        }
        animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setStartDelay(delayMs).setDuration(CHIP_IN).setInterpolator(Easing.OVERSHOOT)
    }

    private fun bind(kind: Chip) {
        val downloading = kind == Chip.DOWNLOADING
        spinner.visibility = if (downloading) VISIBLE else GONE
        icon.visibility = if (downloading) GONE else VISIBLE
        val (text, color, fill) = when (kind) {
            Chip.DOWNLOADING -> Triple(R.string.chip_downloading, INK, 0.95f)
            Chip.WAITING_WIFI -> Triple(R.string.chip_waiting_wifi, WAITING, 0.92f)
            Chip.UNSUPPORTED -> Triple(R.string.chip_unsupported, SECONDARY, 0.92f)
            Chip.FAILED -> Triple(R.string.chip_failed, FAILED, 0.95f)
        }
        when (kind) {
            Chip.WAITING_WIFI -> icon.setImageResource(R.drawable.ic_chip_wifi)
            Chip.UNSUPPORTED -> icon.setImageResource(R.drawable.ic_chip_unsupported)
            Chip.FAILED -> icon.setImageResource(R.drawable.ic_chip_failed)
            Chip.DOWNLOADING -> Unit
        }
        icon.setColorFilter(color)
        label.setText(text)
        label.setTextColor(color)
        val shadow = if (kind == Chip.FAILED) withAlpha(0.45f, 0xFDBA74) else withAlpha(0.22f, 0x7C3AED)
        background = GlowDrawable(CssGradientDrawable.solid(withAlpha(fill, 0xFFFFFF), -1f), shadow, 12 * d, 4 * d, 0f, -1f)
    }

    companion object {
        private const val CHIP_IN = 220L
        private const val CHIP_OUT = 160L
        private val INK = 0xFF1E1B4B.toInt()
        private val WAITING = 0xFF8A86A0.toInt()
        private val SECONDARY = 0xFF6B5FA8.toInt()
        private val FAILED = 0xFF9A3412.toInt()
    }
}
