package us.jhangy.textify

import android.Manifest
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import us.jhangy.textify.databinding.ActivityMainBinding
import kotlin.math.max

/**
 * Launcher entry (spec §2): bubble running -> settings; overlay permission present -> screen-capture consent
 * with no UI (translucent window); otherwise the onboarding screen (§3) until the permission is granted.
 */
class MainActivity : AppCompatActivity() {

    private enum class Ui { INTRO, DENIED, GRANTED }

    private var b: ActivityMainBinding? = null      // null = router mode, nothing drawn
    private var ui = Ui.INTRO
    private var ctaTapped = false                   // user went to the overlay setting at least once
    private var captureRequested = false
    private var closing = false
    private val main = Handler(Looper.getMainLooper())
    private val recheck = Runnable { refreshState() }
    private val grantedHold = Runnable { requestCapture() }
    private val dots = mutableListOf<ValueAnimator>()

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { launchConsent() }

    private val consent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                CaptureService.start(this, res.resultCode, data)
                toast(R.string.toast_started)
            } else {
                toast(R.string.toast_consent_denied)
            }
            close()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        captureRequested = savedInstanceState?.getBoolean(KEY_REQUESTED) ?: false
        ctaTapped = savedInstanceState?.getBoolean(KEY_CTA) ?: false
        if (CaptureService.isRunning) {
            SettingsActivity.start(this)
            finish()
            return
        }
        if (Settings.canDrawOverlays(this)) {        // no setContentView: the translucent window stays empty
            if (!captureRequested) requestCapture()   // a recreated activity keeps the pending result
            return
        }
        val v = ActivityMainBinding.inflate(layoutInflater)
        b = v
        setContentView(v.root)
        setupOnboarding(v)                           // B2
        onBackPressedDispatcher.addCallback(this) { later() }
        if (savedInstanceState == null) playIntro(v) // B2
    }

    override fun onResume() {
        super.onResume()
        val v = b ?: return
        v.demo.resume()
        refreshState()
        main.postDelayed(recheck, RECHECK)           // "300 ms recheck", all API levels (§3.3)
    }

    override fun onPause() {
        main.removeCallbacks(recheck)
        b?.demo?.pause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_REQUESTED, captureRequested)
        outState.putBoolean(KEY_CTA, ctaTapped)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        dots.forEach { it.cancel() }
        super.onDestroy()
    }

    /** §2: notification permission first (API 33+, result ignored), then the consent dialog. */
    private fun requestCapture() {
        if (captureRequested) return
        captureRequested = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchConsent()
        }
    }

    private fun launchConsent() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        consent.launch(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                mpm.createScreenCaptureIntent()
            }
        )
    }

    private fun later() {
        toast(R.string.toast_later)
        close()
    }

    /** §2 close: onboarding root alpha 1 -> 0 over appClose 300, then finish; router mode finishes at once. */
    private fun close() {
        if (closing) return
        closing = true
        main.removeCallbacksAndMessages(null)
        val root = b?.root
        if (root == null) finish()
        else root.animate().alpha(0f).setDuration(APP_CLOSE).withEndAction { finish() }
    }

    private fun toast(res: Int) = Toast.makeText(applicationContext, res, Toast.LENGTH_SHORT).show()

    // ---- onboarding (B2) ----
    private fun setupOnboarding(v: ActivityMainBinding) {
        val d = resources.displayMetrics.density
        v.root.background = HaloStyle.page(this, HaloStyle.ONBOARDING_SPOTS)
        ViewCompat.setOnApplyWindowInsetsListener(v.root) { root, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.updatePadding(left = i.left, top = i.top, right = i.right)  // the sheet runs under the nav bar
            v.actions.updatePadding(bottom = (34 * d).toInt() + i.bottom)
            v.ready.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = (56 * d).toInt() + i.bottom }
            WindowInsetsCompat.CONSUMED
        }
        // re-fit whenever the root or the sheet content changes size (insets, copy swap)
        v.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitSheet(v) }
        v.demo.configure(DemoSpec.ONBOARDING)
        val sheetFill = GradientDrawable().apply {   // top corners only: CssGradientDrawable has one radius
            setColor(withAlpha(0.94f, 0xFFFFFF))
            val r = 32 * d
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        v.sheet.background = GlowDrawable(sheetFill, withAlpha(0.14f, 0x7C3AED), 40 * d, -10 * d, 0f, 32 * d)
        v.track.setLabels(getString(R.string.step_overlay), getString(R.string.step_capture))
        for (c in v.facts.children) {
            c.background = CssGradientDrawable(intArrayOf(withAlpha(0.14f, 0xA78BFA), withAlpha(0.10f, 0xF472B6)), null, 135f, -1f)
        }
        v.later.background = CssGradientDrawable.solid(withAlpha(0.08f, 0x7C3AED), -1f)
        v.cta.background = HaloStyle.cta(this)
        for (dot in listOf(v.dot1, v.dot2, v.dot3)) {
            dot.background = CssGradientDrawable.solid(0xFFA78BFA.toInt(), -1f)   // 6dp square, r = h/2 = circle
        }
        v.cta.setOnClickListener {
            ctaTapped = true
            // API 30+ ignores the Uri and opens the app list (§12); one path
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        v.later.setOnClickListener { later() }
        ui = if (ctaTapped) Ui.DENIED else Ui.INTRO
        render(v, animate = false)
    }

    /** §3.3 intro: stage box and sheet alpha 0 -> 1, translationY 14dp -> 0, introIn 360, stagger 60 (block 0 = status-bar spacer, not drawn). */
    private fun playIntro(v: ActivityMainBinding) {
        val dy = 14 * resources.displayMetrics.density
        listOf(v.demo, v.sheet).forEachIndexed { i, block ->
            block.alpha = 0f
            block.translationY = dy
            block.animate().alpha(1f).translationY(0f)
                .setStartDelay(INTRO_STAGGER * (i + 1)).setDuration(INTRO_IN).setInterpolator(Easing.EMPHASIZED)
        }
    }

    /**
     * Lead ruling (short screens): the sheet starts at 448dp below the status bar when its content fits, otherwise
     * it moves up just enough to show its natural height (never above the content top); if even that is too small
     * the ScrollView scrolls. Only re-lays out when the value changes (no layout loop).
     */
    private fun fitSheet(v: ActivityMainBinding) {
        val preferred = (448 * resources.displayMetrics.density).toInt()
        // natural content height = laid-out body minus what the weighted spacer absorbed (fillViewport);
        // no extra measure pass, so the real layout is never disturbed
        val natural = v.sheetBody.height - v.spacer.height
        val available = v.root.height - v.root.paddingTop
        val top = (available - natural).coerceIn(0, preferred)
        val lp = v.sheet.layoutParams as ViewGroup.MarginLayoutParams
        if (lp.topMargin != top) {
            lp.topMargin = top
            v.sheet.post { v.sheet.layoutParams = lp }   // not inside the layout pass
        }
    }

    /** Re-reads the overlay permission (onResume and +300 ms); on change swaps copy and state visuals (§3.3). */
    private fun refreshState() {
        val v = b ?: return
        if (closing || ui == Ui.GRANTED) return
        val next = when {
            Settings.canDrawOverlays(this) -> Ui.GRANTED
            ctaTapped -> Ui.DENIED
            else -> Ui.INTRO
        }
        if (next == ui) return
        ui = next
        render(v, animate = true)
        if (next == Ui.GRANTED) main.postDelayed(grantedHold, GRANTED_HOLD)
    }

    /** §3.2 copy + visuals for [ui]. */
    private fun render(v: ActivityMainBinding, animate: Boolean) {
        val granted = ui == Ui.GRANTED
        when (ui) {
            Ui.INTRO -> { swap(v.title, R.string.onboard_title, animate); swap(v.lead, R.string.onboard_lead, animate); swap(v.cta, R.string.perm_cta, animate) }
            Ui.DENIED -> { swap(v.title, R.string.perm_denied_title, animate); swap(v.lead, R.string.onboard_denied_lead, animate); swap(v.cta, R.string.onboard_cta_retry, animate) }
            Ui.GRANTED -> { swap(v.title, R.string.perm_granted_title, animate); swap(v.lead, R.string.onboard_granted_lead, animate) }
        }
        v.demo.setGranted(ui != Ui.DENIED, animate)        // ghostSwap 300 inside CaptureDemoView
        v.track.setState(
            when (ui) { Ui.INTRO -> StepTrackView.State.CURRENT; Ui.DENIED -> StepTrackView.State.DENIED; Ui.GRANTED -> StepTrackView.State.GRANTED },
            animate,
        )
        v.cta.isEnabled = !granted
        v.later.isEnabled = !granted
        fadeTo(v.cta, if (granted) 0f else 1f, animate)
        fadeTo(v.later, if (granted) 0f else 1f, animate)
        fadeTo(v.facts, if (granted) 0.4f else 1f, animate)
        fadeTo(v.ready, if (granted) 1f else 0f, animate)
        if (granted && dots.isEmpty()) startDots(v)
    }

    /** copyOut 120 ease-in -> set text -> copyIn 220. */
    private fun swap(view: TextView, res: Int, animate: Boolean) {
        if (!animate) { view.setText(res); return }
        view.animate().alpha(0f).setStartDelay(0L).setDuration(COPY_OUT).setInterpolator(Easing.EASE_IN).withEndAction {
            view.setText(res)
            view.animate().alpha(1f).setDuration(COPY_IN).setInterpolator(Easing.EASE_OUT)
        }
    }

    private fun fadeTo(view: View, to: Float, animate: Boolean) {
        if (animate) view.animate().alpha(to).setStartDelay(0L).setDuration(STATE_FADE) else view.alpha = to
    }

    /** §3.1 item 6: dot alpha .3 -> 1 (30%) -> .3 (60%) -> .3, 1200 ms loop, delays 0/150/300. */
    private fun startDots(v: ActivityMainBinding) {
        listOf(v.dot1, v.dot2, v.dot3).forEachIndexed { i, dot ->
            val pulse = PropertyValuesHolder.ofKeyframe(
                View.ALPHA,
                Keyframe.ofFloat(0f, 0.3f), Keyframe.ofFloat(0.3f, 1f), Keyframe.ofFloat(0.6f, 0.3f), Keyframe.ofFloat(1f, 0.3f),
            )
            dots += ObjectAnimator.ofPropertyValuesHolder(dot, pulse).apply {
                duration = DOT_LOOP
                repeatCount = ValueAnimator.INFINITE
                startDelay = DOT_DELAY * i
                start()
            }
        }
    }

    companion object {
        private const val KEY_REQUESTED = "captureRequested"
        private const val KEY_CTA = "ctaTapped"
        // [P2] TIMINGS (ms)
        private const val INTRO_IN = 360L
        private const val INTRO_STAGGER = 60L
        private const val COPY_OUT = 120L
        private const val COPY_IN = 220L
        private const val STATE_FADE = 220L
        private const val APP_CLOSE = 300L
        private const val RECHECK = 300L
        private const val GRANTED_HOLD = 900L
        private const val DOT_LOOP = 1200L
        private const val DOT_DELAY = 150L
    }
}

