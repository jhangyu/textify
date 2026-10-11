package us.jhangy.textify

import android.animation.ObjectAnimator
import android.animation.TimeInterpolator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.os.Bundle
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import us.jhangy.textify.databinding.ActivitySessionBinding
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Frozen-screen capture session (a2 "Halo"). Screenshot 1:1 under hidden system bars, edge glow +
 * scrim while OCR runs, then one selectable pill per OCR paragraph. Tap pill = copy paragraph, long-press =
 * system selection, X/back = closeOut fade, then CaptureService brings the bubble back.
 * Settings button = session fades out and SettingsActivity opens (bubble stays hidden).
 */
class SessionActivity : AppCompatActivity() {

    private lateinit var b: ActivitySessionBinding
    private var shot: Bitmap? = null
    private val origin = IntArray(2)          // window position on screen; screenshot px - origin = view px
    private var startOrientation = Configuration.ORIENTATION_UNDEFINED
    private var closing = false
    private var ended = false
    private var ocrRunning = false
    private var actionMode: ActionMode? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()                    // decor does not fit system windows + cutout mode (library handles API levels)
        val bmp = pending
        pending = null
        if (bmp == null) { finish(); return } // nothing to show; onDestroy still ends the session
        shot = bmp
        active = WeakReference(this)
        startOrientation = resources.configuration.orientation

        b = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(b.root)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        b.shot.setImageBitmap(bmp)
        b.root.doOnLayout { root ->
            root.getLocationOnScreen(origin)
            b.shot.imageMatrix = Matrix().apply { setTranslate(-origin[0].toFloat(), -origin[1].toFloat()) }
            b.hint.translationY = -0.04f * root.height       // a2 hint centre sits at 46% of the height
        }

        b.close.setOnClickListener { close() }
        b.settings.setOnClickListener { openSettings() }
        b.pills.setOnClickListener { actionMode?.finish() }   // empty space never closes; it only drops a selection
        onBackPressedDispatcher.addCallback(this) {
            val mode = actionMode
            if (mode != null) mode.finish() else close()
        }

        b.hintOrb.setImageDrawable(orb(dim = true))
        b.close.apply { alpha = 0f; scaleX = 0.6f; scaleY = 0.6f; rotation = -90f; isEnabled = false }
        b.settings.apply { alpha = 0f; scaleX = 0.6f; scaleY = 0.6f; rotation = -90f; isEnabled = false }

