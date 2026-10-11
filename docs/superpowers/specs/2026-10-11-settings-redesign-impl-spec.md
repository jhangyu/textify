# Settings / permission / language / icon redesign — implementation spec

Status: implementation spec, derived from the approved design. Binding sources (later overrides earlier):
- Design decisions: `docs/superpowers/specs/2026-10-11-settings-redesign-design.md` (all sections; "Final picks" wins).
- Brief: `docs/design/settings-mockup-brief.md`.
- Chosen mockups (header comments + CSS/JS hold every value below):
  - `docs/design/mockups/settings/settings-final.html` — merged settings page, session settings button, transitions. Cited as **[SF]** (header), **[SF-css]** (style block), **[SF-js]** (script).
  - `docs/design/mockups/settings/permission-2-living-demo.html` — first-launch onboarding. Cited as **[P2]**, **[P2-css]**, **[P2-js]**.
  - `docs/design/mockups/settings/language-3-live-preview.html` — language-section style reference (already merged into [SF]). Cited as **[L3]**.
  - `docs/design/mockups/settings/icon-final-3-diagonal-veil.html` — icons. Cited as **[IC]**, **[IC-css]**, **[IC-svg]** (inline SVG in the script data `D.bub`).
  - `docs/design/mockups/a2-halo.html` — session visual source (already implemented). Cited as **[A2]**.

Units: CSS px in mockups = dp; CSS font px = sp. Colours written `#RRGGBB a%` mean that colour at that alpha; Android `#AARRGGBB` is given where the mockup gives it.
CSS angles (`linear-gradient(100deg, …)`) follow the CSS convention: 0deg = towards top, clockwise; the gradient line passes through the box centre and its length is `|w·sinθ| + |h·cosθ|`.

## 0. Project rules (apply to every component)
1. Unreleased project: no backward compatibility. Replaced code, resources, strings, prefs keys and docs are deleted completely (no migration of the old `script` pref key).
2. One code path for API 26–36. A `Build.VERSION.SDK_INT` branch is allowed only when the other side is unreachable (API does not exist). Every allowed branch is listed in §11 with its proof. The mockups' "RenderEffect blur API 31+ / concentric strokes 26–30", "setShadowLayer API 28+", "outline spot shadow tint API 28+" and "performHapticFeedback(REJECT) API 30+" suggestions are **not** used: glows use the existing single-path blurred-bitmap technique (`PillView.renderGlow`, `EdgeGlowView` mask) on all levels, haptics use `HapticFeedbackConstants.VIRTUAL_KEY` (since API 5) on all levels.
3. Light theme only. No dark resources, no `DayNight` parent.
4. No solid outline strokes as frames; every fade/transition ≤ 400 ms ([brief] §Style).
5. Third-party API claims below were verified against the installed artefacts (see §12). Anything not verifiable is marked **VERIFY** with a fallback.
6. All UI copy is Traditional Chinese, taken verbatim from the mockups. Copy that no mockup contains is marked **PROPOSED** (user may change the wording; it does not block implementation).

