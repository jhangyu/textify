# Capture Session — Area U (Session UI) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use podium:team-spawn (recommended) or podium:team-fable to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `ResultActivity` with a full-screen frozen-screen `SessionActivity` that shows the screenshot 1:1, an a2 "Halo" edge glow, a scrim during OCR, one selectable/copyable pill per OCR line, an X button, a copy toast and a "No text detected" hint.

**Architecture:** One translucent, full-screen `SessionActivity` (own task, system bars hidden, laid out into the display cutout) owns the session. Two custom views do the drawing: `EdgeGlowView` (rotating SweepGradient halo, coloured through a pre-blurred mask bitmap) and `PillView` (an `AppCompatTextView` with selectable transparent text that draws the pill, glow, press/flash overlay and shine). Toast, hint, X and scrim are plain XML views animated with `ViewPropertyAnimator` / `ObjectAnimator`.

**Tech Stack:** Kotlin 2.2.0, AGP 8.13.2, compileSdk 36, minSdk 26, androidx.core:core-ktx 1.13.1, androidx.appcompat 1.7.0, androidx.activity-ktx 1.9.3, ViewBinding. No new dependencies.

**Decisions (hard-to-reverse only):**
- Edge-glow rendering. Alternatives: (1) a2 native note — RenderEffect blur on API 31+ and stacked strokes on API 26–30; (2) spec suggestion — ALPHA_8 mask drawn with `drawBitmap` and a SweepGradient paint shader; (3) **chosen** — blurred mask rendered with `BlurMaskFilter` on a software `Canvas(Bitmap)` at 1/4 resolution, composited with `ComposeShader(SweepGradient, BitmapShader(mask), PorterDuff.Mode.DST_IN)`. Drivers: project rule "one code path API 26–36"; every API used is documented as supported under hardware acceleration on API 26 (see Evidence A). Drawbacks: the mask is re-rendered in software every frame while the halo width breathes (activation phase only, ≤ OCR duration); once settled the mask is static and only the shader rotates.
- Pill glow. Alternatives: `Paint.setShadowLayer` (a2 note; hardware support only from API 28) vs **chosen** per-pill pre-blurred bitmap (software `BlurMaskFilter`, inner part cleared so it is an outer glow like CSS `box-shadow`). Driver: one code path. Drawback: one small bitmap per pill (rendered at 1/2 scale).
- Tap vs. long-press. Alternatives: `OnClickListener` vs **chosen** `GestureDetector.onSingleTapUp` inside `PillView.onTouchEvent`. Driver: Evidence B2 — a selectable TextView is focusable-in-touch-mode, so the first tap only takes focus and never calls `performClick`.
- Session window. Translucent activity in its own task (`taskAffinity=""`) so the close fade reveals the real app underneath and finishing returns to that app, not to `MainActivity`. No fixed `screenOrientation` (API 26 throws for translucent activities with a fixed orientation); instead the session finishes if the orientation changes.

Plan gate: `/Users/jhangyu/.claude/plugins/cache/jhangyu/podium/1.1.0/protocols/review.md` §L0 Plan gate.

## Global Constraints

