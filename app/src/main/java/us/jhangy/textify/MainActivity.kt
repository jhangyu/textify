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
import android.view.animation.PathInterpolator
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
    private fun setupOnboarding(v: ActivityMainBinding) { /* B2 */ }
    private fun playIntro(v: ActivityMainBinding) { /* B2 */ }
    private fun refreshState() { /* B2 */ }

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
        private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)   // [P2] cubic-bezier(.2,0,0,1)
        private val EASE_IN = PathInterpolator(0.42f, 0f, 1f, 1f)     // CSS ease-in (copyOut)
        private val EASE_OUT = PathInterpolator(0f, 0f, 0.58f, 1f)    // CSS ease-out (copyIn; [P2] gives no curve)
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
