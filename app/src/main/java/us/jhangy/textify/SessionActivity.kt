package us.jhangy.textify

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
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
import android.os.SystemClock
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
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
 * scrim while OCR runs, then one selectable pill per OCR paragraph. Tap pill = copy paragraph (translation mode: copy the translation, or open the bottom panel if truncated), long-press =
 * system selection, X/back = closeOut fade, then CaptureService brings the bubble back.
 * Settings button = session fades out and SettingsActivity opens (bubble stays hidden).
 * Translate button (translation spec §3) = translated cards in place of the paragraphs, and back.
 */
class SessionActivity : AppCompatActivity() {

    private lateinit var b: ActivitySessionBinding
    private var shot: Bitmap? = null
    private val origin = IntArray(2)          // window position on screen; screenshot px - origin = view px
    private var startOrientation = Configuration.ORIENTATION_UNDEFINED
    private var closing = false
    private var ended = false
    private val pendingShimmer = HashMap<PillView, Runnable>()   // tide-timed shimmer starts not yet fired
    private var ocrRunning = false
    private var actionMode: ActionMode? = null

    // translation (spec §3); every list is indexed by paragraph in display order (top, then left)
    private var blocks: List<OcrBlock> = emptyList()
    private val pills = ArrayList<PillView>()
    private val chips = ArrayList<StatusChipView>()
    private var states: Array<ParagraphState?> = emptyArray()
    private val cardColors = HashMap<Int, Int>()
    private var tideAt = LongArray(0)         // uptime at which the current tide reaches box i (shimmer starts then)
    private var iconTint: ValueAnimator? = null
    private var iconColor = PLUM
    private var translating = false           // translation mode on (button on or busy)
    private val runs = ArrayList<TranslateRun>()
    private lateinit var panel: TranslationPanel

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
        b.trBtn.setOnClickListener { toggleTranslation() }
        b.pills.setOnClickListener { actionMode?.finish() }   // empty space never closes; it only drops a selection
        panel = TranslationPanel(b, ::putClip)
        onBackPressedDispatcher.addCallback(this) {
            val mode = actionMode
            when {
                panel.isOpen -> panel.close()          // spec §3: back closes the panel first
                mode != null -> mode.finish()
                else -> close()
            }
        }

        b.hintOrb.setImageDrawable(orb(dim = true))
        b.close.apply { alpha = 0f; scaleX = 0.6f; scaleY = 0.6f; rotation = -90f; isEnabled = false }
        b.settings.apply { alpha = 0f; scaleX = 0.6f; scaleY = 0.6f; rotation = -90f; isEnabled = false }
        b.trBtn.apply { alpha = 0f; scaleX = 0.6f; scaleY = 0.6f; rotation = -90f; isEnabled = false }
        // prism-tide #trBtn on state: LinearGradient(135deg) #7C3AED -> #EC4899 + glow #F472B6 45% r18
        b.trOn.background = GlowDrawable(
            CssGradientDrawable(intArrayOf(0xFF7C3AED.toInt(), 0xFFEC4899.toInt()), null, 135f, -1f),
            withAlpha(0.45f, 0xF472B6), dp(18f), 0f, 0f, -1f,
        )
        b.tide.background = CssGradientDrawable(TIDE_COLORS, TIDE_STOPS, 180f, 0f)