/** CSS flex-wrap row with an 8dp gap both ways (onboarding fact chips, §3.1 item 4). */
class WrapLayout(context: Context, attrs: AttributeSet?) : ViewGroup(context, attrs) {
    private val gap = 8f * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val h = place(width - paddingLeft - paddingRight, layout = false) { c -> measureChild(c, widthMeasureSpec, heightMeasureSpec) }
        setMeasuredDimension(width, resolveSize(h + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        place(r - l - paddingLeft - paddingRight, layout = true) {}
    }

    /** Flows the visible children into rows of [maxW]; returns the content height. */
    private inline fun place(maxW: Int, layout: Boolean, measure: (View) -> Unit): Int {
        var x = 0f; var y = 0f; var rowH = 0
        for (c in children) {
            if (c.visibility == GONE) continue
            measure(c)
            if (x > 0f && x + c.measuredWidth > maxW) { x = 0f; y += rowH + gap; rowH = 0 }
            if (layout) {
                val cl = paddingLeft + x.toInt(); val ct = paddingTop + y.toInt()
                c.layout(cl, ct, cl + c.measuredWidth, ct + c.measuredHeight)
            }
            x += c.measuredWidth + gap
            rowH = max(rowH, c.measuredHeight)
        }
        return (y + rowH).toInt()
    }
}