## 1. Current code facts (verified in the repo, commit `fbc1cb8`)
- `MainActivity.kt` (81 lines): Start/Stop buttons, `RadioGroup` script picker, requests `POST_NOTIFICATIONS` (API 33+), opens `ACTION_MANAGE_OVERLAY_PERMISSION` with `package:` Uri, launches `createScreenCaptureIntent()`, calls `CaptureService.start/stop`. Layout `res/layout/activity_main.xml`.
- `Prefs.kt`: `enum class Script { CHINESE, JAPANESE, KOREAN }`, single `script()` / `setScript()` on key `"script"` in prefs file `"textify"`.
- `Ocr.kt`: `Ocr.recognize(ctx, bitmap, script: Script, onResult: (List<OcrLine>) -> Unit)`; one recognizer; lines sorted top then left; failure = empty list. Callers: `SessionActivity.kt:100` only (plus none elsewhere).
- `CaptureService.kt`: private `instance`; `onSessionEnded()`, `start()`, `stop()` (only caller: `MainActivity.kt` stop button); notification uses `android.R.drawable.ic_menu_crop`, title `R.string.notif_title`, action `R.string.stop`; `if (SDK_INT >= O)` around `createNotificationChannel` and `if (SDK_INT >= Q) startForegroundService else startService` — both branches are dead/unjustified at minSdk 26 (`startForegroundService` since 26, verified `api-versions.xml`), so both checks are removed (§5).
- `SessionActivity.kt`: X button `@id/close` (40×40dp, top|end, marginTop 44dp, marginEnd 16dp, `session_x_bg`, elevation 4dp); `showClose()` = alpha via `fade(1f, XIN=240, EMPHASIZED)` + scale .6→1 + rotation −90→0 with `X_OVERSHOOT`; `close()` = root alpha →0 `CLOSE_OUT=300` with `CLOSE_CURVE (.3,0,.8,.15)` then `endSession(); finish()`.
- `Bubble.kt`: `fadeOut(onHidden)`, `fadeIn()` (bubbleIn 300, scale .7→1, `EMPHASIZED`); `bubble.xml` ImageView 56dp, padding 15dp, src `@drawable/ic_scan`.
- `PillView.kt`: private `renderGlow()` = blurred round rect on a half-scale software bitmap, inside cleared (CSS `box-shadow 0 0 12px`); fill `LinearGradient #A78BFA 20% → #F472B6 16%`.
- `EdgeGlowView.kt`: a2 halo; constants `GLOW_IN 350, GLOW_SETTLE 400, BLOOM 400, GLOW_LOOP 2400, BREATH 1200, RESIDUAL_SPIN .12, HALO_BLUR_DP 30, CORE_BLUR_DP 7, CORNER_DP 36`, colours/stops `#A78BFA 0, #F472B6 .2, #FDBA74 .38, #67E8F9 .56, #818CF8 .76, #A78BFA 1`; public `activate()`, `settle()`.
- Launcher: `mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml` both use `drawable/ic_launcher_bg.xml` (#3F6FFF) + `ic_launcher_fg.xml` (inset of `ic_menu_crop`). Manifest has `android:icon` only, no `android:roundIcon`.
- Themes: `Theme.Textify` (parent `Theme.AppCompat.DayNight`, `colorPrimary #3F6FFF`), `Theme.Textify.Session` (translucent, transparent background, `windowAnimationStyle @null`, `windowDisablePreview`, `colorControlActivated #7C3AED`).
- No unit tests and no test dependencies exist. README "How it works" describes the old `MainActivity`.

## 2. Launch flow (`MainActivity`, rewritten in place)
Source: design Decision 1, [brief] §New navigation 1, [P2-js].

`MainActivity` stays the launcher activity; it becomes a router plus the onboarding screen. Its old UI and every reference to `Prefs.script`, `CaptureService.stop`, `scriptGroup`, `startButton`, `stopButton` is deleted.

`onCreate`:
1. `enableEdgeToEdge(SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT), SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))` (light page in every system mode; signature verified in `androidx.activity:activity:1.9.3`, §12).
2. If `CaptureService.isRunning` → `SettingsActivity.start(this)`; `finish()`; return.
3. If `Settings.canDrawOverlays(this)` → do **not** call `setContentView` (window is translucent, nothing is drawn) → `requestCapture()`.
4. Else → `setContentView(onboarding)` and play the intro (§3).

`requestCapture()`:
- API 33+ and `POST_NOTIFICATIONS` not granted → launch the permission request; on its result (granted or not) launch consent. Otherwise launch consent directly. (Preserves current behaviour, serialised instead of two dialogs at once.)
- Consent intent: API 34+ `mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())`, else `mpm.createScreenCaptureIntent()` ([P2] NATIVE NOTES "API 34+ createConfigForDefaultDisplay()"; fork proof §11).
- Result `RESULT_OK && data != null` → `CaptureService.start(this, code, data)`; text toast `toast_started`; close.
- Otherwise → text toast `toast_consent_denied`; close.
- Close = if the onboarding root exists: root `alpha 1→0` over **appClose 300 ms** ([P2] TIMINGS) then `finish()`; else `finish()` immediately.

Toasts are `Toast.makeText(...)` text toasts only. The mockups' orb-icon toast is not built: custom-view toasts posted from the background are blocked for apps targeting API 30+ (verified, §12) and the activity is finishing when they show.

## 3. Onboarding screen (`activity_main.xml`, rewritten)
Source: [P2] (header + css + js). Shown only when the overlay permission is missing.

### 3.1 Layout (dp / sp)
- Root: full screen, background = `MistBackground` (§8.3) with spots [P2-css `#sIntro`]: `#A78BFA 30%` ellipse 380×300 at (20%, 10%); `#67E8F9 22%` 360×300 at (90%, 40%); `#FDBA74 20%` 300×240 at (30%, 60%); base `#FAF5FF`; each spot fades to transparent at 70% of its radius. Content is padded by system-bar insets.
- Stage box [P2-css `.stagebox`]: left/right margin 26, top 54 (below the status-bar inset), height 420, corner 34, fill `LinearGradient 160deg #A78BFA 14% → #67E8F9 12%`, clips children. Contains the `CaptureDemoView` (§8.2, recipe `ONBOARDING`) and the denied chip.
- Mini screen [P2-css `.mini`]: 212×400, centred horizontally, top 26, corners 26 top-left/top-right only, fill `#F3F5F8`, shadow `#4C1D95 30%` blur 40 dy 18 spread −12. Header 42 high, `#FFFFFF`, padding 0 10, gap 8: avatar 22dp circle `LinearGradient 135deg #FFB27A → #FF6F91`, label `demo_header` 11sp weight 600 `#1D2330`. Messages: padding 10, gap 8; message bubble padding 6/9, corner 12, max width 82%, 10.5sp line-height 1.45, `#FFFFFF` on `#1D2330`; "me" bubbles right-aligned `#2F6BFF` with `#FFFFFF` text. Messages (in order, me = right): `demo_line_1`, `demo_line_2`, `demo_line_3` (me), `demo_line_4`, `demo_line_5`, `demo_line_6` (me).
- Mini bubble [P2-css `.mb`]: 36dp, right 6, top 176, bubble fill as `bubble_bg.xml`, glyph `@drawable/ic_scan` 20dp; tap dot `.mt` same box, `rgba(120,110,150,.32)` = `#786E96 32%`.
- Ghost [P2-css `.ghost`]: grey bubble 36dp at the same place, `#FFFFFF` fill, glyph grayscale, alpha .45; peach pulse ring 60dp at right −6, top 164: radial `#FDBA74 0%` at 45% → `#FDBA74 55%` at 62% → `#FDBA74 0%` at 75%.
- Denied chip [P2-css `.chip`]: centred at 50%/44% of the stage box, padding 9/16, full-round, `#FFFFFF 94%`, text `demo_chip` 13sp weight 600 `#9A3412`, glow `#FDBA74 35%` blur 24 dy 8.
- Sheet [P2-css `.sheet`]: from top 448 to the bottom, corners 32 top only, `#FFFFFF 94%`, shadow `#7C3AED 14%` blur 40 dy −10; padding 26 top, 28 sides. Children, top to bottom:
  1. `StepTrackView` (§8.4).
  2. Title 26sp bold, line-height 1.28, letter-spacing −.01em, margin 20 top / 10 bottom, `#1E1B4B`.
  3. Lead 15sp, line-height 1.6, `#4C4380`.
  4. Facts row [P2-css `.facts`]: wrap, gap 8, margin-top 18; chips padding 7/12, full-round, `LinearGradient 135deg #A78BFA 14% → #F472B6 10%`, 12.5sp `#4C1D95`: `onboard_fact_1`, `onboard_fact_2`, `onboard_fact_3`.
  5. Actions (bottom-anchored, padding 16 top / 34 bottom + nav-bar inset, gap 10): "later" button height 56, padding 0/20, full-round, `#7C3AED 8%`, 15sp `#4C1D95`, text `onboard_later`; CTA fills remaining width, height 56, full-round, 16.5sp bold `#FFFFFF`, background `HaloStyle.cta` (§8.3).
  6. Ready line [P2-css `.ready`]: centred, bottom 56, 14sp `#6B5FA8`, text `onboard_ready` + three 6dp dots `#A78BFA` margin 0/2, dot alpha loop .3→1→.3 over 1200 ms (peak at 30%), delays 0/150/300 ms; hidden (alpha 0) except in state granted.

### 3.2 States and copy [P2-js `COPY`]
| state | title | lead | CTA |
|---|---|---|---|
| intro | `onboard_title` 一點泡泡，⏎畫面上的字都能複製 | `onboard_lead` | `perm_cta` 開啟顯示權限 |
| denied | `perm_denied_title` 泡泡還出不來 | `onboard_denied_lead` | `onboard_cta_retry` 再開一次設定 |
| granted | `perm_granted_title` 泡泡已就位 | `onboard_granted_lead` | (hidden) |

Visuals per state [P2-css]: intro = demo live, track empty, node 1 "current" (gradient `135deg #A78BFA → #F472B6`, white text, glow `#F472B6 45%` r12). denied = demo ghost (§8.2), node 1 peach. granted = track fills, node 1 check, node 2 gradient; CTA + later fade to alpha 0 over 220 ms and become disabled; facts alpha .4 (220 ms); ready line alpha 1 (220 ms).

### 3.3 Behaviour and timings [P2] TIMINGS
- Intro: three blocks (status-bar spacer, stage box, sheet) `alpha 0→1, translationY 14dp→0`, **introIn 360**, stagger **60** (block i delay = 60·i), curve `(.2,0,0,1)`.
- CTA tap → `startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))`. On API 30+ the system ignores the Uri and opens the app list (verified, §12); copy already tells the user to tap Textify in the list. No branch.
- `onResume`: read `canDrawOverlays`; schedule one re-read after **300 ms** (header "300 ms recheck"); applied on all API levels (one path). On change: copy swap = old text alpha →0 **copyOut 120** (ease-in), set text, alpha →1 **copyIn 220**; state visuals switch (ghostSwap 300, trackFill 400, nodeCheck 260). Granted → after **grantedHold 900** → `requestCapture()`. Denied → stay.
- Later button and system back → text toast `toast_later`; close (§2).
- `onPause` → `CaptureDemoView.pause()`; `onResume` → `resume()`.

## 4. Session settings button (`SessionActivity`, `activity_session.xml`)
Source: [SF] NATIVE NOTES "Session button" and "Transition", [SF-css `.sbtn`, `.session.toset`].
- New `FrameLayout @+id/settings` 40×40dp, `layout_gravity top|end`, marginTop 44dp, marginEnd **68dp** (16 + 40 + 12), background `@drawable/session_x_bg`, elevation 4dp, clickable/focusable, contentDescription `session_settings` 設定; child ImageView 20dp `@drawable/ic_session_settings` (§9.6). Placed after `@id/close` in z-order.
- Enter = same animator as X (`alpha 0→1` 240 ms `EMPHASIZED`, scale .6→1, rotation −90→0, `X_OVERSHOOT`) with start delay **btnStagger 40**. Initial state as X (alpha 0, scale .6, rotation −90, disabled).
- Tap (allowed during OCR and after; ignored while `closing`): `closing = true`; finish any ActionMode; settings button `rotation → 90`, `scale → 1.08` over **sessionOut 300** (`[SF-css] .session.toset`; its extra box-shadow glow is not built); root `alpha 1→0` over **sessionOut 300** curve `(.3,0,.8,.15)`; end action, in this order: `CaptureService.setSettingsVisible(true)`, `SettingsActivity.start(this)`, `endSession()`, `finish()`.
- X path unchanged. `Ocr.recognize` call changes to `Ocr.recognize(this, bmp, Prefs.scripts(this)) { … }`.
- Copy changes: `session_close` 關閉擷取 ([SF] aria-label), `session_no_text` 沒有偵測到文字, `session_no_text_sub` 點 ✕ 關閉 (**PROPOSED** — [A2] has mixed English/Chinese; design Decision 7 requires zh-TW).

## 5. Capture service (`CaptureService.kt`)
Source: design Decisions 1–2, [SF] "Leaving", [IC] NATIVE NOTES "Notification".

Frozen companion API (all main thread):
```kotlin
val isRunning: Boolean                      // instance != null
fun setSettingsVisible(visible: Boolean)    // no-op if not running
fun onSessionEnded()                        // existing; now skips fadeIn while settings is visible
fun start(ctx: Context, code: Int, data: Intent)   // always ctx.startForegroundService(i)
```
`stop(ctx)` is deleted (no caller after §2). Instance state adds `settingsVisible = false`.

- `setSettingsVisible(true)`: `settingsVisible = true`; cancel a pending delayed fade-in; `bubble?.fadeOut {}` (harmless if already hidden).
- `setSettingsVisible(false)`: `settingsVisible = false`; if `busy` return (the session end will fade in). If `Settings.canDrawOverlays(this)` → post **bubbleDelay 120** ms, then if still `!settingsVisible && !busy` → `bubble?.fadeIn()` (bubbleIn 300, alpha 0→1, scale .7→1 — existing). Else → text toast `toast_bubble_hidden` 未授權懸浮權限，泡泡暫時無法顯示; bubble stays hidden.
- `sessionEnded()`: `busy = false`; `if (!settingsVisible) bubble?.fadeIn()`.
- Notification [IC]: `setSmallIcon(R.drawable.ic_stat_textify)`, `setColor(0xFF7C3AED.toInt())`, title `notif_title` Textify 正在執行, text `notif_text` 輕觸浮動泡泡即可擷取畫面上的文字；把泡泡拖到底部的 ✕ 可停止。 ([IC] shade mock), action label `notif_stop` 停止 (**PROPOSED**: the mock shade has no action; the existing stop action is kept). Channel creation without SDK check.
- Known platform behaviour (no code): on Android 15 QPR1+ the projection stops when the screen locks or the status-bar chip is tapped; the existing `MediaProjection.Callback.onStop → stopSelf()` covers it (verified, §12).

## 6. OCR multi-select and merge (`Prefs.kt`, `Ocr.kt`)
Source: design Decision 5, [brief] §New navigation 4.

### 6.1 Prefs
```kotlin
enum class Script { CHINESE, JAPANESE, KOREAN }           // unchanged
object Prefs {
    fun scripts(ctx: Context): Set<Script>               // key "scripts" (StringSet of enum names); unknown names dropped; empty or missing -> setOf(CHINESE)
    fun setScripts(ctx: Context, scripts: Set<Script>)   // require(scripts.isNotEmpty())
}
```
Old key `"script"`, `script()`, `setScript()` deleted. Returned set iterates in enum order.

### 6.2 Ocr
```kotlin
data class OcrLine(val text: String, val box: Rect)       // unchanged
object Ocr {
    fun recognize(ctx: Context, bitmap: Bitmap, scripts: Set<Script>, onResult: (List<OcrLine>) -> Unit)
}
// OcrMerge.kt (pure Kotlin, no android.* types, JVM-testable)
internal data class OcrCandidate(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val confidence: Float)
internal fun mergeOcr(candidates: List<OcrCandidate>): List<OcrCandidate>
```
- One `InputImage.fromBitmap(bitmap, 0)`; one recognizer per selected script, all `process()` calls started immediately (parallel). Each recognizer is closed in its own complete listener. A recognizer's failure (listener or synchronous throw) contributes an empty list. `onResult` is called exactly once on the main thread after all have completed.
- Candidates: every `Text.Line` with non-null `boundingBox` and non-blank text, `confidence = line.confidence` (`Text.Line.getConfidence(): Float`, verified in the installed jar, §12).
- `mergeOcr`: sort by confidence descending, then text length descending, then input order (stable); accept a candidate unless its IoU with an already accepted candidate is **> 0.5** (strictly greater); return accepted, sorted by `top`, then `left`. IoU = intersection area / union area on integer pixel rects; zero union → 0.
- **VERIFY** (cannot be checked offline): whether confidence values of the Chinese, Japanese and Korean models are on a comparable scale. Fallback already built in: equal confidences (e.g. all 0) fall through to "longer text wins", which is the design's stated fallback. No extra code.
- **VERIFY** on device: memory/time of three bundled recognizers running at once on a full-resolution screenshot. Fallback if it fails (OOM or > 2× single-model time): run the selected recognizers sequentially in the same function; the merge does not change.

## 7. Settings screen (`SettingsActivity`, `activity_settings.xml`)
Source: [SF] all sections.

### 7.1 Activity, entry and exit
```kotlin
class SettingsActivity : AppCompatActivity() {
    companion object { fun start(ctx: Context) }   // Intent flags NEW_TASK | NO_ANIMATION
}
```
- Manifest: `exported=false`, theme `Theme.Textify.Translucent` (§10), `excludeFromRecents=true`, `taskAffinity=""` (own task, same as the session, so finishing returns to the app the user was in).
- `onCreate`: `enableEdgeToEdge` as §2; root padded by system-bar insets; content starts at `alpha 0, scale 1.04` and animates to `alpha 1, scale 1` over **settingsIn 320** curve `(0,0,.2,1)`; after settingsIn the preview lines light (§7.4).
- `onStart` → `CaptureService.setSettingsVisible(true)`.
- Leave (back arrow or system back): content `alpha 1→0`, `scale 1→1.04` over **settingsOut 260** curve `(.3,0,.8,.15)`, then `finish()`.
- `onStop` → if `openingSystemSettings` is false → `CaptureService.setSettingsVisible(false)`. `openingSystemSettings` is set true right before launching the overlay settings (§7.3) and false in `onResume`. `onDestroy` → `CaptureService.setSettingsVisible(false)` when the last call was `true` (covers the user never returning from system settings).
- Page [SF-css `.settings`]: `MistBackground` with spots `#A78BFA 35%` 360×300 at (0%, 0%); `#F472B6 25%` 320×300 at (100%, 8%); `#67E8F9 28%` 380×320 at (100%, 100%); `#FDBA74 28%` 340×300 at (0%, 96%); base `#FAF5FF`; transparent at 70%.
- Top bar [SF-css `.st-top`]: padding 10/12/4, gap 6; back button 48dp circle, `@drawable/ic_back` 24dp, pressed fill `#A78BFA 18%`, contentDescription `settings_back` 返回; title `settings_title` 設定 28sp bold, letter-spacing .02em, `#1E1B4B`.
- Body: platform `ScrollView` (no nested scrolling is needed; [SF] says NestedScrollView, the platform view is enough), padding 6/18/28, vertical gap 16, scrollbars hidden, `clipChildren=false` on content so glows draw outside cards.
- Card [SF-css `.card`]: `HaloStyle.card` = fill `#FFFFFF 78%` (`#C7FFFFFF`) corner 28, glow `#7C3AED 12%` blur 24 dy 10 spread −8; padding 16/16/18.
- Section title [SF-css `.sec`]: 13sp bold `#6B5FA8` letter-spacing .06em, margin 0/4/12; optional small part weight 500, alpha .8.
- Footer `settings_foot` 新設定會在下次擷取時生效: centred, 12.5sp `#6B5FA8`, margin-top 2.

### 7.2 Permission card — layout [SF-css permission section]
- Section `perm_section` 懸浮泡泡權限.
- Stage box: height 178, corner 24, `LinearGradient 160deg #A78BFA 14% → #67E8F9 12%`, clips children; contains `CaptureDemoView` recipe `SETTINGS`.
- Mini screen: 176×200, centred, top 18, corners 20 top only, `#F3F5F8`, shadow `#4C1D95 30%` blur 32 dy 14 spread −12. Header 30 high white, padding 0/8, gap 6, avatar 16dp gradient as §3.1, label `demo_header` 10sp weight 600 `#1D2330`. Messages padding 8, gap 6; bubble padding 4/7, corner 10, max width 86%, 9.5sp line-height 1.45. Lines: `demo_line_1`, `demo_line_2`, `demo_line_3` (me), `demo_line_4`, `demo_line_6` (me).
- Mini bubble 30dp at right 5, top 84, glyph 17dp; ghost bubble same place; peach pulse 60dp at right −10, top 69.
- Chip `demo_chip` 沒有權限，泡泡無法出現: centred in the stage box, padding 8/14, full-round, `#FFFFFF 94%`, 12.5sp weight 600 `#9A3412`, glow `#FDBA74 35%` blur 24 dy 8.
- `StepTrackView` margin 16/4/0.
- Status [SF-css `.pstat`]: margin 14/4/0; title 19sp bold; text 13sp line-height 1.55 `#6B5FA8` margin-top 3.
  - granted: `perm_granted_title` 泡泡已就位 / `perm_granted_text` 已允許顯示在其他應用程式上層。每次啟動泡泡時，系統仍會詢問是否允許擷取畫面。
  - denied: `perm_denied_title` 泡泡還出不來 / `perm_denied_text` 需要開啟「顯示在其他應用程式上層」，泡泡才能浮在其他 App 上。
- denied only — CTA [SF-css `.cta`]: margin-top 14, full width, height 52, full-round, 16sp bold white, `HaloStyle.cta`, text `perm_cta` 開啟顯示權限.
- granted only — switch row [SF-css `.prow`]: margin-top 12, padding 10/10/10/16, full-round, fill `#7C3AED 6%` (pressed 12%), label `perm_switch` 允許顯示在其他應用程式上層 14.5sp weight 600, switch 52×32 full-round on = `LinearGradient 90deg #A78BFA → #F472B6`, thumb 24dp `#FFFFFF` (glow `#1E1B4B 25%` blur 3 dy 1) at x 4+20; off fill `#1E1B4B 12%`, thumb at x 4; thumb move **toggle 200** `(.2,0,0,1)`. Accessibility role switch, checked = permission.

### 7.3 Permission card — behaviour
- State = `Settings.canDrawOverlays(this)`, read in `onResume` plus one re-read after 300 ms (same as §3.3).
- granted → demo live, track filled, node 1 check, node 2 gradient, switch row visible, CTA gone. denied → demo ghost (`ghostSwap 300`, `peachPulse 400 ×2`, loop paused), node 1 peach, CTA visible, switch row gone.
- CTA and switch row both: `openingSystemSettings = true`; start `ACTION_MANAGE_OVERLAY_PERMISSION` with `package:` Uri (same as §3.3).
- Return from system settings (first `onResume` after `openingSystemSettings`): stage box flash [SF-css `#permCard.flash`] — glow ring `#A78BFA 60% → 0%` spreading 0 → 14dp, 400 ms ease-out, drawn by `CaptureDemoView.flash()`.
- `onPause` → `demo.pause()`; `onResume` → `demo.resume()` (resume is a no-op while denied).

### 7.4 Language card [SF] (2), [SF-css language section], [L3]
- Section `lang_section` 辨識語言 + small `lang_section_hint` 可多選，至少一種.
- `LanguagePreviewView` (§8.5): height 214, corner 24, padding 10. Shot: `#FFFFFF`, corner 18, padding 10/12/0, gap 8, 13sp `#1D2330`, scrim overlay `#1E1B4B 5%`. Bar: avatar 24dp gradient as §3.1 + `preview_header` 旅行群組 12.5sp bold. Rows (text, side, tag, key): `preview_zh` 週五下午三點在車站集合 left `tag_zh` 中 zh; `preview_en` Flight JL 802 · Gate 34B right `tag_en` ABC en; `preview_ja` 東京駅 八重洲口で待ち合わせ left `tag_ja` 日 ja; `preview_ko` 감사합니다, 내일 봐요! right `tag_ko` 韓 ko. Row bubble padding 5/10, corner 12, `#F1F3F7` (right rows `#E8EEFC`), line-height 1.35.
- Toggle row [SF-css `.tg`]: margin-top 14, gap 10, three `LanguageToggleView` (§8.6) with equal weight: (`lang_zh` 中文, `lang_zh_sub` 繁體 · 简体), (`lang_ja` 日本語, `lang_ja_sub` 日文), (`lang_ko` 한국어, `lang_ko_sub` 韓文).
- Notice area [SF-css `.notice`], height 54: resting text `lang_latin_note` 英文 ABC 在任何一種語言下都能辨識 (12.5sp `#6B5FA8`, top 16, sides 4). Block capsule (hidden at rest): top 8, padding 10/14, corner 18, fill `#FFFFFF 94%`, glow `#A78BFA 28%` blur 16 plus `#4C1D95 10%` blur 18 dy 6, 13sp weight 600 `#1E1B4B`, left bar 6dp wide full height corner 3 `LinearGradient 180deg #F472B6 → #FDBA74`, gap 10, text `lang_block` 至少保留一種語言，才能辨識畫面上的文字.
- Timeline [SF-css `.tl`]: padding 14/14/12, corner 20, fill `#FAF5FF 90%`. Header row: `speed_label` 辨識時間 13sp weight 600 `#6B5FA8`; value 17sp weight 800 `#4C1D95` = `speed_fast` 最快 (n = 1) or `speed_slower` 稍慢 (n = 2, 3) [SF-js `SPEED`]. `SpeedLanesView` (§8.7) margin 10/0/6. Description 12.5sp line-height 1.55 `#1E1B4B 70%`: n = 1 `speed_desc_one` 只跑 1 個辨識模型，結果最快出現。; n ≥ 2 `speed_desc_many` (format `%1$d 個模型同時辨識同一張截圖；語言越多，辨識稍慢。`) [SF-js `DESC`].
- Behaviour:
  - Initial: toggles, preview, lanes and texts from `Prefs.scripts()` without animation; preview rows unlit until settingsIn ends, then `preview.setScripts(selected, animate = true)`.
  - Tap a toggle that is off → add script; tap one that is on and is not the last → remove; `Prefs.setScripts()` immediately (applies from the next capture, design Decision 2). Then `preview.setScripts`, `lanes.setScripts`, value text, description cross-fade (**hintCross 200**: alpha →0 200 ms, swap text, alpha →1 200 ms; only when n changed). Any successful toggle hides an open block capsule at once (msgOut).
  - Tap the last selected toggle → `toggle.reject()`; block capsule in: alpha 0→1, translationY −8dp→0, **msgIn 220** ease-out; resting text alpha →0 160 ms; auto-hide at msgIn + **blockHold 1500** with **msgOut 160** ease-in (alpha →0, translationY →−8dp), resting text back; scroll the capsule into view (`requestRectangleOnScreen`).

## 8. Shared visual components (new, one implementation each)
### 8.1 `EdgeGlowView` recipe generalisation
Source: existing a2 implementation + [SF] mini halo JS + [P2] mini halo JS + [L3]/[SF] residual edge.
```kotlin
data class HaloRecipe(
    val cornerDp: Float, val haloBlurDp: Float, val coreBlurDp: Float,
    val haloActiveDp: Float, val haloBreathDp: Float, val haloBloomDp: Float, val haloSettledDp: Float,
    val alphaActive: Float, val alphaSettled: Float,
    val coreDp: Float, val coreBreathDp: Float, val coreAlpha: Float,
    val residualSpin: Float, val extendBottomDp: Float,
) { companion object { val SESSION: HaloRecipe; val ONBOARDING_MINI: HaloRecipe; val SETTINGS_MINI: HaloRecipe; val PREVIEW_EDGE: HaloRecipe } }
class EdgeGlowView(context, attrs) {            // existing constructor kept for the XML use
    var recipe: HaloRecipe                       // default SESSION; set before first layout
    fun activate(); fun settle()                 // existing behaviour, now recipe-driven
    fun fadeOut(ms: Long)                        // alpha -> 0 over ms (ease-out cubic), then stops animating
    fun showSettled()                            // a = 1, mix = 1 immediately, keeps rotating at residualSpin
    fun clear()                                  // a = 0, stop, nothing drawn
}
```
Halo width = `(haloActive + haloBreath·b + haloBloom·bloom)·(1−mix) + haloSettled·mix`; halo alpha = `a·lerp(alphaActive, alphaSettled, mix)`; core width = `coreDp + coreBreath·b`, alpha `a·(1−mix)·coreAlpha`, skipped when `coreDp == 0`; spin speed = `lerp(1, residualSpin, mix)`; ring rect = `(0, 0, w, h + extendBottomDp)` with corner `cornerDp`. `b`, `bloom`, timings (glowIn 350, glowSettle 400, bloom 400, glowLoop 2400, breath 1200) unchanged.

| recipe | corner | haloBlur | coreBlur | haloActive | breath | bloom | settled | αActive | αSettled | core | coreBreath | coreα | residualSpin | extendBottom | source |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| SESSION | 36 | 30 | 7 | 44 | 10 | 70 | 26 | .75 | .22 | 12 | 4 | .9 | .12 | 0 | existing `EdgeGlowView` / [A2] |
| ONBOARDING_MINI | 26 | 18 | 4 | 26 | 0 | 40 | 16 | .75 | .30 | 7 | 0 | .9 | 1 | 40 | [P2-js] mini halo `ring(g,(26+40*bloom)*(1-mix)+16*mix,18,a*(.75-.45*mix))`, core `ring(g,7,4,…*.9)`, rect `H+40`, r 26 |
| SETTINGS_MINI | 20 | 15 | 3 | 22 | 0 | 34 | 14 | .75 | .30 | 6 | 0 | .9 | 1 | 30 | [SF-js] mini halo `(22+34*bloom)*(1-mix)+14*mix`, blur 15, core 6 blur 3, rect `H+30`, r 20 |
| PREVIEW_EDGE | 24 | 12 | 0 | 10 | 0 | 0 | 10 | .22 | .22 | 0 | 0 | 0 | .12 | 0 | [SF] "preview residual edge sweep alpha .22 blur 12dp"; [L3] "stroke 10dp … drift 20000 ms/turn" (2400 / .12 = 20000) |

Mini recipes: [SF]/[P2] blur values are the CSS canvas `blur()` px = dp. The session's visible output must not change (SESSION reproduces the current constants exactly).

### 8.2 `CaptureDemoView` (looping miniature capture)
Source: [P2] NATIVE NOTES "Demo", [SF] NATIVE NOTES "Permission card", [P2-css]/[SF-css] keyframes `pill`/`mpill`, `mb`, `mt`, `gp`.
```kotlin
class CaptureDemoView(context: Context, attrs: AttributeSet?) : FrameLayout {
    fun configure(spec: DemoSpec)
    fun setGranted(granted: Boolean, animate: Boolean)  // true = live loop; false = ghost frame + peach pulse x2, loop paused
    fun pause(); fun resume()                            // activity lifecycle; resume restarts the loop only when granted
    fun flash()                                          // §7.3 return flash
}
data class DemoSpec(val halo: HaloRecipe, val bubbleDp: Float, val bubbleRightDp: Float, val bubbleTopDp: Float,
                    val glyphDp: Float, val pulseDp: Float, val pulseRightDp: Float, val pulseTopDp: Float,
                    val pillPadXDp: Float, val pillPadYDp: Float) {
    companion object { val ONBOARDING: DemoSpec; val SETTINGS: DemoSpec }
}
```
- XML children supplied by the screen layout: `@id/demoScreen` (the mini screen; every `TextView` tagged `line` gets a pill, in layout order) and `@id/demoChip` (the denied chip, sibling inside the stage box is not required — it must be a child of this view). The view adds its own `EdgeGlowView` sized to `demoScreen`, and draws bubble, tap dot, ghost and pulse in `dispatchDraw`.
- ONBOARDING = (ONBOARDING_MINI, 36, 6, 176, 20, 60, −6, 164, 5, 2) [P2-css `.mb`, `.gp`, `.ln::before` left/right −5 top/bottom −2]. SETTINGS = (SETTINGS_MINI, 30, 5, 84, 17, 60, −10, 69, 4, 2) [SF-css `.mb`, `.gp`, `.mini .ln::before` −4/−2].
- One `ValueAnimator(0f..4800f)`, `INFINITE`, linear, duration **demoLoop 4800**; every element is a function of the loop time `p` (one invalidate per frame). Keyframes ([P2-css]/[SF-css], percent of 4800):
  - bubble: visible until 20% (960); scale .9 at 21% (1008, press 96 incl. back to 1 at 23%); 23%→27% (1104→1296, **bubbleOut 192**) alpha →0 scale →.7; hidden until 85% (4080); 85%→91% (**bubbleIn 288**) back to alpha 1 scale 1.
  - tap dot: alpha 0 scale 1.6 until 18%; 21% alpha 1 scale .8; 26% alpha 0.
  - halo: `activate()` at 1150, `settle()` at 1900, `fadeOut(288)` at 3800 (**haloIn 350, haloSettle 400, sessionOut 288**).
  - pill i (layout order): hidden (alpha 0, scale .9) until 31% + 72·i ms; 31%→37% (+72·i) alpha →1 scale →1 (**288**, stagger **72**); hold to 79%; 79%→85% (+72·i) alpha →0.
  - Pills: `PillPainter` (§8.3) with `#A78BFA 26% → #F472B6 22%`, glow `#A78BFA 30%` r8, full-round, rect = text line bounds (via `Layout.getLineBounds`/`getLineLeft/Right`) padded by `pillPadX/Y`.
- Ghost (`setGranted(false)`): live layer (bubble, dot, halo, pills) cross-fades out and ghost bubble (white fill, glyph grayscale via `ColorMatrix.setSaturation(0)`, alpha .45) + chip fade in, **ghostSwap 300**; peach pulse ring: alpha .9→0, scale .8→1.3, **400 ms** ease-out, played **2** times; the loop animator is paused. `setGranted(true)` reverses (ghostSwap 300) and restarts the loop at 0.
- `flash()`: 400 ms ring glow `#A78BFA 60%→0%`, spread 0→14dp around the view bounds, ease-out.

### 8.3 `Glow.kt` (shared painting helpers)
Source: existing `PillView.renderGlow`/fill; [SF] Alphas; [P2] Alphas.
```kotlin
internal fun withAlpha(alpha: Float, rgb: Int): Int                    // replaces the private `argb` copies in PillView.kt (round 1) and SessionActivity.kt (round 2); different name so both can coexist during round 1
internal fun glowBitmap(w: Float, h: Float, cornerPx: Float, blurPx: Float, color: Int, spreadPx: Float, clearInside: Boolean): Bitmap  // half-scale software BlurMaskFilter, padded by blurPx
class PillPainter(fromColor: Int, toColor: Int, glowColor: Int, glowRadiusPx: Float, cornerPx: Float /* < 0 = height/2 */) {
    fun setSize(w: Int, h: Int); fun draw(canvas: Canvas, alpha: Float, scale: Float)
}
class GlowDrawable(fill: Drawable, glowColor: Int, blurPx: Float, dyPx: Float, spreadPx: Float, cornerPx: Float /* < 0 = full-round */) : Drawable
class MistBackground(spots: List<MistSpot>, base: Int) : Drawable
data class MistSpot(val color: Int /* with alpha */, val cx: Float, val cy: Float /* fractions of bounds */, val rwDp: Float, val rhDp: Float)
object HaloStyle {
    fun cta(ctx: Context): Drawable     // LinearGradient 100deg #7C3AED -> #A855F7 55% -> #EC4899, full-round, glow #7C3AED 30% blur 26 dy 10
    fun card(ctx: Context): Drawable    // §7.1
    fun chip(ctx: Context): Drawable    // #FFFFFF 94% full-round, glow #FDBA74 35% blur 24 dy 8
    val ONBOARDING_SPOTS: List<MistSpot>; val SETTINGS_SPOTS: List<MistSpot>
}
```
- `PillView` uses `PillPainter(#A78BFA 20%, #F472B6 16%, #A78BFA 28%, 12dp, full)` — pixels identical to today.
- `MistBackground` spot = `RadialGradient` at (cx·w, cy·h), radius `rwDp`, stops `[color @ 0, transparent @ .7]`, local matrix scaling y by `rhDp / rwDp` (CSS `radial-gradient(W H at x y, c, transparent 70%)`).
- Glows draw outside the view bounds; parents set `clipChildren=false`, `clipToPadding=false` (as the session pill container does).

### 8.4 `StepTrackView` (2-node progress track)
Source: [P2-css]/[SF-css] `.track`, `.node`, `.trk`.
```kotlin
class StepTrackView(context: Context, attrs: AttributeSet?) : View {
    enum class State { CURRENT, DENIED, GRANTED }
    fun setState(state: State, animate: Boolean)
    fun setLabels(first: CharSequence, second: CharSequence)   // step_overlay 顯示泡泡, step_capture 擷取畫面
}
```
Row: gap 10, labels 12.5sp weight 600 `#6B5FA8`; node = 24dp circle, number 12sp (`1`, `2`), node-label gap 7; track flexible, height 6, corner 3, rest `#7C3AED 12%`, fill `LinearGradient 90deg #A78BFA → #F472B6 → #FDBA74` revealed by clip width 0→100% over **trackFill 400** `(.2,0,0,1)`. Node colours (transition **nodeCheck 260**): rest `#7C3AED 12%` / text `#4C1D95`; CURRENT node 1 = `135deg #A78BFA → #F472B6`, white, glow `#F472B6 45%` r12 (onboarding only uses CURRENT); DENIED node 1 = `#FDBA74 30%` / `#9A3412`, no glow; GRANTED node 1 = `#7C3AED 16%` / `#7C3AED` with check `@drawable/ic_check` 14dp instead of the number; GRANTED node 2 = `135deg #F472B6 → #FDBA74`, white, glow `#FDBA74 55%` r12; track filled. Settings uses DENIED / GRANTED only (no CURRENT state in [SF]).

### 8.5 `LanguagePreviewView`
Source: [SF] NATIVE NOTES "Language card", [SF-css] `.pv`, `.pln`, [SF-js] `lightLines`.
```kotlin
class LanguagePreviewView(context: Context, attrs: AttributeSet?) : FrameLayout {
    fun setScripts(scripts: Set<Script>, animate: Boolean)
}
```
- XML children: an `EdgeGlowView` is added by the view (recipe `PREVIEW_EDGE`, `showSettled()` when attached, `clear()` when detached), then the shot container from the layout. Rows are children tagged `zh`, `en`, `ja`, `ko`; each row has a text child tagged `text` and a tag child tagged `tag`.
- Lit rule: row `en` is lit whenever `scripts` is non-empty; `zh`/`ja`/`ko` lit when CHINESE/JAPANESE/KOREAN selected ([SF] header: English lights for any selection).
- Pill per row: `PillPainter(#A78BFA 20%, #F472B6 16%, #A78BFA 28%, 12dp, corner 12dp)` on rect = row bounds outset 6 horizontally / 3 vertically [SF-css `.pln .pill` inset −3 −6]. Light on: alpha 0→1, scale .96→1, **pillIn 260** ease-out; off: **pillOut 200** ease-in. Newly lit rows staggered `round(i/(n−1)·140)` ms over the n rows turning on (**boxStagger 140**), 0 when n = 1. Tag (`9.5sp bold`, padding 1/6, corner 8, `#FFFFFF`, text `#7C3AED`, shadow `#4C1D95 12%` r4, at row right −6 / top −9) alpha 0↔1 over 200 ms; unlit text alpha .5, lit 1, 200 ms.

### 8.6 `LanguageToggleView`
Source: [SF-css] `.pl`, `.rubber`, `.rflash`; [L3] TIMINGS.
```kotlin
class LanguageToggleView(context: Context, attrs: AttributeSet?) : FrameLayout {
    var checked: Boolean                  // setter animates the fill (toggleFill 240); set via setChecked(v, animate)
    fun setChecked(value: Boolean, animate: Boolean)
    fun reject()                          // rubber + flash + haptic
}
```
- XML children: glyph `TextView` tagged `glyph` (20sp weight 800), sub `TextView` tagged `sub` (11.5sp weight 600), gap 2, centred. Height 78dp, corner 24.
- Off: fill `#7C3AED 7%`, glyph `#1E1B4B 60%`, sub `#1E1B4B 55%`. On: fill `LinearGradient 135deg #7C3AED → #F472B6` + glow `#F472B6 30%` blur 18 (via `GlowDrawable`), both texts `#FFFFFF`. Switching cross-fades the on layer and text colours over **toggleFill 240** ease-out. Pressed: scale .97.
- `reject()`: scale keyframes 1 → .92 (30%) → 1.04 (65%) → 1 over **shake 360** ease-out; flash ring `#F472B6 0% → 55% (at 35%, spread 7dp) → 0%` over **rejectFlash 380**; `performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)`.
- Accessibility: class name `android.widget.CheckBox`, checkable, checked state = `checked`.

### 8.7 `SpeedLanesView`
Source: [SF] NATIVE NOTES "Lanes", [SF-css] `.lane`.
```kotlin
class SpeedLanesView(context: Context, attrs: AttributeSet?) : View {
    fun setScripts(scripts: Set<Script>, animate: Boolean)
}
```
Up to three rows in fixed order zh, ja, ko; a row is shown when its script is selected. Row: height 0 ↔ 16dp + bottom margin 5dp, alpha 0 ↔ 1; label (`中`, `日`, `韓` from strings `tag_zh`, `tag_ja`, `tag_ko`, passed in by the activity via `setLabels`) 18dp wide, 11sp bold `#6B5FA8`, centred, gap 8; track flexible, height 12, corner 6, `#7C3AED 8%`; bar width = 58% / 64% / 70% of the track for 1 / 2 / 3 selected scripts ("illustrative, not measured", [SF]); bar gradients `zh #A78BFA → #818CF8`, `ja #F472B6 → #FDBA74`, `ko #67E8F9 → #818CF8`. Height, alpha and width animate over **laneGrow 300** curve `(.2,.8,.2,1)`. The view's measured height follows the animated row heights. Additional API: `fun setLabels(zh: CharSequence, ja: CharSequence, ko: CharSequence)`.

## 9. Icons (VectorDrawables, from [IC] VECTOR DATA — copy path data byte-for-byte)
### 9.1 `drawable/ic_launcher_background.xml` — viewport 108×108, width/height 108dp
Four paths, all `M0,0h108v108h-108z`:
1. fill `#FAF5FF`.
2. linear (18,18)→(90,90): `#BFA78BFA` 0, `#9EF472B6` .35, `#B3FDBA74` .65, `#B367E8F9` 1.
3. radial centre 54,54 r31: `#FFFFFFFF` 0, `#D9FFFFFF` .85, `#00FFFFFF` 1.
4. radial centre 54,54 r64: `#00FAF5FF` .6, `#99FAF5FF` 1.
### 9.2 `drawable/ic_launcher_foreground.xml` — viewport 108×108
1. disc shadow, fill radial centre 54,56 r31: `#247C3AED` 0, `#247C3AED` .82, `#007C3AED` 1; path `M54,23a31,31 0 1,1 0,62a31,31 0 1,1 0,-62Z`.
2. disc, fill radial centre 54,44 r34: `#FFFFFFFF` .55, `#FFF3EDFF` 1; path `M54,27a27,27 0 1,1 0,54a27,27 0 1,1 0,-54Z`.
3. glyph inside `<group translateX="33" translateY="33" scaleX="1.75" scaleY="1.75">`, stroke `#7C3AED` width 2.2, round cap/join, no fill: `M4,8V6a2,2 0,0 1,2 -2h2M16,4h2a2,2 0,0 1,2 2v2M20,16v2a2,2 0,0 1,-2 2h-2M8,20H6a2,2 0,0 1,-2 -2v-2M8.5,9h7M12,9v7`.
### 9.3 `drawable/ic_launcher_monochrome.xml` — viewport 108×108
`<group translateX="27" translateY="27" scaleX="2.25" scaleY="2.25">`, the same glyph path, stroke `#FF000000` width 2.2, round cap/join (glyph only).
### 9.4 Adaptive icon
- `mipmap-anydpi-v26/ic_launcher.xml`: `<adaptive-icon>` background `@drawable/ic_launcher_background`, foreground `@drawable/ic_launcher_foreground`, monochrome `@drawable/ic_launcher_monochrome` (ignored below API 33, [IC] NATIVE NOTES).
- Manifest `android:icon="@mipmap/ic_launcher"` and `android:roundIcon="@mipmap/ic_launcher"` (design "Final picks": no separate round variant). The [IC] `ic_launcher_round*` data is **not** used.
- Deleted: `drawable/ic_launcher_bg.xml`, `drawable/ic_launcher_fg.xml`, `mipmap-anydpi-v26/ic_launcher_round.xml`. No PNG mipmaps (minSdk 26).
### 9.5 Bubble glyph `drawable/ic_scan.xml` (Lumen Pill) — viewport 24×24, 24dp
1. brackets, stroke width 2.4, round cap/join, stroke `linear (4,4)→(20,20) #7C3AED → #4C1D95`: `M4,8V6a2,2 0,0 1,2 -2h2M16,4h2a2,2 0,0 1,2 2v2M20,16v2a2,2 0,0 1,-2 2h-2M8,20H6a2,2 0,0 1,-2 -2v-2`.
2. group `stem` (pivot irrelevant): fill `#7C3AED` `M12,9.5a1.1,1.1 0 0 1 1.1,1.1v5.1a1.1,1.1 0 0 1 -2.2,0v-5.1a1.1,1.1 0 0 1 1.1,-1.1z`.
3. group `pill` (pivotX 7.6): fill `linear (7.6,0)→(16.4,0) #A78BFA 0, #F472B6 .55, #FDBA74 1`: `M9.4,7.6h5.2a1.8,1.8 0 0 1 0,3.6h-5.2a1.8,1.8 0 0 1 0,-3.6z`.
4. group `shineClip` with `<clip-path>` = the pill path; inner group `shine` (translateX animated): rect `M7.6,7.6h4v3.6h-4z`, fill `linear (7.6,0)→(11.6,0) #00FFFFFF 0, #E6FFFFFF .5, #00FFFFFF 1` ([IC-svg] `g3-shine` stop-opacity .9 = `E6`), initial `fillAlpha 0`.
- `drawable/avd_scan_in.xml` (AnimatedVectorDrawable on `ic_scan`) [IC] TIMINGS "Bubble glyph entrance": `pill` scaleX 0→1 300 ms `(.2,0,0,1)`; `stem` translateY −3→0 and fillAlpha 0→1, 220 ms, delay 120, `(.2,0,0,1)`; `shine` translateX −4→10, 380 ms, delay 260, `(.4,0,.2,1)`, with shine fillAlpha 1 during and 0 at the end ([IC-css] `d3sh`).
- `layout/bubble.xml`: ImageView padding 15dp → **9dp**, src `@drawable/avd_scan_in`; `bubble_bg.xml` unchanged.
- `Bubble.fadeIn()` restarts the entrance: `(icon.drawable as AnimatedVectorDrawable).run { reset(); start() }`.
- The mini bubbles in §8.2 draw `@drawable/ic_scan` (static, final frame: stem/pill visible, shine alpha 0).
### 9.6 Other vectors
- `drawable/ic_stat_textify.xml` (Prism Frame) viewport 24, 24dp, stroke `#FFFFFF` width 2, round cap/join, no fill: `M4,8V6a2,2 0,0 1,2 -2h2M16,4h2a2,2 0,0 1,2 2v2M20,16v2a2,2 0,0 1,-2 2h-2M8,20H6a2,2 0,0 1,-2 -2v-2M8.5,9h7M12,9v7` [IC].
- `drawable/ic_session_settings.xml` viewport 24, 20dp, stroke `#4C1D95` width 2.2, round caps, no fill [SF]: `M5,8H12M16,8H19M5,16H8M12,16H19` plus circles r2 at (14,8) and (10,16) as arc paths `M14,6a2,2 0 1,1 0,4a2,2 0 1,1 0,-4M10,14a2,2 0 1,1 0,4a2,2 0 1,1 0,-4`.
- `drawable/ic_back.xml` viewport 24, 24dp, stroke `#4C1D95` width 2.2, round cap/join [SF] `.st-top`: `M19,12H5M11,6l-6,6 6,6`.
- `drawable/ic_check.xml` viewport 24, 14dp, stroke `#7C3AED` width 3, round cap/join [SF] `.ck`: `M5,12.5l4.5,4.5L19,7.5`.
- Notification small icon is white-on-transparent (brief rule) and tinted by `setColor` in the shade.

## 10. Resources: strings, themes, manifest, README
### 10.1 `values/strings.xml` — complete final content (all others deleted)
| name | value | source |
|---|---|---|
| app_name | Textify | existing |
| notif_title | Textify 正在執行 | [IC] shade |
| notif_text | 輕觸浮動泡泡即可擷取畫面上的文字；把泡泡拖到底部的 ✕ 可停止。 | [IC] shade |
| notif_stop | 停止 | PROPOSED |
| session_close | 關閉擷取 | [SF] |
| session_settings | 設定 | [SF] |
| session_no_text | 沒有偵測到文字 | [A2] Chinese part |
| session_no_text_sub | 點 ✕ 關閉 | PROPOSED |
| toast_started | Textify 已啟動 · 點一下泡泡即可擷取文字 | [P2-js] |
| toast_consent_denied | 未允許擷取畫面，Textify 已關閉 | [P2-js] |
| toast_later | 開啟權限後才能使用泡泡 | [P2-js] |
| toast_bubble_hidden | 未授權懸浮權限，泡泡暫時無法顯示 | design / [SF] |
| onboard_title | 一點泡泡，\n畫面上的字都能複製 | [P2-js] |
| onboard_lead | 泡泡要能浮在其他 App 上層，需要你開啟「顯示在其他應用程式上層」權限。 | [P2-js] |
| onboard_denied_lead | 系統設定裡 Textify 的開關仍是關閉的。在清單中點 Textify，打開開關後按返回即可。 | [P2-js] |
| onboard_granted_lead | 最後一步：系統會詢問是否允許擷取畫面。Android 每次啟動泡泡都會問一次。 | [P2-js] |
| onboard_cta_retry | 再開一次設定 | [P2-js] |
| onboard_later | 稍後 | [P2] |
| onboard_fact_1 | 只在點泡泡時讀取畫面 | [P2] |
| onboard_fact_2 | 文字辨識在裝置上完成 | [P2] |
| onboard_fact_3 | 拖到底部 ✕ 即關閉 | [P2] |
| onboard_ready | 準備螢幕擷取 | [P2] |
| step_overlay | 顯示泡泡 | [P2]/[SF] |
| step_capture | 擷取畫面 | [P2]/[SF] |
| demo_header | 設計小組 | [P2]/[SF] |
| demo_line_1 | 下週的 workshop 資料好了嗎？ | [P2]/[SF] |
| demo_line_2 | 地點：松仁路 100 號 12F | [P2]/[SF] |
| demo_line_3 | 好，晚點傳 slides 給大家 | [P2]/[SF] |
| demo_line_4 | Wi-Fi：Textify-2026 | [P2]/[SF] |
| demo_line_5 | 報名截止 10/18 (Sat) | [P2] |
| demo_line_6 | 收到，謝謝！ | [P2]/[SF] |
| demo_chip | 沒有權限，泡泡無法出現 | [P2]/[SF] |
| perm_section | 懸浮泡泡權限 | [SF] |
| perm_granted_title | 泡泡已就位 | [SF]/[P2] |
| perm_granted_text | 已允許顯示在其他應用程式上層。每次啟動泡泡時，系統仍會詢問是否允許擷取畫面。 | [SF] |
| perm_denied_title | 泡泡還出不來 | [SF]/[P2] |
| perm_denied_text | 需要開啟「顯示在其他應用程式上層」，泡泡才能浮在其他 App 上。 | [SF] |
| perm_cta | 開啟顯示權限 | [SF]/[P2] |
| perm_switch | 允許顯示在其他應用程式上層 | [SF] |
| settings_title | 設定 | [SF] |
| settings_back | 返回 | [SF] |
| settings_foot | 新設定會在下次擷取時生效 | [SF] |
| lang_section | 辨識語言 | [SF] |
| lang_section_hint | 可多選，至少一種 | [SF] |
| lang_zh / lang_zh_sub | 中文 / 繁體 · 简体 | [SF] |
| lang_ja / lang_ja_sub | 日本語 / 日文 | [SF] |
| lang_ko / lang_ko_sub | 한국어 / 韓文 | [SF] |
| lang_latin_note | 英文 ABC 在任何一種語言下都能辨識 | [SF] |
| lang_block | 至少保留一種語言，才能辨識畫面上的文字 | [SF] |
| preview_header | 旅行群組 | [SF] |
| preview_zh / preview_en / preview_ja / preview_ko | 週五下午三點在車站集合 / Flight JL 802 · Gate 34B / 東京駅 八重洲口で待ち合わせ / 감사합니다, 내일 봐요! | [SF] |
| tag_zh / tag_en / tag_ja / tag_ko | 中 / ABC / 日 / 韓 | [SF] |
| speed_label | 辨識時間 | [SF] |
| speed_fast / speed_slower | 最快 / 稍慢 | [SF-js] |
| speed_desc_one | 只跑 1 個辨識模型，結果最快出現。 | [SF-js] |
| speed_desc_many | %1$d 個模型同時辨識同一張截圖；語言越多，辨識稍慢。 | [SF-js] |

The settings mini screen uses five lines (1, 2, 3, 4, 6; [SF] has no `demo_line_5`). Deleted names: `start`, `stop`, `script_label`, `script_chinese`, `script_japanese`, `script_korean`, `grant_overlay`, `bubble_started`, `consent_denied`, `hint`.

### 10.2 `values/themes.xml`
- `Theme.Textify`: parent `Theme.AppCompat.Light.NoActionBar`, no `colorPrimary` override (the `#3F6FFF` default is deleted), `colorControlActivated` / `android:colorControlActivated` `#7C3AED`. Application theme.
- `Theme.Textify.Translucent` (replaces `Theme.Textify.Session`, same items: `windowIsTranslucent true`, `windowBackground` transparent, `windowAnimationStyle @null`, `windowDisablePreview true`, `windowNoTitle true`). Used by `MainActivity`, `SessionActivity`, `SettingsActivity`. `Theme.Textify.Session` is deleted (one theme for three identical needs).

### 10.3 `AndroidManifest.xml`
- `<application android:roundIcon="@mipmap/ic_launcher">` added.
- `MainActivity`: `android:theme="@style/Theme.Textify.Translucent"`, `excludeFromRecents="true"`, launcher intent filter kept.
- `SessionActivity`: theme renamed to `Theme.Textify.Translucent`; everything else unchanged.
- `SettingsActivity`: new entry per §7.1, `configChanges` not set (it re-creates on rotation; all state is in prefs/permission).
- Permissions unchanged.

### 10.4 `README.md` "How it works" + "OCR languages"
Rewrite step 1 to the new launch flow (§2), mention `SettingsActivity` (opened from the session's settings button or the launcher while the bubble runs) and the multi-select OCR (§6). Delete every mention of Start/Stop buttons and of a single selected language.

## 11. Allowed API-level branches (proofs)
| branch | why the other side is unreachable | proof |
|---|---|---|
| `SDK_INT >= 33` → request `POST_NOTIFICATIONS` | the runtime permission does not exist below 33 | existing code; constant added API 33 |
| `SDK_INT >= 34` → `createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())` | `MediaProjectionConfig` and the overload exist only from 34; below 34 there is no single-app option to opt out of | `platforms/android-36/data/api-versions.xml`: `MediaProjectionConfig since="34"`, `createScreenCaptureIntent(MediaProjectionConfig) since="34"` |
Removed: `SDK_INT >= O` (channel) and `SDK_INT >= Q` (start) in `CaptureService` — always true / `startForegroundService since="26"`.

## 12. Verification results
| item | result | how verified |
|---|---|---|
| `Text.Line` confidence | `public float getConfidence()` (and `getAngle()`) exist on `com.google.mlkit.vision.text.Text$Line` | `javap` on `play-services-mlkit-text-recognition-common-19.1.0` (the only resolved version, pulled by `text-recognition-chinese/japanese/korean:16.0.1`); ML Kit release notes 2022-08-16 "exposed confidence score for Text Recognition and Text recognition v2" (context7 `/websites/developers_google_ml-kit`) |
| confidence comparability across script models | not documented | **VERIFY** on device; fallback §6.2 |
| `ACTION_MANAGE_OVERLAY_PERMISSION` on API 30+ | "always bring the user to the top-level Settings screen … Any `package:` data in the intent is ignored"; user must select the app | developer.android.com/about/versions/11/privacy/permissions |
| MediaProjection auto-grants overlay | on API 30+ an app capturing via MediaProjection that requests `SYSTEM_ALERT_WINDOW` is auto-granted unless the user explicitly denied it | same page; informational only — flow unchanged |
| Consent dialog / API 34 picker | Android 14+ offers "app window or entire display" for each session; `createConfigForDefaultDisplay()` opts out; OEMs "can override your app's opt out" | developer.android.com/media/grow/media-projection (context7 `/websites/developer_android_guide` "OVERRIDE_DISABLE_MEDIA_PROJECTION_SINGLE_APP_OPTION"). Exact dialog wording and default selection are not documented → **VERIFY** on device; known limitation: if the device overrides the opt-out and the user picks one app, captures show only that app (no code fallback) |
| Consent per session | one consent per `getMediaProjection`; one `createVirtualDisplay` per projection (API 34+ `SecurityException` otherwise) — matches the existing `ScreenGrabber` | media-projection page |
| Android 15 QPR1+ | projection stops on screen lock / status-bar chip; `MediaProjection.Callback.onStop` is called | media-projection page |
| Custom toasts | custom-view toasts from the background are blocked for targetSdk ≥ 30; `Toast.makeText` text toasts allowed | developer.android.com/about/versions/11/behavior-changes-11 |
| `enableEdgeToEdge(SystemBarStyle.light(...), …)` | `SystemBarStyle.Companion.light(int, int)` and `EdgeToEdge.enable(ComponentActivity, SystemBarStyle, SystemBarStyle)` exist | `javap` on `androidx.activity:activity:1.9.3` (installed) |
| `HapticFeedbackConstants.REJECT` | `since="30"` → not used; `VIRTUAL_KEY since="5"` used | `api-versions.xml` |
| `junit:junit` test dependency | latest `4.13.2` | Maven Central search API |
| Overlay revoked while the service runs | system behaviour for existing overlay windows not documented here | **VERIFY** on device; the service only calls `fadeIn` after `canDrawOverlays` is true, so no new window operation happens without permission |

## 13. Acceptance (whole feature)
1. `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug testDebugUnitTest` exit 0.
2. `OcrMergeTest` (JVM) passes: overlap > 0.5 keeps the higher confidence; equal confidence keeps the longer text; IoU exactly 0.5 keeps both; disjoint lines both kept; output sorted top then left.
3. `grep -rn "Prefs.script(\|setScript(\|CaptureService.stop\|scriptGroup\|startButton\|stopButton\|ic_launcher_bg\|ic_launcher_fg\|ic_launcher_round\|ic_menu_crop\|Theme.Textify.Session\|DayNight\|3F6FFF" app/src README.md` → no output.
4. Every string name in §10.1's deleted list has zero hits under `app/src`; every string in §10.1 has at least one reference under `app/src/main` other than `strings.xml`.
5. `grep -rn "SDK_INT" app/src/main/java` → only the two branches of §11.
6. Path data of §9 present verbatim (`grep -F` per path).
7. On-device checklist (user): first launch without overlay permission shows onboarding with the looping demo; granting returns to the granted state and the consent dialog; consent accept → bubble + toast + app closes; deny → toast + close; launcher while running → settings; session settings button → session fades, settings fades in, bubble hidden; back → bubble fades in; with overlay revoked → toast, bubble hidden; deselecting the last language is blocked with the notice; multi-language capture shows merged pills without duplicates; launcher icon correct under circle/squircle/rounded masks and themed icons; bubble glyph entrance plays; notification icon is the prism frame.