        // activation: glow blooms in, faint scrim after scrimDelay, OCR runs
        showClose()                           // X is available from capture start (pressing it during OCR closes)
        b.glow.activate()
        b.scrim.background = scrimDrawable()
        b.scrim.animate().alpha(1f).setStartDelay(SCRIM_DELAY).setDuration(SCRIM_IN).setInterpolator(Easing.EASE_OUT)
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
            b.scrim.animate().alpha(0f).setStartDelay(0L).setDuration(SCRIM_OUT).setInterpolator(Easing.EASE_IN)
            b.glow.settle()
            if (found.isEmpty()) showHint() else showPills(found)
        }
    }

    private fun showPills(found: List<OcrBlock>) {
        val d = resources.displayMetrics.density
        val padX = (PillView.PAD_X_DP * d).roundToInt()
        val padY = (PillView.PAD_Y_DP * d).roundToInt()
        blocks = found                        // mergeOcr order (top, then left): pills, selection and translation all index this list
        states = arrayOfNulls(blocks.size)
        tideAt = LongArray(blocks.size)
        val n = blocks.size
        blocks.forEachIndexed { i, block ->
            val pill = PillView(this, block)
            pill.layoutParams = FrameLayout.LayoutParams(block.box.width() + 2 * padX, block.box.height() + 2 * padY).apply {
                leftMargin = block.box.left - padX - origin[0]
                topMargin = block.box.top - padY - origin[1]
            }
            pill.selectionActive = { actionMode != null }
            pill.onTap = { p, dismissOnly -> onPillTap(i, p, dismissOnly) }
            pill.customSelectionActionModeCallback = selectionCallback(pill)
            b.pills.addView(pill)
            pills += pill
            // a2: box i delay = round(i/(n-1) * boxStagger)
            pill.playIn(if (n > 1) (i * BOX_STAGGER / (n - 1)).roundToLong() else 0L)
        }
        // chips after every box so they draw above them (prism-tide .chip: right -6dp, top -11dp of the box)
        val containerW = b.pills.width
        for (pill in pills) {
            val lp = pill.layoutParams as FrameLayout.LayoutParams
            val chip = StatusChipView(this)
            chip.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (CHIP_H_DP * d).roundToInt(), Gravity.TOP or Gravity.RIGHT,
            ).apply {
                rightMargin = containerW - (lp.leftMargin + lp.width) - (CHIP_DX_DP * d).roundToInt()
                topMargin = lp.topMargin - (CHIP_DY_DP * d).roundToInt()
            }
            b.pills.addView(chip)
            chips += chip
        }
        showTranslateButton()
    }

    /** Tap on paragraph [i]: drop a selection, copy the translation (translated card) or the paragraph. */
    private fun onPillTap(i: Int, pill: PillView, dismissOnly: Boolean) {
        val shown = pill.translationText
        when {
            dismissOnly -> actionMode?.finish()
            pill.showsTranslation && pill.truncated -> openPanel(i, pill)   // spec §3: truncated box opens the panel
            pill.showsTranslation && shown != null -> copyText(pill, shown)
            else -> copyText(pill, blocks[i].text)
        }
    }

    /** Panel header "source → target" (e.g. 英文 → 繁體中文), original paragraph and full translation. */
    private fun openPanel(i: Int, pill: PillView) {
        val done = states[i] as? ParagraphState.Done ?: return
        val label = getString(R.string.panel_lang, Languages.zhName(done.source), Languages.targetTitle(Prefs.target(this).key))
        panel.open(pill, blocks[i].text, done.text, label)
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
        putClip(text)
        copied(pill)
    }

    private fun putClip(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("textify", text))
    }

    private fun copied(pill: PillView) {
        if (closing) return
        pill.playCopied()
    }

    // ---- translation (spec §3) ----

    /** Off -> on: (re)start translation for paragraphs that need it and sweep the tide in; on / busy -> original. */
    private fun toggleTranslation() {
        if (closing || pills.isEmpty()) return
        actionMode?.finish()
        panel.close()
        translating = !translating
        cancelShimmers()
        tide(translating)                       // first: sets each box's tide arrival time, which gates its shimmer
        if (translating) startRun()
        refreshButton()
    }

    /** Spec §2/§5: never-run, WaitingWifi and Failed paragraphs are (re)translated; others keep their result. */
    private fun startRun() {
        val todo = blocks.indices.filter { needsRun(states[it]) }.associateWith { blocks[it].text }
        if (todo.isNotEmpty()) runs += Translate.start(this, todo, Prefs.target(this), ::onParagraphState)
    }

    private fun onParagraphState(i: Int, s: ParagraphState) {
        if (ended || isDestroyed) return
        states[i] = s
        if (translating) applyState(i, PillView.Reveal.LEFT_RIGHT, 0L)   // prism-tide arrive
        refreshButton()
    }

    /** Maps paragraph [i]'s state to its box and chip for the current mode (spec §3). */
    private fun applyState(i: Int, reveal: PillView.Reveal, delayMs: Long) {
        val pill = pills[i]
        val s = states[i]
        val shimmer = translating && s == ParagraphState.Translating
        val wait = tideAt[i] - SystemClock.uptimeMillis()
        if (shimmer && wait > 0L) {             // prism-tide: shimmer starts when the tide reaches the box
            pill.shimmering = false
            pendingShimmer.remove(pill)?.let { pill.removeCallbacks(it) }
            val r = Runnable {
                pendingShimmer.remove(pill)
                if (!ended && !closing && pill.isAttachedToWindow && translating && states[i] == ParagraphState.Translating) pill.shimmering = true
            }
            pendingShimmer[pill] = r
            pill.postDelayed(r, wait)
        } else {
            pill.shimmering = shimmer
        }
        chips[i].show(if (translating) chipFor(s) else null, delayMs)
        if (translating && s is ParagraphState.Done) {
            if (!pill.showsTranslation || pill.translationText != s.text) pill.showTranslation(s.text, cardColor(i), reveal, delayMs, blocks[i].vertical && verticalTarget(Prefs.target(this)))
        } else if (pill.showsTranslation) {
            pill.showOriginal(reveal, delayMs)
        }
    }

    /** Card colour = average screenshot colour along the box edge, sampled once per paragraph. */
    private fun cardColor(i: Int): Int = cardColors.getOrPut(i) {
        shot?.let { edgeColor(it, blocks[i].box) } ?: Color.WHITE
    }

    /** prism-tide tide 400 (in) / tideBack 320 (out); each box follows with the tide delay (Global Constraints). */
    private fun tide(on: Boolean) {
        val h = max(1, b.root.height).toFloat()
        val band = dp(TIDE_BAND_DP)
        b.tide.animate().cancel()
        b.tide.visibility = View.VISIBLE
        b.tide.translationY = if (on) -band else h
        b.tide.animate().translationY(if (on) h else -band)
            .setStartDelay(0L).setDuration(if (on) TIDE else TIDE_BACK).setInterpolator(Easing.FAST_OUT_SLOW_IN)
            .withEndAction { b.tide.visibility = View.INVISIBLE }
        pills.forEachIndexed { i, pill ->
            val cy = (pill.top + pill.height / 2f) / h
            val delay = if (on) (TIDE * cy * 0.85f).roundToLong() else (TIDE_BACK * (1f - cy) * 0.8f).roundToLong()
            tideAt[i] = SystemClock.uptimeMillis() + if (on) max(0L, delay) else 0L
            applyState(i, PillView.Reveal.TOP_DOWN, max(0L, delay))
        }
    }

    /** Final pick: busy (ring, dimmed icon) only while a paragraph is Translating; on = gradient; off = white. */
    private fun refreshButton() {
        val busy = translating && anyTranslating(states.asList())
        val on = translating && !busy
        b.trOn.animate().alpha(if (on) 1f else 0f).setDuration(BTN_STATE)
        iconTint?.cancel()                      // same duration / curve as the gradient's alpha fade above
        iconTint = ValueAnimator.ofObject(ArgbEvaluator(), iconColor, if (on) Color.WHITE else PLUM).apply {
            duration = BTN_STATE
            addUpdateListener { iconColor = it.animatedValue as Int; b.trIcon.setColorFilter(iconColor) }
            start()
        }
        b.trIcon.animate().alpha(if (busy) 0.55f else 1f).setDuration(BTN_STATE)
        if (busy) b.trRing.visibility = View.VISIBLE
        b.trRing.animate().alpha(if (busy) 1f else 0f).setDuration(BTN_STATE).withEndAction {
            if (!(translating && anyTranslating(states.asList()))) b.trRing.visibility = View.INVISIBLE
        }
        b.trBtn.isSelected = translating
    }

    /** Same entrance as X / settings (xIn 240, overshoot), one more btnStagger later; only once OCR found text. */
    private fun showTranslateButton() {
        val delay = 2 * BTN_STAGGER
        b.trBtn.isEnabled = true
        b.trBtn.fade(1f, XIN, Easing.EMPHASIZED, delay)
        b.trBtn.animate().scaleX(1f).scaleY(1f).rotation(0f)
            .setStartDelay(delay).setDuration(XIN).setInterpolator(Easing.OVERSHOOT)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private fun showClose() {
        listOf(b.close, b.settings).forEachIndexed { i, btn ->
            val delay = i * BTN_STAGGER
            btn.isEnabled = true
            btn.fade(1f, XIN, Easing.EMPHASIZED, delay)
            btn.animate().scaleX(1f).scaleY(1f).rotation(0f)
                .setStartDelay(delay).setDuration(XIN).setInterpolator(Easing.OVERSHOOT)
        }
    }

    private fun showHint() {
        b.hint.apply { visibility = View.VISIBLE; alpha = 0f; scaleX = 0.92f; scaleY = 0.92f }
        b.hint.fade(1f, HINT_IN, Easing.EMPHASIZED)
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
            .setDuration(CLOSE_OUT).setInterpolator(Easing.OVERSHOOT)
        b.root.animate().alpha(0f).setDuration(CLOSE_OUT).setInterpolator(Easing.CLOSE)
            .withEndAction { endSession(); finish() }
    }

    /** §4: session fades out, then settings opens with the bubble hidden. Allowed during and after OCR. */
    private fun openSettings() {
        if (closing) return
        closing = true
        actionMode?.finish()
        b.settings.animate().rotation(90f).scaleX(1.08f).scaleY(1.08f)
            .setStartDelay(0L).setDuration(SESSION_OUT).setInterpolator(Easing.OVERSHOOT)
        b.root.animate().alpha(0f).setDuration(SESSION_OUT).setInterpolator(Easing.CLOSE)
            .withEndAction {
                CaptureService.setSettingsVisible(true)
                SettingsActivity.start(this)
                endSession()
                finish()
            }
    }

    /** Exactly one session-ended callback to the service per activity instance; late translation results are dropped. */
    private fun cancelShimmers() {
        pendingShimmer.forEach { (pill, r) -> pill.removeCallbacks(r) }
        pendingShimmer.clear()
    }

    private fun endSession() {
        if (ended) return
        ended = true
        cancelShimmers()
        runs.forEach { it.close() }           // translation spec §2/§5: model downloads keep running
        runs.clear()
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
        // prism-tide header TIMINGS (ms) and geometry
        private const val TIDE = 400L
        private const val TIDE_BACK = 320L
        private const val BTN_STATE = 240L
        private const val TIDE_BAND_DP = 220f
        private const val CHIP_H_DP = 20f
        private const val CHIP_DX_DP = 6f
        private const val CHIP_DY_DP = 11f

        private const val INK = 0x1E1B4B
        private val PLUM = 0xFF4C1D95.toInt()
        private val ORB_COLORS = longArrayOf(0xFFA78BFA, 0xFFF472B6, 0xFFFDBA74, 0xFF67E8F9, 0xFF818CF8, 0xFFA78BFA).map { it.toInt() }.toIntArray()
        // prism-tide tide band: vertical [0, #A78BFA 42%, #F472B6 48%, #FDBA74 40%, #67E8F9 34%, 0]
        private val TIDE_COLORS = intArrayOf(
            withAlpha(0f, 0xA78BFA), withAlpha(0.42f, 0xA78BFA), withAlpha(0.48f, 0xF472B6),
            withAlpha(0.40f, 0xFDBA74), withAlpha(0.34f, 0x67E8F9), withAlpha(0f, 0x67E8F9),
        )
        private val TIDE_STOPS = floatArrayOf(0f, 0.30f, 0.48f, 0.64f, 0.80f, 1f)

        private val HINT_OVERSHOOT = PathInterpolator(0.2f, 0f, 0f, 1.2f)   // a2 .nohint transform

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
