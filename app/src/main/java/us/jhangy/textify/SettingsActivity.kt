package us.jhangy.textify

import android.animation.LayoutTransition
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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
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
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Merged settings page (spec §7): overlay-permission card with the looping capture demo, and the
 * multi-select OCR language card, and the translation card (translation spec §4). While it is visible the capture bubble is hidden (§5).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private val main = Handler(Looper.getMainLooper())
    private var granted: Boolean? = null            // null until the first onResume
    private var openingSystemSettings = false
    private var visibleSent = false                 // the service was last told that settings is visible
    private var leaving = false
    private val recheck = Runnable { applyPermission(Settings.canDrawOverlays(this)) }
    private val autoHide = Runnable { hideBlock() }
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
        setupTranslateCard()                        // translation spec §4
        b.back.setOnClickListener { leave() }
        onBackPressedDispatcher.addCallback(this) { if (sheetMode != SheetMode.NONE) closeSheet() else leave() }

        // settingsIn 320 (0,0,.2,1): alpha 0 -> 1, scale 1.04 -> 1; then the preview lines light (§7.4)
        b.content.apply { alpha = 0f; scaleX = 1.04f; scaleY = 1.04f }
        b.content.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(SETTINGS_IN).setInterpolator(Easing.DECELERATE)
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
        Translate.onModelsChanged = { refreshModels() }   // a session download may finish while Settings is open
        Llm.onChanged = { onLlmChanged() }
        refreshModels()                                    // spec §4: query ML Kit every time Settings opens
        b.trPreview.resume()
    }

    override fun onPause() {
        main.removeCallbacks(recheck)
        b.demo.pause()
        Translate.onModelsChanged = null
        Llm.onChanged = null
        b.trPreview.pause()
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
            .setDuration(SETTINGS_OUT).setInterpolator(Easing.CLOSE)
            .withEndAction { finish() }
    }

    private fun stylePage() {
        b.root.background = HaloStyle.page(this, HaloStyle.SETTINGS_SPOTS)
        b.back.background = pressed(oval(withAlpha(0.18f, 0xA78BFA)), null)
        b.permCard.background = HaloStyle.card(this)
        b.langCard.background = HaloStyle.card(this)
        b.trCard.background = HaloStyle.card(this)
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
    private fun setupLanguageCard() {
        // static styling (the preview's shot, rows, tags and avatar are styled by LanguagePreviewView)
        val capsule = GlowDrawable(round(withAlpha(0.94f, 0xFFFFFF), 18 * d), withAlpha(0.28f, 0xA78BFA), 16 * d, 0f, 0f, 18 * d)
        b.noteBlock.background = GlowDrawable(capsule, withAlpha(0.10f, 0x4C1D95), 18 * d, 6 * d, 0f, 18 * d)
        b.noteBar.background = CssGradientDrawable(intArrayOf(0xFFF472B6.toInt(), 0xFFFDBA74.toInt()), null, 180f, 3 * d)
        b.timeline.background = round(withAlpha(0.90f, 0xFAF5FF), 20 * d)
        b.lanes.setLabels(getString(R.string.tag_zh), getString(R.string.tag_ja), getString(R.string.tag_ko))

        // initial state, no animation; preview lights after settingsIn (onCreate)
        val selected = Prefs.scripts(this)
        for ((script, toggle) in toggles()) {
            toggle.setChecked(script in selected, animate = false)
            toggle.setOnClickListener { onToggle(script, toggle) }
        }
        b.preview.setScripts(emptySet(), animate = false)
        b.lanes.setScripts(selected, animate = false)
        showSpeed(selected.size)
    }

    private fun toggles() = listOf(Script.CHINESE to b.toggleZh, Script.JAPANESE to b.toggleJa, Script.KOREAN to b.toggleKo)

    /** Add / remove a script; the last one cannot be removed (reject + block capsule). Saved at once (next capture). */
    private fun onToggle(script: Script, toggle: LanguageToggleView) {
        val current = Prefs.scripts(this)
        val next = if (script in current) current - script else current + script
        if (next.isEmpty()) {
            toggle.reject()
            showBlock()
            return
        }
        Prefs.setScripts(this, next)
        toggle.setChecked(script in next, animate = true)
        hideBlock()                                      // msgOut at once
        b.preview.setScripts(next, animate = true)
        b.lanes.setScripts(next, animate = true)
        crossFadeSpeed(next.size)                        // n always changes on a successful toggle
    }

    private fun showSpeed(n: Int) {
        b.speedValue.setText(if (n == 1) R.string.speed_fast else R.string.speed_slower)
        b.speedDesc.text = if (n == 1) getString(R.string.speed_desc_one) else getString(R.string.speed_desc_many, n)
    }

    /** hintCross 200: alpha -> 0, swap text, alpha -> 1. */
    private fun crossFadeSpeed(n: Int) {
        b.speedDesc.animate().alpha(0f).setDuration(HINT_CROSS)
        b.speedValue.animate().alpha(0f).setDuration(HINT_CROSS).withEndAction {
            showSpeed(n)
            b.speedValue.animate().alpha(1f).setDuration(HINT_CROSS)
            b.speedDesc.animate().alpha(1f).setDuration(HINT_CROSS)
        }
    }

    /** msgIn 220 ease-out (alpha, translationY -8dp -> 0); resting text out 160; auto-hide at msgIn + blockHold 1500. */
    private fun showBlock() {
        main.removeCallbacks(autoHide)
        b.noteBlock.animate().alpha(1f).translationY(0f).setDuration(MSG_IN).setInterpolator(Easing.EASE_OUT)
        b.noteRest.animate().alpha(0f).setDuration(NOTE_FADE)
        b.notice.requestRectangleOnScreen(Rect(0, 0, b.notice.width, b.notice.height))
        main.postDelayed(autoHide, MSG_IN + BLOCK_HOLD)
    }

    /** msgOut 160 ease-in (alpha -> 0, translationY -> -8dp); resting text back. Always retargets: a show queued this frame has not moved the alpha yet. */
    private fun hideBlock() {
        main.removeCallbacks(autoHide)
        b.noteBlock.animate().alpha(0f).translationY(-8 * d).setDuration(MSG_OUT).setInterpolator(Easing.EASE_IN)
        b.noteRest.animate().alpha(1f).setDuration(NOTE_FADE)
    }

    // ---- translation card (translation spec §4, prism-tide #trCard) ----

    private enum class SheetMode { NONE, TARGET, DOWNLOAD }

    private var sheetMode = SheetMode.NONE
    private var wifiOnly = true
    private var lastRows: Map<String, ModelState> = emptyMap()

    private fun setupTranslateCard() {
        val rowBg = { pressed(round(withAlpha(0.12f, 0x7C3AED), 24 * d), round(withAlpha(0.06f, 0x7C3AED), 24 * d)) }
        b.targetRow.background = rowBg()
        b.wifiRow.background = rowBg()
        b.modelWell.background = round(withAlpha(0.90f, 0xFAF5FF), 22 * d)
        b.modelWell.layoutTransition = LayoutTransition().apply { setDuration(ROW_ANIM) }   // rowIn / rowOut 260
        b.dlBtn.background = pressed(round(withAlpha(0.14f, 0x7C3AED), -1f), round(withAlpha(0.08f, 0x7C3AED), -1f))
        b.wifiTrackOff.background = round(withAlpha(0.12f, 0x1E1B4B), -1f)
        b.wifiTrackOn.background = CssGradientDrawable(intArrayOf(0xFFA78BFA.toInt(), 0xFFF472B6.toInt()), null, 90f, -1f)
        b.wifiThumb.background = GlowDrawable(oval(Color.WHITE), withAlpha(0.25f, 0x1E1B4B), 3 * d, 1 * d, 0f, -1f)
        b.sheet.background = sheetBackground()
        b.sheetGrip.background = CssGradientDrawable(intArrayOf(0xFFA78BFA.toInt(), 0xFFF472B6.toInt(), 0xFFFDBA74.toInt()), null, 90f, 2 * d)
        ViewCompat.setAccessibilityDelegate(b.wifiRow, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Switch::class.java.name
                info.isCheckable = true
                info.isChecked = wifiOnly
            }
        })
        applyTarget(Prefs.target(this).key)
        wifiOnly = Prefs.wifiOnly(this)
        showWifi(animate = false)
        b.targetRow.setOnClickListener { openSheet(SheetMode.TARGET) }
        b.wifiRow.setOnClickListener {
            wifiOnly = !wifiOnly
            Prefs.setWifiOnly(this, wifiOnly)                 // saved at once
            showWifi(animate = true)
            if (!wifiOnly) {                                  // Wi-Fi-only off: retry what waited for Wi-Fi
                Translate.waitingWifi.forEach { Translate.download(this, it) }
                MODELS.forEach { if (Llm.waitingWifi(it)) Llm.download(this, it) }
                refreshModels()
            }
        }
        b.dlBtn.setOnClickListener { openSheet(SheetMode.DOWNLOAD) }
        b.sheetScrim.setOnClickListener { closeSheet() }
    }

    /** Shows the target in the target row and the preview strip. */
    private fun applyTarget(key: String) {
        b.targetValue.text = Languages.targetTitle(key)
        b.trPreview.setTarget(key)
    }

    /** toggle 200 (.2,0,0,1): on track fades in, thumb slides 20dp. */
    private fun showWifi(animate: Boolean) {
        val x = if (wifiOnly) 20 * d else 0f
        val a = if (wifiOnly) 1f else 0f
        if (animate) {
            b.wifiThumb.animate().translationX(x).setDuration(TOGGLE).setInterpolator(Easing.EMPHASIZED)
            b.wifiTrackOn.animate().alpha(a).setDuration(TOGGLE)
        } else {
            b.wifiThumb.translationX = x
            b.wifiTrackOn.alpha = a
        }
    }

    private var modelRequest = 0   // latest refreshModels call; older (out-of-order) results are ignored

    /** Spec §4: the downloaded set comes from ML Kit every time; downloading / waiting come from Translate. */
    private fun refreshModels() {
        val request = ++modelRequest
        Translate.downloaded { done ->
            if (!isDestroyed && request == modelRequest) renderModels(modelRows(done, Translate.downloading, Translate.waitingWifi))
        }
    }

    /** Diffs [rows] into the well: removed / added rows animate (LayoutTransition), changed rows swap in place. */
    private fun renderModels(rows: List<Pair<String, ModelState>>) {
        val next = rows.toMap()
        val well = b.modelWell
        for (i in well.childCount - 1 downTo 0) if (well.getChildAt(i).tag.let { it !is LlmTag && it !in next }) well.removeViewAt(i)
        renderLlms()
        rows.forEachIndexed { rowIndex, (code, state) ->
            val index = rowIndex + MODELS.size                // LLM rows come first
            val existing = (0 until well.childCount).map { well.getChildAt(it) }.firstOrNull { it.tag == code }
            if (existing == null) {
                well.addView(modelRow(code, state), index)
            } else if (lastRows[code] != state) {
                val at = well.indexOfChild(existing)
                val transition = well.layoutTransition
                well.layoutTransition = null                  // state change: swap without the row animation
                well.removeViewAt(at)
                val row = modelRow(code, state)
                well.addView(row, at)
                well.layoutTransition = transition
                if (lastRows[code] == ModelState.DOWNLOADING && state == ModelState.DONE) sparkle(row)
            }
        }
        lastRows = next
    }

    private enum class LlmState { IDLE, DOWNLOADING, WAITING_WIFI, DONE }

    /** Tag of an LLM row in the well (never equal to an ML Kit code tag). */
    private data class LlmTag(val key: String)

    /** Last rendered (state, active) per model key; sub label per key for in-place progress updates. */
    private val lastLlm = HashMap<String, Pair<LlmState, Boolean>>()
    private val llmSub = HashMap<String, TextView>()

    private fun llmState(m: ModelSpec) = when {
        Llm.isDownloaded(this, m) -> LlmState.DONE
        Llm.downloading(m) -> LlmState.DOWNLOADING
        Llm.waitingWifi(m) -> LlmState.WAITING_WIFI
        else -> LlmState.IDLE
    }

    private fun llmSubText(m: ModelSpec, state: LlmState, active: Boolean): String {
        val text = when (state) {
            LlmState.IDLE -> getString(R.string.llm_size, String.format(Locale.US, "%.2f", m.size / 1e9))
            LlmState.DOWNLOADING -> getString(R.string.llm_downloading, (Llm.progress(m) * 100).toInt())
            LlmState.WAITING_WIFI -> getString(R.string.chip_waiting_wifi)
            LlmState.DONE -> getString(R.string.llm_done)
        }
        return if (active) getString(R.string.llm_active, text) else text
    }

    /** Llm state or progress changed: re-render rows whose state changed, else only update the % text. */
    private fun onLlmChanged() {
        renderLlms()
        val active = Prefs.activeModel(this)
        for (m in MODELS) if (llmState(m) == LlmState.DOWNLOADING) llmSub[m.key]?.text = llmSubText(m, LlmState.DOWNLOADING, m == active)
    }

    /** Keeps one row per LLM at the top of the well in sync with Llm and the active choice (same swap / sparkle as the ML Kit rows). */
    private fun renderLlms() {
        val active = Prefs.activeModel(this)
        val well = b.modelWell
        MODELS.forEachIndexed { index, m ->
            val now = llmState(m) to (m == active)
            val last = lastLlm[m.key]
            if (now == last) return@forEachIndexed
            val existing = (0 until well.childCount).map { well.getChildAt(it) }.firstOrNull { it.tag == LlmTag(m.key) }
            val row = llmRow(m, now.first, now.second)
            val transition = well.layoutTransition
            if (existing != null) { well.layoutTransition = null; well.removeView(existing) }
            well.addView(row, index)
            well.layoutTransition = transition
            if (last?.first == LlmState.DOWNLOADING && now.first == LlmState.DONE) sparkle(row)
            lastLlm[m.key] = now
        }
    }

    /** Tap = make [m] the active translator (and start its download if needed); the trash button deletes it. */
    private fun pickLlm(m: ModelSpec) {
        if (Prefs.activeModel(this) != m) {
            Prefs.setActiveModel(this, m.key)
            Llm.release()                                   // one engine at a time: free the previous model now
        }
        Llm.download(this, m)                               // no-op when downloaded or running; re-checks the network
        renderLlms()
    }

    private fun llmRow(m: ModelSpec, state: LlmState, active: Boolean): View {
        val grey = state == LlmState.WAITING_WIFI
        val row = LinearLayout(this).apply {
            tag = LlmTag(m.key)
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(px(10f), 0, px(4f), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(60f))
            if (active) background = round(withAlpha(0.10f, 0x7C3AED), 14 * d)
        }
        row.addView(disc(m.glyph, 14f, grey), LinearLayout.LayoutParams(px(38f), px(38f)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = true }
        titleRow.addView(label(m.title, 15f, true, if (grey) GREY else INK))
        titleRow.addView(label(m.badge, 12f, true, if (grey) GREY else SECONDARY).apply { setPaddingRelative(px(6f), 0, 0, 0) })
        texts.addView(titleRow)
        val subColor = when { state == LlmState.DOWNLOADING || active -> VIOLET; grey -> GREY; else -> SECONDARY }
        llmSub[m.key] = label(llmSubText(m, state, active), 12.5f, state == LlmState.DOWNLOADING || active, subColor).also { texts.addView(it) }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(12f) })
        row.addView(FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(px(44f), px(44f))
            when (state) {
                LlmState.DONE -> {
                    background = pressed(oval(withAlpha(0.16f, 0xF472B6)), null)
                    contentDescription = getString(R.string.model_delete, m.title)
                    isClickable = true
                    isFocusable = true
                    addView(ImageView(context).apply { setImageResource(R.drawable.ic_trash) }, FrameLayout.LayoutParams(px(20f), px(20f), Gravity.CENTER))
                    setOnClickListener { Llm.delete(this@SettingsActivity, m); renderLlms() }
                }
                LlmState.DOWNLOADING -> addView(SpinnerRingView(context).apply { strokePx = 3 * d }, FrameLayout.LayoutParams(px(22f), px(22f), Gravity.CENTER))
                LlmState.WAITING_WIFI -> addView(ImageView(context).apply { setImageResource(R.drawable.ic_chip_wifi); setColorFilter(GREY) },
                    FrameLayout.LayoutParams(px(20f), px(20f), Gravity.CENTER))
                LlmState.IDLE -> addView(ImageView(context).apply { setImageResource(R.drawable.ic_download); setColorFilter(VIOLET) },
                    FrameLayout.LayoutParams(px(20f), px(20f), Gravity.CENTER))
            }
        })
        row.setOnClickListener { pickLlm(m) }
        return row
    }

    /** prism-tide .mrow: 60dp, disc 38dp, title + small name, status line, right-side action / state. */
    private fun modelRow(code: String, state: ModelState): View {
        val waiting = state == ModelState.WAITING_WIFI
        val row = LinearLayout(this).apply {
            tag = code
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(px(10f), 0, px(4f), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(60f))
        }
        row.addView(disc(code, 14f, waiting), LinearLayout.LayoutParams(px(38f), px(38f)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = true }
        titleRow.addView(label(Languages.modelTitle(code), 15f, true, if (waiting) GREY else INK))
        titleRow.addView(label(Languages.modelSub(code), 12f, true, if (waiting) GREY else SECONDARY).apply { setPaddingRelative(px(6f), 0, 0, 0) })
        texts.addView(titleRow)
        val sub = when (state) {
            ModelState.BUILTIN -> R.string.model_builtin_sub
            ModelState.DONE -> R.string.model_done_sub
            ModelState.DOWNLOADING -> R.string.chip_downloading
            ModelState.WAITING_WIFI -> R.string.chip_waiting_wifi
        }
        val subColor = when (state) { ModelState.DOWNLOADING -> VIOLET; ModelState.WAITING_WIFI -> GREY; else -> SECONDARY }
        texts.addView(label(getString(sub), 12.5f, state == ModelState.DOWNLOADING, subColor))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(12f) })
        row.addView(modelAction(code, state))
        if (waiting) {                                       // tap a waiting row to retry (download() re-checks the network)
            row.setOnClickListener { Translate.download(this, code); refreshModels() }
        }
        return row
    }

    /** Right side of a model row: 內建 badge, trash (deletes at once, final pick), spinner, or wifi icon. */
    private fun modelAction(code: String, state: ModelState): View = when (state) {
        ModelState.BUILTIN -> label(getString(R.string.model_builtin), 12f, true, SECONDARY).apply {
            setPadding(px(10f), px(4f), px(10f), px(4f))
            background = round(withAlpha(0.10f, 0x7C3AED), -1f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = px(8f) }
        }
        ModelState.DONE -> FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(px(44f), px(44f))
            background = pressed(oval(withAlpha(0.16f, 0xF472B6)), null)
            contentDescription = getString(R.string.model_delete, Languages.modelTitle(code))
            isClickable = true
            isFocusable = true
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_trash) }, FrameLayout.LayoutParams(px(20f), px(20f), Gravity.CENTER))
            setOnClickListener { Translate.delete(code) { if (!isDestroyed) refreshModels() } }
        }
        ModelState.DOWNLOADING -> FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(px(44f), px(44f))
            addView(SpinnerRingView(context).apply { strokePx = 3 * d }, FrameLayout.LayoutParams(px(22f), px(22f), Gravity.CENTER))
        }
        ModelState.WAITING_WIFI -> FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(px(44f), px(44f))
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_chip_wifi); setColorFilter(GREY) },
                FrameLayout.LayoutParams(px(20f), px(20f), Gravity.CENTER))
        }
    }

    /** Language disc: 135° gradient of Languages.discColors with a white glyph; grey when waiting for Wi-Fi. */
    private fun disc(code: String, textSp: Float, grey: Boolean): TextView = TextView(this).apply {
        text = Languages.glyph(code)
        textSize = textSp
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        maxLines = 1
        setTextColor(if (grey) GREY else Color.WHITE)
        background = if (grey) oval(GREY_DISC) else CssGradientDrawable(Languages.discColors(code), null, 135f, -1f)
    }

    private fun label(s: CharSequence, sp: Float, bold: Boolean, color: Int): TextView = TextView(this).apply {
        text = s
        textSize = sp
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setTextColor(color)
        maxLines = 1
    }

    private fun px(dp: Float): Int = (dp * d).roundToInt()

    /** prism-tide sparkle 380: download done -> disc scale 1 -> 1.15 -> 1. */
    private fun sparkle(row: View) {
        val disc = (row as LinearLayout).getChildAt(0)
        disc.animate().scaleX(1.15f).scaleY(1.15f).setDuration(SPARKLE * 45 / 100).withEndAction {
            disc.animate().scaleX(1f).scaleY(1f).setDuration(SPARKLE * 55 / 100)
        }
    }

    /** prism-tide .sheet: white 97 %, 28dp top corners, box-shadow 0 -12px 40px #4C1D95 22 %. */
    private fun sheetBackground(): Drawable {
        val r = 28 * d
        val fill = GradientDrawable().apply {
            setColor(withAlpha(0.97f, 0xFFFFFF))
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        return GlowDrawable(fill, withAlpha(0.22f, 0x4C1D95), 40 * d, -12 * d, 0f, r)
    }

    /** sheetIn 300 (decelerate) + scrim fade; at most 74 % of the screen (prism-tide .sheet max-height). */
    private fun openSheet(mode: SheetMode) {
        sheetMode = mode
        b.sheetTitle.setText(if (mode == SheetMode.TARGET) R.string.tr_target else R.string.tr_predownload)
        b.sheetSub.setText(if (mode == SheetMode.TARGET) R.string.sheet_target_sub else R.string.sheet_dl_sub)
        fillSheet()
        (b.sheet.layoutParams as FrameLayout.LayoutParams).topMargin = (b.root.height * (1f - SHEET_MAX)).roundToInt()
        b.sheet.requestLayout()
        b.sheetScroll.scrollTo(0, 0)
        b.sheetScrim.visibility = View.VISIBLE
        b.sheet.visibility = View.VISIBLE
        b.sheetScrim.animate().cancel()
        b.sheet.animate().cancel()
        b.sheetScrim.animate().alpha(1f).setDuration(SHEET_IN).setInterpolator(Easing.EASE_OUT)
        b.sheet.translationY = b.root.height.toFloat()
        b.sheet.animate().translationY(0f).setDuration(SHEET_IN).setInterpolator(Easing.DECELERATE)
    }

    /** sheetOut 240 (accelerate); tap on the scrim or back. */
    private fun closeSheet() {
        if (sheetMode == SheetMode.NONE) return
        sheetMode = SheetMode.NONE
        b.sheetScrim.animate().alpha(0f).setDuration(SHEET_OUT).setInterpolator(Easing.EASE_IN)
            .withEndAction { b.sheetScrim.visibility = View.GONE }
        b.sheet.animate().translationY(b.sheet.height.toFloat()).setDuration(SHEET_OUT).setInterpolator(Easing.ACCELERATE)
            .withEndAction { b.sheet.visibility = View.GONE }
    }

    private fun fillSheet() {
        val list = b.sheetList
        list.removeAllViews()
        val langs = Translate.languages()
        if (sheetMode == SheetMode.TARGET) {
            val current = Prefs.target(this).key
            for (key in TargetLang.keys(langs)) {
                val selected = key == current
                list.addView(sheetRow(Languages.targetTitle(key), Languages.targetSub(key), TargetLang.of(key).mlkit, radio(selected), selected, true) { pickTarget(key) })
            }
        } else {
            for (code in TargetLang.modelKeys(langs)) {
                val state = lastRows[code]
                list.addView(sheetRow(Languages.modelTitle(code), Languages.modelSub(code), code, state?.let { stateLabel(it) }, false, state == null || state == ModelState.WAITING_WIFI) { pickDownload(code) })   // waiting rows stay pickable: download() re-checks the network
            }
        }
    }

    /** prism-tide .lrow: 58dp, radius 18dp, disc 34dp, title 15.5sp bold + sub 12.5sp, right-side view. */
    private fun sheetRow(title: String, sub: String, discCode: String, right: View?, selected: Boolean, enabled: Boolean, onPick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(px(12f), 0, px(14f), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(58f))
            background = if (selected) round(withAlpha(0.07f, 0x7C3AED), 18 * d) else pressed(round(withAlpha(0.08f, 0x7C3AED), 18 * d), null)
            isEnabled = enabled
            if (enabled) setOnClickListener { onPick() }
        }
        row.addView(disc(discCode, 13f, false), LinearLayout.LayoutParams(px(34f), px(34f)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 15.5f, true, if (enabled) INK else GREY))
        texts.addView(label(sub, 12.5f, false, SECONDARY))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(12f) })
        right?.let { row.addView(it) }
        return row
    }

    /** prism-tide .radio: #7C3AED 12 % dot; selected = gradient #7C3AED -> #F472B6 with glow and a white centre. */
    private fun radio(selected: Boolean): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(px(24f), px(24f))
        background = if (!selected) oval(withAlpha(0.12f, 0x7C3AED)) else LayerDrawable(
            arrayOf<Drawable>(
                GlowDrawable(CssGradientDrawable(intArrayOf(0xFF7C3AED.toInt(), 0xFFF472B6.toInt()), null, 135f, -1f), withAlpha(0.45f, 0xF472B6), 12 * d, 0f, 0f, -1f),
                oval(Color.WHITE),
            ),
        ).apply { setLayerInset(1, px(7f), px(7f), px(7f), px(7f)) }
    }

    /** Disabled download-sheet row state: 內建 / ✓ 已下載 / spinner 下載中 / 等待 Wi-Fi (12.5sp bold #6B5FA8). */
    private fun stateLabel(state: ModelState): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        when (state) {
            ModelState.DONE -> addView(ImageView(context).apply { setImageResource(R.drawable.ic_check) }, LinearLayout.LayoutParams(px(16f), px(16f)))
            ModelState.DOWNLOADING -> addView(SpinnerRingView(context).apply { strokePx = 2.5f * d }, LinearLayout.LayoutParams(px(16f), px(16f)))
            else -> Unit
        }
        val text = when (state) {
            ModelState.BUILTIN -> R.string.model_builtin
            ModelState.DONE -> R.string.sheet_done
            ModelState.DOWNLOADING -> R.string.sheet_downloading
            ModelState.WAITING_WIFI -> R.string.chip_waiting_wifi
        }
        addView(label(getString(text), 12.5f, true, SECONDARY).apply { setPaddingRelative(if (childCount > 0) px(6f) else 0, 0, 0, 0) })
    }

    private fun pickTarget(key: String) {
        Prefs.setTarget(this, key)
        applyTarget(key)
        fillSheet()                                           // move the radio before the sheet leaves
        main.postDelayed({ closeSheet() }, PICK_HOLD)
    }

    private fun pickDownload(code: String) {
        closeSheet()
        main.postDelayed({
            Translate.download(this, code)
            refreshModels()
        }, SHEET_OUT)
    }

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
        // translation card (prism-tide TIMINGS, ms)
        private const val TOGGLE = 200L
        private const val ROW_ANIM = 260L
        private const val SPARKLE = 380L
        private const val SHEET_IN = 300L
        private const val SHEET_OUT = 240L
        private const val PICK_HOLD = 220L
        private const val SHEET_MAX = 0.74f
        private val INK = 0xFF1E1B4B.toInt()
        private val SECONDARY = 0xFF6B5FA8.toInt()
        private val VIOLET = 0xFF7C3AED.toInt()
        private val GREY = 0xFF8A86A0.toInt()
        private val GREY_DISC = 0xFFE5E2EE.toInt()

        fun start(ctx: Context) {
            ctx.startActivity(
                Intent(ctx, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }
    }
}
