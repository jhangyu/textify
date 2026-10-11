package us.jhangy.textify

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.Switch
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import us.jhangy.textify.databinding.ActivitySettingsBinding

/**
 * Merged settings page (spec §7): overlay-permission card with the looping capture demo, and the
 * multi-select OCR language card. While it is visible the capture bubble is hidden (§5).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private val main = Handler(Looper.getMainLooper())
    private var granted: Boolean? = null            // null until the first onResume
    private var openingSystemSettings = false
    private var visibleSent = false                 // last CaptureService.setSettingsVisible call was true
    private var leaving = false
    private val recheck = Runnable { applyPermission(Settings.canDrawOverlays(this)) }
    private val d get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { root, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.updatePadding(i.left, i.top, i.right, i.bottom)
            WindowInsetsCompat.CONSUMED
        }
        stylePage()
        setupPermissionCard()
        setupLanguageCard()                         // C2
        b.back.setOnClickListener { leave() }
        onBackPressedDispatcher.addCallback(this) { leave() }

        // settingsIn 320 (0,0,.2,1): alpha 0 -> 1, scale 1.04 -> 1; then the preview lines light (§7.4)
        b.content.apply { alpha = 0f; scaleX = 1.04f; scaleY = 1.04f }
        b.content.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(SETTINGS_IN).setInterpolator(ENTER)
            .withEndAction { b.preview.setScripts(Prefs.scripts(this), animate = true) }
    }

    override fun onStart() {
        super.onStart()
        CaptureService.setSettingsVisible(true)
        visibleSent = true
    }

    override fun onResume() {
        super.onResume()
        val returning = openingSystemSettings
        openingSystemSettings = false
        applyPermission(Settings.canDrawOverlays(this))
        if (returning) b.demo.flash()               // §7.3 return flash
        b.demo.resume()                             // no-op while denied
        main.postDelayed(recheck, RECHECK)
    }

    override fun onPause() {
        main.removeCallbacks(recheck)
        b.demo.pause()
        super.onPause()
    }

    override fun onStop() {
        if (!openingSystemSettings) {
            CaptureService.setSettingsVisible(false)
            visibleSent = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (visibleSent) CaptureService.setSettingsVisible(false)   // user never came back from system settings
        super.onDestroy()
    }

    /** settingsOut 260 (.3,0,.8,.15): alpha 1 -> 0, scale 1 -> 1.04, then finish. */
    private fun leave() {
        if (leaving) return
        leaving = true
        b.content.animate().alpha(0f).scaleX(1.04f).scaleY(1.04f)
            .setDuration(SETTINGS_OUT).setInterpolator(EXIT)
            .withEndAction { finish() }
    }

    private fun stylePage() {
        b.root.background = HaloStyle.page(this, HaloStyle.SETTINGS_SPOTS)
        b.back.background = pressed(oval(withAlpha(0.18f, 0xA78BFA)), null)
        b.permCard.background = HaloStyle.card(this)
        b.langCard.background = HaloStyle.card(this)
    }

    // ---- permission card (§7.2, §7.3) ----

    private fun setupPermissionCard() {
        b.demo.configure(DemoSpec.SETTINGS)
        b.track.setLabels(getString(R.string.step_overlay), getString(R.string.step_capture))
        b.cta.background = HaloStyle.cta(this)
        b.switchRow.background = pressed(round(withAlpha(0.12f, 0x7C3AED), -1f), round(withAlpha(0.06f, 0x7C3AED), -1f))
        // ponytail: the switch row exists only while granted, so the switch is always drawn "on";
        // the off fill and thumb travel (toggle 200) are never visible and are not built.
        b.switchTrack.background = CssGradientDrawable(intArrayOf(0xFFA78BFA.toInt(), 0xFFF472B6.toInt()), null, 90f, -1f)
        b.switchThumb.background = GlowDrawable(oval(Color.WHITE), withAlpha(0.25f, 0x1E1B4B), 3 * d, 1 * d, 0f, -1f)
        ViewCompat.setAccessibilityDelegate(b.switchRow, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Switch::class.java.name
                info.isCheckable = true
                info.isChecked = granted == true
            }
        })
        b.cta.setOnClickListener { openOverlaySettings() }
        b.switchRow.setOnClickListener { openOverlaySettings() }
    }

    /** onResume and +300 ms re-read (§7.3). First call applies without animation. */
    private fun applyPermission(g: Boolean) {
        if (g == granted) return
        val animate = granted != null
        granted = g
        b.demo.setGranted(g, animate)
        b.track.setState(if (g) StepTrackView.State.GRANTED else StepTrackView.State.DENIED, animate)
        b.statusTitle.setText(if (g) R.string.perm_granted_title else R.string.perm_denied_title)
        b.statusText.setText(if (g) R.string.perm_granted_text else R.string.perm_denied_text)
        b.cta.isVisible = !g
        b.switchRow.isVisible = g
    }

    private fun openOverlaySettings() {
        openingSystemSettings = true
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    // ---- language card (§7.4): step C2.2 ----
    private fun setupLanguageCard() { /* C2 */ }

    // ---- small drawables ----
    private fun round(color: Int, radiusPx: Float) = CssGradientDrawable.solid(color, radiusPx)
    private fun oval(color: Int) = CssGradientDrawable.solid(color, -1f)          // square views only -> circle
    private fun pressed(on: Drawable, off: Drawable?) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), on)
        addState(IntArray(0), off ?: ColorDrawable(Color.TRANSPARENT))
    }

    companion object {
        // [SF] TIMINGS (ms)
        private const val SETTINGS_IN = 320L
        private const val SETTINGS_OUT = 260L
        private const val RECHECK = 300L
        private const val HINT_CROSS = 200L
        private const val MSG_IN = 220L
        private const val MSG_OUT = 160L
        private const val NOTE_FADE = 160L
        private const val BLOCK_HOLD = 1500L
        private val ENTER = PathInterpolator(0f, 0f, 0.2f, 1f)       // settingsIn
        private val EXIT = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)   // settingsOut
        private val EASE_OUT = PathInterpolator(0f, 0f, 0.58f, 1f)   // msgIn
        private val EASE_IN = PathInterpolator(0.42f, 0f, 1f, 1f)    // msgOut

        fun start(ctx: Context) {
            ctx.startActivity(
                Intent(ctx, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }
    }
}