- Build: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug` exits 0 (run by the gate-runner, not by implementers).
- `grep -r ResultActivity app/src` returns nothing at the end of the feature.
- No per-API-level divergent code paths in app code (`Build.VERSION.SDK_INT` must not appear in any Area U file). The only version branches allowed are inside androidx libraries.
- No new dependencies; `app/build.gradle.kts` is owned by neither area and must not be edited. Consequence: there is no unit-test source set; every task's check is the compile gate + grep markers + the on-device checklist (spec §Testing).
- Frozen cross-area interfaces (copy exactly, do not change):
  ```kotlin
  // Ocr.kt (Area S owns)
  data class OcrLine(val text: String, val box: android.graphics.Rect)   // box in screenshot pixels
  object Ocr { fun recognize(ctx: Context, bitmap: Bitmap, script: Script, onResult: (List<OcrLine>) -> Unit) } // failure => emptyList()

  // SessionActivity.kt (Area U owns)
  companion object {
      fun start(ctx: Context, shot: Bitmap)   // stores bitmap in in-memory holder, starts activity (NEW_TASK, no animation)
      fun finishActive()                      // called by service when projection stops; no-op if none open
  }
  // SessionActivity runs Ocr.recognize itself, using Prefs.script(this).

  // CaptureService.kt (Area S owns)
  companion object {
      fun onSessionEnded()                    // called by SessionActivity at finish; fades bubble back in; no-op if service not running
  }
  ```
  Note: the spec text says `CaptureService.showBubble()`; the frozen contract replaced it with `CaptureService.onSessionEnded()`. Use `onSessionEnded()`.
- Area U owns only: `SessionActivity.kt`, `EdgeGlowView.kt`, `PillView.kt`, `res/layout/activity_session.xml`, `res/drawable/session_x_bg.xml`, `res/drawable/session_toast_bg.xml`, `res/drawable/session_hint_bg.xml`, `res/drawable/ic_session_close.xml`, `res/values/themes.xml`, `res/values/strings.xml`, `AndroidManifest.xml`, `README.md`, and the deletion of `ResultActivity.kt` + `res/layout/activity_result.xml`. Never edit `CaptureService.kt`, `Ocr.kt`, `bubble*`/dismiss drawables or layouts (Area S).
- All visual values come from the header comment of `docs/design/mockups/a2-halo.html` (table below). CSS px map to dp.

### a2 values used (verbatim from the a2 header comment / a2 CSS)

| Name | a2 value | Android value in this plan |
|---|---|---|
| Sweep colours/stops | `[#A78BFA 0, #F472B6 .20, #FDBA74 .38, #67E8F9 .56, #818CF8 .76, #A78BFA 1]` | `EdgeGlowView.COLORS` / `STOPS` |
| glowLoop / breath | 2400 / 1200 ms | `GLOW_LOOP = 2400f`, `BREATH = 1200f` |
| Halo pass | width 44dp ±10dp (sine, period breath) + bloom 70dp collapsing over bloom 400 easeOut, blur 30dp, alpha .75 | `lerp(44+10b+70·bloom, 26, mix)`, `HALO_BLUR_DP = 30f`, alpha `lerp(.75,.22,mix)` |
| Core pass | width 12dp ±4dp, blur 7dp, alpha .9 | `12+4b`, `CORE_BLUR_DP = 7f`, alpha `.9·(1−mix)` |
| Settled | core → 0, halo 26dp, blur 30dp, alpha .22 (residual halo @22%), rotation 12% speed | `mix → 1`, `RESIDUAL_SPIN = .12f` |
| glowIn / glowSettle / bloom | 350 / 400 (easeInOut) / 400 (easeOut) | `GLOW_IN = 350L`, `GLOW_SETTLE = 400L`, `BLOOM = 400f` |
| Ring corner radius | `o.r ?? 36` (a2 `ring()` helper) | `CORNER_DP = 36f` |
| Scrim | RadialGradient #1E1B4B 5% centre → 16% corners, CSS `radial-gradient(120% 80% at 50% 50%)` | `argb(.05f, 0x1E1B4B)` → `argb(.16f, 0x1E1B4B)`, radii 1.2·W × 0.8·H |
| scrimDelay / scrimIn / scrimOut | 150 / 300 ease-out / 300 ease-in | `SCRIM_DELAY = 150L`, `SCRIM_IN = 300L`, `SCRIM_OUT = 300L` |
| Box pill | LinearGradient #A78BFA 20% → #F472B6 16%, radius = height/2 | `argb(.20f, 0xA78BFA)` → `argb(.16f, 0xF472B6)` |
| Pill glow | soft glow #A78BFA 28% r12 | `argb(.28f, 0xA78BFA)`, `GLOW_R_DP = 12f` |
| Pressed/copied overlay | #7C3AED 28% → #EC4899 26%; pressing opacity .65 (140 ms); copied 1 → 0 over flash 380 ease-out | `argb(.28f,0x7C3AED)` → `argb(.26f,0xEC4899)` |
| Press scale | `.box.pressing{transform:scale(1.03)}` | `1.03f` |
| Shine | white 0 → 90% → 0, band 48% of pill width, translateX(-110%) → pill width, flash 380, cubic-bezier(.4,0,.2,1) | `SHINE_CURVE = PathInterpolator(.4f,0f,.2f,1f)` |
| Selecting | `.box.selecting{opacity:.45; box-shadow:none}` | fill ×.45, no glow |
| Selection highlight | #7C3AED 26% | `highlightColor = argb(.26f, 0x7C3AED)` |
| Selection handles | `--handle:#7C3AED` | theme `colorControlActivated = #7C3AED` |
| boxIn / boxStagger | 260 cubic-bezier(.2,0,0,1), from scale .9 opacity 0; delay_i = round(i/(n-1)·140) | `BOX_IN = 260L`, `BOX_STAGGER = 140f` |
| Box padding | 7×3 | `PAD_X_DP = 7f`, `PAD_Y_DP = 3f` |
| X button | 40×40, top 44, right 16, #FFFFFF 86%, icon #4C1D95 20dp stroke 2.2; in: xIn 240 opacity cubic(.2,0,0,1), transform from scale .6 rotate −90 with cubic(.2,0,0,1.3); closing: scale .6 rotate 90 over closeOut | `session_x_bg` `#DBFFFFFF`, `XIN = 240L` |
| closeOut | 300, cubic-bezier(.3,0,.8,.15) | `CLOSE_OUT = 300L`, `CLOSE_CURVE` |
| Toast | bottom 48, padding 9 18 9 10, gap 10, surface #FFFFFF 94%, "Copied" 14 bold #1E1B4B, line 13 #6B5FA8 max 230 ellipsis; in toastIn 220 cubic(.2,0,0,1.25) from translateY 10 scale .92; hold 1500 (hide at toastIn+toastHold); out toastOut 160 ease-in | `session_toast_bg` `#F0FFFFFF`, `TOAST_IN = 220L`, `TOAST_HOLD = 1500L`, `TOAST_OUT = 160L` |
| Hint | centre at top 46%, padding 12 20 12 12, gap 12, surface 92%, 14 bold + 12 #6B5FA8, text "No text detected" / "沒有偵測到文字 · tap ✕ to close"; hintIn 300, opacity cubic(.2,0,0,1), from scale .92 with cubic(.2,0,0,1.2) | `session_hint_bg` `#EBFFFFFF`, `HINT_IN = 300L` |
| Orb | 26×26, conic #A78BFA,#F472B6,#FDBA74,#67E8F9,#818CF8,#A78BFA from 0deg, white hole inset 7; dim = grayscale + opacity .6 | `ORB_COLORS`, inset 7dp, saturation 0 + alpha 153 |

Alpha bytes: 5% = 13, 16% = 41, 20% = 51, 22% = 56, 26% = 66, 28% = 71, 86% = 0xDB, 90% = 230, 92% = 0xEB, 94% = 0xF0 (all `round(p·255)`, computed by the `argb()` helper in code).

a2 values deliberately **not** used by Area U, with reason:
- `bubbleOut 180`, `bubbleIn 300`, `dzIn 220`, `magnet 160`, `snap 360`, `dismiss 280`, magnet radius 104, dismiss target 100, dismiss gradient — Area S.
- `selIn 200` (handle pop / menu in) and `longPress 450` — handles, copy menu and long-press timeout are the system's (`TextView` selection + `ViewConfiguration.getLongPressTimeout()`), required by spec "system handles + system copy menu".
- Shadows: CSS box-shadows of X/toast/hint/orb are approximated by `android:elevation` (neutral shadow colour). `ponytail:` coloured shadows need `outlineSpotShadowColor` (API 28+); add only if the device check shows the neutral shadow is wrong.
- X `:active` scale .9 — skipped: a pressed-state animator would fight the closing animation that starts on the same click.

---

## Evidence (verified 2026-10-11 against official docs and library sources)

### A. Edge glow — one code path for API 26–36 is viable (no USER DECISION needed)

A1. `BlurMaskFilter` is not supported on a hardware-accelerated canvas at any API level. developer.android.com/develop/ui/views/graphics/hardware-accel, table "Support for drawing operations": `Paint | setMaskFilter() | ✗`. Same table: `setShadowLayer() (other than text) | 28`. ⇒ Neither may be used in `onDraw` on API 26–27; both glows are therefore pre-blurred into bitmaps on a software canvas.

A2. A `Canvas(Bitmap)` is a software canvas. Same page: "When a view is attached to a hardware accelerated window, it can still be drawn using a non-hardware accelerated Canvas. This happens, for instance, when drawing a view into a bitmap for caching purposes." `Canvas.isHardwareAccelerated()`: "True if drawing operations are hardware accelerated, false otherwise." `EdgeGlowView` asserts `check(!maskCanvas.isHardwareAccelerated)` at runtime so a violation crashes loudly instead of drawing nothing.

A3. `BlurMaskFilter(float radius, Blur style)` — API 1 — "radius: The radius to extend the blur from the original mask. Must be > 0." (developer.android.com/reference/android/graphics/BlurMaskFilter).

A4. Spec suggestion (ALPHA_8 bitmap + shader paint in `drawBitmap`): `Bitmap.extractAlpha()` docs say an alpha bitmap "may be drawn with Canvas.drawBitmap(), where the color(s) will be taken from the paint that is passed to the draw call". No official page states that a `Shader` on that paint colourises an ALPHA_8 `drawBitmap` under the hardware renderer on API 26–27; the hardware-accel table does not list `drawBitmap`. ⇒ Not proven; **not used**. Replaced by a fully documented equivalent:

A5. `ComposeShader(Shader shaderA, Shader shaderB, PorterDuff.Mode mode)` — API 1 — "shaderA: The colors from this shader are seen as the "dst" by the mode. shaderB: The colors from this shader are seen as the "src" by the mode." `PorterDuff.Mode.DST_IN`: "α_out = α_src · α_dst, C_out = C_dst · α_src". With dst = SweepGradient (opaque colours) and src = BitmapShader(mask) the result is the sweep colour with the mask alpha — exactly a2's "SweepGradient stroke with blur and alpha". Hardware support: the hardware-accel table restricts ComposeShader only for "ComposeShader inside ComposeShader | 28" and "Same type shaders inside ComposeShader | 28"; ours is one level, BitmapShader + SweepGradient (different types) ⇒ supported on API 26.

A6. Rotation: `Shader.setLocalMatrix(Matrix)` — "Set the shader's local matrix." A new `ComposeShader` is built each frame from the rotated sweep so no cached native shader can hold a stale matrix; a new `BitmapShader` is built whenever the mask pixels change so no stale bitmap snapshot is drawn.

A7. Overlay alpha: `Paint.setAlpha` docs only define it for the paint colour, not for shaders ⇒ shader-filled overlays are faded with `Canvas.saveLayerAlpha(float,float,float,float,int)` (API 21) instead; glow alphas are baked into mask pixels with a solid-colour paint.

Residual risk (not a correctness gap): CSS `blur(30px)` in the mockup is a standard deviation; `BlurMaskFilter` takes a radius (Skia sigma ≈ 0.57735·radius + 0.5). The plan copies the a2 header mapping literally (`BlurMaskFilter(30dp)`), so the on-device halo is somewhat tighter than the HTML. Calibration knobs: `HALO_BLUR_DP`, `CORE_BLUR_DP`, `MASK_SCALE`.

### B. Text selection in a transparent-text selectable TextView

B1. `TextView.setTextIsSelectable(boolean)` — API 11 — "When you call this method to set the value of textIsSelectable, it sets the flags focusable, focusableInTouchMode, clickable, and longClickable to the same value." Long-press then starts the system selection with handles and the floating toolbar (`ActionMode.TYPE_FLOATING`: "The action mode is treated as a Floating Toolbar"). The floating toolbar needs an Activity window (spec "Why an Activity").

B2. Tap-to-copy cannot use `OnClickListener`: AOSP `View.onTouchEvent` (frameworks/base main, View.java ~L18104): `if (isFocusable() && isFocusableInTouchMode() && !isFocused()) { focusTaken = requestFocus(); } … if (!focusTaken) { … performClickInternal(); }` ⇒ the first tap on each pill only takes focus. `PillView` therefore detects taps with `GestureDetector.onSingleTapUp` (not called when a long-press fired, and not called after a scroll) and passes every event to `super.onTouchEvent` so the TextView's own long-press selection keeps working. Accessibility clicks are routed through `performAccessibilityAction(ACTION_CLICK)`.

B3. `setCustomSelectionActionModeCallback(ActionMode.Callback)` — "If provided, this ActionMode.Callback will be used to create the ActionMode when text selection is initiated in this View. The standard implementation populates the menu with a subset of Select All, Cut, Copy, Paste, Replace and Share … Returning false from onCreateActionMode(ActionMode, Menu) will prevent the action mode from being started." ⇒ the callback returns `true` from `onCreateActionMode` (keeps the system menu) and `false` from `onActionItemClicked` (the TextView performs Copy itself); the activity only records the live `ActionMode` so tap-outside / back / another-pill tap can call `ActionMode.finish()`.

B4. `setHighlightColor(int)` — "Sets the color used to display the selection highlight." It is independent of the text colour, so `setTextColor(Color.TRANSPARENT)` leaves glyphs invisible while the #7C3AED@26% highlight still shows. `setAutoSizeTextTypeUniformWithConfiguration(min, max, step, unit)` — "Added in API level 26" (= minSdk).

B5. **USER DECISION (non-blocking, default = spec):** developer.android.com/develop/ui/views/touch-and-input/copy-paste: "Starting in Android 13, the system displays a standard visual confirmation when content is added to the clipboard … To avoid duplicate displays of information, we strongly recommend removing toasts or snackbars shown after an in-app copy for Android 13 and higher." The spec requires the a2 toast on every copy. The plan implements the spec (toast on all API levels, single code path). On Android 13+ the user will therefore see the a2 toast **and** the system clipboard preview. Following Google's advice would need an `SDK_INT >= 33` branch, which the project rule forbids unless the user approves it.

### C. Full-screen, cutout, 1:1 mapping (androidx.core 1.13.1, androidx.activity 1.9.3)

C1. `androidx.activity:activity:1.9.3` sources, `EdgeToEdge.kt`: `ComponentActivity.enableEdgeToEdge()` calls `WindowCompat.setDecorFitsSystemWindows(window, false)` and `adjustLayoutInDisplayCutoutMode(window)`, which sets `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` on API 28–29 and `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` on API 30+. The version branch lives inside the library; app code has one path.

C2. `WindowManager.LayoutParams.layoutInDisplayCutoutMode` — "Added in API level 28". API 26–27 have no cutout API at all, so nothing can be set there (provably unavailable, handled by the library doing nothing).

C3. `androidx.core:core:1.13.1` sources: `WindowCompat.getInsetsController(Window, View)` returns `WindowInsetsControllerCompat`; `WindowInsetsControllerCompat.hide(@InsetsType int types)`; `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE = 2`; `WindowInsetsCompat.Type.systemBars()`.

C4. 1:1 mapping safety net: the screenshot is drawn by an `ImageView` with `scaleType="matrix"` and `imageMatrix = translate(-originX, -originY)` where `origin = root.getLocationOnScreen()`. Pills are placed at `box − origin`. If any device still keeps the window off the cutout (API 26–27 vendor notches), the screenshot and pills stay aligned with the real screen pixels.

---
## File map

| File | Status | Responsibility | Task |
|---|---|---|---|
| `app/src/main/res/values/strings.xml` | modify | Area U strings (Area S needs none) | 1, 6 |
| `app/src/main/res/values/themes.xml` | modify | `Theme.Textify.Session`; delete `Theme.Textify.Transparent` | 1 |
| `app/src/main/res/drawable/session_x_bg.xml` | create | X circle surface | 1 |
| `app/src/main/res/drawable/session_toast_bg.xml` | create | toast capsule surface | 1 |
| `app/src/main/res/drawable/session_hint_bg.xml` | create | hint capsule surface | 1 |
| `app/src/main/res/drawable/ic_session_close.xml` | create | X glyph | 1 |
| `app/src/main/res/layout/activity_session.xml` | create | layer stack | 1 |
| `app/src/main/AndroidManifest.xml` | modify | register `SessionActivity`, drop `ResultActivity` | 1 |
| `app/src/main/java/us/jhangy/textify/ResultActivity.kt` | delete | — | 1 |
| `app/src/main/res/layout/activity_result.xml` | delete | — | 1 |
| `app/src/main/java/us/jhangy/textify/SessionActivity.kt` | create (T2) / complete (T5) | session lifecycle + choreography | 2, 5 |
| `app/src/main/java/us/jhangy/textify/EdgeGlowView.kt` | create | halo renderer | 3 |
| `app/src/main/java/us/jhangy/textify/PillView.kt` | create | one OCR line pill | 4 |
| `README.md` | modify | "How it works" | 6 |

---

## Stage 1 — Task skeletons

### Task 1: Session resources, manifest, ResultActivity removal

**Files:**
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/drawable/session_x_bg.xml`, `session_toast_bg.xml`, `session_hint_bg.xml`, `ic_session_close.xml`, `app/src/main/res/layout/activity_session.xml`
- Delete: `app/src/main/java/us/jhangy/textify/ResultActivity.kt`, `app/src/main/res/layout/activity_result.xml`

**Interfaces:**
- Consumes: class name `us.jhangy.textify.EdgeGlowView` (Task 3) used as an XML tag.
- Produces: `ActivitySessionBinding` (ViewBinding of `activity_session.xml`) with ids `root: FrameLayout`, `shot: ImageView`, `scrim: View`, `pills: FrameLayout`, `glow: EdgeGlowView`, `close: FrameLayout`, `hint: LinearLayout`, `hintOrb: ImageView`, `toast: LinearLayout`, `toastOrb: ImageView`, `toastText: TextView`. Style `@style/Theme.Textify.Session`. Strings `R.string.copied` ("Copied"), `R.string.session_no_text`, `R.string.session_no_text_sub`, `R.string.session_close`. Drawables `R.drawable.session_x_bg`, `session_toast_bg`, `session_hint_bg`, `ic_session_close`.

**Behavior:** Layer order bottom→top (spec §Architecture): screenshot, scrim, pill container, glow, X, hint, toast. X/hint/toast use a2 sizes and surfaces. The session theme is translucent (close fade reveals the live app), has no window animation, no starting-window preview, and tints selection handles #7C3AED. The manifest entry gives the session its own task and excludes it from Recents; config changes are handled in-process (the activity finishes itself on rotation, Task 2).

**Constraints:**
- Theme parent `Theme.AppCompat.Light.NoActionBar` (light floating toolbar, matches a2 white menu).
- No `android:screenOrientation` on `SessionActivity` (API 26 crashes for translucent + fixed orientation).
- Strings `copy` and `close` are deleted; `copied` is kept and its value changed to `Copied`. `no_text` stays until Task 6 (old `CaptureService` still uses it until Area S round 2). `app_name`, `notif_title`, `stop` are used by Area S and must stay.

**Acceptance criteria:**
- [ ] `test ! -e app/src/main/java/us/jhangy/textify/ResultActivity.kt && test ! -e app/src/main/res/layout/activity_result.xml` exits 0
- [ ] `grep -r ResultActivity app/src` prints nothing
- [ ] `grep -c 'Theme.Textify.Session' app/src/main/res/values/themes.xml app/src/main/AndroidManifest.xml` prints `1` for each file
- [ ] `grep -E 'name="(copy|close)"' app/src/main/res/values/strings.xml` prints nothing
- [ ] `grep -c 'us.jhangy.textify.EdgeGlowView' app/src/main/res/layout/activity_session.xml` prints `1`

### Task 2: SessionActivity shell (holder, full-screen window, 1:1 screenshot, close/back)

**Files:**
- Create: `app/src/main/java/us/jhangy/textify/SessionActivity.kt`

**Interfaces:**
- Consumes: `ActivitySessionBinding` (Task 1); `CaptureService.onSessionEnded()` (Area S, frozen).
- Produces (frozen contract, exact):
  ```kotlin
  class SessionActivity : AppCompatActivity() {
      companion object {
          fun start(ctx: Context, shot: Bitmap)
          fun finishActive()
      }
  }
  ```

**Behavior:**
- `start`: must be called on the main thread. Takes ownership of `shot` (the activity recycles it). Recycles any older unconsumed bitmap still in the holder, stores `shot`, starts the activity with `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_NO_ANIMATION`.
- `finishActive`: recycles and clears an unconsumed holder bitmap; finishes the open session immediately (no fade). No-op if none.
- `onCreate`: `enableEdgeToEdge()`, hide system bars (transient-by-swipe), take the bitmap from the holder (holder empty ⇒ `finish()` and return; `onDestroy` still reports the session end so the bubble reappears). Show the screenshot 1:1 using the origin offset (Evidence C4). X click and system back call `close()`. Tapping empty space does nothing.
- `close()`: once only; root alpha → 0 over closeOut 300 `CLOSE_CURVE`, X scale .6 + rotation 90° over 300; at the end call `CaptureService.onSessionEnded()` then `finish()`.
- `onDestroy`: clear the active reference, release and recycle the bitmap, call `CaptureService.onSessionEnded()` if not called yet (exactly once per activity).
- Orientation change during the session ⇒ `finish()` (screenshot no longer maps 1:1).

**Constraints:**
- No `Build.VERSION.SDK_INT` in the file.
- `CaptureService.onSessionEnded()` is invoked exactly once per activity instance.

**Acceptance criteria:**
- [ ] `grep -cE 'fun start\(ctx: Context, shot: Bitmap\)|fun finishActive\(\)' app/src/main/java/us/jhangy/textify/SessionActivity.kt` prints `2`
- [ ] `grep -c 'CaptureService.onSessionEnded()' app/src/main/java/us/jhangy/textify/SessionActivity.kt` prints `1` (single call site inside `endSession()`)
- [ ] `grep -c 'SDK_INT' app/src/main/java/us/jhangy/textify/SessionActivity.kt` prints `0`
- [ ] Compile gate after round 2 of both areas: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug` exit 0

### Task 3: EdgeGlowView

**Files:**
- Create: `app/src/main/java/us/jhangy/textify/EdgeGlowView.kt`

**Interfaces:**
- Consumes: nothing project-specific.
- Produces:
  ```kotlin
  class EdgeGlowView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
      fun activate()   // bloom in: alpha 0→1 over GLOW_IN 350 (easeOut cubic), bloom 400, breathing, full-speed rotation
      fun settle()     // OCR done: mix 0→1 over GLOW_SETTLE 400 (easeInOut cubic): core fades out, halo → 26dp @ .22, rotation → 12%
  }
  ```

**Behavior:** Port of a2 `THEME.glow` + frame loop 1:1 (see a2 table). Each frame: `spin += dt · lerp(1, .12, mix)` (dt capped at 64 ms); `b = sin(spin/1200·2π)`; `bloom = 1 − easeOut(min(1, t/400))`; halo width `lerp(44+10b+70·bloom, 26, mix)` dp, alpha `a·lerp(.75,.22,mix)`, blur 30dp; core width `12+4b` dp, alpha `a·(1−mix)·.9` drawn only if `> .01`, blur 7dp. Both strokes are round rects on the view bounds with corner 36dp, rendered white into one ARGB_8888 mask at `MASK_SCALE = .25` with `BlurMaskFilter` on a software canvas; the mask is redrawn only when quantized width/alpha change (0.5dp / 1/255). The view draws one full-size rect with `ComposeShader(sweep rotated by spin/2400·360°, BitmapShader(mask scaled ×4), DST_IN)`. Keeps invalidating every frame while active (residual drift for the whole session). Not clickable — touches pass through to the pills below.

**Constraints:**
- No `setMaskFilter` / `setShadowLayer` on the view's own canvas; no `RenderEffect`; no `SDK_INT`.
- `check(!maskCanvas.isHardwareAccelerated)` present.
- All constants from the a2 table, named as in that table.

**Acceptance criteria:**
- [ ] `grep -cE 'fun activate\(\)|fun settle\(\)' app/src/main/java/us/jhangy/textify/EdgeGlowView.kt` prints `2`
- [ ] `grep -c 'ComposeShader' …/EdgeGlowView.kt` ≥ 1 and `grep -cE 'RenderEffect|SDK_INT|setShadowLayer' …/EdgeGlowView.kt` prints `0`
- [ ] `grep -c 'isHardwareAccelerated' …/EdgeGlowView.kt` prints `1`
- [ ] `grep -cE '0xFFA78BFA|0xFFF472B6|0xFFFDBA74|0xFF67E8F9|0xFF818CF8' …/EdgeGlowView.kt` prints `1` (the single `COLORS` line holds all) and `grep -c '0.2f, 0.38f, 0.56f, 0.76f' …/EdgeGlowView.kt` prints `1`
- [ ] Compile gate exit 0

### Task 4: PillView

**Files:**
- Create: `app/src/main/java/us/jhangy/textify/PillView.kt`

**Interfaces:**
- Consumes: `OcrLine(text: String, box: Rect)` (Area S, frozen).
- Produces:
  ```kotlin
  class PillView(context: Context, val line: OcrLine) : AppCompatTextView(context) {
      var onTap: ((pill: PillView, dismissOnly: Boolean) -> Unit)?   // single tap; dismissOnly = a selection was active at ACTION_DOWN
      var selectionActive: () -> Boolean                             // supplied by the activity: is any selection ActionMode alive?
      var selecting: Boolean                                          // set by the activity while this pill's ActionMode is alive
      fun playIn(delayMs: Long)                                       // boxIn 260: alpha 0→1, scale .9→1, cubic(.2,0,0,1)
      fun playCopied()                                                // flash 380 + shine 380
      companion object { const val PAD_X_DP = 7f; const val PAD_Y_DP = 3f }
  }
  ```

**Behavior:**
- Text = `line.text`, transparent text colour, highlight #7C3AED@26%, selectable, single line, no font padding, gravity start|center_vertical, padding 7dp × 3dp, uniform auto-size (min 4px, max = max(5, box height) px, step 1px). Starts invisible (alpha 0, scale .9) until `playIn`.
- Draw order in `onDraw` (pinned to view coordinates even when the TextView scrolls during selection): outer glow bitmap (unless `selecting`), pill fill (×.45 alpha while `selecting`), pressed/copied overlay through `saveLayerAlpha`, shine band clipped to the pill path, then `super.onDraw` (selection highlight).
- Touch: ACTION_DOWN records `dismissOnly = selectionActive()`; if not dismissOnly → press in (scale 1.03 over 260 `EMPHASIZED`, overlay → .65 over 140 CSS-ease). ACTION_UP/CANCEL, long-press, scroll → press out. `onSingleTapUp` → `onTap(this, dismissOnly)`. All events also go to `super.onTouchEvent` (system long-press selection).
- Accessibility click (`ACTION_CLICK`) → `onTap(this, false)`.
- `playCopied`: overlay 1 → 0 over 380 ease-out; shine band (48% of width) translates from −110% of the band width to the pill width over 380 `SHINE_CURVE`.
- Glow bitmap: rendered on size change at `GLOW_SCALE = .5`, `BlurMaskFilter(12dp)` of the pill shape in #A78BFA@28%, then the pill shape is cleared (outer glow only). Requires the parent to have `clipChildren=false` (Task 1 layout).

**Constraints:**
- No `setShadowLayer`, no `SDK_INT`, no `Paint.setAlpha` on shader paints (use `saveLayerAlpha` / baked colours).

**Acceptance criteria:**
- [ ] `grep -cE 'var onTap: \(\(pill: PillView, dismissOnly: Boolean\) -> Unit\)\?|var selectionActive: \(\) -> Boolean|var selecting|fun playIn\(delayMs: Long\)|fun playCopied\(\)' app/src/main/java/us/jhangy/textify/PillView.kt` prints `5`
- [ ] `grep -cE 'setShadowLayer|SDK_INT|setOnClickListener' …/PillView.kt` prints `0`
- [ ] `grep -c 'GestureDetector' …/PillView.kt` ≥ 1 and `grep -c 'saveLayerAlpha' …/PillView.kt` prints `1`
- [ ] Compile gate exit 0

### Task 5: Session choreography (OCR, glow, scrim, pills, X, toast, hint, selection)

**Files:**
- Modify (replace whole file): `app/src/main/java/us/jhangy/textify/SessionActivity.kt`

**Interfaces:**
- Consumes: Task 2 companion (unchanged, exact), `ActivitySessionBinding` ids (Task 1), `EdgeGlowView.activate()/settle()` (Task 3), `PillView(context, line)`, `onTap`, `selectionActive`, `selecting`, `playIn(delayMs)`, `playCopied()`, `PillView.PAD_X_DP/PAD_Y_DP` (Task 4), `Ocr.recognize(ctx, bitmap, script, onResult: (List<OcrLine>) -> Unit)`, `Prefs.script(ctx)`, `OcrLine` (Area S / existing).
- Produces: nothing new (companion unchanged).

**Behavior (spec §Data flow 3–7, a2 timings):**
- On create: glow `activate()`; scrim alpha 0 → 1 after scrimDelay 150 over scrimIn 300 (CSS ease-out); X hidden (alpha 0, scale .6, rotation −90, disabled); OCR starts (`Ocr.recognize(this, bmp, Prefs.script(this))` where `bmp` is the bitmap taken from the holder, result re-posted to the UI thread).
- OCR done (only once, after first layout so the origin is known): scrim → 0 over scrimOut 300 (CSS ease-in); glow `settle()`; X in (alpha over xIn 240 `EMPHASIZED`, scale/rotation over 240 `X_OVERSHOOT`, enabled); empty list ⇒ hint in (alpha 300 `EMPHASIZED`, scale .92 → 1 over 300 `HINT_OVERSHOOT`, centred at 46% height); else one `PillView` per line, lines sorted top-then-left, placed at `box − origin − padding` with size `box + 2·padding`, `playIn(round(i/(n−1)·140))` (0 when n = 1).
- Tap pill: if `dismissOnly` → finish the live selection; else copy `line.text` to the clipboard (`ClipData.newPlainText("textify", text)`), `playCopied()`, toast.
- Long-press: system selection; custom ActionMode callback stores the mode, sets `pill.selecting`; on system Copy, show shine + toast with the selected substring (the TextView performs the copy).
- Empty-space tap: finishes a live selection, otherwise nothing. Back: finishes a live selection, otherwise `close()`.
- Toast: text = copied text; in over toastIn 220 (alpha `EMPHASIZED`, translateY 10dp → 0 and scale .92 → 1 with `TOAST_OVERSHOOT`); hide scheduled toastIn + toastHold = 1720 ms after show; out over toastOut 160 CSS ease-in back to translateY 10dp / scale .92 / alpha 0. A new copy restarts it.
- Bitmap lifetime: if OCR is still running at `onDestroy`, recycle in the OCR callback instead (ML Kit may still read it).
- OCR finishes after `close()` started ⇒ ignored.

**Constraints:** Same as Task 2. Orb drawable built in code (GradientDrawable sweep, 6 colours, white hole inset 7dp; dim = saturation 0 + alpha 153); orb ImageViews are rotated −90° in XML so the sweep starts at 12 o'clock like CSS `conic-gradient(from 0deg)`.

**Acceptance criteria:**
- [ ] Task 2 grep criteria still hold.
- [ ] `grep -c 'Ocr.recognize(this, bmp, Prefs.script(this))' …/SessionActivity.kt` prints `1`
- [ ] `grep -cE 'setCustomSelectionActionModeCallback|customSelectionActionModeCallback' …/SessionActivity.kt` ≥ 1
- [ ] `grep -cE 'SCRIM_DELAY = 150L|SCRIM_IN = 300L|SCRIM_OUT = 300L|XIN = 240L|HINT_IN = 300L|TOAST_IN = 220L|TOAST_HOLD = 1500L|TOAST_OUT = 160L|CLOSE_OUT = 300L|BOX_STAGGER = 140f' …/SessionActivity.kt` prints `10`
- [ ] Compile gate after round 2 of both areas exit 0; spec §Testing on-device checklist (user): bubble tap → bloom/scrim/pills/residual halo; tap pill copies + shine + toast; long-press handles + system copy; empty tap nothing; X and back close; no-text hint; every fade ≤ 400 ms.

### Task 6: README + string cleanup

**Files:**
- Modify: `README.md`, `app/src/main/res/values/strings.xml`

**Interfaces:** none.

**Behavior:** README "How it works" describes bubble → frozen session → pills → X/back, and drag-to-dismiss; no mention of `ResultActivity`. Delete `<string name="no_text">`: the Area S plan removes its only use (`CaptureService` Task 5: "Removed: … `Toast`/`no_text` usage") in Area S round 2, which runs in parallel with this task.

**Acceptance criteria:**
- [ ] `grep -c ResultActivity README.md` prints `0`; `grep -c SessionActivity README.md` ≥ 1
- [ ] `grep -c 'name="no_text"' app/src/main/res/values/strings.xml` prints `0`; after Area S round 2 is also done, `grep -rn 'no_text' app/src` prints nothing
- [ ] Compile gate exit 0

---

## Stage 2 — Steps

Testing note for every task: the project has no unit-test source set and `app/build.gradle.kts` belongs to neither area, so the TDD "failing test" step is replaced by (1) the grep markers in each task's acceptance list — run them first and see them fail (file absent / count 0), then again after the change — and (2) the compile gate run by the gate-runner at the end of the round. Implementers do not run Gradle themselves (it exceeds the 40 s foreground limit).

Git rules for every commit step: commit only your own paths with a pathspec; never `git add -A`, `stash` (including `stash list`), `reset`, `checkout --` or `clean`. Run `git rev-parse --abbrev-ref HEAD` before committing and `git status --porcelain -- <your paths>` after (must be empty). Commit only if the lead's dispatch prompt tells you to.

### Task 1 steps

- [ ] **Step 1: Confirm the markers fail before the change**

Run:
```bash
cd /Users/jhangyu/project/textify
grep -c 'Theme.Textify.Session' app/src/main/res/values/themes.xml; test -e app/src/main/res/layout/activity_session.xml; echo "layout_exists_rc=$?"
```
Expected: `0` and `layout_exists_rc=1`.

- [ ] **Step 2: Replace `app/src/main/res/values/themes.xml`**

```xml
<resources>
    <style name="Theme.Textify" parent="Theme.AppCompat.DayNight">
        <item name="colorPrimary">#3F6FFF</item>
    </style>

    <!-- Capture session: translucent so the closeOut fade reveals the live app underneath;
         no window animation (start uses FLAG_ACTIVITY_NO_ANIMATION, finish uses this style);
         no preview window (avoids a flash before the screenshot is drawn);
         selection handles tinted a2 violet (--handle #7C3AED). -->
    <style name="Theme.Textify.Session" parent="Theme.AppCompat.Light.NoActionBar">
        <item name="android:windowIsTranslucent">true</item>
        <item name="android:windowBackground">@android:color/transparent</item>
        <item name="android:windowAnimationStyle">@null</item>
        <item name="android:windowDisablePreview">true</item>
        <item name="android:windowNoTitle">true</item>
        <item name="colorControlActivated">#7C3AED</item>
        <item name="android:colorControlActivated">#7C3AED</item>
    </style>
</resources>
```

- [ ] **Step 3: Edit `app/src/main/res/values/strings.xml`**

Delete these two lines:
```xml
    <string name="copy">Copy</string>
    <string name="close">Close</string>
```
Replace `<string name="copied">Copied to clipboard</string>` with `<string name="copied">Copied</string>`.
Insert before `</resources>`:
```xml
    <string name="session_no_text">No text detected</string>
    <string name="session_no_text_sub">沒有偵測到文字 · tap ✕ to close</string>
    <string name="session_close">Close capture session</string>
```
Area S needs no new strings (Area S plan, Global Constraints: "Area S needs no new strings. Area S keeps using existing `app_name`, `notif_title`, `stop` — Area U must not delete them."). Do not touch `app_name`, `notif_title`, `stop`. Keep `no_text` for now (Task 6 deletes it).

- [ ] **Step 4: Create the four drawables**

`app/src/main/res/drawable/session_x_bg.xml` (a2 X surface #FFFFFF 86%):
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="#DBFFFFFF" />
</shape>
```

`app/src/main/res/drawable/session_toast_bg.xml` (a2 toast surface #FFFFFF 94%, capsule):
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="#F0FFFFFF" />
    <corners android:radius="999dp" />
</shape>
```

`app/src/main/res/drawable/session_hint_bg.xml` (a2 hint surface #FFFFFF 92%, capsule):
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="#EBFFFFFF" />
    <corners android:radius="999dp" />
</shape>
```

`app/src/main/res/drawable/ic_session_close.xml` (a2 ICON_X: `M6 6l12 12M18 6L6 18`, stroke 2.2, round caps, colour #4C1D95):
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="20dp"
    android:height="20dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:pathData="M6,6L18,18M18,6L6,18"
        android:strokeColor="#4C1D95"
        android:strokeWidth="2.2"
        android:strokeLineCap="round" />
</vector>
```

- [ ] **Step 5: Create `app/src/main/res/layout/activity_session.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Capture session layers, bottom to top (spec §Architecture):
     screenshot, scrim, pill container, edge glow, X, hint, toast. -->
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:clipChildren="false">

    <ImageView
        android:id="@+id/shot"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:importantForAccessibility="no"
        android:scaleType="matrix" />

    <View
        android:id="@+id/scrim"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:alpha="0" />

    <!-- clipChildren=false: pills draw their outer glow outside their own bounds -->
    <FrameLayout
        android:id="@+id/pills"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:clipChildren="false"
        android:clipToPadding="false" />

    <us.jhangy.textify.EdgeGlowView
        android:id="@+id/glow"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:importantForAccessibility="no" />

    <FrameLayout
        android:id="@+id/close"
        android:layout_width="40dp"
        android:layout_height="40dp"
        android:layout_gravity="top|end"
        android:layout_marginTop="44dp"
        android:layout_marginEnd="16dp"
        android:background="@drawable/session_x_bg"
        android:clickable="true"
        android:contentDescription="@string/session_close"
        android:elevation="4dp"
        android:focusable="true">

        <ImageView
            android:layout_width="20dp"
            android:layout_height="20dp"
            android:layout_gravity="center"
            android:importantForAccessibility="no"
            android:src="@drawable/ic_session_close" />
    </FrameLayout>

    <LinearLayout
        android:id="@+id/hint"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:background="@drawable/session_hint_bg"
        android:elevation="8dp"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:paddingStart="12dp"
        android:paddingTop="12dp"
        android:paddingEnd="20dp"
        android:paddingBottom="12dp"
        android:visibility="gone">

        <ImageView
            android:id="@+id/hintOrb"
            android:layout_width="26dp"
            android:layout_height="26dp"
            android:importantForAccessibility="no"
            android:rotation="-90" />

        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="12dp"
            android:orientation="vertical">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/session_no_text"
                android:textColor="#1E1B4B"
                android:textSize="14sp"
                android:textStyle="bold" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/session_no_text_sub"
                android:textColor="#6B5FA8"
                android:textSize="12sp" />
        </LinearLayout>
    </LinearLayout>

    <LinearLayout
        android:id="@+id/toast"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|center_horizontal"
        android:layout_marginBottom="48dp"
        android:background="@drawable/session_toast_bg"
        android:elevation="8dp"
        android:gravity="center_vertical"
        android:orientation="horizontal"
        android:paddingStart="10dp"
        android:paddingTop="9dp"
        android:paddingEnd="18dp"
        android:paddingBottom="9dp"
        android:visibility="gone">

        <ImageView
            android:id="@+id/toastOrb"
            android:layout_width="26dp"
            android:layout_height="26dp"
            android:importantForAccessibility="no"
            android:rotation="-90" />

        <!-- inner row keeps "Copied" and the line text on one baseline (a2 .tx align-items:baseline) -->
        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="10dp"
            android:orientation="horizontal">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/copied"
                android:textColor="#1E1B4B"
                android:textSize="14sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/toastText"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="8dp"
                android:ellipsize="end"
                android:maxWidth="230dp"
                android:maxLines="1"
                android:textColor="#6B5FA8"
                android:textSize="13sp" />
        </LinearLayout>
    </LinearLayout>
</FrameLayout>
```

- [ ] **Step 6: Edit `app/src/main/AndroidManifest.xml`**

Replace the whole `ResultActivity` element:
```xml
        <activity
            android:name=".ResultActivity"
            android:exported="false"
            android:excludeFromRecents="true"
            android:theme="@style/Theme.Textify.Transparent" />
```
with:
```xml
        <!-- Own task (taskAffinity="") so finishing returns to the app the user was in;
             no screenOrientation: API 26 rejects fixed orientation on translucent activities. -->
        <activity
            android:name=".SessionActivity"
            android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboard|keyboardHidden|navigation|uiMode|density|fontScale|locale|layoutDirection|colorMode"
            android:excludeFromRecents="true"
            android:exported="false"
            android:taskAffinity=""
            android:theme="@style/Theme.Textify.Session" />
```

- [ ] **Step 7: Delete the old result screen**

```bash
cd /Users/jhangyu/project/textify
rm app/src/main/java/us/jhangy/textify/ResultActivity.kt app/src/main/res/layout/activity_result.xml
```
(Both files are untracked in git today; `rm` is the deletion. `CaptureService.kt` still calls `ResultActivity.show` until Area S round 2 replaces it with `SessionActivity.start`; the compile gate runs only after round 2 of both areas.)

- [ ] **Step 8: Run the Task 1 acceptance greps** — all must print the expected values listed in Task 1.

- [ ] **Step 9: Commit (only if the lead says so)**

```bash
cd /Users/jhangyu/project/textify
git rev-parse --abbrev-ref HEAD
git add app/src/main/res/values/themes.xml app/src/main/res/values/strings.xml app/src/main/AndroidManifest.xml app/src/main/res/drawable/session_x_bg.xml app/src/main/res/drawable/session_toast_bg.xml app/src/main/res/drawable/session_hint_bg.xml app/src/main/res/drawable/ic_session_close.xml app/src/main/res/layout/activity_session.xml
git commit -m "feat(session): session resources, manifest entry, remove ResultActivity" -- app/src/main/res/values/themes.xml app/src/main/res/values/strings.xml app/src/main/AndroidManifest.xml app/src/main/res/drawable/session_x_bg.xml app/src/main/res/drawable/session_toast_bg.xml app/src/main/res/drawable/session_hint_bg.xml app/src/main/res/drawable/ic_session_close.xml app/src/main/res/layout/activity_session.xml
```

### Task 2 steps

- [ ] **Step 1: Confirm the markers fail before the change**

Run: `cd /Users/jhangyu/project/textify && test -e app/src/main/java/us/jhangy/textify/SessionActivity.kt; echo "rc=$?"`
Expected: `rc=1`.

- [ ] **Step 2: Create `app/src/main/java/us/jhangy/textify/SessionActivity.kt` (shell)**

```kotlin
package us.jhangy.textify

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.view.animation.PathInterpolator
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import us.jhangy.textify.databinding.ActivitySessionBinding
import java.lang.ref.WeakReference

/**
 * Frozen-screen capture session. Shows the screenshot 1:1 under hidden system bars;
 * X or back fades the session out (closeOut) and tells CaptureService to bring the bubble back.
 */
class SessionActivity : AppCompatActivity() {

    private lateinit var b: ActivitySessionBinding
    private var shot: Bitmap? = null
    private val origin = IntArray(2)          // window position on screen; screenshot px - origin = view px
    private var startOrientation = Configuration.ORIENTATION_UNDEFINED
    private var closing = false
    private var ended = false

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
        }

        b.close.setOnClickListener { close() }
        onBackPressedDispatcher.addCallback(this) { close() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation != startOrientation) finish()   // screenshot no longer maps 1:1
    }

    private fun close() {
        if (closing) return
        closing = true
        b.close.animate().scaleX(0.6f).scaleY(0.6f).rotation(90f)
            .setDuration(CLOSE_OUT).setInterpolator(X_OVERSHOOT)
        b.root.animate().alpha(0f).setDuration(CLOSE_OUT).setInterpolator(CLOSE_CURVE)
            .withEndAction { endSession(); finish() }
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
            bmp.recycle()
        }
        shot = null
        endSession()
        super.onDestroy()
    }

    companion object {
        private const val CLOSE_OUT = 300L
        private val X_OVERSHOOT = PathInterpolator(0.2f, 0f, 0f, 1.3f)     // a2 .xbtn transform curve
        private val CLOSE_CURVE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)  // a2 close() opacity curve

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
```

Notes for the implementer: `enableEdgeToEdge()` is `androidx.activity.enableEdgeToEdge` (activity 1.9.3); it must run before `setContentView`. `doOnLayout` (core-ktx 1.13.1) runs immediately if the view is already laid out. `onBackPressedDispatcher.addCallback(owner) { }` is `androidx.activity.addCallback`.

- [ ] **Step 3: Run the Task 2 acceptance greps**

```bash
cd /Users/jhangyu/project/textify
F=app/src/main/java/us/jhangy/textify/SessionActivity.kt
grep -cE 'fun start\(ctx: Context, shot: Bitmap\)|fun finishActive\(\)' $F   # expect 2
grep -c 'CaptureService.onSessionEnded()' $F                                  # expect 1
grep -c 'SDK_INT' $F                                                         # expect 0
```

- [ ] **Step 4: Report to the lead.** There is no round-1 compile gate: `CaptureService.kt` keeps calling the old `Ocr` signature and `ResultActivity` until Area S round 2 rewrites it. The gate-runner runs `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug; echo RC=$?` after round 2 of both areas.

- [ ] **Step 5: Commit (only if the lead says so)**

```bash
cd /Users/jhangyu/project/textify
git rev-parse --abbrev-ref HEAD
git add app/src/main/java/us/jhangy/textify/SessionActivity.kt
git commit -m "feat(session): SessionActivity shell with 1:1 screenshot and close" -- app/src/main/java/us/jhangy/textify/SessionActivity.kt
```

### Task 3 steps

- [ ] **Step 1: Confirm the markers fail before the change**

Run: `cd /Users/jhangyu/project/textify && test -e app/src/main/java/us/jhangy/textify/EdgeGlowView.kt; echo "rc=$?"`
Expected: `rc=1`.

- [ ] **Step 2: Create `app/src/main/java/us/jhangy/textify/EdgeGlowView.kt`**

```kotlin
package us.jhangy.textify

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.AnimationUtils
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * a2 "Halo" edge glow. One code path for API 26-36:
 * the blurred ring (halo + core) is rendered white into a 1/4-scale mask on a software canvas
 * (BlurMaskFilter is not supported on hardware canvases), then coloured by a rotating
 * SweepGradient through ComposeShader(sweep, mask, DST_IN).
 */
class EdgeGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density

    // animated state, same names as the a2 mockup's `g`
    private var a = 0f          // overall glow alpha
    private var mix = 0f        // 0 = activation look, 1 = settled residual look
    private var spin = 0f       // rotation progress in ms (advances slower once settled)
    private var t0 = 0L         // activation time, drives the bloom term
    private var last = 0L
    private var running = false
    private var alphaAnim: ValueAnimator? = null
    private var mixAnim: ValueAnimator? = null

    private var mask: Bitmap? = null
    private var maskCanvas: Canvas? = null
    private var maskShader: BitmapShader? = null
    private var sweep: SweepGradient? = null
    private var maskKey = -1L
    private val rotation = Matrix()
    private val paint = Paint()
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val haloBlur = BlurMaskFilter(HALO_BLUR_DP * dp * MASK_SCALE, BlurMaskFilter.Blur.NORMAL)
    private val coreBlur = BlurMaskFilter(CORE_BLUR_DP * dp * MASK_SCALE, BlurMaskFilter.Blur.NORMAL)

    /** Bloom in from the bezel: alpha 0 -> 1 over glowIn, breathing, full-speed rotation. */
    fun activate() {
        t0 = AnimationUtils.currentAnimationTimeMillis()
        last = t0
        spin = 0f
        mix = 0f
        maskKey = -1L
        mixAnim?.cancel()
        alphaAnim?.cancel()
        alphaAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = GLOW_IN
            interpolator = EASE_OUT_CUBIC
            addUpdateListener { a = it.animatedValue as Float }
            start()
        }
        running = true
        postInvalidateOnAnimation()
    }

    /** OCR done: core fades out, halo settles to 26dp @ .22, rotation slows to 12%. */
    fun settle() {
        mixAnim?.cancel()
        mixAnim = ValueAnimator.ofFloat(mix, 1f).apply {
            duration = GLOW_SETTLE
            interpolator = EASE_IN_OUT_CUBIC
            addUpdateListener { mix = it.animatedValue as Float }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        // no recycle() of the old mask: the last recorded frame may still reference it; GC frees it
        val m = Bitmap.createBitmap(
            max(1, (w * MASK_SCALE).roundToInt()),
            max(1, (h * MASK_SCALE).roundToInt()),
            Bitmap.Config.ARGB_8888,
        )
        val c = Canvas(m)
        check(!c.isHardwareAccelerated) { "glow mask must be drawn on a software canvas (BlurMaskFilter)" }
        mask = m
        maskCanvas = c
        maskShader = null
        maskKey = -1L
        sweep = SweepGradient(w / 2f, h / 2f, COLORS, STOPS)
    }

    override fun onDraw(canvas: Canvas) {
        if (!running) return
        val m = mask ?: return
        val sw = sweep ?: return
        val now = AnimationUtils.currentAnimationTimeMillis()
        val dt = min(64L, now - last)
        last = now
        spin += dt * lerp(1f, RESIDUAL_SPIN, mix)
        if (a > 0.001f) {
            val breath = sin(spin / BREATH * 2.0 * PI).toFloat()
            val bloom = 1f - easeOut(min(1f, (now - t0) / BLOOM))
            renderMask(
                m,
                haloWidthDp = lerp(44f + 10f * breath + 70f * bloom, 26f, mix),
                haloAlpha = a * lerp(0.75f, 0.22f, mix),
                coreWidthDp = 12f + 4f * breath,
                coreAlpha = a * (1f - mix) * 0.9f,
            )
            rotation.setRotate(spin / GLOW_LOOP * 360f, width / 2f, height / 2f)
            sw.setLocalMatrix(rotation)
            // new ComposeShader each frame so the rotated sweep is never served from a cached native shader
            paint.shader = ComposeShader(sw, maskShader!!, PorterDuff.Mode.DST_IN)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
        postInvalidateOnAnimation()
    }

    /** Redraws the white blurred ring mask only when its quantized geometry/alpha changed. */
    private fun renderMask(m: Bitmap, haloWidthDp: Float, haloAlpha: Float, coreWidthDp: Float, coreAlpha: Float) {
        val hw = (haloWidthDp * 2f).roundToInt()                       // 0.5 dp steps
        val ha = (haloAlpha.coerceIn(0f, 1f) * 255f).roundToInt()
        val cw = (coreWidthDp * 2f).roundToInt()
        val ca = if (coreAlpha > 0.01f) (coreAlpha.coerceIn(0f, 1f) * 255f).roundToInt() else 0
        val key = (hw.toLong() shl 48) or (ha.toLong() shl 32) or (cw.toLong() shl 16) or ca.toLong()
        if (key == maskKey && maskShader != null) return
        maskKey = key

        val c = maskCanvas ?: return
        val s = MASK_SCALE
        val mw = m.width.toFloat()
        val mh = m.height.toFloat()
        val r = CORNER_DP * dp * s
        m.eraseColor(Color.TRANSPARENT)
        ring.maskFilter = haloBlur
        ring.strokeWidth = haloWidthDp * dp * s
        ring.alpha = ha
        c.drawRoundRect(0f, 0f, mw, mh, r, r, ring)
        if (ca > 0) {
            ring.maskFilter = coreBlur
            ring.strokeWidth = coreWidthDp * dp * s
            ring.alpha = ca
            c.drawRoundRect(0f, 0f, mw, mh, r, r, ring)
        }
        // new BitmapShader after every pixel change: never draw a stale snapshot of the mask
        maskShader = BitmapShader(m, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(width / mw, height / mh) })
        }
    }

    override fun onDetachedFromWindow() {
        alphaAnim?.cancel()
        mixAnim?.cancel()
        running = false
        super.onDetachedFromWindow()
    }

    companion object {
        // a2 header: TIMINGS and GLOW RECIPE
        private const val GLOW_IN = 350L
        private const val GLOW_SETTLE = 400L
        private const val BLOOM = 400f
        private const val GLOW_LOOP = 2400f
        private const val BREATH = 1200f
        private const val RESIDUAL_SPIN = 0.12f
        private const val HALO_BLUR_DP = 30f
        private const val CORE_BLUR_DP = 7f
        private const val CORNER_DP = 36f                 // a2 ring() default radius
        // ponytail: 1/4-scale mask keeps the per-frame software blur cheap; raise if the halo looks blocky
        private const val MASK_SCALE = 0.25f

        private val COLORS = longArrayOf(0xFFA78BFA, 0xFFF472B6, 0xFFFDBA74, 0xFF67E8F9, 0xFF818CF8, 0xFFA78BFA).map { it.toInt() }.toIntArray()
        private val STOPS = floatArrayOf(0f, 0.2f, 0.38f, 0.56f, 0.76f, 1f)

        private val EASE_OUT_CUBIC = TimeInterpolator { easeOut(it) }
        private val EASE_IN_OUT_CUBIC = TimeInterpolator { t ->
            if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f
        }

        private fun easeOut(t: Float) = 1f - (1f - t).pow(3)
        private fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t
    }
}
```

- [ ] **Step 3: Run the Task 3 acceptance greps**

```bash
cd /Users/jhangyu/project/textify
F=app/src/main/java/us/jhangy/textify/EdgeGlowView.kt
grep -cE 'fun activate\(\)|fun settle\(\)' $F                                   # expect 2
grep -c 'ComposeShader' $F                                                      # expect >= 1
grep -cE 'RenderEffect|SDK_INT|setShadowLayer' $F                               # expect 0
grep -c 'isHardwareAccelerated' $F                                              # expect 1
grep -cE '0xFFA78BFA|0xFFF472B6|0xFFFDBA74|0xFF67E8F9|0xFF818CF8' $F             # expect 1
grep -c '0.2f, 0.38f, 0.56f, 0.76f' $F                                          # expect 1
```

- [ ] **Step 4: Report to the lead.** (Compile gate runs after round 2 of both areas.)

- [ ] **Step 5: Commit (only if the lead says so)**

```bash
cd /Users/jhangyu/project/textify
git rev-parse --abbrev-ref HEAD
git add app/src/main/java/us/jhangy/textify/EdgeGlowView.kt
git commit -m "feat(session): a2 halo EdgeGlowView (single path, software-blurred mask + ComposeShader)" -- app/src/main/java/us/jhangy/textify/EdgeGlowView.kt
```

### Task 4 steps

- [ ] **Step 1: Confirm the markers fail before the change**

Run: `cd /Users/jhangyu/project/textify && test -e app/src/main/java/us/jhangy/textify/PillView.kt; echo "rc=$?"`
Expected: `rc=1`.

- [ ] **Step 2: Create `app/src/main/java/us/jhangy/textify/PillView.kt`**

```kotlin
package us.jhangy.textify

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.animation.doOnEnd
import kotlin.math.max
import kotlin.math.roundToInt

private fun argb(alpha: Float, rgb: Int): Int = ((alpha * 255f).roundToInt() shl 24) or (rgb and 0xFFFFFF)

/**
 * One OCR line as an a2 "Halo" pill. The text is real, selectable and transparent, sized to
 * roughly cover the glyphs in the screenshot, so long-press gives system handles + copy menu.
 * Tap is detected with GestureDetector: a selectable TextView is focusable in touch mode, so the
 * first tap would only take focus and never reach an OnClickListener.
 */
@SuppressLint("ViewConstructor")
class PillView(context: Context, val line: OcrLine) : AppCompatTextView(context) {

    /** Single tap. dismissOnly = a text selection was alive when the finger went down. */
    var onTap: ((pill: PillView, dismissOnly: Boolean) -> Unit)? = null

    /** Supplied by the activity: is any selection ActionMode alive right now? */
    var selectionActive: () -> Boolean = { false }

    /** Set by the activity while this pill's selection ActionMode is alive (a2 .box.selecting). */
    var selecting = false
        set(value) {
            field = value
            invalidate()
        }

    private val dp = resources.displayMetrics.density
    private val glowRadius = GLOW_R_DP * dp
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillSelecting = Paint(Paint.ANTI_ALIAS_FLAG)
    private val overlay = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pillPath = Path()
    private val glowDst = RectF()
    private var glow: Bitmap? = null
    private var overlayAlpha = 0f
    private var shineX = Float.NaN
    private var overlayAnim: ValueAnimator? = null
    private var shineAnim: ValueAnimator? = null
    private var dismissOnly = false

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onTap?.invoke(this@PillView, dismissOnly)
            return false
        }

        override fun onLongPress(e: MotionEvent) = press(false)

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            press(false)
            return false
        }
    })

    init {
        text = line.text
        setTextColor(Color.TRANSPARENT)
        highlightColor = argb(0.26f, 0x7C3AED)
        setTextIsSelectable(true)
        setSingleLine(true)
        maxLines = 1
        includeFontPadding = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        val px = (PAD_X_DP * dp).roundToInt()
        val py = (PAD_Y_DP * dp).roundToInt()
        setPadding(px, py, px, py)
        setAutoSizeTextTypeUniformWithConfiguration(4, max(5, line.box.height()), 1, TypedValue.COMPLEX_UNIT_PX)
        alpha = 0f
        scaleX = 0.9f
        scaleY = 0.9f
    }

    /** a2 boxIn: opacity 0 -> 1 and scale .9 -> 1 over 260 ms, cubic-bezier(.2,0,0,1), after [delayMs]. */
    fun playIn(delayMs: Long) {
        animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(delayMs).setDuration(BOX_IN).setInterpolator(EMPHASIZED)
    }

    /** a2 copy feedback: overlay flash 1 -> 0 (ease-out) and a white shine sweep, both over flash 380 ms. */
    fun playCopied() {
        animateOverlay(1f, 0L, EMPHASIZED)
        animateOverlay(0f, FLASH, EASE_OUT)
        val band = width * SHINE_BAND
        shineAnim?.cancel()
        shineAnim = ValueAnimator.ofFloat(-1.1f * band, width.toFloat()).apply {
            duration = FLASH
            interpolator = SHINE_CURVE
            addUpdateListener { shineX = it.animatedValue as Float; invalidate() }
            doOnEnd { shineX = Float.NaN; invalidate() }
            start()
        }
    }

    private fun press(on: Boolean) {
        val s = if (on) 1.03f else 1f
        animate().scaleX(s).scaleY(s).setStartDelay(0L).setDuration(BOX_IN).setInterpolator(EMPHASIZED)
        animateOverlay(if (on) 0.65f else 0f, PRESS_FADE, CSS_EASE)
    }

    private fun animateOverlay(to: Float, ms: Long, curve: TimeInterpolator) {
        overlayAnim?.cancel()
        if (ms == 0L) {
            overlayAlpha = to
            invalidate()
            return
        }
        overlayAnim = ValueAnimator.ofFloat(overlayAlpha, to).apply {
            duration = ms
            interpolator = curve
            addUpdateListener { overlayAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility") // accessibility clicks are handled in performAccessibilityAction
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dismissOnly = selectionActive()
                if (!dismissOnly) press(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> press(false)
        }
        gestures.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_CLICK) {
            onTap?.invoke(this, false)
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        val fw = w.toFloat()
        val fh = h.toFloat()
        val r = fh / 2f
        pillPath.reset()
        pillPath.addRoundRect(0f, 0f, fw, fh, r, r, Path.Direction.CW)
        fill.shader = LinearGradient(0f, 0f, fw, 0f, argb(0.20f, 0xA78BFA), argb(0.16f, 0xF472B6), Shader.TileMode.CLAMP)
        fillSelecting.shader = LinearGradient(0f, 0f, fw, 0f, argb(0.20f * SELECTING, 0xA78BFA), argb(0.16f * SELECTING, 0xF472B6), Shader.TileMode.CLAMP)
        overlay.shader = LinearGradient(0f, 0f, fw, 0f, argb(0.28f, 0x7C3AED), argb(0.26f, 0xEC4899), Shader.TileMode.CLAMP)
        val band = fw * SHINE_BAND
        shine.shader = LinearGradient(
            0f, 0f, band, 0f,
            intArrayOf(argb(0f, 0xFFFFFF), argb(0.9f, 0xFFFFFF), argb(0f, 0xFFFFFF)), null, Shader.TileMode.CLAMP,
        )
        glow = renderGlow(fw, fh)   // old bitmap left to GC: the last recorded frame may still reference it
        glowDst.set(-glowRadius, -glowRadius, fw + glowRadius, fh + glowRadius)
    }

    /** Outer glow like CSS box-shadow 0 0 12px: blurred pill on a software canvas, pill area cleared. */
    private fun renderGlow(w: Float, h: Float): Bitmap {
        val s = GLOW_SCALE
        val pad = glowRadius * s
        val bmp = Bitmap.createBitmap(
            max(1, (w * s + 2f * pad).roundToInt()),
            max(1, (h * s + 2f * pad).roundToInt()),
            Bitmap.Config.ARGB_8888,
        )
        val c = Canvas(bmp)
        val r = h * s / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = argb(0.28f, 0xA78BFA)
            maskFilter = BlurMaskFilter(glowRadius * s, BlurMaskFilter.Blur.NORMAL)
        }
        c.drawRoundRect(pad, pad, pad + w * s, pad + h * s, r, r, p)
        p.maskFilter = null
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        c.drawRoundRect(pad, pad, pad + w * s, pad + h * s, r, r, p)
        return bmp
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f
        val saved = canvas.save()
        canvas.translate(scrollX.toFloat(), scrollY.toFloat())   // keep the pill fixed while the text scrolls
        if (!selecting) glow?.let { canvas.drawBitmap(it, null, glowDst, glowPaint) }
        canvas.drawRoundRect(0f, 0f, w, h, r, r, if (selecting) fillSelecting else fill)
        if (overlayAlpha > 0f) {
            val layer = canvas.saveLayerAlpha(0f, 0f, w, h, (overlayAlpha.coerceIn(0f, 1f) * 255f).roundToInt())
            canvas.drawRoundRect(0f, 0f, w, h, r, r, overlay)
            canvas.restoreToCount(layer)
        }
        if (!shineX.isNaN()) {
            val clip = canvas.save()
            canvas.clipPath(pillPath)
            canvas.translate(shineX, 0f)
            canvas.drawRect(0f, 0f, w * SHINE_BAND, h, shine)
            canvas.restoreToCount(clip)
        }
        canvas.restoreToCount(saved)
        super.onDraw(canvas)   // transparent glyphs + selection highlight
    }

    override fun onDetachedFromWindow() {
        overlayAnim?.cancel()
        shineAnim?.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        const val PAD_X_DP = 7f
        const val PAD_Y_DP = 3f
        private const val GLOW_R_DP = 12f
        // ponytail: glow bitmap at half resolution; it is a soft blur so the loss is invisible
        private const val GLOW_SCALE = 0.5f
        private const val BOX_IN = 260L
        private const val FLASH = 380L
        private const val PRESS_FADE = 140L
        private const val SHINE_BAND = 0.48f
        private const val SELECTING = 0.45f
        private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)      // a2 boxIn
        private val CSS_EASE = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)  // CSS "ease" (pressing overlay)
        private val EASE_OUT = PathInterpolator(0f, 0f, 0.58f, 1f)       // CSS "ease-out" (copied flash)
        private val SHINE_CURVE = PathInterpolator(0.4f, 0f, 0.2f, 1f)   // a2 shine
    }
}
```

- [ ] **Step 3: Run the Task 4 acceptance greps**

```bash
cd /Users/jhangyu/project/textify
F=app/src/main/java/us/jhangy/textify/PillView.kt
grep -cE 'var onTap: \(\(pill: PillView, dismissOnly: Boolean\) -> Unit\)\?|var selectionActive: \(\) -> Boolean|var selecting|fun playIn\(delayMs: Long\)|fun playCopied\(\)' $F   # expect 5
grep -cE 'setShadowLayer|SDK_INT|setOnClickListener' $F      # expect 0
grep -c 'GestureDetector' $F                                  # expect >= 1
grep -c 'saveLayerAlpha' $F                                   # expect 1
```

- [ ] **Step 4: Report to the lead.** (Compile gate runs after round 2 of both areas.)

- [ ] **Step 5: Commit (only if the lead says so)**

```bash
cd /Users/jhangyu/project/textify
git rev-parse --abbrev-ref HEAD
git add app/src/main/java/us/jhangy/textify/PillView.kt
git commit -m "feat(session): PillView (selectable a2 pill, tap copy, shine/flash)" -- app/src/main/java/us/jhangy/textify/PillView.kt
```

### Task 5 steps

- [ ] **Step 1: Confirm the markers fail before the change**

Run: `cd /Users/jhangyu/project/textify && grep -c 'Ocr.recognize(this, bmp, Prefs.script(this))' app/src/main/java/us/jhangy/textify/SessionActivity.kt`
Expected: `0`.

- [ ] **Step 2: Replace `app/src/main/java/us/jhangy/textify/SessionActivity.kt` with the complete session**

The companion `start`/`finishActive`, `close()`, `endSession()` and the window setup are unchanged from Task 2; the rest adds the a2 choreography.

```kotlin
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

private fun argb(alpha: Float, rgb: Int): Int = ((alpha * 255f).roundToInt() shl 24) or (rgb and 0xFFFFFF)

/**
 * Frozen-screen capture session (a2 "Halo"). Screenshot 1:1 under hidden system bars, edge glow +
 * scrim while OCR runs, then one selectable pill per OCR line. Tap pill = copy line, long-press =
 * system selection, X/back = closeOut fade, then CaptureService brings the bubble back.
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
    private val hideToast = Runnable { toastOut() }

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
        b.pills.setOnClickListener { actionMode?.finish() }   // empty space never closes; it only drops a selection
        onBackPressedDispatcher.addCallback(this) {
            val mode = actionMode
            if (mode != null) mode.finish() else close()
        }

        b.toastOrb.setImageDrawable(orb(dim = false))
        b.hintOrb.setImageDrawable(orb(dim = true))
        b.close.apply { alpha = 0f; scaleX = 0.6f; scaleY = 0.6f; rotation = -90f; isEnabled = false }

        // activation: glow blooms in, faint scrim after scrimDelay, OCR runs
        b.glow.activate()
        b.scrim.background = scrimDrawable()
        b.scrim.animate().alpha(1f).setStartDelay(SCRIM_DELAY).setDuration(SCRIM_IN).setInterpolator(EASE_OUT)
        ocrRunning = true
        Ocr.recognize(this, bmp, Prefs.script(this)) { lines -> runOnUiThread { onOcr(lines) } }
    }

    private fun onOcr(lines: List<OcrLine>) {
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
            showClose()
            if (lines.isEmpty()) showHint() else showPills(lines)
        }
    }

    private fun showPills(lines: List<OcrLine>) {
        val padX = (PillView.PAD_X_DP * resources.displayMetrics.density).roundToInt()
        val padY = (PillView.PAD_Y_DP * resources.displayMetrics.density).roundToInt()
        val sorted = lines.sortedWith(compareBy({ it.box.top }, { it.box.left }))
        val n = sorted.size
        sorted.forEachIndexed { i, line ->
            val pill = PillView(this, line)
            pill.layoutParams = FrameLayout.LayoutParams(line.box.width() + 2 * padX, line.box.height() + 2 * padY).apply {
                leftMargin = line.box.left - padX - origin[0]
                topMargin = line.box.top - padY - origin[1]
            }
            pill.selectionActive = { actionMode != null }
            pill.onTap = { p, dismissOnly ->
                if (dismissOnly) actionMode?.finish() else copyLine(p, p.line.text)
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
                if (s in 0 until e) {
                    val part = pill.text.subSequence(s, e).toString()
                    pill.post { copied(pill, part) } // feedback only; the TextView performs the copy
                }
            }
            return false                      // let the TextView handle the item
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            if (actionMode === mode) actionMode = null
            pill.selecting = false
        }
    }

    private fun copyLine(pill: PillView, text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("textify", text))
        copied(pill, text)
    }

    private fun copied(pill: PillView, text: String) {
        if (closing) return
        pill.playCopied()
        toastIn(text)
    }

    private fun showClose() {
        b.close.isEnabled = true
        b.close.fade(1f, XIN, EMPHASIZED)
        b.close.animate().scaleX(1f).scaleY(1f).rotation(0f).setDuration(XIN).setInterpolator(X_OVERSHOOT)
    }

    private fun showHint() {
        b.hint.apply { visibility = View.VISIBLE; alpha = 0f; scaleX = 0.92f; scaleY = 0.92f }
        b.hint.fade(1f, HINT_IN, EMPHASIZED)
        b.hint.animate().scaleX(1f).scaleY(1f).setDuration(HINT_IN).setInterpolator(HINT_OVERSHOOT)
    }

    private fun toastIn(text: String) {
        val t = b.toast
        val drop = 10f * resources.displayMetrics.density
        t.removeCallbacks(hideToast)
        b.toastText.text = text
        t.animate().cancel()
        t.apply { visibility = View.VISIBLE; alpha = 0f; translationY = drop; scaleX = 0.92f; scaleY = 0.92f }
        t.fade(1f, TOAST_IN, EMPHASIZED)
        t.animate().translationY(0f).scaleX(1f).scaleY(1f).setDuration(TOAST_IN).setInterpolator(TOAST_OVERSHOOT)
        t.postDelayed(hideToast, TOAST_IN + TOAST_HOLD)   // a2: hide at toastIn + toastHold after show
    }

    private fun toastOut() {
        val t = b.toast
        val drop = 10f * resources.displayMetrics.density
        t.fade(0f, TOAST_OUT, EASE_IN)
        t.animate().translationY(drop).scaleX(0.92f).scaleY(0.92f).setDuration(TOAST_OUT).setInterpolator(EASE_IN)
    }

    /** a2 scrim: RadialGradient #1E1B4B 5% centre -> 16%, CSS radial-gradient(120% 80% at 50% 50%). */
    private fun scrimDrawable() = ShapeDrawable(RectShape()).apply {
        shaderFactory = object : ShapeDrawable.ShaderFactory() {
            override fun resize(width: Int, height: Int): Shader {
                val w = max(1, width).toFloat()
                val h = max(1, height).toFloat()
                return RadialGradient(w / 2f, h / 2f, 1.2f * w, argb(0.05f, INK), argb(0.16f, INK), Shader.TileMode.CLAMP).apply {
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

    private fun View.fade(to: Float, ms: Long, curve: TimeInterpolator) {
        ObjectAnimator.ofFloat(this, View.ALPHA, to).apply {
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
            b.toast.removeCallbacks(hideToast)
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
        private const val TOAST_IN = 220L
        private const val TOAST_HOLD = 1500L
        private const val TOAST_OUT = 160L
        private const val CLOSE_OUT = 300L
        private const val BOX_STAGGER = 140f

        private const val INK = 0x1E1B4B
        private val ORB_COLORS = longArrayOf(0xFFA78BFA, 0xFFF472B6, 0xFFFDBA74, 0xFF67E8F9, 0xFF818CF8, 0xFFA78BFA).map { it.toInt() }.toIntArray()

        private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)         // a2 cubic-bezier(.2,0,0,1)
        private val X_OVERSHOOT = PathInterpolator(0.2f, 0f, 0f, 1.3f)      // a2 .xbtn transform
        private val HINT_OVERSHOOT = PathInterpolator(0.2f, 0f, 0f, 1.2f)   // a2 .nohint transform
        private val TOAST_OVERSHOOT = PathInterpolator(0.2f, 0f, 0f, 1.25f) // a2 .toast.show
        private val EASE_OUT = PathInterpolator(0f, 0f, 0.58f, 1f)          // CSS ease-out (scrimIn)
        private val EASE_IN = PathInterpolator(0.42f, 0f, 1f, 1f)           // CSS ease-in (scrimOut, toastOut)
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
```

Implementation notes:
- `bmp` (the non-null local) is passed to OCR; `shot` is a mutable nullable property and would not smart-cast.
- ML Kit `Task` listeners run on the main thread already; `runOnUiThread` executes immediately in that case and protects against an Area S implementation that calls back on another thread.
- `onTap` while closing: `copied()` ignores it.

- [ ] **Step 3: Run the Task 5 acceptance greps**

```bash
cd /Users/jhangyu/project/textify
F=app/src/main/java/us/jhangy/textify/SessionActivity.kt
grep -cE 'fun start\(ctx: Context, shot: Bitmap\)|fun finishActive\(\)' $F   # expect 2
grep -c 'CaptureService.onSessionEnded()' $F                                  # expect 1
grep -c 'SDK_INT' $F                                                         # expect 0
grep -c 'Ocr.recognize(this, bmp, Prefs.script(this))' $F                     # expect 1
grep -cE 'customSelectionActionModeCallback' $F                               # expect >= 1
grep -cE 'SCRIM_DELAY = 150L|SCRIM_IN = 300L|SCRIM_OUT = 300L|XIN = 240L|HINT_IN = 300L|TOAST_IN = 220L|TOAST_HOLD = 1500L|TOAST_OUT = 160L|CLOSE_OUT = 300L|BOX_STAGGER = 140f' $F   # expect 10
```

- [ ] **Step 4: Report to the lead for the round-2 compile gate and the on-device checklist.**

- [ ] **Step 5: Commit (only if the lead says so)**

```bash
cd /Users/jhangyu/project/textify
git rev-parse --abbrev-ref HEAD
git add app/src/main/java/us/jhangy/textify/SessionActivity.kt
git commit -m "feat(session): OCR pills, glow/scrim choreography, toast, hint, selection" -- app/src/main/java/us/jhangy/textify/SessionActivity.kt
```

### Task 6 steps

- [ ] **Step 1: Confirm the markers fail before the change**

Run: `cd /Users/jhangyu/project/textify && grep -c ResultActivity README.md`
Expected: `1` (must become `0`).

- [ ] **Step 2: Replace the "How it works" section of `README.md`**

Replace the three numbered items under `## How it works` with:
```markdown
1. `MainActivity` requests the overlay permission + screen-capture consent, lets
   you pick the OCR language, then starts `CaptureService`.
2. `CaptureService` (foreground, type `mediaProjection`) keeps one VirtualDisplay +
   ImageReader for its whole lifetime and shows a draggable bubble. Tapping the
   bubble fades it out, takes the latest frame and opens `SessionActivity`.
   Dragging the bubble onto the bottom target stops the service.
3. `SessionActivity` freezes the screen: the screenshot is shown 1:1 under hidden
   system bars with an edge glow while `Ocr` runs, then every recognised line
   becomes a pill. Tap a pill to copy the line; long-press for partial selection
   with the system copy menu. X or back closes the session and the bubble returns.
```

- [ ] **Step 3: Delete `no_text`**

Delete the line `<string name="no_text">No text found.</string>` from `app/src/main/res/values/strings.xml` (its only user, the old `CaptureService`, is rewritten by Area S round 2 in parallel).

- [ ] **Step 4: Run the Task 6 acceptance greps**

```bash
cd /Users/jhangyu/project/textify
grep -c ResultActivity README.md          # expect 0
grep -c SessionActivity README.md         # expect >= 1
grep -c 'name="no_text"' app/src/main/res/values/strings.xml   # expect 0
grep -rn 'R.string.no_text\|@string/no_text' app/src  # expect no output once Area S round 2 is done
grep -r ResultActivity app/src            # expect no output once Area S round 2 is done
```

- [ ] **Step 5: Commit (only if the lead says so)**

```bash
cd /Users/jhangyu/project/textify
git rev-parse --abbrev-ref HEAD
git add README.md app/src/main/res/values/strings.xml
git commit -m "docs: README for capture session; drop unused strings" -- README.md app/src/main/res/values/strings.xml
```

---

## Self-review

1. **Spec coverage.** Full-screen + hidden bars + cutout + 1:1 → Task 2 (Evidence C). Layers screenshot/scrim/pills/glow/X/toast-hint → Task 1 layout. Glow bloom/breath/settle/residual → Task 3. Pill look, transparent selectable text, highlight, auto-size, padding 7×3, press 1.03, shine + flash → Task 4. OCR in the activity with `Prefs.script(this)`, scrim in/out, settle, stagger, xIn, hint, tap copy, long-press system selection, toast, empty-space no-op, X/back close, closeOut, `onSessionEnded`, bitmap recycle, holder cleared when read → Tasks 2 + 5. `finishActive` → Task 2. `ResultActivity` + layout + `copy`/`close` strings deleted, README → Tasks 1 + 6. Spec items owned by Area S (bubble fade, persistent VirtualDisplay, drag-to-dismiss) are out of scope here. One deliberate ordering choice: the spec puts "X fades in (xIn 240)" at OCR done; the a2 mockup shows X at capture start. The plan follows the spec; back closes the session during OCR.
2. **Placeholder scan.** No placeholder markers or cross-task "same as" references; every code step contains complete code; every acceptance item is a command with an expected value.
3. **Type consistency.** `PillView(context, line)`, `onTap: ((PillView, Boolean) -> Unit)?`, `selectionActive: () -> Boolean`, `selecting`, `playIn(Long)`, `playCopied()`, `PAD_X_DP`/`PAD_Y_DP` match between Task 4 and Task 5. `EdgeGlowView.activate()/settle()` match Task 3 and Task 5. Binding ids in Task 5 (`root, shot, scrim, pills, glow, close, hint, hintOrb, toast, toastOrb, toastText`) match the Task 1 layout. Contract signatures `start(ctx: Context, shot: Bitmap)`, `finishActive()`, `CaptureService.onSessionEnded()`, `Ocr.recognize(ctx, bitmap, script, onResult: (List<OcrLine>) -> Unit)`, `OcrLine(text, box: Rect)` are used verbatim.