        // activation: glow blooms in, faint scrim after scrimDelay, OCR runs
        showClose()                           // X is available from capture start (pressing it during OCR closes)
        b.glow.activate()
        b.scrim.background = scrimDrawable()
        b.scrim.animate().alpha(1f).setStartDelay(SCRIM_DELAY).setDuration(SCRIM_IN).setInterpolator(EASE_OUT)
        ocrRunning = true
        Ocr.recognize(this, bmp, Prefs.scripts(this)) { found -> runOnUiThread { onOcr(found) } }
    }

    private fun onOcr(found: List<OcrBlock>) {
        ocrRunning = false
        if (isDestroyed) {                    // session already gone: the bitmap is ours to free now
            shot?.recycle()
            shot = null
            return
        }
        if (closing) return
        b.root.doOnLayout {
            b.scrim.animate().cancel()
            b.scrim.animate().alpha(0f).setStartDelay(0L).setDuration(SCRIM_OUT).setInterpolator(EASE_IN)
            b.glow.settle()
            if (found.isEmpty()) showHint() else showPills(found)
        }
    }

    private fun showPills(found: List<OcrBlock>) {
        val padX = (PillView.PAD_X_DP * resources.displayMetrics.density).roundToInt()
        val padY = (PillView.PAD_Y_DP * resources.displayMetrics.density).roundToInt()
        val sorted = found.sortedWith(compareBy({ it.box.top }, { it.box.left }))
        val n = sorted.size
        sorted.forEachIndexed { i, block ->
            val pill = PillView(this, block)
            pill.layoutParams = FrameLayout.LayoutParams(block.box.width() + 2 * padX, block.box.height() + 2 * padY).apply {
                leftMargin = block.box.left - padX - origin[0]
                topMargin = block.box.top - padY - origin[1]
            }
            pill.selectionActive = { actionMode != null }
            pill.onTap = { p, dismissOnly ->
                if (dismissOnly) actionMode?.finish() else copyText(p, p.block.text)
            }
            pill.customSelectionActionModeCallback = selectionCallback(pill)
            b.pills.addView(pill)
            // a2: box i delay = round(i/(n-1) * boxStagger)
            pill.playIn(if (n > 1) (i * BOX_STAGGER / (n - 1)).roundToLong() else 0L)
        }
    }

    private fun selectionCallback(pill: PillView) = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            actionMode = mode
            pill.selecting = true
            return true                       // keep the system menu (Copy, Select all, ...)
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId == android.R.id.copy) {
                val s = minOf(pill.selectionStart, pill.selectionEnd)
                val e = maxOf(pill.selectionStart, pill.selectionEnd)
                if (s in 0 until e) pill.post { copied(pill) } // feedback only; the TextView performs the copy
            }
            return false                      // let the TextView handle the item
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            if (actionMode === mode) actionMode = null
            pill.selecting = false
        }
    }

    private fun copyText(pill: PillView, text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("textify", text))
        copied(pill)
    }

    private fun copied(pill: PillView) {
        if (closing) return
        pill.playCopied()
    }

    private fun showClose() {
        listOf(b.close, b.settings).forEachIndexed { i, btn ->
            val delay = i * BTN_STAGGER
            btn.isEnabled = true
            btn.fade(1f, XIN, EMPHASIZED, delay)
            btn.animate().scaleX(1f).scaleY(1f).rotation(0f)
                .setStartDelay(delay).setDuration(XIN).setInterpolator(X_OVERSHOOT)
        }
    }

    private fun showHint() {
        b.hint.apply { visibility = View.VISIBLE; alpha = 0f; scaleX = 0.92f; scaleY = 0.92f }
        b.hint.fade(1f, HINT_IN, EMPHASIZED)
        b.hint.animate().scaleX(1f).scaleY(1f).setDuration(HINT_IN).setInterpolator(HINT_OVERSHOOT)
    }

    /** a2 scrim: RadialGradient #1E1B4B 5% centre -> 16%, CSS radial-gradient(120% 80% at 50% 50%). */
    private fun scrimDrawable() = ShapeDrawable(RectShape()).apply {
        shaderFactory = object : ShapeDrawable.ShaderFactory() {
            override fun resize(width: Int, height: Int): Shader {
                val w = max(1, width).toFloat()
                val h = max(1, height).toFloat()
                return RadialGradient(w / 2f, h / 2f, 1.2f * w, withAlpha(0.05f, INK), withAlpha(0.16f, INK), Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply { setScale(1f, (0.8f * h) / (1.2f * w), w / 2f, h / 2f) })
                }
            }
        }
    }

    /** a2 orb: conic ring of the halo colours with a white hole (inset 7dp); dim = grayscale + opacity .6. */
    private fun orb(dim: Boolean): Drawable {
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.SWEEP_GRADIENT
            colors = ORB_COLORS
        }
        val hole = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        val inset = (7f * resources.displayMetrics.density).roundToInt()
        return LayerDrawable(arrayOf<Drawable>(ring, hole)).apply {
            setLayerInset(1, inset, inset, inset, inset)
            if (dim) {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                alpha = 153
            }
        }
    }

    private fun View.fade(to: Float, ms: Long, curve: TimeInterpolator, delay: Long = 0L) {
        ObjectAnimator.ofFloat(this, View.ALPHA, to).apply {
            startDelay = delay
            duration = ms
            interpolator = curve
            setAutoCancel(true)               // a newer fade of the same view replaces the running one
            start()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation != startOrientation) finish()   // screenshot no longer maps 1:1
    }

    private fun close() {
        if (closing) return
        closing = true
        actionMode?.finish()
        b.close.animate().scaleX(0.6f).scaleY(0.6f).rotation(90f)
            .setDuration(CLOSE_OUT).setInterpolator(X_OVERSHOOT)
        b.root.animate().alpha(0f).setDuration(CLOSE_OUT).setInterpolator(CLOSE_CURVE)
            .withEndAction { endSession(); finish() }
    }

    /** §4: session fades out, then settings opens with the bubble hidden. Allowed during and after OCR. */
    private fun openSettings() {
        if (closing) return
        closing = true
        actionMode?.finish()
        b.settings.animate().rotation(90f).scaleX(1.08f).scaleY(1.08f)
            .setStartDelay(0L).setDuration(SESSION_OUT).setInterpolator(X_OVERSHOOT)
        b.root.animate().alpha(0f).setDuration(SESSION_OUT).setInterpolator(CLOSE_CURVE)
            .withEndAction {
                CaptureService.setSettingsVisible(true)
                SettingsActivity.start(this)
                endSession()
                finish()
            }
    }

    /** Exactly one session-ended callback to the service per activity instance. */
    private fun endSession() {
        if (ended) return
        ended = true
        CaptureService.onSessionEnded()
    }

    override fun onDestroy() {
        if (active?.get() === this) active = null
        shot?.let { bmp ->
            b.shot.setImageDrawable(null)
            if (!ocrRunning) {                // ML Kit may still read it; onOcr frees it otherwise
                bmp.recycle()
                shot = null
            }
        }
        endSession()
        super.onDestroy()
    }

    companion object {
        // a2 header TIMINGS (ms)
        private const val SCRIM_DELAY = 150L
        private const val SCRIM_IN = 300L
        private const val SCRIM_OUT = 300L
        private const val XIN = 240L
        private const val HINT_IN = 300L
        private const val CLOSE_OUT = 300L
        private const val SESSION_OUT = 300L
        private const val BTN_STAGGER = 40L
        private const val BOX_STAGGER = 140f

        private const val INK = 0x1E1B4B
        private val ORB_COLORS = longArrayOf(0xFFA78BFA, 0xFFF472B6, 0xFFFDBA74, 0xFF67E8F9, 0xFF818CF8, 0xFFA78BFA).map { it.toInt() }.toIntArray()

        private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)         // a2 cubic-bezier(.2,0,0,1)
        private val X_OVERSHOOT = PathInterpolator(0.2f, 0f, 0f, 1.3f)      // a2 .xbtn transform
        private val HINT_OVERSHOOT = PathInterpolator(0.2f, 0f, 0f, 1.2f)   // a2 .nohint transform
        private val EASE_OUT = PathInterpolator(0f, 0f, 0.58f, 1f)          // CSS ease-out (scrimIn)
        private val EASE_IN = PathInterpolator(0.42f, 0f, 1f, 1f)           // CSS ease-in (scrimOut)
        private val CLOSE_CURVE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)   // a2 close() opacity curve

        private var pending: Bitmap? = null
        private var active: WeakReference<SessionActivity>? = null

        /** Main thread only. Takes ownership of [shot]; the session recycles it. */
        fun start(ctx: Context, shot: Bitmap) {
            pending?.recycle()
            pending = shot
            ctx.startActivity(
                Intent(ctx, SessionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }

        /** Projection stopped: drop any unconsumed shot and close the open session without animation. */
        fun finishActive() {
            pending?.recycle()
            pending = null
            active?.get()?.finish()
        }
    }
}
