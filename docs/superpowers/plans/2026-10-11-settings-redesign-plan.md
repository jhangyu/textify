# Settings / Permission / Language / Icon Redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use podium:team-spawn (recommended) or podium:team-fable to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the old Start/Stop `MainActivity` with an auto-routing launch flow + Halo onboarding, a merged Halo settings screen (overlay permission + multi-select OCR languages) reachable from the session and the launcher, multi-recognizer OCR with IoU merge, and the final icon set.

**Architecture:** Round 1 builds shared, not-yet-used pieces (icons, glow/pill/halo painters, demo/track/language views, pure OCR merge + first unit test) while the old app keeps working. Round 2 switches the app over in one convergent step (service, OCR, prefs, launcher, settings, session button, resources). All drawing uses one code path for API 26–36: blurred bitmaps rendered with `BlurMaskFilter` on a software canvas, gradients via `LinearGradient`/`RadialGradient`/`SweepGradient`.

**Tech Stack:** Kotlin 2.2.0, AGP 8.13.2, compileSdk 36, minSdk 26, targetSdk 36, androidx.core-ktx 1.13.1, appcompat 1.7.0, activity-ktx 1.9.3, ViewBinding, ML Kit text-recognition-{chinese,japanese,korean} 16.0.1. New: `testImplementation("junit:junit:4.13.2")` (Maven Central latest, verified).

**Decisions (hard-to-reverse only):**
- Glow rendering: RenderEffect / `setShadowLayer` / tinted outline shadows (mockup notes, API-gated) vs **chosen** pre-blurred software bitmaps (existing `PillView`/`EdgeGlowView` technique) generalised in `Glow.kt`. Driver: project rule "one code path API 26–36". Drawback: one small bitmap per glowing element, rebuilt on size change.
- Demo / preview content: pre-rendered bitmaps (mockup notes) vs **chosen** real `TextView`s in XML with pills drawn from their text-layout line bounds. Driver: no binary assets, text stays translatable, pills track real glyph extents. Drawback: views must be laid out before pills can be placed (handled in `onLayout`).
- OCR merge location: `object Ocr` vs **chosen** top-level `mergeOcr` in `OcrMerge.kt` with plain-`Int` boxes. Driver: JVM-testable without Android stubs and lets the test land in round 1 without touching `Ocr.kt`.
- Toasts: custom orb-layout toast (mockup) vs **chosen** `Toast.makeText` text toasts. Driver: custom-view toasts from the background are blocked at targetSdk ≥ 30 (spec §12).

Plan gate: `/Users/jhangyu/.claude/plugins/cache/jhangyu/podium/1.1.0/protocols/review.md` §L0 Plan gate.

Spec: `docs/superpowers/specs/2026-10-11-settings-redesign-impl-spec.md` (cited §n). Outline (approved): `docs/superpowers/plans/2026-10-11-settings-redesign-outline.md`. Execution plan: `docs/superpowers/plans/2026-10-11-settings-redesign-execution.md`.

## Global Constraints

- Repo `/Users/jhangyu/project/textify`, branch `main`. Paths below: `java/…` = `app/src/main/java/us/jhangy/textify/…`, `res/…` = `app/src/main/res/…`.
- Build gate (gate-runner only; implementers never run Gradle — it exceeds the foreground limit): `cd /Users/jhangyu/project/textify && JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug testDebugUnitTest; echo "RC=$?"`. Pass = `RC=0`.
- One code path API 26–36. `Build.VERSION.SDK_INT` may appear only in the two places listed in spec §11 (both in round-2 `MainActivity.kt`). Never use `RenderEffect`, `setShadowLayer`, `outlineSpotShadowColor`, `HapticFeedbackConstants.REJECT`, `Typeface.create(…, weight, …)`, `android:textFontWeight`.
- Font weights: CSS weight ≥ 600 → `Typeface.DEFAULT_BOLD` / `android:textStyle="bold"`; 500 and below → normal. (Weight-specific typefaces need API 28.)
- Units: CSS px = dp, CSS font px = sp. Colours `#RRGGBB a%` = `withAlpha(a, 0xRRGGBB)`. CSS easing → `PathInterpolator`: `ease` (.25,.1,.25,1), `ease-out` (0,0,.58,1), `ease-in` (.42,0,1,1), emphasized (.2,0,0,1).
- CSS `linear-gradient(θdeg, …)`: direction `(sin θ, −cos θ)`, line length `|w·sin θ| + |h·cos θ|`, centred on the box (implemented once in `CssGradientDrawable`).
- Glows outside a view's bounds need `clipChildren=false` and `clipToPadding=false` on every ancestor up to the nearest one that is large enough (the session pill container precedent).
- Light theme only; all fades ≤ 400 ms; no solid outline frames; UI copy zh-TW from spec §10.1 (round 1 adds no strings — round-1 views take text from their callers' layouts).
- Unreleased: delete replaced code fully; no compatibility shims except the round-1 compatibility of existing public APIs listed in "Round-1 compatibility contract" (needed because round-2 owners replace the callers).
- Git (shared tree, every unit): `git rev-parse --abbrev-ref HEAD` must print `main`; `git add <own paths>` then `git commit -m "…" -- <own paths>`; deletions via `git rm <path>` and the same pathspec commit; `git status --porcelain -- <own paths>` empty afterwards. Forbidden: `git stash` (incl. `stash list`), `reset`, `checkout --`, `clean`, `add -A`, `push`. Commit message ends with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>` (or the attribution line the dispatch prompt gives).
- No sleep / polling. Report `READY_FOR_SIGNOFF` or `BLOCKED` to the lead; the lead signs off and closes tickets.
- Baton sizes: "judgment" units carry ≤ 2 tickets per holder, "mechanical" units ≤ 4 (CLAUDE.md §6). At the limit write a baton file `docs/logs/settings-redesign-baton-<unit>.md` (contract verbatim + done evidence + failure trail + red lines) and report `READY_FOR_HANDOFF`.

## Frozen interfaces (round 1)

R1-B lands every `Glow.kt` / `EdgeGlowView.kt` signature below in its **first commit** (bodies complete or minimal-but-compiling, never `TODO()`), before R1-C / R1-D compile against them. Round-2 units code against this table, not against spec §8 code blocks; where they differ, **this table wins** (spec §8 is amended to point here).

```kotlin
// ---------------- java/Glow.kt (R1-B) ----------------
internal fun withAlpha(alpha: Float, rgb: Int): Int                  // ((alpha*255).roundToInt() shl 24) or (rgb and 0xFFFFFF)
internal fun Context.dpToPx(dp: Float): Float                        // dp * resources.displayMetrics.density
internal const val GLOW_SCALE = 0.5f                                  // glow bitmaps are rendered at half resolution
internal fun glowPad(blurPx: Float, spreadPx: Float, dyPx: Float): Float   // blurPx + max(0, spreadPx) + |dyPx|
/** CSS box-shadow `dy blur spread color` around a w×h round rect (cornerPx < 0 = h/2). The bitmap covers the
 *  element padded by glowPad on every side, at GLOW_SCALE; draw it into RectF(-pad, -pad, w+pad, h+pad).
 *  clearInside erases the element's own rect (CSS box-shadow never paints under the element). */
internal fun glowBitmap(w: Float, h: Float, cornerPx: Float, blurPx: Float, color: Int,
                        spreadPx: Float = 0f, dyPx: Float = 0f, clearInside: Boolean = true): Bitmap

class PillPainter(fromColor: Int, toColor: Int, glowColor: Int, glowRadiusPx: Float, cornerPx: Float /* < 0 = h/2 */) {
    fun setSize(w: Int, h: Int)                                       // rebuilds shader + glow; call from onSizeChanged/onBoundsChange
    fun draw(canvas: Canvas, alpha: Float = 1f, scale: Float = 1f, withGlow: Boolean = true)  // box (0,0,w,h) of the current canvas, scale about its centre
}
class CssGradientDrawable(colors: IntArray, positions: FloatArray?, angleDeg: Float, cornerPx: Float /* < 0 = h/2 */) : Drawable() {
    companion object { fun solid(color: Int, cornerPx: Float): CssGradientDrawable }
}
class GlowDrawable(fill: Drawable, glowColor: Int, blurPx: Float, dyPx: Float, spreadPx: Float, cornerPx: Float /* < 0 = h/2 */) : Drawable()
data class MistSpot(val color: Int, val cx: Float, val cy: Float, val rwDp: Float, val rhDp: Float)   // cx, cy = fractions of bounds
class MistBackground(spots: List<MistSpot>, base: Int, density: Float) : Drawable()
object HaloStyle {
    val MIST: Int                                                     // 0xFFFAF5FF
    val ONBOARDING_SPOTS: List<MistSpot>                              // spec §3.1
    val SETTINGS_SPOTS: List<MistSpot>                                // spec §7.1
    fun page(ctx: Context, spots: List<MistSpot>): Drawable           // MistBackground(spots, MIST, density)
    fun cta(ctx: Context): Drawable                                   // spec §8.3 cta
    fun card(ctx: Context): Drawable                                  // spec §7.1 card
    fun chip(ctx: Context): Drawable                                  // spec §7.2 chip
}

// ---------------- java/EdgeGlowView.kt (R1-B) ----------------
data class HaloRecipe(
    val cornerDp: Float, val insetDp: Float, val haloBlurDp: Float, val coreBlurDp: Float,
    val haloActiveDp: Float, val haloBreathDp: Float, val haloBloomDp: Float, val haloSettledDp: Float,
    val alphaActive: Float, val alphaSettled: Float,
    val coreDp: Float, val coreBreathDp: Float, val coreAlpha: Float,
    val residualSpin: Float, val extendBottomDp: Float,
) { companion object { val SESSION: HaloRecipe; val ONBOARDING_MINI: HaloRecipe; val SETTINGS_MINI: HaloRecipe; val PREVIEW_EDGE: HaloRecipe } }
class EdgeGlowView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var recipe: HaloRecipe                                            // default SESSION
    fun activate(); fun settle()                                      // unchanged contract (SessionActivity uses these)
    fun fadeOut(ms: Long); fun showSettled(); fun clear()
}

// ---------------- java/PillView.kt (R1-B) — public surface unchanged ----------------
class PillView(context: Context, val line: OcrLine) : AppCompatTextView(context) {
    var onTap: ((pill: PillView, dismissOnly: Boolean) -> Unit)?; var selectionActive: () -> Boolean; var selecting: Boolean
    fun playIn(delayMs: Long); fun playCopied()
    companion object { const val PAD_X_DP = 7f; const val PAD_Y_DP = 3f }
}

// ---------------- java/CaptureDemoView.kt (R1-C) ----------------
data class DemoSpec(
    val halo: HaloRecipe, val stageCornerDp: Float, val screenCornerDp: Float, val screenShadowBlurDp: Float, val screenShadowDyDp: Float,
    val msgCornerDp: Float, val bubbleDp: Float, val bubbleRightDp: Float, val bubbleTopDp: Float, val glyphDp: Float,
    val pillPadXDp: Float, val pillPadYDp: Float, val pulseDp: Float,
) { companion object { val ONBOARDING: DemoSpec; val SETTINGS: DemoSpec } }
class CaptureDemoView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    fun configure(spec: DemoSpec)                                     // call once after inflation, before the first layout
    fun setGranted(granted: Boolean, animate: Boolean)
    fun pause(); fun resume(); fun flash()
}
// ---------------- java/StepTrackView.kt (R1-C) ----------------
class StepTrackView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    enum class State { CURRENT, DENIED, GRANTED }
    fun setLabels(first: CharSequence, second: CharSequence)
    fun setState(state: State, animate: Boolean)
}

// ---------------- java/LanguagePreviewView.kt, LanguageToggleView.kt, SpeedLanesView.kt (R1-D) ----------------
class LanguagePreviewView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    fun setScripts(scripts: Set<Script>, animate: Boolean)
}
class LanguageToggleView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    var checked: Boolean; private set
    fun setChecked(value: Boolean, animate: Boolean)
    fun reject()
}
class SpeedLanesView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    fun setLabels(zh: CharSequence, ja: CharSequence, ko: CharSequence)
    fun setScripts(scripts: Set<Script>, animate: Boolean)
}

// ---------------- java/OcrMerge.kt (R1-E) ----------------
internal data class OcrCandidate(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val confidence: Float)
internal fun iou(a: OcrCandidate, b: OcrCandidate): Float
internal fun mergeOcr(candidates: List<OcrCandidate>): List<OcrCandidate>
```

### XML contracts of the round-1 views (round-2 layouts must follow these; children are found by `android:tag`, so round 1 needs no id resources)
| view | required children (by `android:tag`) | the view itself sets |
|---|---|---|
| `CaptureDemoView` (it **is** the stage box: size/margins from the layout) | `demoScreen` — the mini screen (vertical container, fixed size, `layout_gravity="center_horizontal"`, top margin); inside it a view tagged `avatar` and message `TextView`s tagged `line` (incoming) or `line_me` (outgoing) in stagger order; `demoChip` — the denied chip `TextView` (`layout_gravity="center"`) | own background (stage gradient), `clipChildren=false`; `demoScreen` background (fill `#F3F5F8` + shadow); `avatar` background; message backgrounds and `line_me` text colour; chip background + alpha; adds an `EdgeGlowView` over `demoScreen` |
| `StepTrackView` | none (`layout_height="wrap_content"` → 24dp) | everything |
| `LanguagePreviewView` | `previewShot` — vertical container (padding 10/12/0 from the layout, child spacing via margins 8dp); inside it a view tagged `avatar` and four row `FrameLayout`s tagged `zh`, `en`, `ja`, `ko` (padding 5dp/10dp, `layout_gravity` start for zh/ja, end for en/ko), each containing a `TextView` tagged `text` and a `TextView` tagged `tag` | adds an `EdgeGlowView` (`PREVIEW_EDGE`) behind the shot; shot background (white r18) + 5% ink scrim; row backgrounds (+ pills); tag backgrounds + position + alpha; text alpha; avatar background; clip flags |
| `LanguageToggleView` | a centred container with `TextView`s tagged `glyph` and `sub` | fill layers, text colours, press scale, accessibility role |
| `SpeedLanesView` | none | everything |

### Round-1 compatibility contract (old code must keep compiling and behaving)
- `SessionActivity.kt` and `activity_session.xml` are **not edited** in round 1; they keep using `EdgeGlowView` from XML with `activate()`/`settle()`, `PillView(this, line)`, `PillView.PAD_X_DP/PAD_Y_DP`, `playIn`, `playCopied`, `onTap`, `selectionActive`, `selecting`, `customSelectionActionModeCallback`, and their own private `argb`. `EdgeGlowView.recipe` defaults to `HaloRecipe.SESSION`, which reproduces today's constants exactly.
- `MainActivity.kt`, `Prefs.kt`, `Ocr.kt`, `CaptureService.kt`, `strings.xml`, `themes.xml`, `AndroidManifest.xml` are not edited in round 1.
- Check (R1 reviewer): `git diff --stat <round-1 base>..HEAD -- app/src/main/java/us/jhangy/textify/SessionActivity.kt app/src/main/java/us/jhangy/textify/MainActivity.kt app/src/main/java/us/jhangy/textify/CaptureService.kt app/src/main/java/us/jhangy/textify/Ocr.kt app/src/main/java/us/jhangy/textify/Prefs.kt app/src/main/res/layout/activity_session.xml app/src/main/res/values app/src/main/AndroidManifest.xml` prints nothing, and the round-1 build gate passes.

## File map
| file | status | unit |
|---|---|---|
| `res/drawable/ic_launcher_background.xml`, `ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml` | create | R1-A |
| `res/drawable/ic_launcher_bg.xml`, `ic_launcher_fg.xml`, `res/mipmap-anydpi-v26/ic_launcher_round.xml` | delete | R1-A |
| `res/mipmap-anydpi-v26/ic_launcher.xml` | modify | R1-A |
| `res/drawable/ic_scan.xml` | modify | R1-A |
| `res/drawable/avd_scan_in.xml`, `ic_stat_textify.xml`, `ic_session_settings.xml`, `ic_back.xml`, `ic_check.xml`, `res/interpolator/emphasized.xml` | create | R1-A |
| `res/layout/bubble.xml`, `java/Bubble.kt` | modify | R1-A |
| `java/Glow.kt` | create | R1-B |
| `java/EdgeGlowView.kt`, `java/PillView.kt` | modify | R1-B |
| `java/CaptureDemoView.kt`, `java/StepTrackView.kt` | create | R1-C |
| `java/LanguagePreviewView.kt`, `java/LanguageToggleView.kt`, `java/SpeedLanesView.kt` | create | R1-D |
| `java/OcrMerge.kt`, `app/src/test/java/us/jhangy/textify/OcrMergeTest.kt` | create | R1-E |
| `app/build.gradle.kts` | modify | R1-E |
| round-2 files | see Round 2 | R2-A … R2-E |

(`res/interpolator/emphasized.xml` is the one file added to R1-A beyond the outline: the AVD needs the `(.2,0,0,1)` curve and the platform has no equivalent interpolator.)

---

## Round 1 — foundations (5 implementers + 1 reviewer, parallel)

Round-1 base: the commit the lead records when dispatching round 1 (`git rev-parse HEAD`). Round-1 exit = all five units signed off, build gate `RC=0`, compatibility check above clean.

### Unit R1-A: Icons and bubble glyph — mechanical, 2 tickets (T1 icons + deletions = Steps 1–6, 9; T2 bubble glyph + entrance = Steps 7, 8, 10, 11), commit after each

**Files:**
- Create: `res/drawable/ic_launcher_background.xml`, `res/drawable/ic_launcher_foreground.xml`, `res/drawable/ic_launcher_monochrome.xml`, `res/drawable/avd_scan_in.xml`, `res/drawable/ic_stat_textify.xml`, `res/drawable/ic_session_settings.xml`, `res/drawable/ic_back.xml`, `res/drawable/ic_check.xml`, `res/interpolator/emphasized.xml`
- Modify: `res/mipmap-anydpi-v26/ic_launcher.xml`, `res/drawable/ic_scan.xml`, `res/layout/bubble.xml`, `java/Bubble.kt`
- Delete: `res/drawable/ic_launcher_bg.xml`, `res/drawable/ic_launcher_fg.xml`, `res/mipmap-anydpi-v26/ic_launcher_round.xml`

**Interfaces:** Produces drawable names `ic_launcher_background`, `ic_launcher_foreground`, `ic_launcher_monochrome`, `ic_scan` (static final frame), `avd_scan_in`, `ic_stat_textify`, `ic_session_settings`, `ic_back`, `ic_check` (used by R1-C `StepTrackView` by name); interpolator `@interpolator/emphasized`. Consumes nothing new.

**Behavior:** Pure resource copy of spec §9 (values from `icon-final-3-diagonal-veil.html` VECTOR DATA). The manifest has no `android:roundIcon` today, so deleting `ic_launcher_round.xml` keeps the build green; R2-E adds `roundIcon="@mipmap/ic_launcher"`. The bubble glyph entrance plays on every `Bubble.fadeIn()`.

**Constraints:** Path data byte-for-byte as below. No PNGs. `ic_stat_textify` is white-only.

**Acceptance criteria:**
- [ ] `for p in 'M54,23a31,31 0 1,1 0,62a31,31 0 1,1 0,-62Z' 'M54,27a27,27 0 1,1 0,54a27,27 0 1,1 0,-54Z' 'M9.4,7.6h5.2a1.8,1.8 0 0 1 0,3.6h-5.2a1.8,1.8 0 0 1 0,-3.6z' 'M12,9.5a1.1,1.1 0 0 1 1.1,1.1v5.1a1.1,1.1 0 0 1 -2.2,0v-5.1a1.1,1.1 0 0 1 1.1,-1.1z' 'M5,8H12M16,8H19M5,16H8M12,16H19'; do grep -rlF "$p" app/src/main/res/drawable >/dev/null || echo "MISSING $p"; done` prints nothing.
- [ ] `ls app/src/main/res/drawable/ic_launcher_bg.xml app/src/main/res/drawable/ic_launcher_fg.xml app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml 2>&1 | grep -c 'No such file'` = 3, and `grep -rn "ic_launcher_bg\|ic_launcher_fg\|ic_launcher_round" app/src` prints nothing.
- [ ] `grep -c '<monochrome' app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` = 1.
- [ ] `grep -c 'android:padding="9dp"' app/src/main/res/layout/bubble.xml` = 1 and `grep -c '@drawable/avd_scan_in' app/src/main/res/layout/bubble.xml` = 1.
- [ ] `grep -n "reset(); start()" app/src/main/java/us/jhangy/textify/Bubble.kt` hits inside `fun fadeIn`.
- [ ] Round-1 build gate `RC=0` (AAPT compiles every vector/AVD).

**Steps:**

- [ ] **Step 1: Confirm markers fail before the change**
```bash
cd /Users/jhangyu/project/textify
test -e app/src/main/res/drawable/ic_launcher_background.xml; echo "bg_rc=$?"; grep -c 'android:padding="9dp"' app/src/main/res/layout/bubble.xml
```
Expected: `bg_rc=1` and `0`.

- [ ] **Step 2: Create `res/interpolator/emphasized.xml`**
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- cubic-bezier(.2,0,0,1), the a2/Halo emphasized curve -->
<pathInterpolator xmlns:android="http://schemas.android.com/apk/res/android"
    android:controlX1="0.2" android:controlY1="0" android:controlX2="0" android:controlY2="1" />
```

- [ ] **Step 3: Create `res/drawable/ic_launcher_background.xml`** (spec §9.1)
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- icon-final-3-diagonal-veil: diagonal spectrum veil + white core + light edge fade (no shape-dependent edge) -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z" android:fillColor="#FAF5FF" />
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="18" android:startY="18" android:endX="90" android:endY="90">
                <item android:offset="0" android:color="#BFA78BFA" />
                <item android:offset="0.35" android:color="#9EF472B6" />
                <item android:offset="0.65" android:color="#B3FDBA74" />
                <item android:offset="1" android:color="#B367E8F9" />
            </gradient>
        </aapt:attr>
    </path>
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="radial" android:centerX="54" android:centerY="54" android:gradientRadius="31">
                <item android:offset="0" android:color="#FFFFFFFF" />
                <item android:offset="0.85" android:color="#D9FFFFFF" />
                <item android:offset="1" android:color="#00FFFFFF" />
            </gradient>
        </aapt:attr>
    </path>
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="radial" android:centerX="54" android:centerY="54" android:gradientRadius="64">
                <item android:offset="0.6" android:color="#00FAF5FF" />
                <item android:offset="1" android:color="#99FAF5FF" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
```

- [ ] **Step 4: Create `res/drawable/ic_launcher_foreground.xml`** (spec §9.2)
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- icon-2 Halo Orb foreground: frosted disc + violet scan glyph (ring removed) -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M54,23a31,31 0 1,1 0,62a31,31 0 1,1 0,-62Z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="radial" android:centerX="54" android:centerY="56" android:gradientRadius="31">
                <item android:offset="0" android:color="#247C3AED" />
                <item android:offset="0.82" android:color="#247C3AED" />
                <item android:offset="1" android:color="#007C3AED" />
            </gradient>
        </aapt:attr>
    </path>
    <path android:pathData="M54,27a27,27 0 1,1 0,54a27,27 0 1,1 0,-54Z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="radial" android:centerX="54" android:centerY="44" android:gradientRadius="34">
                <item android:offset="0.55" android:color="#FFFFFFFF" />
                <item android:offset="1" android:color="#FFF3EDFF" />
            </gradient>
        </aapt:attr>
    </path>
    <group android:translateX="33" android:translateY="33" android:scaleX="1.75" android:scaleY="1.75">
        <path
            android:pathData="M4,8V6a2,2 0,0 1,2 -2h2M16,4h2a2,2 0,0 1,2 2v2M20,16v2a2,2 0,0 1,-2 2h-2M8,20H6a2,2 0,0 1,-2 -2v-2M8.5,9h7M12,9v7"
            android:fillColor="#00000000"
            android:strokeColor="#7C3AED"
            android:strokeWidth="2.2"
            android:strokeLineCap="round"
            android:strokeLineJoin="round" />
    </group>
</vector>
```

- [ ] **Step 5: Create `res/drawable/ic_launcher_monochrome.xml`** (spec §9.3)
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Android 13 themed icon: glyph only, no disc/ring -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <group android:translateX="27" android:translateY="27" android:scaleX="2.25" android:scaleY="2.25">
        <path
            android:pathData="M4,8V6a2,2 0,0 1,2 -2h2M16,4h2a2,2 0,0 1,2 2v2M20,16v2a2,2 0,0 1,-2 2h-2M8,20H6a2,2 0,0 1,-2 -2v-2M8.5,9h7M12,9v7"
            android:fillColor="#00000000"
            android:strokeColor="#FF000000"
            android:strokeWidth="2.2"
            android:strokeLineCap="round"
            android:strokeLineJoin="round" />
    </group>
</vector>
```

- [ ] **Step 6: Replace `res/mipmap-anydpi-v26/ic_launcher.xml`; delete the old launcher art**
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- monochrome is ignored below API 33. android:roundIcon (R2-E) points at this same icon. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
```
```bash
cd /Users/jhangyu/project/textify
git rm app/src/main/res/drawable/ic_launcher_bg.xml app/src/main/res/drawable/ic_launcher_fg.xml app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
```

- [ ] **Step 7: Replace `res/drawable/ic_scan.xml`** (spec §9.5, Lumen Pill; static state = final frame, shine invisible)
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- icon-3 Lumen Pill bubble glyph. Group/path names are animation targets of avd_scan_in.xml. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path
        android:pathData="M4,8V6a2,2 0,0 1,2 -2h2M16,4h2a2,2 0,0 1,2 2v2M20,16v2a2,2 0,0 1,-2 2h-2M8,20H6a2,2 0,0 1,-2 -2v-2"
        android:fillColor="#00000000"
        android:strokeWidth="2.4"
        android:strokeLineCap="round"
        android:strokeLineJoin="round">
        <aapt:attr name="android:strokeColor">
            <gradient android:type="linear" android:startX="4" android:startY="4" android:endX="20" android:endY="20">
                <item android:offset="0" android:color="#FF7C3AED" />
                <item android:offset="1" android:color="#FF4C1D95" />
            </gradient>
        </aapt:attr>
    </path>
    <group android:name="stem">
        <path
            android:name="stemPath"
            android:pathData="M12,9.5a1.1,1.1 0 0 1 1.1,1.1v5.1a1.1,1.1 0 0 1 -2.2,0v-5.1a1.1,1.1 0 0 1 1.1,-1.1z"
            android:fillColor="#7C3AED" />
    </group>
    <group android:name="pill" android:pivotX="7.6">
        <path android:pathData="M9.4,7.6h5.2a1.8,1.8 0 0 1 0,3.6h-5.2a1.8,1.8 0 0 1 0,-3.6z">
            <aapt:attr name="android:fillColor">
                <gradient android:type="linear" android:startX="7.6" android:startY="0" android:endX="16.4" android:endY="0">
                    <item android:offset="0" android:color="#FFA78BFA" />
                    <item android:offset="0.55" android:color="#FFF472B6" />
                    <item android:offset="1" android:color="#FFFDBA74" />
                </gradient>
            </aapt:attr>
        </path>
    </group>
    <group android:name="shineClip">
        <clip-path android:pathData="M9.4,7.6h5.2a1.8,1.8 0 0 1 0,3.6h-5.2a1.8,1.8 0 0 1 0,-3.6z" />
        <group android:name="shine">
            <path
                android:name="shinePath"
                android:pathData="M7.6,7.6h4v3.6h-4z"
                android:fillAlpha="0">
                <aapt:attr name="android:fillColor">
                    <gradient android:type="linear" android:startX="7.6" android:startY="0" android:endX="11.6" android:endY="0">
                        <item android:offset="0" android:color="#00FFFFFF" />
                        <item android:offset="0.5" android:color="#E6FFFFFF" />
                        <item android:offset="1" android:color="#00FFFFFF" />
                    </gradient>
                </aapt:attr>
            </path>
        </group>
    </group>
</vector>
```

- [ ] **Step 8: Create `res/drawable/avd_scan_in.xml`** (spec §9.5 timings: pill 300; stem 220 delay 120; shine 380 delay 260 `(.4,0,.2,1)` = `@android:interpolator/fast_out_slow_in`)

Sequential sets with a 0-ms first animator put stem and shine into their start values at t = 0, so nothing shows its final state during the start delay.
```xml
<?xml version="1.0" encoding="utf-8"?>
<animated-vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:drawable="@drawable/ic_scan">
    <target android:name="pill">
        <aapt:attr name="android:animation">
            <objectAnimator android:propertyName="scaleX" android:valueType="floatType"
                android:valueFrom="0" android:valueTo="1" android:duration="300"
                android:interpolator="@interpolator/emphasized" />
        </aapt:attr>
    </target>
    <target android:name="stem">
        <aapt:attr name="android:animation">
            <set android:ordering="sequentially">
                <objectAnimator android:propertyName="translateY" android:valueType="floatType"
                    android:valueFrom="-3" android:valueTo="-3" android:duration="0" />
                <objectAnimator android:propertyName="translateY" android:valueType="floatType"
                    android:valueFrom="-3" android:valueTo="0" android:startOffset="120" android:duration="220"
                    android:interpolator="@interpolator/emphasized" />
            </set>
        </aapt:attr>
    </target>
    <target android:name="stemPath">
        <aapt:attr name="android:animation">
            <set android:ordering="sequentially">
                <objectAnimator android:propertyName="fillAlpha" android:valueType="floatType"
                    android:valueFrom="0" android:valueTo="0" android:duration="0" />
                <objectAnimator android:propertyName="fillAlpha" android:valueType="floatType"
                    android:valueFrom="0" android:valueTo="1" android:startOffset="120" android:duration="220"
                    android:interpolator="@interpolator/emphasized" />
            </set>
        </aapt:attr>
    </target>
    <target android:name="shine">
        <aapt:attr name="android:animation">
            <set android:ordering="sequentially">
                <objectAnimator android:propertyName="translateX" android:valueType="floatType"
                    android:valueFrom="-4" android:valueTo="-4" android:duration="0" />
                <objectAnimator android:propertyName="translateX" android:valueType="floatType"
                    android:valueFrom="-4" android:valueTo="10" android:startOffset="260" android:duration="380"
                    android:interpolator="@android:interpolator/fast_out_slow_in" />
            </set>
        </aapt:attr>
    </target>
    <target android:name="shinePath">
        <aapt:attr name="android:animation">
            <set android:ordering="sequentially">
                <objectAnimator android:propertyName="fillAlpha" android:valueType="floatType"
                    android:valueFrom="1" android:valueTo="1" android:startOffset="260" android:duration="0" />
                <objectAnimator android:propertyName="fillAlpha" android:valueType="floatType"
                    android:valueFrom="0" android:valueTo="0" android:startOffset="380" android:duration="0" />
            </set>
        </aapt:attr>
    </target>
</animated-vector>
```

- [ ] **Step 9: Create the five small vectors** (spec §9.6)

`res/drawable/ic_stat_textify.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- icon-1 Prism Frame notification icon: white on transparent -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path
        android:pathData="M4,8V6a2,2 0,0 1,2 -2h2M16,4h2a2,2 0,0 1,2 2v2M20,16v2a2,2 0,0 1,-2 2h-2M8,20H6a2,2 0,0 1,-2 -2v-2M8.5,9h7M12,9v7"
        android:fillColor="#00000000"
        android:strokeColor="#FFFFFF"
        android:strokeWidth="2"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
</vector>
```
`res/drawable/ic_session_settings.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- settings-final session button: two slider lines + knobs -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="20dp" android:height="20dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path
        android:pathData="M5,8H12M16,8H19M5,16H8M12,16H19"
        android:fillColor="#00000000"
        android:strokeColor="#4C1D95"
        android:strokeWidth="2.2"
        android:strokeLineCap="round" />
    <path
        android:pathData="M14,6a2,2 0 1,1 0,4a2,2 0 1,1 0,-4M10,14a2,2 0 1,1 0,4a2,2 0 1,1 0,-4"
        android:fillColor="#00000000"
        android:strokeColor="#4C1D95"
        android:strokeWidth="2.2"
        android:strokeLineCap="round" />
</vector>
```
`res/drawable/ic_back.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24"
    android:autoMirrored="true">
    <path
        android:pathData="M19,12H5M11,6l-6,6 6,6"
        android:fillColor="#00000000"
        android:strokeColor="#4C1D95"
        android:strokeWidth="2.2"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
</vector>
```

`res/drawable/ic_check.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- step-track check (settings-final .ck): stroke #7C3AED 3, round -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="14dp" android:height="14dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path
        android:pathData="M5,12.5l4.5,4.5L19,7.5"
        android:fillColor="#00000000"
        android:strokeColor="#7C3AED"
        android:strokeWidth="3"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
</vector>
```

- [ ] **Step 10: Edit `res/layout/bubble.xml`** — in the `ImageView`: `android:padding="15dp"` → `android:padding="9dp"`, `android:src="@drawable/ic_scan"` → `android:src="@drawable/avd_scan_in"`. Nothing else changes.

- [ ] **Step 11: Edit `java/Bubble.kt`** — the icon becomes an `ImageView` and every fade-in replays the glyph entrance.
  - Add imports `android.graphics.drawable.AnimatedVectorDrawable` and `android.widget.ImageView`.
  - Replace `private val icon: View = bubble.findViewById(R.id.bubbleIcon)` with `private val icon: ImageView = bubble.findViewById(R.id.bubbleIcon)`.
  - Replace `fadeIn()` with:
```kotlin
    /** bubbleIn 300: visible and touchable, alpha 1 / scale 1; replays the Lumen Pill glyph entrance (avd_scan_in). */
    fun fadeIn() {
        hidden = false
        bubble.visibility = View.VISIBLE
        setTouchable(true)
        (icon.drawable as AnimatedVectorDrawable).run { reset(); start() }
        icon.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(BUBBLE_IN).setInterpolator(EMPHASIZED).start()
    }
```

- [ ] **Step 12: Re-run the acceptance greps** (all from the Acceptance list). Expected: no `MISSING`, counts as listed.

- [ ] **Step 13: Commit** (one commit for both tickets, after Step 12)
```bash
cd /Users/jhangyu/project/textify && git rev-parse --abbrev-ref HEAD
set -- app/src/main/res/drawable/ic_launcher_background.xml app/src/main/res/drawable/ic_launcher_foreground.xml app/src/main/res/drawable/ic_launcher_monochrome.xml app/src/main/res/drawable/avd_scan_in.xml app/src/main/res/drawable/ic_stat_textify.xml app/src/main/res/drawable/ic_session_settings.xml app/src/main/res/drawable/ic_back.xml app/src/main/res/drawable/ic_check.xml app/src/main/res/interpolator/emphasized.xml app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml app/src/main/res/drawable/ic_scan.xml app/src/main/res/layout/bubble.xml app/src/main/java/us/jhangy/textify/Bubble.kt app/src/main/res/drawable/ic_launcher_bg.xml app/src/main/res/drawable/ic_launcher_fg.xml app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml   # zsh- and bash-safe path list
git add -- $(for f in "$@"; do test -e "$f" && echo "$f"; done)   # deleted paths are already staged by git rm
git commit -m "feat(icons): diagonal-veil launcher icon, Lumen Pill bubble glyph with entrance, notification/settings/back vectors" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>" -- "$@"
git status --porcelain -- "$@"
```
Expected: last command prints nothing.

### Unit R1-B: Halo primitives — judgment, 2 tickets (T1 = Steps 1–2, 4, 5: `Glow.kt` + `EdgeGlowView.kt` first commit + `SIGNATURES <hash>` message; T2 = Step 3 + Step 6: `PillView` on `PillPainter`, second commit)

**Files:**
- Create: `java/Glow.kt`
- Modify: `java/EdgeGlowView.kt` (full replacement below), `java/PillView.kt` (targeted edits)

**Interfaces:** Produces every `Glow.kt` / `EdgeGlowView.kt` row of "Frozen interfaces (round 1)". Consumes nothing new. Keeps the round-1 compatibility contract for `SessionActivity`.

**Behavior:** One shared implementation for CSS-like glows (`glowBitmap`, `GlowDrawable`), CSS angle gradients (`CssGradientDrawable`), Halo pills (`PillPainter`), mist page backgrounds (`MistBackground`, `HaloStyle`) and parameterised halos (`HaloRecipe`). `PillView` and the session halo render exactly as before (same constants; `PillView` selecting fill differs only by 8-bit rounding because the 45% factor moves from the colour to the paint alpha).

**Constraints:** No `SDK_INT`, `RenderEffect`, `setShadowLayer`. `glowBitmap` draws on a software `Canvas(Bitmap)` (BlurMaskFilter). Old bitmaps are never recycled (the last recorded frame may still reference them; GC frees them) — same rule as today.

**Acceptance criteria:**
- [ ] First commit of this unit contains `Glow.kt` and `EdgeGlowView.kt` with every frozen signature: `for s in 'internal fun withAlpha(' 'internal fun Context.dpToPx(' 'internal fun glowPad(' 'internal fun glowBitmap(' 'class PillPainter(' 'class CssGradientDrawable(' 'class GlowDrawable(' 'data class MistSpot(' 'class MistBackground(' 'object HaloStyle' 'data class HaloRecipe(' 'fun fadeOut(ms: Long)' 'fun showSettled()' 'fun clear()'; do grep -rqF "$s" app/src/main/java/us/jhangy/textify/Glow.kt app/src/main/java/us/jhangy/textify/EdgeGlowView.kt || echo "MISSING $s"; done` prints nothing.
- [ ] `grep -n "HALO_BLUR_DP\|CORE_BLUR_DP\|CORNER_DP\|RESIDUAL_SPIN" app/src/main/java/us/jhangy/textify/EdgeGlowView.kt` prints nothing (moved into `HaloRecipe.SESSION`), and `grep -c "cornerDp = 36f, insetDp = 0f, haloBlurDp = 30f, coreBlurDp = 7f" app/src/main/java/us/jhangy/textify/EdgeGlowView.kt` = 1.
- [ ] `grep -n "private fun argb\|renderGlow\|fillSelecting\|BlurMaskFilter" app/src/main/java/us/jhangy/textify/PillView.kt` prints nothing; `grep -c "PillPainter(" app/src/main/java/us/jhangy/textify/PillView.kt` = 1.
- [ ] `grep -n "SDK_INT\|RenderEffect\|setShadowLayer" app/src/main/java/us/jhangy/textify/Glow.kt app/src/main/java/us/jhangy/textify/EdgeGlowView.kt app/src/main/java/us/jhangy/textify/PillView.kt` prints nothing.
- [ ] Compatibility: `git diff --stat <round-1 base>..HEAD -- app/src/main/java/us/jhangy/textify/SessionActivity.kt app/src/main/res/layout/activity_session.xml` prints nothing; round-1 build gate `RC=0`.

**Steps:**

- [ ] **Step 1: Confirm markers fail**
```bash
cd /Users/jhangyu/project/textify; test -e app/src/main/java/us/jhangy/textify/Glow.kt; echo "glow_rc=$?"; grep -c "renderGlow" app/src/main/java/us/jhangy/textify/PillView.kt
```
Expected: `glow_rc=1`, count ≥ 1.

- [ ] **Step 2: Create `java/Glow.kt`**
```kotlin
package us.jhangy.textify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Shared Halo painting helpers (spec §8.3). One code path for API 26-36: every glow is a blurred shape
 * rendered once per size with BlurMaskFilter on a software canvas, the technique PillView/EdgeGlowView use.
 */

internal fun withAlpha(alpha: Float, rgb: Int): Int = ((alpha * 255f).roundToInt() shl 24) or (rgb and 0xFFFFFF)

internal fun Context.dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

// ponytail: glow bitmaps at half resolution; they are soft blurs so the loss is invisible
internal const val GLOW_SCALE = 0.5f

internal fun glowPad(blurPx: Float, spreadPx: Float, dyPx: Float): Float = blurPx + max(0f, spreadPx) + abs(dyPx)

/**
 * CSS `box-shadow: 0 dy blur spread color` around a w×h round rect (cornerPx < 0 = h/2).
 * The bitmap covers the element padded by [glowPad] on every side at [GLOW_SCALE];
 * draw it into RectF(-pad, -pad, w + pad, h + pad). clearInside erases the element's own rect.
 */
internal fun glowBitmap(
    w: Float, h: Float, cornerPx: Float, blurPx: Float, color: Int,
    spreadPx: Float = 0f, dyPx: Float = 0f, clearInside: Boolean = true,
): Bitmap {
    val s = GLOW_SCALE
    val pad = glowPad(blurPx, spreadPx, dyPx)
    val bmp = Bitmap.createBitmap(
        max(1, ((w + 2f * pad) * s).roundToInt()),
        max(1, ((h + 2f * pad) * s).roundToInt()),
        Bitmap.Config.ARGB_8888,
    )
    val c = Canvas(bmp)
    val r = if (cornerPx < 0f) h / 2f else cornerPx
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        if (blurPx > 0f) maskFilter = BlurMaskFilter(blurPx * s, BlurMaskFilter.Blur.NORMAL)
    }
    val rs = max(0f, r + spreadPx) * s
    c.drawRoundRect(
        (pad - spreadPx) * s, (pad - spreadPx + dyPx) * s,
        (pad + w + spreadPx) * s, (pad + h + spreadPx + dyPx) * s, rs, rs, p,
    )
    if (clearInside) {
        p.maskFilter = null
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        c.drawRoundRect(pad * s, pad * s, (pad + w) * s, (pad + h) * s, r * s, r * s, p)
    }
    return bmp
}

/** Halo pill: horizontal LinearGradient fill + outer glow (CSS box-shadow 0 0 glowRadius glowColor). */
class PillPainter(
    private val fromColor: Int,
    private val toColor: Int,
    private val glowColor: Int,
    private val glowRadiusPx: Float,
    private val cornerPx: Float,
) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glowDst = RectF()
    private var glow: Bitmap? = null
    private var w = 0f
    private var h = 0f

    fun setSize(w: Int, h: Int) {
        this.w = w.toFloat()
        this.h = h.toFloat()
        if (w <= 0 || h <= 0) {
            glow = null
            return
        }
        fill.shader = LinearGradient(0f, 0f, this.w, 0f, fromColor, toColor, Shader.TileMode.CLAMP)
        glow = if (glowRadiusPx > 0f) glowBitmap(this.w, this.h, cornerPx, glowRadiusPx, glowColor) else null
        glowDst.set(-glowRadiusPx, -glowRadiusPx, this.w + glowRadiusPx, this.h + glowRadiusPx)
    }

    /** Draws into the box (0, 0, w, h) of the current canvas; alpha multiplies fill and glow; scale is about the centre. */
    fun draw(canvas: Canvas, alpha: Float = 1f, scale: Float = 1f, withGlow: Boolean = true) {
        if (w <= 0f || h <= 0f || alpha <= 0f) return
        val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        val saved = canvas.save()
        if (scale != 1f) canvas.scale(scale, scale, w / 2f, h / 2f)
        if (withGlow) glow?.let {
            glowPaint.alpha = a
            canvas.drawBitmap(it, null, glowDst, glowPaint)
        }
        val r = if (cornerPx < 0f) h / 2f else cornerPx
        fill.alpha = a
        canvas.drawRoundRect(0f, 0f, w, h, r, r, fill)
        canvas.restoreToCount(saved)
    }
}

/** Round rect filled with a CSS `linear-gradient(angleDeg, colors)` (0deg = up, clockwise). cornerPx < 0 = h/2. */
class CssGradientDrawable(
    private val colors: IntArray,
    private val positions: FloatArray?,
    private val angleDeg: Float,
    private val cornerPx: Float,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val radius: Float get() = if (cornerPx < 0f) rect.height() / 2f else cornerPx

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
        if (rect.isEmpty) return
        val t = Math.toRadians(angleDeg.toDouble())
        val dx = sin(t).toFloat()
        val dy = -cos(t).toFloat()
        val half = (abs(rect.width() * dx) + abs(rect.height() * dy)) / 2f
        val cx = rect.centerX()
        val cy = rect.centerY()
        paint.shader = LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half, colors, positions, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = paint.alpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radius)
    }

    companion object {
        fun solid(color: Int, cornerPx: Float) = CssGradientDrawable(intArrayOf(color, color), null, 180f, cornerPx)
    }
}

/** [fill] plus a CSS box-shadow drawn outside its bounds (ancestors need clipChildren=false). */
class GlowDrawable(
    private val fill: Drawable,
    private val glowColor: Int,
    private val blurPx: Float,
    private val dyPx: Float,
    private val spreadPx: Float,
    private val cornerPx: Float,
) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private var glow: Bitmap? = null

    override fun onBoundsChange(bounds: Rect) {
        fill.bounds = bounds
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) {
            glow = null
            return
        }
        val pad = glowPad(blurPx, spreadPx, dyPx)
        glow = glowBitmap(w, h, cornerPx, blurPx, glowColor, spreadPx, dyPx, clearInside = true)
        dst.set(bounds.left - pad, bounds.top - pad, bounds.right + pad, bounds.bottom + pad)
    }

    override fun draw(canvas: Canvas) {
        glow?.let { canvas.drawBitmap(it, null, dst, paint) }
        fill.draw(canvas)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        fill.alpha = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = paint.alpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getOutline(outline: Outline) {
        fill.getOutline(outline)
    }
}

/** One CSS `radial-gradient(rw rh at cx cy, color, transparent 70%)` spot; cx, cy are fractions of the bounds. */
data class MistSpot(val color: Int, val cx: Float, val cy: Float, val rwDp: Float, val rhDp: Float)

/** Mist page: solid base + elliptical radial spots (settings / onboarding backgrounds). */
class MistBackground(private val spots: List<MistSpot>, base: Int, private val density: Float) : Drawable() {
    private val basePaint = Paint().apply { color = base }
    private val paints = spots.map { Paint(Paint.DITHER_FLAG) }

    override fun onBoundsChange(bounds: Rect) {
        spots.forEachIndexed { i, s ->
            val cx = bounds.left + s.cx * bounds.width()
            val cy = bounds.top + s.cy * bounds.height()
            val rw = s.rwDp * density
            val rh = s.rhDp * density
            paints[i].shader = RadialGradient(
                cx, cy, rw, intArrayOf(s.color, s.color and 0x00FFFFFF), floatArrayOf(0f, 0.7f), Shader.TileMode.CLAMP,
            ).apply { setLocalMatrix(Matrix().apply { setScale(1f, rh / rw, cx, cy) }) }
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawRect(b, basePaint)
        paints.forEach { canvas.drawRect(b, it) }
    }

    override fun setAlpha(alpha: Int) {
        basePaint.alpha = alpha
        paints.forEach { it.alpha = alpha }
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        basePaint.colorFilter = colorFilter
        paints.forEach { it.colorFilter = colorFilter }
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Shared Halo surfaces (values: spec §3.1, §7.1, §7.2, §8.3; mockups settings-final / permission-2). */
object HaloStyle {
    val MIST: Int = 0xFFFAF5FF.toInt()

    /** permission-2 #sIntro */
    val ONBOARDING_SPOTS = listOf(
        MistSpot(withAlpha(0.30f, 0xA78BFA), 0.20f, 0.10f, 380f, 300f),
        MistSpot(withAlpha(0.22f, 0x67E8F9), 0.90f, 0.40f, 360f, 300f),
        MistSpot(withAlpha(0.20f, 0xFDBA74), 0.30f, 0.60f, 300f, 240f),
    )

    /** settings-final .settings */
    val SETTINGS_SPOTS = listOf(
        MistSpot(withAlpha(0.35f, 0xA78BFA), 0f, 0f, 360f, 300f),
        MistSpot(withAlpha(0.25f, 0xF472B6), 1f, 0.08f, 320f, 300f),
        MistSpot(withAlpha(0.28f, 0x67E8F9), 1f, 1f, 380f, 320f),
        MistSpot(withAlpha(0.28f, 0xFDBA74), 0f, 0.96f, 340f, 300f),
    )

    fun page(ctx: Context, spots: List<MistSpot>): Drawable = MistBackground(spots, MIST, ctx.resources.displayMetrics.density)

    /** CTA: linear-gradient(100deg, #7C3AED, #A855F7 55%, #EC4899), full-round, box-shadow 0 10px 26px #7C3AED 30%. */
    fun cta(ctx: Context): Drawable = GlowDrawable(
        CssGradientDrawable(intArrayOf(0xFF7C3AED.toInt(), 0xFFA855F7.toInt(), 0xFFEC4899.toInt()), floatArrayOf(0f, 0.55f, 1f), 100f, -1f),
        withAlpha(0.30f, 0x7C3AED), ctx.dpToPx(26f), ctx.dpToPx(10f), 0f, -1f,
    )

    /** Card: #FFFFFF 78% (#C7FFFFFF) r28, box-shadow 0 10px 24px -8px #7C3AED 12% (the 0 1px 2px 4% hairline layer is not drawn). */
    fun card(ctx: Context): Drawable {
        val r = ctx.dpToPx(28f)
        return GlowDrawable(
            CssGradientDrawable.solid(0xC7FFFFFF.toInt(), r),
            withAlpha(0.12f, 0x7C3AED), ctx.dpToPx(24f), ctx.dpToPx(10f), ctx.dpToPx(-8f), r,
        )
    }

    /** Denied chip: #FFFFFF 94% full-round, box-shadow 0 8px 24px #FDBA74 35%. */
    fun chip(ctx: Context): Drawable = GlowDrawable(
        CssGradientDrawable.solid(withAlpha(0.94f, 0xFFFFFF), -1f),
        withAlpha(0.35f, 0xFDBA74), ctx.dpToPx(24f), ctx.dpToPx(8f), 0f, -1f,
    )
}
```

- [ ] **Step 3: Edit `java/PillView.kt`** (public surface unchanged)
  1. Delete the line `private fun argb(alpha: Float, rgb: Int): Int = …` and replace every remaining `argb(` call in the file with `withAlpha(` (highlight colour, overlay shader, shine shader).
  2. Delete the fields `glowRadius`, `fill`, `fillSelecting`, `glowPaint`, `glowDst`, `glow`; add after `private val dp = …`:
```kotlin
    // a2 box: LinearGradient #A78BFA 20% -> #F472B6 16%, glow #A78BFA 28% r12, radius = h/2
    private val painter = PillPainter(withAlpha(0.20f, 0xA78BFA), withAlpha(0.16f, 0xF472B6), withAlpha(0.28f, 0xA78BFA), GLOW_R_DP * dp, -1f)
```
  3. In `onSizeChanged`: delete the `fill.shader = …`, `fillSelecting.shader = …`, `glow = renderGlow(fw, fh)` and `glowDst.set(…)` lines; add `painter.setSize(w, h)` right after the `if (w == 0 || h == 0) return` guard. Keep `pillPath`, `overlay.shader`, `shine.shader` code.
  4. Delete the whole `renderGlow` function and its KDoc.
  5. In `onDraw` replace the two lines
     `if (!selecting) glow?.let { canvas.drawBitmap(it, null, glowDst, glowPaint) }` and
     `canvas.drawRoundRect(0f, 0f, w, h, r, r, if (selecting) fillSelecting else fill)` with
```kotlin
        painter.draw(canvas, alpha = if (selecting) SELECTING else 1f, withGlow = !selecting)   // a2 .box.selecting: fill x.45, no glow
```
  6. Delete `private const val GLOW_SCALE = 0.5f` and its `ponytail:` comment from the companion (the shared `GLOW_SCALE` in `Glow.kt` replaces it).
  7. Remove now-unused imports: `android.graphics.Bitmap`, `android.graphics.BlurMaskFilter`, `android.graphics.PorterDuff`, `android.graphics.PorterDuffXfermode`, `android.graphics.RectF` (keep `Canvas`, `Color`, `LinearGradient`, `Paint`, `Path`, `Shader`).

- [ ] **Step 4: Replace `java/EdgeGlowView.kt` completely**
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
import androidx.core.animation.doOnEnd
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Geometry/alpha of one Halo ring (spec §8.1). Halo width = (active + breath·b + bloom·bloomTerm)·(1-mix) + settled·mix,
 * halo alpha = a·lerp(alphaActive, alphaSettled, mix); core (skipped when coreDp == 0) width = core + coreBreath·b,
 * alpha a·(1-mix)·coreAlpha; spin speed lerp(1, residualSpin, mix); ring rect = (inset, inset, w-inset, h+extendBottom-inset).
 */
data class HaloRecipe(
    val cornerDp: Float,
    val insetDp: Float,
    val haloBlurDp: Float,
    val coreBlurDp: Float,
    val haloActiveDp: Float,
    val haloBreathDp: Float,
    val haloBloomDp: Float,
    val haloSettledDp: Float,
    val alphaActive: Float,
    val alphaSettled: Float,
    val coreDp: Float,
    val coreBreathDp: Float,
    val coreAlpha: Float,
    val residualSpin: Float,
    val extendBottomDp: Float,
) {
    companion object {
        /** a2 session halo (values unchanged from the capture-session implementation). */
        val SESSION = HaloRecipe(
            cornerDp = 36f, insetDp = 0f, haloBlurDp = 30f, coreBlurDp = 7f,
            haloActiveDp = 44f, haloBreathDp = 10f, haloBloomDp = 70f, haloSettledDp = 26f,
            alphaActive = 0.75f, alphaSettled = 0.22f, coreDp = 12f, coreBreathDp = 4f, coreAlpha = 0.9f,
            residualSpin = 0.12f, extendBottomDp = 0f,
        )

        /** permission-2 mini halo: ring((26+40·bloom)(1-mix)+16·mix, blur 18, a(.75-.45mix)), core ring(7, blur 4), rect H+40, r26. */
        val ONBOARDING_MINI = HaloRecipe(
            cornerDp = 26f, insetDp = 0f, haloBlurDp = 18f, coreBlurDp = 4f,
            haloActiveDp = 26f, haloBreathDp = 0f, haloBloomDp = 40f, haloSettledDp = 16f,
            alphaActive = 0.75f, alphaSettled = 0.30f, coreDp = 7f, coreBreathDp = 0f, coreAlpha = 0.9f,
            residualSpin = 1f, extendBottomDp = 40f,
        )

        /** settings-final mini halo: (22+34·bloom)(1-mix)+14·mix, blur 15, core 6 blur 3, rect H+30, r20. */
        val SETTINGS_MINI = HaloRecipe(
            cornerDp = 20f, insetDp = 0f, haloBlurDp = 15f, coreBlurDp = 3f,
            haloActiveDp = 22f, haloBreathDp = 0f, haloBloomDp = 34f, haloSettledDp = 14f,
            alphaActive = 0.75f, alphaSettled = 0.30f, coreDp = 6f, coreBreathDp = 0f, coreAlpha = 0.9f,
            residualSpin = 1f, extendBottomDp = 30f,
        )

        /**
         * Language preview residual edge: a 10dp band inside a r24 box (centre line inset 5dp, radius 24-5 = 19),
         * blur 12, alpha .22, one turn per 20000 ms (= glowLoop 2400 / .12).
         */
        val PREVIEW_EDGE = HaloRecipe(
            cornerDp = 19f, insetDp = 5f, haloBlurDp = 12f, coreBlurDp = 0f,
            haloActiveDp = 10f, haloBreathDp = 0f, haloBloomDp = 0f, haloSettledDp = 10f,
            alphaActive = 0.22f, alphaSettled = 0.22f, coreDp = 0f, coreBreathDp = 0f, coreAlpha = 0f,
            residualSpin = 0.12f, extendBottomDp = 0f,
        )
    }
}

/**
 * a2 "Halo" edge glow, parameterised by [recipe]. One code path for API 26-36:
 * the blurred ring (halo + core) is rendered white into a 1/4-scale mask on a software canvas
 * (BlurMaskFilter is not supported on hardware canvases), then coloured by a rotating
 * SweepGradient through ComposeShader(sweep, mask, DST_IN).
 */
class EdgeGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density

    /** Set before the first layout; default = the a2 session halo. */
    var recipe: HaloRecipe = HaloRecipe.SESSION
        set(value) {
            field = value
            haloBlur = blur(value.haloBlurDp)
            coreBlur = blur(value.coreBlurDp)
            maskKey = -1L
            invalidate()
        }

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
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)   // bilinear upscale of the 1/4-scale mask
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private var haloBlur: BlurMaskFilter? = blur(recipe.haloBlurDp)
    private var coreBlur: BlurMaskFilter? = blur(recipe.coreBlurDp)

    private fun blur(rDp: Float): BlurMaskFilter? =
        if (rDp > 0f) BlurMaskFilter(rDp * dp * MASK_SCALE, BlurMaskFilter.Blur.NORMAL) else null

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

    /** Core fades out, halo settles to recipe.haloSettledDp @ alphaSettled, rotation slows to residualSpin. */
    fun settle() {
        mixAnim?.cancel()
        mixAnim = ValueAnimator.ofFloat(mix, 1f).apply {
            duration = GLOW_SETTLE
            interpolator = EASE_IN_OUT_CUBIC
            addUpdateListener { mix = it.animatedValue as Float }
            start()
        }
    }

    /** Overall alpha -> 0 over [ms] (ease-out cubic); stops animating when it reaches 0. */
    fun fadeOut(ms: Long) {
        alphaAnim?.cancel()
        alphaAnim = ValueAnimator.ofFloat(a, 0f).apply {
            duration = ms
            interpolator = EASE_OUT_CUBIC
            addUpdateListener { a = it.animatedValue as Float }
            doOnEnd { if (a <= 0.001f) running = false }
            start()
        }
    }

    /** Settled look at once (no bloom, no core), rotating at residualSpin. */
    fun showSettled() {
        alphaAnim?.cancel()
        mixAnim?.cancel()
        a = 1f
        mix = 1f
        val now = AnimationUtils.currentAnimationTimeMillis()
        t0 = now - BLOOM.toLong()
        last = now
        maskKey = -1L
        running = true
        postInvalidateOnAnimation()
    }

    /** Nothing drawn, no animation. */
    fun clear() {
        alphaAnim?.cancel()
        mixAnim?.cancel()
        a = 0f
        running = false
        invalidate()
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
        val r = recipe
        val now = AnimationUtils.currentAnimationTimeMillis()
        val dt = min(64L, now - last)
        last = now
        spin += dt * lerp(1f, r.residualSpin, mix)
        if (a > 0.001f) {
            val breath = sin(spin / BREATH * 2.0 * PI).toFloat()
            val bloom = 1f - easeOut(min(1f, (now - t0) / BLOOM))
            renderMask(
                m,
                haloWidthDp = (r.haloActiveDp + r.haloBreathDp * breath + r.haloBloomDp * bloom) * (1f - mix) + r.haloSettledDp * mix,
                haloAlpha = a * lerp(r.alphaActive, r.alphaSettled, mix),
                coreWidthDp = r.coreDp + r.coreBreathDp * breath,
                coreAlpha = if (r.coreDp > 0f) a * (1f - mix) * r.coreAlpha else 0f,
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
        val ca = if (coreAlpha > 0.01f) (coreAlpha.coerceIn(0f, 1f) * 255f).roundToInt() else 0
        val cw = if (ca > 0) (coreWidthDp * 2f).roundToInt() else 0  // invisible core: breathing must not dirty the mask
        val key = (hw.toLong() shl 48) or (ha.toLong() shl 32) or (cw.toLong() shl 16) or ca.toLong()
        if (key == maskKey && maskShader != null) return
        maskKey = key

        val c = maskCanvas ?: return
        val s = MASK_SCALE
        val mw = m.width.toFloat()
        val mh = m.height.toFloat()
        val rr = recipe.cornerDp * dp * s
        val ins = recipe.insetDp * dp * s
        val bottom = mh + recipe.extendBottomDp * dp * s - ins
        m.eraseColor(Color.TRANSPARENT)
        ring.maskFilter = haloBlur
        ring.strokeWidth = haloWidthDp * dp * s
        ring.alpha = ha
        c.drawRoundRect(ins, ins, mw - ins, bottom, rr, rr, ring)
        if (ca > 0) {
            ring.maskFilter = coreBlur
            ring.strokeWidth = coreWidthDp * dp * s
            ring.alpha = ca
            c.drawRoundRect(ins, ins, mw - ins, bottom, rr, rr, ring)
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
        // a2 header TIMINGS (shared by every recipe)
        private const val GLOW_IN = 350L
        private const val GLOW_SETTLE = 400L
        private const val BLOOM = 400f
        private const val GLOW_LOOP = 2400f
        private const val BREATH = 1200f
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
  Before writing, diff the old file's `activate`/`settle`/`onSizeChanged`/`renderMask` bodies against the above: the only intended differences are the recipe lookups, the inset/extend rect, and the new `fadeOut`/`showSettled`/`clear`. If the old file contains anything else not reproduced here, keep it and report it.

- [ ] **Step 5: First commit (frozen signatures land)** — commit `Glow.kt` and `EdgeGlowView.kt` (complete bodies; they compile with the unchanged `PillView`). Do Step 3 (`PillView`) after this commit and commit it separately with the same command pattern (`set -- app/src/main/java/us/jhangy/textify/PillView.kt`).
```bash
cd /Users/jhangyu/project/textify && git rev-parse --abbrev-ref HEAD
set -- app/src/main/java/us/jhangy/textify/Glow.kt app/src/main/java/us/jhangy/textify/EdgeGlowView.kt   # zsh- and bash-safe path list
git add -- "$@" && git commit -m "feat(halo): shared Glow helpers and HaloRecipe-driven EdgeGlowView" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>" -- "$@"
git status --porcelain -- "$@"
```
Then SendMessage team-lead exactly: `SIGNATURES <hash>` (the lead forwards the hash to R1-C and R1-D).

- [ ] **Step 6: Run the acceptance greps**; report. Build is run by the gate-runner at round exit.

### Unit R1-C: Capture demo + step track — judgment, 2 tickets (T1 `CaptureDemoView`; T2 `StepTrackView`), commit after each

**Files:**
- Create: `java/CaptureDemoView.kt`, `java/StepTrackView.kt`

**Interfaces:**
- Consumes (frozen, R1-B): `HaloRecipe.ONBOARDING_MINI`, `HaloRecipe.SETTINGS_MINI`, `EdgeGlowView(context)` + `recipe`/`activate`/`settle`/`fadeOut`/`clear`, `PillPainter`, `CssGradientDrawable`, `GlowDrawable`, `HaloStyle.chip`, `withAlpha`. Resources by name: existing `R.drawable.bubble_bg`, `R.drawable.ic_scan` (name unchanged by R1-A), new `R.drawable.ic_check` (R1-A, same round).
- Produces: `DemoSpec` (+ `ONBOARDING`, `SETTINGS`), `CaptureDemoView`, `StepTrackView` exactly as frozen; XML contract rows for both views.

**Behavior:** spec §8.2 (loop keyframes, ghost, pulse, flash) and §8.4 (track). The demo view is the stage box itself (rounded gradient, clips its children to its corner) so the mini screen's bottom and the pulse are cut exactly like the CSS `overflow:hidden`. The mini halo is clipped to the mini screen (the CSS canvas lives inside `.mini`). Pills are drawn between the mini screen and the halo; the bubble, tap dot, ghost and pulse above the halo, all clipped to the mini screen. Loop runs only while attached, granted and not paused. Entering the denied state always plays the peach pulse twice.

**Constraints:** exactly one `INFINITE` animator (the 4800 ms loop) per demo view. No `SDK_INT`. Uses `Typeface.DEFAULT_BOLD` for weight ≥ 600. Sizes in sp via `TypedValue.applyDimension` (not the deprecated `scaledDensity`).

**Acceptance criteria:**
- [ ] `grep -c "INFINITE" app/src/main/java/us/jhangy/textify/CaptureDemoView.kt` = 1; `grep -c "LOOP = 4800f" app/src/main/java/us/jhangy/textify/CaptureDemoView.kt` = 1.
- [ ] `grep -c "HaloRecipe.ONBOARDING_MINI, stageCornerDp = 34f, screenCornerDp = 26f, screenShadowBlurDp = 40f, screenShadowDyDp = 18f, msgCornerDp = 12f, bubbleDp = 36f, bubbleRightDp = 6f, bubbleTopDp = 176f, glyphDp = 20f, pillPadXDp = 5f, pillPadYDp = 2f, pulseDp = 60f" app/src/main/java/us/jhangy/textify/CaptureDemoView.kt` = 1 and the same for `HaloRecipe.SETTINGS_MINI, stageCornerDp = 24f, screenCornerDp = 20f, screenShadowBlurDp = 32f, screenShadowDyDp = 14f, msgCornerDp = 10f, bubbleDp = 30f, bubbleRightDp = 5f, bubbleTopDp = 84f, glyphDp = 17f, pillPadXDp = 4f, pillPadYDp = 2f, pulseDp = 60f`.
- [ ] Keyframe constants present: `for k in "960f" "1008f" "1104f" "1296f" "4080f" "4368f" "864f" "1248f" "PILL_IN_AT = 1488f" "PILL_OUT_AT = 3792f" "PILL_FADE = 288f" "PILL_STAGGER = 72f" "HALO_ON = 1150f" "HALO_SETTLE = 1900f" "HALO_OFF = 3800f" "HALO_OUT = 288L" "GHOST_SWAP = 300L" "PULSE = 400L" "FLASH = 400L"; do grep -qF "$k" app/src/main/java/us/jhangy/textify/CaptureDemoView.kt || echo "MISSING $k"; done` prints nothing.
- [ ] `grep -c "TRACK_FILL = 400L\|NODE_CHECK = 260L" app/src/main/java/us/jhangy/textify/StepTrackView.kt` = 2; `grep -c 'R.drawable.ic_check' app/src/main/java/us/jhangy/textify/StepTrackView.kt` = 1.
- [ ] `grep -n "SDK_INT\|scaledDensity\|setShadowLayer" app/src/main/java/us/jhangy/textify/CaptureDemoView.kt app/src/main/java/us/jhangy/textify/StepTrackView.kt` prints nothing.
- [ ] Round-1 build gate `RC=0`.

**Steps:**

- [ ] **Step 1: Read the R1-B signatures commit** whose hash the lead gives in the dispatch prompt: `git show <hash> -- app/src/main/java/us/jhangy/textify/Glow.kt app/src/main/java/us/jhangy/textify/EdgeGlowView.kt`. Code only against those signatures. Do not edit R1-B files.

- [ ] **Step 2: Create `java/CaptureDemoView.kt`**
```kotlin
package us.jhangy.textify

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Geometry of one demo variant (spec §8.2; permission-2 = ONBOARDING, settings-final = SETTINGS). */
data class DemoSpec(
    val halo: HaloRecipe,
    val stageCornerDp: Float,
    val screenCornerDp: Float,
    val screenShadowBlurDp: Float,
    val screenShadowDyDp: Float,
    val msgCornerDp: Float,
    val bubbleDp: Float,
    val bubbleRightDp: Float,
    val bubbleTopDp: Float,
    val glyphDp: Float,
    val pillPadXDp: Float,
    val pillPadYDp: Float,
    val pulseDp: Float,
) {
    companion object {
        /** permission-2: .stagebox r34, .mini r26 shadow 0 18 40 -12, .m r12, .mb 36 @ right 6 top 176, glyph 20, .ln::before -5/-2, .gp 60. */
        val ONBOARDING = DemoSpec(HaloRecipe.ONBOARDING_MINI, stageCornerDp = 34f, screenCornerDp = 26f, screenShadowBlurDp = 40f, screenShadowDyDp = 18f, msgCornerDp = 12f, bubbleDp = 36f, bubbleRightDp = 6f, bubbleTopDp = 176f, glyphDp = 20f, pillPadXDp = 5f, pillPadYDp = 2f, pulseDp = 60f)

        /** settings-final: .stagebox r24, .mini r20 shadow 0 14 32 -12, .mm r10, .mb 30 @ right 5 top 84, glyph 17, .ln::before -4/-2, .gp 60. */
        val SETTINGS = DemoSpec(HaloRecipe.SETTINGS_MINI, stageCornerDp = 24f, screenCornerDp = 20f, screenShadowBlurDp = 32f, screenShadowDyDp = 14f, msgCornerDp = 10f, bubbleDp = 30f, bubbleRightDp = 5f, bubbleTopDp = 84f, glyphDp = 17f, pillPadXDp = 4f, pillPadYDp = 2f, pulseDp = 60f)
    }
}

/**
 * Looping miniature of a capture (bubble tap -> Halo ring -> line pills -> close), spec §8.2.
 * This view is the stage box. XML children (by tag): `demoScreen` (direct child; inside: `avatar`,
 * TextViews `line` / `line_me`) and `demoChip`. One 4800 ms ValueAnimator drives every element.
 */
class CaptureDemoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private class Pill(val index: Int, val rect: RectF, val painter: PillPainter)

    private val dp = resources.displayMetrics.density
    private var spec = DemoSpec.SETTINGS
    private val halo = EdgeGlowView(context)
    private var screen: View? = null
    private var chip: View? = null
    private val lines = ArrayList<TextView>()
    private val pills = ArrayList<Pill>()
    private val bubbleBox = RectF()
    private val stageClip = Path()

    private val bubbleBg: Drawable = ContextCompat.getDrawable(context, R.drawable.bubble_bg)!!.mutate()
    private val glyph: Drawable = ContextCompat.getDrawable(context, R.drawable.ic_scan)!!.mutate()
    private val ghostGlyph: Drawable = ContextCompat.getDrawable(context, R.drawable.ic_scan)!!.mutate().apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })   // CSS filter: grayscale(1)
    }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var p = 0f            // loop time, ms
    private var granted = true
    private var paused = false
    private var ghost = 0f        // 0 = live, 1 = ghost (denied)
    private var pulse = -1f       // current pulse progress 0..1, < 0 = none
    private var flashT = -1f      // return-flash progress 0..1, < 0 = none
    private var ghostAnim: ValueAnimator? = null
    private var pulseAnim: ValueAnimator? = null
    private var flashAnim: ValueAnimator? = null

    private val loop = ValueAnimator.ofFloat(0f, LOOP).apply {
        duration = LOOP.toLong()
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { onLoop(it.animatedValue as Float) }
    }

    init {
        clipChildren = false
        setWillNotDraw(false)
    }

    fun configure(spec: DemoSpec) {
        this.spec = spec
        halo.recipe = spec.halo
        // permission-2 / settings-final .stagebox: linear-gradient(160deg, #A78BFA 14%, #67E8F9 12%)
        background = CssGradientDrawable(intArrayOf(withAlpha(0.14f, 0xA78BFA), withAlpha(0.12f, 0x67E8F9)), null, 160f, spec.stageCornerDp * dp)
        val scr = checkNotNull(findViewWithTag<View>(TAG_SCREEN)) { "CaptureDemoView needs a direct child tagged '$TAG_SCREEN'" }
        check(scr.parent === this) { "'$TAG_SCREEN' must be a direct child of CaptureDemoView" }
        screen = scr
        val corner = spec.screenCornerDp * dp
        scr.background = GlowDrawable(
            CssGradientDrawable.solid(0xFFF3F5F8.toInt(), corner),
            withAlpha(0.30f, 0x4C1D95), spec.screenShadowBlurDp * dp, spec.screenShadowDyDp * dp, SCREEN_SPREAD_DP * dp, corner,
        )
        scr.findViewWithTag<View>(TAG_AVATAR)?.background =
            CssGradientDrawable(intArrayOf(0xFFFFB27A.toInt(), 0xFFFF6F91.toInt()), null, 135f, -1f)
        lines.clear()
        collectLines(scr)
        val mc = spec.msgCornerDp * dp
        lines.forEach { tv ->
            if (tv.tag == TAG_LINE_ME) {
                tv.background = CssGradientDrawable.solid(0xFF2F6BFF.toInt(), mc)
                tv.setTextColor(Color.WHITE)
            } else {
                tv.background = CssGradientDrawable.solid(Color.WHITE, mc)
                tv.setTextColor(0xFF1D2330.toInt())
            }
        }
        chip = findViewWithTag<View>(TAG_CHIP)?.also {
            it.background = HaloStyle.chip(context)
            it.alpha = ghost
        }
        if (halo.parent == null) addView(halo, indexOfChild(scr) + 1, LayoutParams(0, 0))
        requestLayout()
    }

    /** true = live loop; false = ghost frame + peach pulse x2, loop stopped. */
    fun setGranted(granted: Boolean, animate: Boolean) {
        val changed = this.granted != granted
        this.granted = granted
        val target = if (granted) 0f else 1f
        ghostAnim?.cancel()
        if (animate && changed) {
            ghostAnim = ValueAnimator.ofFloat(ghost, target).apply {
                duration = GHOST_SWAP
                addUpdateListener { setGhost(it.animatedValue as Float) }
                start()
            }
        } else {
            setGhost(target)
        }
        if (!granted) {
            loop.cancel()
            halo.clear()
            playPulse()
        }
        updateLoop()
    }

    fun pause() {
        paused = true
        updateLoop()
    }

    fun resume() {
        paused = false
        updateLoop()
    }

    /** Return-from-system-settings flash: ring #A78BFA 60% -> 0, spread 0 -> 14dp, 400 ms ease-out (drawn outside the stage). */
    fun flash() {
        flashAnim?.cancel()
        flashAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FLASH
            interpolator = EASE_OUT
            addUpdateListener { flashT = it.animatedValue as Float; invalidate() }
            doOnEnd { flashT = -1f; invalidate() }
            start()
        }
    }

    private fun setGhost(v: Float) {
        ghost = v
        chip?.alpha = v
        halo.alpha = 1f - v
        invalidate()
    }

    private fun playPulse() {
        pulseAnim?.cancel()
        pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE
            repeatCount = 1                       // CSS animation-iteration-count 2
            interpolator = EASE_OUT
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            doOnEnd { pulse = -1f; invalidate() }
            start()
        }
    }

    private fun updateLoop() {
        val run = granted && !paused && isAttachedToWindow
        when {
            run && !loop.isStarted -> {
                p = 0f
                loop.start()
            }
            run && loop.isPaused -> loop.resume()
            !run && loop.isStarted && !loop.isPaused -> {
                loop.pause()
                halo.clear()
            }
        }
    }

    private fun onLoop(t: Float) {
        val prev = p
        p = t
        if (crossed(prev, t, HALO_ON)) halo.activate()
        if (crossed(prev, t, HALO_SETTLE)) halo.settle()
        if (crossed(prev, t, HALO_OFF)) halo.fadeOut(HALO_OUT)
        invalidate()
    }

    /** True when loop time moved past [at] between two frames (handles the wrap at 4800). */
    private fun crossed(prev: Float, now: Float, at: Float) =
        if (now >= prev) at > prev && at <= now else at > prev || at <= now

    private fun collectLines(v: View) {
        if (v is TextView && (v.tag == TAG_LINE || v.tag == TAG_LINE_ME)) lines += v
        if (v is ViewGroup) for (i in 0 until v.childCount) collectLines(v.getChildAt(i))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val scr = screen ?: return
        halo.measure(
            MeasureSpec.makeMeasureSpec(scr.measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(scr.measuredHeight, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val scr = screen ?: return
        halo.layout(scr.left, scr.top, scr.right, scr.bottom)
        val b = spec.bubbleDp * dp
        val bx = scr.right - spec.bubbleRightDp * dp
        val by = scr.top + spec.bubbleTopDp * dp
        bubbleBox.set(bx - b, by, bx, by + b)
        val r = spec.stageCornerDp * dp
        stageClip.reset()
        stageClip.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, Path.Direction.CW)
        buildPills()
    }

    /** One pill per text-layout line of every `line` TextView, outset by pillPadX/Y (CSS .ln::before). */
    private fun buildPills() {
        pills.clear()
        val px = spec.pillPadXDp * dp
        val py = spec.pillPadYDp * dp
        lines.forEachIndexed { i, tv ->
            val layout = tv.layout ?: return@forEachIndexed
            var ox = 0f
            var oy = 0f
            var c: View = tv
            while (c !== this) {
                ox += c.left
                oy += c.top
                c = c.parent as View
            }
            ox += tv.totalPaddingLeft
            oy += tv.totalPaddingTop
            for (l in 0 until layout.lineCount) {
                val rect = RectF(
                    ox + layout.getLineLeft(l) - px, oy + layout.getLineTop(l) - py,
                    ox + layout.getLineRight(l) + px, oy + layout.getLineBottom(l) + py,
                )
                // mini pills: LinearGradient #A78BFA 26% -> #F472B6 22%, glow #A78BFA 30% r8, full-round
                val painter = PillPainter(withAlpha(0.26f, 0xA78BFA), withAlpha(0.22f, 0xF472B6), withAlpha(0.30f, 0xA78BFA), PILL_GLOW_DP * dp, -1f)
                painter.setSize(rect.width().roundToInt(), rect.height().roundToInt())
                pills += Pill(i, rect, painter)
            }
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.clipPath(stageClip)                 // CSS .stagebox overflow:hidden
        super.dispatchDraw(canvas)
        canvas.restoreToCount(saved)
        drawFlash(canvas)
    }

    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        val scr = screen
        if (child === halo && scr != null) {
            val saved = canvas.save()
            canvas.clipRect(scr.left, scr.top, scr.right, scr.bottom)   // the CSS canvas lives inside .mini
            val r = super.drawChild(canvas, child, drawingTime)
            drawBubble(canvas)
            canvas.restoreToCount(saved)
            return r
        }
        val r = super.drawChild(canvas, child, drawingTime)
        if (child === scr) drawPills(canvas)
        return r
    }

    private fun drawPills(canvas: Canvas) {
        val live = 1f - ghost
        if (live <= 0f) return
        for (pl in pills) {
            val q = ((p - pl.index * PILL_STAGGER) % LOOP + LOOP) % LOOP
            val a: Float
            val s: Float
            when {
                q < PILL_IN_AT -> { a = 0f; s = 0.9f }
                q < PILL_IN_AT + PILL_FADE -> { val f = (q - PILL_IN_AT) / PILL_FADE; a = f; s = 0.9f + 0.1f * f }
                q < PILL_OUT_AT -> { a = 1f; s = 1f }
                q < PILL_OUT_AT + PILL_FADE -> { a = 1f - (q - PILL_OUT_AT) / PILL_FADE; s = 1f }
                else -> { a = 0f; s = 1f }
            }
            if (a * live <= 0f) continue
            val saved = canvas.save()
            canvas.translate(pl.rect.left, pl.rect.top)
            pl.painter.draw(canvas, a * live, s)
            canvas.restoreToCount(saved)
        }
    }

    private fun drawBubble(canvas: Canvas) {
        val live = 1f - ghost
        val cx = bubbleBox.centerX()
        val cy = bubbleBox.centerY()
        val br = bubbleBox.width() / 2f
        if (live > 0f) {
            val (ba, bs) = bubbleFrame(p)
            if (ba > 0f) drawBubbleAt(canvas, bubbleBg, glyph, live * ba, bs)
            val (da, ds) = dotFrame(p)
            if (da > 0f) {
                dot.color = withAlpha(0.32f * da * live, 0x786E96)   // rgba(120,110,150,.32)
                canvas.drawCircle(cx, cy, br * ds, dot)
            }
        }
        if (ghost > 0f) {
            // .gb: white disc + grayscale glyph, opacity .45
            val layer = canvas.saveLayerAlpha(bubbleBox, (GHOST_ALPHA * ghost * 255f).roundToInt())
            canvas.drawCircle(cx, cy, br, white)
            placeGlyph(ghostGlyph)
            ghostGlyph.draw(canvas)
            canvas.restoreToCount(layer)
        }
        if (pulse >= 0f) {
            // .gp: radial-gradient(circle, peach 0 @45%, peach 55% @62%, peach 0 @75%) on a 60dp disc,
            // CSS default extent farthest-corner = r·√2; opacity .9 -> 0, scale .8 -> 1.3
            val s = 0.8f + 0.5f * pulse
            val r = spec.pulseDp * dp / 2f * s
            pulsePaint.shader = RadialGradient(
                cx, cy, r * sqrt(2f),
                intArrayOf(withAlpha(0f, 0xFDBA74), withAlpha(0.55f, 0xFDBA74), withAlpha(0f, 0xFDBA74)),
                floatArrayOf(0.45f, 0.62f, 0.75f), Shader.TileMode.CLAMP,
            )
            pulsePaint.alpha = (0.9f * (1f - pulse) * 255f).roundToInt()
            canvas.drawCircle(cx, cy, r, pulsePaint)
        }
    }

    private fun drawBubbleAt(canvas: Canvas, bg: Drawable, g: Drawable, alpha: Float, scale: Float) {
        val saved = canvas.save()
        canvas.scale(scale, scale, bubbleBox.centerX(), bubbleBox.centerY())
        val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        bg.setBounds(bubbleBox.left.roundToInt(), bubbleBox.top.roundToInt(), bubbleBox.right.roundToInt(), bubbleBox.bottom.roundToInt())
        bg.alpha = a
        bg.draw(canvas)
        placeGlyph(g)
        g.alpha = a
        g.draw(canvas)
        canvas.restoreToCount(saved)
    }

    private fun placeGlyph(g: Drawable) {
        val half = spec.glyphDp * dp / 2f
        val cx = bubbleBox.centerX()
        val cy = bubbleBox.centerY()
        g.setBounds((cx - half).roundToInt(), (cy - half).roundToInt(), (cx + half).roundToInt(), (cy + half).roundToInt())
    }

    private fun drawFlash(canvas: Canvas) {
        if (flashT < 0f) return
        val spread = FLASH_SPREAD_DP * dp * flashT
        if (spread <= 0f) return
        flashPaint.strokeWidth = spread
        flashPaint.color = withAlpha(0.6f * (1f - flashT), 0xA78BFA)
        val h = spread / 2f
        val r = spec.stageCornerDp * dp + h
        canvas.drawRoundRect(-h, -h, width + h, height + h, r, r, flashPaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateLoop()
    }

    override fun onDetachedFromWindow() {
        loop.cancel()
        ghostAnim?.cancel()
        pulseAnim?.cancel()
        flashAnim?.cancel()
        halo.clear()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val TAG_SCREEN = "demoScreen"
        const val TAG_CHIP = "demoChip"
        const val TAG_AVATAR = "avatar"
        const val TAG_LINE = "line"
        const val TAG_LINE_ME = "line_me"

        // permission-2 / settings-final demo loop (ms; keyframe % x 4800)
        const val LOOP = 4800f
        const val HALO_ON = 1150f          // haloIn 350 from 1150
        const val HALO_SETTLE = 1900f      // haloSettle 400 from 1900
        const val HALO_OFF = 3800f         // sessionOut 288 from 3800
        const val HALO_OUT = 288L
        const val PILL_IN_AT = 1488f       // 31%
        const val PILL_OUT_AT = 3792f      // 79%
        const val PILL_FADE = 288f         // 6% = pills 288 each
        const val PILL_STAGGER = 72f
        const val GHOST_SWAP = 300L
        const val PULSE = 400L             // peachPulse 400 x2
        const val FLASH = 400L
        const val FLASH_SPREAD_DP = 14f
        const val GHOST_ALPHA = 0.45f
        const val PILL_GLOW_DP = 8f
        const val SCREEN_SPREAD_DP = -12f

        val EASE_OUT = PathInterpolator(0f, 0f, 0.58f, 1f)   // CSS ease-out

        private fun seg(t: Float, from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)

        /** CSS @keyframes mb: (alpha, scale). 20% 960, 21% 1008 (press .9), 23% 1104, 27% 1296 (bubbleOut 192), 85% 4080, 91% 4368 (bubbleIn 288). */
        fun bubbleFrame(t: Float): Pair<Float, Float> = when {
            t < 960f -> 1f to 1f
            t < 1008f -> 1f to (1f - 0.1f * seg(t, 960f, 1008f))
            t < 1104f -> 1f to (0.9f + 0.1f * seg(t, 1008f, 1104f))
            t < 1296f -> seg(t, 1104f, 1296f).let { (1f - it) to (1f - 0.3f * it) }
            t < 4080f -> 0f to 0.7f
            t < 4368f -> seg(t, 4080f, 4368f).let { it to (0.7f + 0.3f * it) }
            else -> 1f to 1f
        }

        /** CSS @keyframes mt: (alpha, scale). 18% 864, 21% 1008, 26% 1248. */
        fun dotFrame(t: Float): Pair<Float, Float> = when {
            t < 864f -> 0f to 1.6f
            t < 1008f -> seg(t, 864f, 1008f).let { it to (1.6f - 0.8f * it) }
            t < 1248f -> (1f - seg(t, 1008f, 1248f)) to 0.8f
            else -> 0f to 0.8f
        }
    }
}
```

- [ ] **Step 3: Create `java/StepTrackView.kt`** (spec §8.4; permission-2/settings-final `.track`)
```kotlin
package us.jhangy.textify

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/** 2-node progress track 顯示泡泡 -> 擷取畫面 (spec §8.4). Height 24dp; gradient-node glows draw outside (parents: clipChildren=false). */
class StepTrackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class State { CURRENT, DENIED, GRANTED }

    private val dp = resources.displayMetrics.density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12.5f); typeface = Typeface.DEFAULT_BOLD; color = 0xFF6B5FA8.toInt()   // weight 600 -> bold
    }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12f); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRest = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(0.12f, 0x7C3AED) }
    private val trackFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val check: Drawable = ContextCompat.getDrawable(context, R.drawable.ic_check)!!.mutate()
    private val nodePx = NODE_DP * dp
    // CURRENT node 1: 135deg #A78BFA -> #F472B6, glow #F472B6 45% r12; GRANTED node 2: 135deg #F472B6 -> #FDBA74, glow #FDBA74 55% r12
    private val gradCurrent = GlowDrawable(CssGradientDrawable(intArrayOf(0xFFA78BFA.toInt(), 0xFFF472B6.toInt()), null, 135f, -1f), withAlpha(0.45f, 0xF472B6), 12f * dp, 0f, 0f, -1f)
    private val gradCapture = GlowDrawable(CssGradientDrawable(intArrayOf(0xFFF472B6.toInt(), 0xFFFDBA74.toInt()), null, 135f, -1f), withAlpha(0.55f, 0xFDBA74), 12f * dp, 0f, 0f, -1f)

    private var first: CharSequence = ""
    private var second: CharSequence = ""
    private var from = State.CURRENT
    private var state = State.CURRENT
    private var mixT = 1f            // 0 = draw `from`, 1 = draw `state`
    private var fill = 0f            // track fill fraction
    private var mixAnim: ValueAnimator? = null
    private var fillAnim: ValueAnimator? = null

    // x positions, computed in place()
    private var n1x = 0f
    private var l1x = 0f
    private var tx0 = 0f
    private var tx1 = 0f
    private var n2x = 0f
    private var l2x = 0f

    fun setLabels(first: CharSequence, second: CharSequence) {
        this.first = first
        this.second = second
        place()
        invalidate()
    }

    fun setState(state: State, animate: Boolean) {
        mixAnim?.cancel()
        fillAnim?.cancel()
        from = if (mixT >= 0.5f) this.state else from
        this.state = state
        val target = if (state == State.GRANTED) 1f else 0f
        if (!animate) {
            mixT = 1f
            fill = target
            invalidate()
            return
        }
        mixT = 0f
        mixAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = NODE_CHECK
            interpolator = CSS_EASE
            addUpdateListener { mixT = it.animatedValue as Float; invalidate() }
            start()
        }
        fillAnim = ValueAnimator.ofFloat(fill, target).apply {
            duration = TRACK_FILL
            interpolator = EMPHASIZED
            addUpdateListener { fill = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (nodePx + paddingTop + paddingBottom).roundToInt()
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(h, heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = place()

    /** [node 24][7][label][10][track][10][node 24][7][label] — CSS .track gap 10, .node gap 7. */
    private fun place() {
        n1x = paddingLeft.toFloat()
        l1x = n1x + nodePx + 7f * dp
        tx0 = l1x + label.measureText(first, 0, first.length) + 10f * dp
        val l2w = label.measureText(second, 0, second.length)
        l2x = width - paddingRight - l2w
        n2x = l2x - 7f * dp - nodePx
        tx1 = n2x - 10f * dp
        trackFill.shader = LinearGradient(tx0, 0f, tx1, 0f, intArrayOf(0xFFA78BFA.toInt(), 0xFFF472B6.toInt(), 0xFFFDBA74.toInt()), null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val cy = paddingTop + nodePx / 2f
        val lb = cy - (label.descent() + label.ascent()) / 2f
        canvas.drawText(first, 0, first.length, l1x, lb, label)
        canvas.drawText(second, 0, second.length, l2x, lb, label)
        // track: 6dp, r3, rest #7C3AED 12%, fill revealed by clip width (permission-2 note "ValueAnimator on clip width")
        val th = 3f * dp
        if (tx1 > tx0) {
            canvas.drawRoundRect(tx0, cy - th, tx1, cy + th, th, th, trackRest)
            if (fill > 0f) {
                val saved = canvas.save()
                canvas.clipRect(tx0, cy - th, tx0 + (tx1 - tx0) * fill, cy + th)
                canvas.drawRoundRect(tx0, cy - th, tx1, cy + th, th, th, trackFill)
                canvas.restoreToCount(saved)
            }
        }
        if (mixT < 1f) {
            drawNode(canvas, 1, from, n1x, cy, 1f - mixT)
            drawNode(canvas, 2, from, n2x, cy, 1f - mixT)
        }
        drawNode(canvas, 1, state, n1x, cy, mixT)
        drawNode(canvas, 2, state, n2x, cy, mixT)
    }

    private fun drawNode(canvas: Canvas, index: Int, s: State, x: Float, cy: Float, alpha: Float) {
        if (alpha <= 0f) return
        val a = (alpha * 255f).roundToInt()
        val l = x.roundToInt()
        val t = (cy - nodePx / 2f).roundToInt()
        val r = nodePx / 2f
        var textColor = 0xFF4C1D95.toInt()
        var showCheck = false
        when {
            index == 1 && s == State.CURRENT -> { gradCurrent.setBounds(l, t, l + nodePx.roundToInt(), t + nodePx.roundToInt()); gradCurrent.alpha = a; gradCurrent.draw(canvas); textColor = 0xFFFFFFFF.toInt() }
            index == 1 && s == State.DENIED -> { solid.color = withAlpha(0.30f, 0xFDBA74); solid.alpha = (0.30f * a).roundToInt(); canvas.drawCircle(x + r, cy, r, solid); textColor = 0xFF9A3412.toInt() }
            index == 1 && s == State.GRANTED -> { solid.color = withAlpha(0.16f, 0x7C3AED); solid.alpha = (0.16f * a).roundToInt(); canvas.drawCircle(x + r, cy, r, solid); showCheck = true }
            index == 2 && s == State.GRANTED -> { gradCapture.setBounds(l, t, l + nodePx.roundToInt(), t + nodePx.roundToInt()); gradCapture.alpha = a; gradCapture.draw(canvas); textColor = 0xFFFFFFFF.toInt() }
            else -> { solid.color = withAlpha(0.12f, 0x7C3AED); solid.alpha = (0.12f * a).roundToInt(); canvas.drawCircle(x + r, cy, r, solid) }
        }
        if (showCheck) {
            val h = CHECK_DP * dp / 2f
            check.setBounds((x + r - h).roundToInt(), (cy - h).roundToInt(), (x + r + h).roundToInt(), (cy + h).roundToInt())
            check.alpha = a
            check.draw(canvas)
        } else {
            num.color = textColor
            num.alpha = (((textColor ushr 24) / 255f) * a).roundToInt()
            canvas.drawText(if (index == 1) "1" else "2", x + r, cy - (num.descent() + num.ascent()) / 2f, num)
        }
    }

    override fun onDetachedFromWindow() {
        mixAnim?.cancel()
        fillAnim?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val NODE_DP = 24f
        const val CHECK_DP = 14f
        const val TRACK_FILL = 400L
        const val NODE_CHECK = 260L
        val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)
        val CSS_EASE = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)   // CSS transition default
    }
}
```
Note: `Paint.color = withAlpha(…)` then `Paint.alpha = …` replaces the colour's alpha, so the second assignment carries the final alpha (fill alpha × cross-fade).

- [ ] **Step 4: Run the acceptance greps.**

- [ ] **Step 5: Commit** (T1 after Step 2 with `set -- app/src/main/java/us/jhangy/textify/CaptureDemoView.kt`, T2 after Step 3 with `set -- app/src/main/java/us/jhangy/textify/StepTrackView.kt`, then the add/commit/status lines below)
```bash
cd /Users/jhangyu/project/textify && git rev-parse --abbrev-ref HEAD
set -- app/src/main/java/us/jhangy/textify/CaptureDemoView.kt app/src/main/java/us/jhangy/textify/StepTrackView.kt   # zsh- and bash-safe path list
git add -- "$@" && git commit -m "feat(ui): CaptureDemoView looping mini capture and StepTrackView" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>" -- "$@"
git status --porcelain -- "$@"
```

### Unit R1-D: Language controls — judgment, 2 tickets (T1 `LanguagePreviewView` + `LanguageToggleView`; T2 `SpeedLanesView`), commit after each

**Files:**
- Create: `java/LanguagePreviewView.kt`, `java/LanguageToggleView.kt`, `java/SpeedLanesView.kt`

**Interfaces:**
- Consumes (frozen, R1-B): `HaloRecipe.PREVIEW_EDGE`, `EdgeGlowView` + `showSettled`/`clear`, `PillPainter`, `CssGradientDrawable`, `GlowDrawable`, `withAlpha`. Existing `enum class Script { CHINESE, JAPANESE, KOREAN }` (unchanged in both rounds).
- Produces: the three classes exactly as frozen; XML contract rows.

**Behavior:** spec §7.4, §8.5–§8.7. Preview: English row lit for any non-empty set; newly lit rows staggered `round(i/(n−1)·140)`; pill in 260 ease-out (alpha 0→1, scale .96→1), out 200 ease-in; tag alpha and text alpha (.5 ↔ 1) over 200 ms CSS `ease`. Pills are part of each row's background drawable so they sit above the row fill and below the text (CSS `.pill` before `.t`). The 5% ink scrim is drawn over the whole shot. Toggle: off/on cross-fade 240 ease-out; reject = rubber 360 + flash 380 + `VIRTUAL_KEY` haptic. Lanes: rows zh/ja/ko, height 0↔16dp + margin 5dp, bar width 58/64/70%, 300 ms `(.2,.8,.2,1)`.

**Constraints:** No `SDK_INT`, no `REJECT`. Weight ≥ 600 → `Typeface.DEFAULT_BOLD`. Text sizes/paddings of XML children come from the round-2 layout (spec §7.4); these views set colours, backgrounds, alpha, positions only.

**Acceptance criteria:**
- [ ] `grep -c "0.58f, 0.64f, 0.70f" app/src/main/java/us/jhangy/textify/SpeedLanesView.kt` = 1; `grep -c "LANE_GROW = 300L" app/src/main/java/us/jhangy/textify/SpeedLanesView.kt` = 1.
- [ ] `for k in "TOGGLE_FILL = 240L" "SHAKE = 360L" "REJECT_FLASH = 380L" "VIRTUAL_KEY"; do grep -qF "$k" app/src/main/java/us/jhangy/textify/LanguageToggleView.kt || echo "MISSING $k"; done` prints nothing; `grep -c "REJECT\b" app/src/main/java/us/jhangy/textify/LanguageToggleView.kt` = 0 (only `REJECT_FLASH`).
- [ ] `for k in "PILL_IN = 260L" "PILL_OUT = 200L" "BOX_STAGGER = 140f" "TEXT_FADE = 200L" '"en" to scripts.isNotEmpty()'; do grep -qF "$k" app/src/main/java/us/jhangy/textify/LanguagePreviewView.kt || echo "MISSING $k"; done` prints nothing.
- [ ] `grep -n "SDK_INT\|setShadowLayer\|scaledDensity" app/src/main/java/us/jhangy/textify/Language*.kt app/src/main/java/us/jhangy/textify/SpeedLanesView.kt` prints nothing.
- [ ] Round-1 build gate `RC=0`.

**Steps:**

- [ ] **Step 1: Read the R1-B signatures commit** (same rule as R1-C Step 1).

- [ ] **Step 2: Create `java/LanguagePreviewView.kt`**
```kotlin
package us.jhangy.textify

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Mini frozen screenshot of the language card (spec §8.5, settings-final .pv): rows light up as Halo pills
 * for the selected scripts. XML children (by tag): `previewShot` (direct child) with `avatar` and rows
 * `zh`/`en`/`ja`/`ko`, each holding `text` and `tag` TextViews.
 */
class LanguagePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private class Row(val view: ViewGroup, val text: View?, val tag: View?, val bg: RowDrawable) {
        var lit = false
        var anim: ValueAnimator? = null
    }

    /** Row fill (r12) + its Halo pill outset 6dp x 3dp (CSS .pln .pill inset -3px -6px), drawn under the row's children. */
    private class RowDrawable(color: Int, private val corner: Float, private val padX: Float, private val padY: Float, private val painter: PillPainter) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        var pillAlpha = 0f
        var pillScale = 0.96f

        override fun onBoundsChange(bounds: Rect) {
            painter.setSize((bounds.width() + 2f * padX).roundToInt(), (bounds.height() + 2f * padY).roundToInt())
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawRoundRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), corner, corner, paint)
            if (pillAlpha <= 0f) return
            val saved = canvas.save()
            canvas.translate(b.left - padX, b.top - padY)
            painter.draw(canvas, pillAlpha, pillScale)
            canvas.restoreToCount(saved)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private val dp = resources.displayMetrics.density
    private val halo = EdgeGlowView(context).apply { recipe = HaloRecipe.PREVIEW_EDGE }
    private var shot: ViewGroup? = null
    private val rows = LinkedHashMap<String, Row>()
    private val scrim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(0.05f, 0x1E1B4B) }   // .pv .shot::after

    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        val s = checkNotNull(findViewWithTag<ViewGroup>(TAG_SHOT)) { "LanguagePreviewView needs a direct child tagged '$TAG_SHOT'" }
        check(s.parent === this) { "'$TAG_SHOT' must be a direct child of LanguagePreviewView" }
        shot = s
        s.clipChildren = false
        s.clipToPadding = false
        s.background = CssGradientDrawable.solid(Color.WHITE, SHOT_CORNER_DP * dp)
        s.findViewWithTag<View>(TAG_AVATAR)?.background =
            CssGradientDrawable(intArrayOf(0xFFFFB27A.toInt(), 0xFFFF6F91.toInt()), null, 135f, -1f)
        for (key in KEYS) {
            val v = checkNotNull(s.findViewWithTag<ViewGroup>(key)) { "LanguagePreviewView: row '$key' missing" }
            v.clipChildren = false
            v.clipToPadding = false
            // preview pill: #A78BFA 20% -> #F472B6 16%, glow #A78BFA 28% r12, radius 12
            val painter = PillPainter(withAlpha(0.20f, 0xA78BFA), withAlpha(0.16f, 0xF472B6), withAlpha(0.28f, 0xA78BFA), 12f * dp, ROW_CORNER_DP * dp)
            val color = if (key in RIGHT) 0xFFE8EEFC.toInt() else 0xFFF1F3F7.toInt()
            val bg = RowDrawable(color, ROW_CORNER_DP * dp, PILL_OUT_X_DP * dp, PILL_OUT_Y_DP * dp, painter)
            v.background = bg
            val tag = v.findViewWithTag<View>(TAG_TAG)?.apply {
                // .pln .tag: white r8, color #7C3AED, box-shadow 0 1px 4px #4C1D95 12%
                background = GlowDrawable(CssGradientDrawable.solid(Color.WHITE, 8f * dp), withAlpha(0.12f, 0x4C1D95), 4f * dp, 1f * dp, 0f, 8f * dp)
                (this as? android.widget.TextView)?.setTextColor(0xFF7C3AED.toInt())
                alpha = 0f
            }
            val text = v.findViewWithTag<View>(TAG_TEXT)?.apply { alpha = UNLIT_TEXT }
            rows[key] = Row(v, text, tag, bg)
        }
        addView(halo, 0, LayoutParams(0, 0))
    }

    fun setScripts(scripts: Set<Script>, animate: Boolean) {
        // English lights for ANY selection: every model also reads Latin (settings-final header)
        val want = mapOf(
            "zh" to (Script.CHINESE in scripts),
            "en" to scripts.isNotEmpty(),
            "ja" to (Script.JAPANESE in scripts),
            "ko" to (Script.KOREAN in scripts),
        )
        rows.forEach { (k, r) -> if (want[k] != true && r.lit) light(r, false, 0L, animate) }
        val turningOn = rows.filter { (k, r) -> want[k] == true && !r.lit }.values.toList()
        val n = turningOn.size
        turningOn.forEachIndexed { i, r ->
            val delay = if (n > 1) (i * BOX_STAGGER / (n - 1)).roundToLong() else 0L
            light(r, true, delay, animate)
        }
    }

    private fun light(r: Row, on: Boolean, delay: Long, animate: Boolean) {
        r.lit = on
        r.anim?.cancel()
        val toA = if (on) 1f else 0f
        val toS = if (on) 1f else 0.96f
        val toText = if (on) 1f else UNLIT_TEXT
        if (!animate) {
            r.bg.pillAlpha = toA
            r.bg.pillScale = toS
            r.bg.invalidateSelf()
            r.tag?.alpha = toA
            r.text?.alpha = toText
            return
        }
        val a0 = r.bg.pillAlpha
        val s0 = r.bg.pillScale
        r.anim = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = delay
            duration = if (on) PILL_IN else PILL_OUT
            interpolator = if (on) EASE_OUT else EASE_IN
            addUpdateListener {
                val f = it.animatedValue as Float
                r.bg.pillAlpha = a0 + (toA - a0) * f
                r.bg.pillScale = s0 + (toS - s0) * f
                r.bg.invalidateSelf()
            }
            start()
        }
        r.tag?.animate()?.alpha(toA)?.setStartDelay(delay)?.setDuration(TEXT_FADE)?.setInterpolator(CSS_EASE)
        r.text?.animate()?.alpha(toText)?.setStartDelay(delay)?.setDuration(TEXT_FADE)?.setInterpolator(CSS_EASE)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        halo.measure(
            MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        halo.layout(0, 0, width, height)                   // .edge inset 0 of .pv (the view's padding is the 10dp band)
        rows.values.forEach { r ->
            val t = r.tag ?: return@forEach
            // .pln .tag: right -6px, top -9px of the row
            val rx = r.view.width + TAG_OUT_X_DP * dp
            val ty = -TAG_OUT_Y_DP * dp
            t.layout((rx - t.measuredWidth).roundToInt(), ty.roundToInt(), rx.roundToInt(), (ty + t.measuredHeight).roundToInt())
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val s = shot ?: return
        val r = SHOT_CORNER_DP * dp
        canvas.drawRoundRect(s.left.toFloat(), s.top.toFloat(), s.right.toFloat(), s.bottom.toFloat(), r, r, scrim)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        halo.showSettled()
    }

    override fun onDetachedFromWindow() {
        rows.values.forEach { it.anim?.cancel() }
        halo.clear()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val TAG_SHOT = "previewShot"
        const val TAG_AVATAR = "avatar"
        const val TAG_TEXT = "text"
        const val TAG_TAG = "tag"
        val KEYS = listOf("zh", "en", "ja", "ko")
        val RIGHT = setOf("en", "ko")              // .pln.me rows
        const val SHOT_CORNER_DP = 18f
        const val ROW_CORNER_DP = 12f
        const val PILL_OUT_X_DP = 6f
        const val PILL_OUT_Y_DP = 3f
        const val TAG_OUT_X_DP = 6f
        const val TAG_OUT_Y_DP = 9f
        const val UNLIT_TEXT = 0.5f
        const val PILL_IN = 260L
        const val PILL_OUT = 200L
        const val TEXT_FADE = 200L
        const val BOX_STAGGER = 140f
        val EASE_OUT = PathInterpolator(0f, 0f, 0.58f, 1f)
        val EASE_IN = PathInterpolator(0.42f, 0f, 1f, 1f)
        val CSS_EASE = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
    }
}
```

- [ ] **Step 3: Create `java/LanguageToggleView.kt`** (settings-final `.pl`)
```kotlin
package us.jhangy.textify

import android.animation.ArgbEvaluator
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Session-style language toggle pill (spec §8.6). XML children (by tag): TextViews `glyph` and `sub`. */
class LanguageToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var checked: Boolean = false
        private set

    private val dp = resources.displayMetrics.density
    private val corner = CORNER_DP * dp
    private val off = CssGradientDrawable.solid(withAlpha(0.07f, 0x7C3AED), corner)
    private val on = GlowDrawable(
        CssGradientDrawable(intArrayOf(0xFF7C3AED.toInt(), 0xFFF472B6.toInt()), null, 135f, corner),
        withAlpha(0.30f, 0xF472B6), 18f * dp, 0f, 0f, corner,
    )
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val argb = ArgbEvaluator()
    private var glyph: TextView? = null
    private var sub: TextView? = null
    private var onLevel = 0f
    private var flash = 0f
    private var fillAnim: ValueAnimator? = null
    private var rubberAnim: ObjectAnimator? = null
    private var flashAnim: ValueAnimator? = null

    init {
        setWillNotDraw(false)
        clipChildren = false
        isClickable = true
        isFocusable = true
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        glyph = findViewWithTag(TAG_GLYPH)
        sub = findViewWithTag(TAG_SUB)
        applyColors()
    }

    fun setChecked(value: Boolean, animate: Boolean) {
        checked = value
        val target = if (value) 1f else 0f
        fillAnim?.cancel()
        if (!animate) {
            onLevel = target
            applyColors()
        } else {
            fillAnim = ValueAnimator.ofFloat(onLevel, target).apply {
                duration = TOGGLE_FILL
                interpolator = EASE_OUT
                addUpdateListener { onLevel = it.animatedValue as Float; applyColors() }
                start()
            }
        }
    }

    /** Last-one deselect: rubber 1 -> .92 -> 1.04 -> 1 (360), flash ring #F472B6 0 -> 55% @35% spread 7dp -> 0 (380), haptic. */
    fun reject() {
        rubberAnim?.cancel()
        val kf = arrayOf(
            Keyframe.ofFloat(0f, 1f),
            Keyframe.ofFloat(0.30f, 0.92f).apply { interpolator = EASE_OUT },
            Keyframe.ofFloat(0.65f, 1.04f).apply { interpolator = EASE_OUT },
            Keyframe.ofFloat(1f, 1f).apply { interpolator = EASE_OUT },
        )
        rubberAnim = ObjectAnimator.ofPropertyValuesHolder(
            this,
            PropertyValuesHolder.ofKeyframe(View.SCALE_X, *kf),
            PropertyValuesHolder.ofKeyframe(View.SCALE_Y, *kf.map { it.clone() }.toTypedArray()),
        ).apply { duration = SHAKE; start() }
        flashAnim?.cancel()
        flashAnim = ValueAnimator.ofPropertyValuesHolder(
            PropertyValuesHolder.ofKeyframe(
                "f",
                Keyframe.ofFloat(0f, 0f),
                Keyframe.ofFloat(0.35f, 1f).apply { interpolator = EASE_OUT },
                Keyframe.ofFloat(1f, 0f).apply { interpolator = EASE_OUT },
            ),
        ).apply {
            duration = REJECT_FLASH
            addUpdateListener { flash = it.animatedValue as Float; invalidate() }
            start()
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun applyColors() {
        glyph?.setTextColor(argb.evaluate(onLevel, withAlpha(0.60f, 0x1E1B4B), Color.WHITE) as Int)
        sub?.setTextColor(argb.evaluate(onLevel, withAlpha(0.55f, 0x1E1B4B), Color.WHITE) as Int)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        off.setBounds(0, 0, w, h)
        on.setBounds(0, 0, w, h)
    }

    override fun onDraw(canvas: Canvas) {
        off.draw(canvas)
        if (onLevel > 0f) {
            on.alpha = (onLevel * 255f).roundToInt()
            on.draw(canvas)
        }
        if (flash > 0f) {
            val spread = FLASH_SPREAD_DP * dp * flash
            flashPaint.strokeWidth = spread
            flashPaint.color = withAlpha(0.55f * flash, 0xF472B6)
            val h = spread / 2f
            canvas.drawRoundRect(-h, -h, width + h, height + h, corner + h, corner + h, flashPaint)
        }
    }

    /** CSS .pl:active scale(.97) (no transition). */
    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        if (rubberAnim?.isRunning == true) return
        val s = if (pressed) 0.97f else 1f
        scaleX = s
        scaleY = s
    }

    override fun getAccessibilityClassName(): CharSequence = CheckBox::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isCheckable = true
        @Suppress("DEPRECATION")
        info.isChecked = checked
    }

    override fun onDetachedFromWindow() {
        fillAnim?.cancel()
        rubberAnim?.cancel()
        flashAnim?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val TAG_GLYPH = "glyph"
        const val TAG_SUB = "sub"
        const val CORNER_DP = 24f
        const val FLASH_SPREAD_DP = 7f
        const val TOGGLE_FILL = 240L
        const val SHAKE = 360L
        const val REJECT_FLASH = 380L
        val EASE_OUT = PathInterpolator(0f, 0f, 0.58f, 1f)
    }
}
```

- [ ] **Step 4: Create `java/SpeedLanesView.kt`** (settings-final `.lane`)
```kotlin
package us.jhangy.textify

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.roundToInt

/** Parallel per-model lanes (spec §8.7): one row per selected script; bar width = illustrative finish time. */
class SpeedLanesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val labels = arrayOf<CharSequence>("", "", "")
    private val show = FloatArray(3)            // 0..1 presence per row (zh, ja, ko)
    private var widthFrac = WIDTHS[0]
    private var anim: ValueAnimator? = null
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        typeface = Typeface.DEFAULT_BOLD
        color = 0xFF6B5FA8.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(0.08f, 0x7C3AED) }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setLabels(zh: CharSequence, ja: CharSequence, ko: CharSequence) {
        labels[0] = zh
        labels[1] = ja
        labels[2] = ko
        invalidate()
    }

    fun setScripts(scripts: Set<Script>, animate: Boolean) {
        val target = FloatArray(3) { if (ORDER[it] in scripts) 1f else 0f }
        val targetW = WIDTHS[scripts.size.coerceIn(1, 3) - 1]
        anim?.cancel()
        if (!animate) {
            target.copyInto(show)
            widthFrac = targetW
            requestLayout()
            invalidate()
            return
        }
        val s0 = show.copyOf()
        val w0 = widthFrac
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = LANE_GROW
            interpolator = LANE_CURVE
            addUpdateListener {
                val f = it.animatedValue as Float
                for (i in 0 until 3) show[i] = s0[i] + (target[i] - s0[i]) * f
                widthFrac = w0 + (targetW - w0) * f
                requestLayout()
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = show.sum() * (ROW_DP + GAP_DP) * dp + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(h.roundToInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        var y = paddingTop.toFloat()
        val left = paddingLeft.toFloat()
        val trackL = left + (LABEL_W_DP + LABEL_GAP_DP) * dp
        val trackR = (width - paddingRight).toFloat()
        val th = TRACK_H_DP * dp
        val tr = th / 2f
        for (i in 0 until 3) {
            val s = show[i]
            if (s <= 0f) continue
            val rowH = ROW_DP * dp * s
            val cy = y + rowH / 2f
            val a = (s * 255f).roundToInt()
            val saved = canvas.save()
            canvas.clipRect(left, y, trackR, y + rowH)       // .lane overflow:hidden while the height animates
            label.alpha = a
            canvas.drawText(labels[i], 0, labels[i].length, left + LABEL_W_DP * dp / 2f, cy - (label.descent() + label.ascent()) / 2f, label)
            track.alpha = (0.08f * a).roundToInt()
            canvas.drawRoundRect(trackL, cy - th / 2f, trackR, cy + th / 2f, tr, tr, track)
            val barR = trackL + (trackR - trackL) * widthFrac
            bar.shader = LinearGradient(trackL, 0f, barR, 0f, COLORS[i][0], COLORS[i][1], Shader.TileMode.CLAMP)
            bar.alpha = a
            canvas.drawRoundRect(trackL, cy - th / 2f, barR, cy + th / 2f, tr, tr, bar)
            canvas.restoreToCount(saved)
            y += rowH + GAP_DP * dp * s
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        val ORDER = arrayOf(Script.CHINESE, Script.JAPANESE, Script.KOREAN)
        val WIDTHS = floatArrayOf(0.58f, 0.64f, 0.70f)          // 1 / 2 / 3 parallel models (illustrative, not measured)
        val COLORS = arrayOf(
            intArrayOf(0xFFA78BFA.toInt(), 0xFF818CF8.toInt()),   // zh
            intArrayOf(0xFFF472B6.toInt(), 0xFFFDBA74.toInt()),   // ja
            intArrayOf(0xFF67E8F9.toInt(), 0xFF818CF8.toInt()),   // ko
        )
        const val ROW_DP = 16f
        const val GAP_DP = 5f
        const val LABEL_W_DP = 18f
        const val LABEL_GAP_DP = 8f
        const val TRACK_H_DP = 12f
        const val LANE_GROW = 300L
        val LANE_CURVE = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    }
}
```

- [ ] **Step 5: Run the acceptance greps.**

- [ ] **Step 6: Commit** (T1 after Steps 2–3 with the first two paths, T2 after Step 4 with `SpeedLanesView.kt`; same add/commit/status lines)
```bash
cd /Users/jhangyu/project/textify && git rev-parse --abbrev-ref HEAD
set -- app/src/main/java/us/jhangy/textify/LanguagePreviewView.kt app/src/main/java/us/jhangy/textify/LanguageToggleView.kt app/src/main/java/us/jhangy/textify/SpeedLanesView.kt   # zsh- and bash-safe path list
git add -- "$@" && git commit -m "feat(ui): language preview, toggle and parallel-lanes views" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>" -- "$@"
git status --porcelain -- "$@"
```

### Unit R1-E: OCR merge + first unit test — judgment, 1 ticket, commit once

**Files:**
- Create: `java/OcrMerge.kt`, `app/src/test/java/us/jhangy/textify/OcrMergeTest.kt`
- Modify: `app/build.gradle.kts` (dependencies block + comment)

**Interfaces:** Produces `OcrCandidate`, `iou`, `mergeOcr` (frozen). R2-A's `Ocr.recognize` maps every ML Kit `Text.Line` (non-null `boundingBox`, non-blank text) to `OcrCandidate(text, box.left, box.top, box.right, box.bottom, line.confidence)`, calls `mergeOcr`, and maps back to `OcrLine(text, Rect(l, t, r, b))`.

**Behavior:** spec §6.2 — greedy suppression: sort by confidence desc, then text length desc, stable (input order); keep a candidate unless its IoU with an already kept one is **> 0.5**; return kept, sorted by top then left. IoU on integer rects, empty intersection or non-positive union → 0. Pure Kotlin (no `android.*`), so it runs on the JVM test runner without Android stubs.

**Constraints:** Test dependency `junit:junit:4.13.2` only (latest on Maven Central, verified). No Robolectric, no `testOptions`.

**Acceptance criteria:**
- [ ] `./gradlew testDebugUnitTest --tests us.jhangy.textify.OcrMergeTest` (gate-runner) passes 5 tests: `overlapAboveHalfKeepsHigherConfidence`, `equalConfidenceKeepsLongerText`, `iouExactlyHalfKeepsBoth`, `disjointLinesBothKept`, `resultSortedTopThenLeft`.
- [ ] Red check (R1 reviewer, on a copy): replace `> IOU_LIMIT` with `>= IOU_LIMIT` in a scratch copy of `OcrMerge.kt` (cp backup first, restore only from the backup) → `iouExactlyHalfKeepsBoth` fails; restore → passes.
- [ ] `grep -c 'testImplementation("junit:junit:4.13.2")' app/build.gradle.kts` = 1; `grep -n "^import android" app/src/main/java/us/jhangy/textify/OcrMerge.kt` prints nothing.

**Steps:**

- [ ] **Step 1: Edit `app/build.gradle.kts`** — in `dependencies { … }` replace the OCR comment and add the test dependency:
```kotlin
    // On-device OCR. Every selected script's recognizer runs on the same screenshot in parallel and the
    // results are merged (OcrMerge.kt); the Chinese model also covers Latin/English.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")

    testImplementation("junit:junit:4.13.2")
```

- [ ] **Step 2: Write the failing test `app/src/test/java/us/jhangy/textify/OcrMergeTest.kt`**
```kotlin
package us.jhangy.textify

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrMergeTest {

    private fun c(text: String, l: Int, t: Int, r: Int, b: Int, conf: Float) = OcrCandidate(text, l, t, r, b, conf)

    @Test
    fun overlapAboveHalfKeepsHigherConfidence() {
        // IoU = 60 / 100 = 0.6
        val out = mergeOcr(listOf(c("low", 0, 0, 10, 10, 0.4f), c("high", 0, 0, 10, 6, 0.9f)))
        assertEquals(listOf("high"), out.map { it.text })
    }

    @Test
    fun equalConfidenceKeepsLongerText() {
        val out = mergeOcr(listOf(c("ab", 0, 0, 10, 10, 0.5f), c("abcd", 1, 0, 10, 10, 0.5f)))
        assertEquals(listOf("abcd"), out.map { it.text })
    }

    @Test
    fun iouExactlyHalfKeepsBoth() {
        // IoU = 50 / 100 = 0.5 -> not > 0.5 -> both kept
        val out = mergeOcr(listOf(c("a", 0, 0, 10, 10, 0.9f), c("b", 0, 0, 10, 5, 0.1f)))
        assertEquals(setOf("a", "b"), out.map { it.text }.toSet())
    }

    @Test
    fun disjointLinesBothKept() {
        val out = mergeOcr(listOf(c("a", 0, 0, 10, 10, 0.9f), c("b", 0, 20, 10, 30, 0.9f)))
        assertEquals(2, out.size)
    }

    @Test
    fun resultSortedTopThenLeft() {
        val out = mergeOcr(
            listOf(
                c("bottom", 0, 50, 10, 60, 0.9f),
                c("topRight", 40, 0, 50, 10, 0.1f),
                c("topLeft", 0, 0, 10, 10, 0.5f),
            ),
        )
        assertEquals(listOf("topLeft", "topRight", "bottom"), out.map { it.text })
    }
}
```
Run (gate-runner, or skip and let the round gate catch it): `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew testDebugUnitTest --tests us.jhangy.textify.OcrMergeTest; echo "RC=$?"` → expected compile failure `Unresolved reference: mergeOcr` (the red step; record it in the report if run).

- [ ] **Step 3: Create `java/OcrMerge.kt`**
```kotlin
package us.jhangy.textify

import kotlin.math.max
import kotlin.math.min

/** One recognised line from one script model; box in screenshot pixels. Pure Kotlin so the merge runs on the JVM test runner. */
internal data class OcrCandidate(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float,
)

/** Intersection over union of two integer rects; 0 when they do not overlap or the union is empty. */
internal fun iou(a: OcrCandidate, b: OcrCandidate): Float {
    val iw = min(a.right, b.right) - max(a.left, b.left)
    val ih = min(a.bottom, b.bottom) - max(a.top, b.top)
    if (iw <= 0 || ih <= 0) return 0f
    val inter = iw.toLong() * ih
    val areaA = (a.right - a.left).toLong() * (a.bottom - a.top)
    val areaB = (b.right - b.left).toLong() * (b.bottom - b.top)
    val union = areaA + areaB - inter
    return if (union <= 0L) 0f else inter.toFloat() / union
}

private const val IOU_LIMIT = 0.5f

/**
 * Merges the lines of several recognizers run on the same screenshot (spec §6.2): highest confidence first
 * (ties: longer text, then input order); a line overlapping an already kept one with IoU > 0.5 is dropped.
 * Equal confidences (e.g. models that report 0) fall through to "longer text wins".
 * Result sorted top-to-bottom, then left-to-right.
 */
internal fun mergeOcr(candidates: List<OcrCandidate>): List<OcrCandidate> {
    val ranked = candidates.sortedWith(
        compareByDescending<OcrCandidate> { it.confidence }.thenByDescending { it.text.length },
    )   // sortedWith is stable: input order breaks remaining ties
    val kept = ArrayList<OcrCandidate>(ranked.size)
    for (c in ranked) if (kept.none { iou(it, c) > IOU_LIMIT }) kept += c
    return kept.sortedWith(compareBy({ it.top }, { it.left }))
}
```

- [ ] **Step 4: Run the test** (gate-runner): same command → `BUILD SUCCESSFUL`, 5 tests, `RC=0`.

- [ ] **Step 5: Commit**
```bash
cd /Users/jhangyu/project/textify && git rev-parse --abbrev-ref HEAD
set -- app/src/main/java/us/jhangy/textify/OcrMerge.kt app/src/test/java/us/jhangy/textify/OcrMergeTest.kt app/build.gradle.kts   # zsh- and bash-safe path list
git add -- "$@" && git commit -m "feat(ocr): IoU merge of multi-recognizer lines with JVM unit test" -m "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>" -- "$@"
git status --porcelain -- "$@"
```

### R1 reviewer (podium:team-reviewer) — checks after all five units report

1. Ownership: `git diff --name-only <round-1 base>..HEAD` lists only the files of the R1 file map (plus nothing else).
2. Compatibility contract: the `git diff --stat` command of "Round-1 compatibility contract" prints nothing.
3. Each unit's acceptance greps (re-run, do not trust reports).
4. Build gate (gate-runner): `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug testDebugUnitTest; echo "RC=$?"` → `RC=0`, and the output contains `OcrMergeTest` with 5 tests run (from `app/build/test-results/testDebugUnitTest/*.xml`: `grep -c "<testcase" …` = 5).
5. R1-E red check (mutation on a copy, restore from the `cp` backup).
6. Code reading against the spec: `HaloRecipe` table = spec §8.1; `DemoSpec` = spec §8.2; keyframe numbers in `CaptureDemoView` = spec §8.2 list; `PillView` constants unchanged (`git diff <base> -- PillView.kt` touches only glow/fill/argb lines); `EdgeGlowView.SESSION` = old constants.
7. Blocker vs. nit: only build failures, compatibility breaks, wrong spec values and ownership violations are blockers (two review rounds max per unit); everything else goes to the parking lot.

Round-1 build failures route to the owner of the failing file (file map); max 2 fix cycles per unit, then the lead escalates.

---

## Round 2 — switch-over (5 implementers + 1 reviewer, parallel; build + tests green at the round-2 gate)

Merged from planner-r2-opus's draft. Path convention for this round: file paths without a leading `app/`, `docs/` or `README` are relative to `app/src/main/` (e.g. `java/us/jhangy/textify/Ocr.kt`, `res/layout/activity_main.xml`); run each unit's acceptance greps from `app/src/main` unless the command itself starts with `cd` or uses `app/…` paths (those run from the repo root). Round-2 base: the commit the lead records when dispatching round 2.

Merge resolutions: the old prefs constant is named `SCRIPTS_KEY` (the R2-A grep for `KEY_SCRIPT` therefore stays valid); R1-E's `app/build.gradle.kts` comment already describes the final parallel-recognizer state, so no round-2 unit needs that file; the onboarding chip offset (`translationY -25dp`) is set by R2-B's layout. The lead's "cssGradient helper" ruling is met by R1-B's `CssGradientDrawable` (the same CSS-angle logic, used by `HaloStyle.cta`), and the stage box is owned by R1-C via `DemoSpec.stageCornerDp` with the flash drawn outside the stage clip.

### Round-2 rules (every R2 unit)

- Round 2 starts after the round-1 gate is green. All R1 files exist in the tree; read them, but code only against the signatures in "Frozen interfaces (round 2)" below. If an R1 file disagrees with this table, STOP and report BLOCKED to team-lead (do not edit R1 files, do not adapt silently).
- Round-2 units switch shared interfaces together, so no unit compiles alone. Implementers do **not** run Gradle. Per-unit checks are the greps in each unit's acceptance list; the build + unit tests run once at the round-2 gate (R2 reviewer section).
- Before editing, run each grep of your acceptance list once and see it fail (red); after the change see it pass (green).
- No `Build.VERSION.SDK_INT` outside the two §11 branches (both in `MainActivity.kt`). Light theme only. Every fade ≤ 400 ms (§0).
- Text weights: Android API 26 has no per-weight font selection on one code path (`android:textFontWeight` is API 28+), so CSS weight 600/700/800 → `android:textStyle="bold"`, weight 500 → normal. Line heights use `app:lineHeight` (AppCompatTextView attribute, verified present in `appcompat-1.7.0` `declare-styleable AppCompatTextView`); value = CSS line-height × font size.
- Shapes: use R1-B `CssGradientDrawable` / `CssGradientDrawable.solid(color, cornerPx)` (`cornerPx < 0` = full-round); a plain `GradientDrawable` only where per-corner radii are needed (onboarding sheet).
- Git: commit only your own paths with a pathspec (`git commit -- <paths>`); never `git add -A`, `stash` (including `stash list`), `reset`, `checkout --`, `clean`, `push`. `git rev-parse --abbrev-ref HEAD` before committing, `git status --porcelain -- <your paths>` after (empty). Commit only if the dispatch prompt says so.
- No sleep/polling. Implementers report `READY_FOR_SIGNOFF` / `BLOCKED`; they never close tickets.

### Frozen interfaces (round 2)

Every implementer codes against exactly these. "Owner" = the unit that defines it.

#### Produced in round 2

| Owner | Exact signature / name | Consumers |
|---|---|---|
| R2-A | `enum class Script { CHINESE, JAPANESE, KOREAN }` (unchanged, `Prefs.kt`) | R2-C, R1-D views |
| R2-A | `object Prefs { fun scripts(ctx: Context): Set<Script>; fun setScripts(ctx: Context, scripts: Set<Script>) }` — §6.1; returned set iterates in enum order, never empty | R2-C, R2-D |
| R2-A | `object Ocr { fun recognize(ctx: Context, bitmap: Bitmap, scripts: Set<Script>, onResult: (List<OcrLine>) -> Unit) }` — §6.2; `onResult` exactly once on the main thread | R2-D |
| R2-A | `data class OcrLine(val text: String, val box: Rect)` (unchanged, `Ocr.kt`) | R2-D (`PillView`) |
| R2-A | `CaptureService.Companion`: `val isRunning: Boolean`, `fun setSettingsVisible(visible: Boolean)`, `fun onSessionEnded()`, `fun start(ctx: Context, code: Int, data: Intent)` — §5. `stop(ctx)` no longer exists. All main thread. | R2-B (`isRunning`, `start`), R2-C (`setSettingsVisible`), R2-D (`setSettingsVisible`, `onSessionEnded`) |
| R2-C | `class SettingsActivity : AppCompatActivity() { companion object { fun start(ctx: Context) } }` — §7.1; flags `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_NO_ANIMATION` | R2-B, R2-D, R2-E (manifest `.SettingsActivity`) |
| R2-B | `ActivityMainBinding` ids: `root` FrameLayout, `demo` CaptureDemoView, `sheet` ScrollView, `sheetBody` LinearLayout, `spacer` Space, `track` StepTrackView, `title`, `lead` TextView, `facts` WrapLayout, `actions` LinearLayout, `later`, `cta` TextView, `ready` LinearLayout, `dot1`/`dot2`/`dot3` View (the demo mini screen and chip are found by tag inside `CaptureDemoView`, no ids) | R2-B only |
| R2-C | `ActivitySettingsBinding` ids: see R2-C layout | R2-C only |
| R2-D | `activity_session.xml` `@+id/settings` FrameLayout | R2-D only |
| R2-E | Strings: exactly the §10.1 table (names below). Themes: `Theme.Textify`, `Theme.Textify.Translucent`. Manifest activity `.SettingsActivity`. | all |

String names R2-B/C/D reference (all defined by R2-E): `notif_title notif_text notif_stop session_close session_settings session_no_text session_no_text_sub toast_started toast_consent_denied toast_later toast_bubble_hidden onboard_title onboard_lead onboard_denied_lead onboard_granted_lead onboard_cta_retry onboard_later onboard_fact_1 onboard_fact_2 onboard_fact_3 onboard_ready step_overlay step_capture demo_header demo_line_1 demo_line_2 demo_line_3 demo_line_4 demo_line_5 demo_line_6 demo_chip perm_section perm_granted_title perm_granted_text perm_denied_title perm_denied_text perm_cta perm_switch settings_title settings_back settings_foot lang_section lang_section_hint lang_zh lang_zh_sub lang_ja lang_ja_sub lang_ko lang_ko_sub lang_latin_note lang_block preview_header preview_zh preview_en preview_ja preview_ko tag_zh tag_en tag_ja tag_ko speed_label speed_fast speed_slower speed_desc_one speed_desc_many` (`speed_desc_many` takes one `%1$d` Int).

#### Consumed from round 1 (must already exist when round 2 starts)

Binding source: the plan file sections **"Frozen interfaces (round 1)"** and **"XML contracts of the round-1 views"** (they win over spec §8 code blocks). Round-2 code uses only these members:

| R1 owner | Exact signature / name |
|---|---|
| R1-A | drawables `R.drawable.ic_stat_textify`, `ic_session_settings`, `ic_back`; mipmap `@mipmap/ic_launcher` |
| R1-B (`Glow.kt`) | `internal fun withAlpha(alpha: Float, rgb: Int): Int` (`rgb` = `0xRRGGBB`); `internal fun Context.dpToPx(dp: Float): Float` |
| R1-B | `class CssGradientDrawable(colors: IntArray, positions: FloatArray?, angleDeg: Float, cornerPx: Float /* < 0 = h/2 */) : Drawable()`; `CssGradientDrawable.solid(color: Int, cornerPx: Float)` |
| R1-B | `class GlowDrawable(fill: Drawable, glowColor: Int, blurPx: Float, dyPx: Float, spreadPx: Float, cornerPx: Float /* < 0 = h/2 */) : Drawable()` |
| R1-B | `object HaloStyle { val MIST: Int; val ONBOARDING_SPOTS: List<MistSpot>; val SETTINGS_SPOTS: List<MistSpot>; fun page(ctx: Context, spots: List<MistSpot>): Drawable; fun cta(ctx: Context): Drawable; fun card(ctx: Context): Drawable; fun chip(ctx: Context): Drawable }` |
| R1-C | `class CaptureDemoView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout` — `fun configure(spec: DemoSpec)` (once after inflation), `fun setGranted(granted: Boolean, animate: Boolean)`, `fun pause()`, `fun resume()`, `fun flash()`; `DemoSpec.ONBOARDING`, `DemoSpec.SETTINGS`. It **is** the stage box (draws stage gradient, sets its own `clipChildren=false`). XML contract: direct child tagged `demoScreen` (vertical, fixed size, `layout_gravity="center_horizontal"` + top margin) containing a view tagged `avatar` and message `TextView`s tagged `line` / `line_me` in stagger order; child `TextView` tagged `demoChip` (`layout_gravity="center"`). The view sets the screen/avatar/message/chip backgrounds, message text colours and chip alpha — layouts set none of these. |
| R1-C | `class StepTrackView @JvmOverloads constructor(…) : View` — `enum class State { CURRENT, DENIED, GRANTED }`, `fun setLabels(first: CharSequence, second: CharSequence)`, `fun setState(state: State, animate: Boolean)`; `layout_height="wrap_content"` |
| R1-D | `class LanguagePreviewView @JvmOverloads constructor(…) : FrameLayout` — `fun setScripts(scripts: Set<Script>, animate: Boolean)`. XML contract: child tagged `previewShot` (vertical, padding 10/12/0, children spaced by 8dp margins) containing a view tagged `avatar` and four row `FrameLayout`s tagged `zh`, `en`, `ja`, `ko` (padding 5dp vertical / 10dp horizontal; `layout_gravity` start for zh/ja, end for en/ko), each with `TextView`s tagged `text` and `tag`. The view sets shot background + scrim, row / tag / avatar backgrounds, tag position and alpha, text alpha, clip flags. |
| R1-D | `class LanguageToggleView @JvmOverloads constructor(…) : FrameLayout` — `var checked: Boolean; private set`, `fun setChecked(value: Boolean, animate: Boolean)`, `fun reject()`. XML: a centred container with `TextView`s tagged `glyph` and `sub` (layout sets text, size, weight; view sets colours, fill, press scale, accessibility). The activity's `OnClickListener` decides; the view never toggles itself. |
| R1-D | `class SpeedLanesView @JvmOverloads constructor(…) : View` — `fun setLabels(zh: CharSequence, ja: CharSequence, ko: CharSequence)`, `fun setScripts(scripts: Set<Script>, animate: Boolean)` |
| R1-E (`OcrMerge.kt`) | `internal data class OcrCandidate(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val confidence: Float)`, `internal fun mergeOcr(candidates: List<OcrCandidate>): List<OcrCandidate>` |

Glow rule (plan Global Constraints): every ancestor of a glowing view up to one large enough sets `clipChildren=false` and `clipToPadding=false`.

### R2-A Service + Ocr/Prefs switch (judgment: 2 tickets)

**Owned files:** `java/us/jhangy/textify/CaptureService.kt`, `java/us/jhangy/textify/Ocr.kt`, `java/us/jhangy/textify/Prefs.kt`.
**Deps:** R1-E `OcrCandidate`, `mergeOcr`; R1-A `R.drawable.ic_stat_textify`; R2-E strings `notif_title`, `notif_text`, `notif_stop`, `toast_bubble_hidden`.
**Produces:** `Prefs.scripts/setScripts`, `Ocr.recognize(…, scripts: Set<Script>, …)`, `CaptureService.isRunning/setSettingsVisible/onSessionEnded/start` (frozen table).
**Baton:** ticket A1 = Prefs + Ocr (steps 1–2); ticket A2 = CaptureService (step 3). One holder may carry both (2 tickets).

#### Step A1.1 — `Prefs.kt` (replace whole file, §6.1)

```kotlin
package us.jhangy.textify

import android.content.Context

/** ML Kit script models. Every model also reads Latin/English. */
enum class Script { CHINESE, JAPANESE, KOREAN }

object Prefs {
    private const val FILE = "textify"
    private const val SCRIPTS_KEY = "scripts"   // StringSet of Script names

    /** Selected scripts in enum order; unknown names dropped; empty or missing -> CHINESE. */
    fun scripts(ctx: Context): Set<Script> {
        val names = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getStringSet(SCRIPTS_KEY, null).orEmpty()
        return Script.entries.filterTo(LinkedHashSet()) { it.name in names }.ifEmpty { setOf(Script.CHINESE) }
    }

    fun setScripts(ctx: Context, scripts: Set<Script>) {
        require(scripts.isNotEmpty())
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putStringSet(SCRIPTS_KEY, scripts.mapTo(HashSet()) { it.name }).apply()
    }
}
```
Deleted: `KEY_SCRIPT`, `script()`, `setScript()` (§0.1: no migration of the old key).

#### Step A1.2 — `Ocr.kt` (replace whole file, §6.2)

```kotlin
package us.jhangy.textify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

/** One recognised text line. [box] is in screenshot (bitmap) pixels. */
data class OcrLine(val text: String, val box: Rect)

/** On-device OCR. Every selected script model runs fully offline, all in parallel on the same image. */
object Ocr {
    /**
     * Calls [onResult] exactly once on the main thread after every recognizer completed; overlapping lines
     * are merged by [mergeOcr] (sorted top, then left). A failing recognizer contributes no lines.
     */
    fun recognize(ctx: Context, bitmap: Bitmap, scripts: Set<Script>, onResult: (List<OcrLine>) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val image = try {
            InputImage.fromBitmap(bitmap, 0)
        } catch (e: Exception) {                 // e.g. recycled bitmap
            main.post { onResult(emptyList()) }
            return
        }
        if (scripts.isEmpty()) { main.post { onResult(emptyList()) }; return }
        val parts = arrayOfNulls<List<OcrCandidate>>(scripts.size)   // touched on the main thread only
        var pending = scripts.size
        fun done(i: Int, found: List<OcrCandidate>) = main.post {
            parts[i] = found
            if (--pending == 0) {
                onResult(mergeOcr(parts.flatMap { it.orEmpty() })
                    .map { OcrLine(it.text, Rect(it.left, it.top, it.right, it.bottom)) })
            }
        }
        scripts.forEachIndexed { i, script ->
            val recognizer = client(script)
            try {
                recognizer.process(image)
                    .addOnSuccessListener { done(i, candidates(it)) }
                    .addOnFailureListener { done(i, emptyList()) }
                    .addOnCompleteListener { recognizer.close() }
            } catch (e: Exception) {             // synchronous throw from process()
                recognizer.close()
                done(i, emptyList())
            }
        }
    }

    private fun client(script: Script): TextRecognizer = when (script) {
        Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    private fun candidates(result: Text): List<OcrCandidate> = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
        val b = line.boundingBox
        if (b == null || line.text.isBlank()) null
        else OcrCandidate(line.text, b.left, b.top, b.right, b.bottom, line.confidence)   // Text.Line.getConfidence(), §12
    }
}
```
Notes: one `InputImage` shared by all recognizers (§6.2); every `process()` starts before any completes (parallel). `ponytail:` §6.2 VERIFY fallback (sequential run on OOM / >2× time) is not built; add it only if the on-device check fails.

#### Step A2.1 — `CaptureService.kt` edits (§5)

Apply to the current file (line refs = current `CaptureService.kt`):
1. Imports: delete `import android.os.Build`; add `import android.provider.Settings` and `import android.widget.Toast`.
2. Class doc comment: append "While [SettingsActivity] is visible the bubble stays hidden ([setSettingsVisible])."
3. Fields after `private var busy = false` (line 31) add:
   ```kotlin
   private var settingsVisible = false
   private val showBubble = Runnable { if (!settingsVisible && !busy) bubble?.fadeIn() }
   ```
4. `capture()` (line 75): first line becomes `if (busy || settingsVisible) return`.
5. `sessionEnded()` (lines 92–95) becomes:
   ```kotlin
   private fun sessionEnded() {
       busy = false
       if (!settingsVisible) bubble?.fadeIn()
   }
   ```
6. New method after `sessionEnded()`:
   ```kotlin
   private fun applySettingsVisible(visible: Boolean) {
       settingsVisible = visible
       main.removeCallbacks(showBubble)
       if (visible) {
           // busy: the bubble is already hidden; a second fadeOut would cancel capture()'s pending grab
           if (!busy) bubble?.fadeOut {}
           return
       }
       if (busy) return                       // the session end fades the bubble in
       if (Settings.canDrawOverlays(this)) {
           main.postDelayed(showBubble, BUBBLE_DELAY)   // bubbleDelay 120, then bubbleIn 300 (Bubble.fadeIn)
       } else {
           Toast.makeText(this, R.string.toast_bubble_hidden, Toast.LENGTH_LONG).show()
       }
   }
   ```
7. `buildNotification()` (lines 97–114) becomes:
   ```kotlin
   private fun buildNotification(): Notification {
       getSystemService(NotificationManager::class.java).createNotificationChannel(
           NotificationChannel(CHANNEL, "Textify", NotificationManager.IMPORTANCE_LOW)
       )
       val stop = PendingIntent.getService(
           this, 0, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
           PendingIntent.FLAG_IMMUTABLE
       )
       return NotificationCompat.Builder(this, CHANNEL)
           .setSmallIcon(R.drawable.ic_stat_textify)
           .setColor(0xFF7C3AED.toInt())
           .setContentTitle(getString(R.string.notif_title))
           .setContentText(getString(R.string.notif_text))
           .addAction(0, getString(R.string.notif_stop), stop)
           .setOngoing(true)
           .build()
   }
   ```
8. `onDestroy()`: first statement after `instance = null` add `main.removeCallbacks(showBubble)`.
9. Companion: add `private const val BUBBLE_DELAY = 120L`; replace `start` and delete `stop`:
   ```kotlin
   /** True while the service instance exists. */
   val isRunning: Boolean get() = instance != null

   /** SettingsActivity / SessionActivity (main thread): hide the bubble while settings is visible. No-op if not running. */
   fun setSettingsVisible(visible: Boolean) {
       instance?.applySettingsVisible(visible)
   }

   fun start(ctx: Context, code: Int, data: Intent) {
       ctx.startForegroundService(
           Intent(ctx, CaptureService::class.java).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data)
       )
   }
   ```
   `ACTION_STOP` stays (notification action).

#### R2-A acceptance
- [ ] `grep -n "fun stop\|ic_menu_crop\|SDK_INT\|KEY_SCRIPT\|fun script(" java/us/jhangy/textify/CaptureService.kt java/us/jhangy/textify/Prefs.kt java/us/jhangy/textify/Ocr.kt` prints nothing
- [ ] `grep -n "createScreenCapture" java/us/jhangy/textify/CaptureService.kt` prints nothing
- [ ] `grep -n "confidence" java/us/jhangy/textify/Ocr.kt` hits
- [ ] `grep -c "val isRunning: Boolean\|fun setSettingsVisible(visible: Boolean)\|fun onSessionEnded()\|fun start(ctx: Context, code: Int, data: Intent)" java/us/jhangy/textify/CaptureService.kt` prints `4`
- [ ] `grep -c "fun scripts(ctx: Context): Set<Script>\|fun setScripts(ctx: Context, scripts: Set<Script>)" java/us/jhangy/textify/Prefs.kt` prints `2`
- [ ] `grep -c "fun recognize(ctx: Context, bitmap: Bitmap, scripts: Set<Script>, onResult: (List<OcrLine>) -> Unit)" java/us/jhangy/textify/Ocr.kt` prints `1`
- [ ] `grep -n "BUBBLE_DELAY = 120L\|ic_stat_textify\|0xFF7C3AED\|notif_text\|notif_stop\|toast_bubble_hidden" java/us/jhangy/textify/CaptureService.kt` shows all six
- [ ] round-2 gate green

---

### R2-B Launch flow + onboarding (judgment: 2 tickets)

**Owned files:** `java/us/jhangy/textify/MainActivity.kt` (rewrite), `res/layout/activity_main.xml` (rewrite).
**Deps:** R1-B (`withAlpha`, `Context.dpToPx`, `GlowDrawable`, `CssGradientDrawable`, `HaloStyle`), R1-C (`CaptureDemoView`, `DemoSpec.ONBOARDING`, `StepTrackView`); R2-A `CaptureService.isRunning`, `CaptureService.start`; R2-C `SettingsActivity.start`; R2-E strings + `Theme.Textify.Translucent`.
**Baton:** ticket B1 = layout + router/consent (steps B1.1–B1.2); ticket B2 = onboarding states, copy swap, intro, ready dots (step B2.1).

#### Step B1.1 — `res/layout/activity_main.xml` (replace whole file, §3.1)

Root FrameLayout; content padded by insets in code. Values in dp/sp from §3.1.

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Onboarding (spec §3.1): stage box with the looping capture demo, bottom sheet, ready line. -->
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:clipChildren="false"
    android:clipToPadding="false">

    <us.jhangy.textify.CaptureDemoView
        android:id="@+id/demo"
        android:layout_width="match_parent"
        android:layout_height="420dp"
        android:layout_marginStart="26dp"
        android:layout_marginTop="54dp"
        android:layout_marginEnd="26dp"
        android:importantForAccessibility="noHideDescendants">

        <LinearLayout
            android:tag="demoScreen"
            android:layout_width="212dp"
            android:layout_height="400dp"
            android:layout_gravity="top|center_horizontal"
            android:layout_marginTop="26dp"
            android:orientation="vertical">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="42dp"
                android:background="#FFFFFF"
                android:gravity="center_vertical"
                android:orientation="horizontal"
                android:paddingStart="10dp"
                android:paddingEnd="10dp">
                <View android:tag="avatar" android:layout_width="22dp" android:layout_height="22dp" />
                <TextView
                    android:layout_width="wrap_content" android:layout_height="wrap_content"
                    android:layout_marginStart="8dp"
                    android:text="@string/demo_header"
                    android:textColor="#1D2330" android:textSize="11sp" android:textStyle="bold" />
            </LinearLayout>

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:padding="10dp">
                <!-- max width 82% of 192dp = 157dp; padding 9/6; gap 8; outgoing = tag line_me + layout_gravity end.
                     Backgrounds and text colours are set by CaptureDemoView. -->
                <TextView android:tag="line"
                    android:layout_width="wrap_content" android:layout_height="wrap_content"
                    android:maxWidth="157dp" android:paddingStart="9dp" android:paddingTop="6dp" android:paddingEnd="9dp" android:paddingBottom="6dp"
                    android:text="@string/demo_line_1" android:textSize="10.5sp" app:lineHeight="15.2sp" />
                <TextView android:tag="line"
                    android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_marginTop="8dp"
                    android:maxWidth="157dp" android:paddingStart="9dp" android:paddingTop="6dp" android:paddingEnd="9dp" android:paddingBottom="6dp"
                    android:text="@string/demo_line_2" android:textSize="10.5sp" app:lineHeight="15.2sp" />
                <TextView android:tag="line_me"
                    android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_marginTop="8dp"
                    android:layout_gravity="end"
                    android:maxWidth="157dp" android:paddingStart="9dp" android:paddingTop="6dp" android:paddingEnd="9dp" android:paddingBottom="6dp"
                    android:text="@string/demo_line_3" android:textSize="10.5sp" app:lineHeight="15.2sp" />
                <TextView android:tag="line"
                    android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_marginTop="8dp"
                    android:maxWidth="157dp" android:paddingStart="9dp" android:paddingTop="6dp" android:paddingEnd="9dp" android:paddingBottom="6dp"
                    android:text="@string/demo_line_4" android:textSize="10.5sp" app:lineHeight="15.2sp" />
                <TextView android:tag="line"
                    android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_marginTop="8dp"
                    android:maxWidth="157dp" android:paddingStart="9dp" android:paddingTop="6dp" android:paddingEnd="9dp" android:paddingBottom="6dp"
                    android:text="@string/demo_line_5" android:textSize="10.5sp" app:lineHeight="15.2sp" />
                <TextView android:tag="line_me"
                    android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_marginTop="8dp"
                    android:layout_gravity="end"
                    android:maxWidth="157dp" android:paddingStart="9dp" android:paddingTop="6dp" android:paddingEnd="9dp" android:paddingBottom="6dp"
                    android:text="@string/demo_line_6" android:textSize="10.5sp" app:lineHeight="15.2sp" />
            </LinearLayout>
        </LinearLayout>

        <!-- centred at 50% / 44% of the 420dp stage: translationY = -0.06 * 420 = -25dp -->
        <TextView
            android:tag="demoChip"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="center"
            android:translationY="-25dp"
            android:paddingStart="16dp" android:paddingTop="9dp" android:paddingEnd="16dp" android:paddingBottom="9dp"
            android:singleLine="true"
            android:text="@string/demo_chip"
            android:textColor="#9A3412" android:textSize="13sp" android:textStyle="bold" />
    </us.jhangy.textify.CaptureDemoView>

    <!-- Sheet: preferred top 448dp (§3.1). On short screens code moves it up (fitSheet) and the
         ScrollView keeps every child reachable (lead ruling). fillViewport keeps the actions bottom-anchored. -->
    <ScrollView
        android:id="@+id/sheet"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:layout_marginTop="448dp"
        android:fillViewport="true"
        android:overScrollMode="never"
        android:scrollbars="none">

    <LinearLayout
        android:id="@+id/sheetBody"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:paddingStart="28dp"
        android:paddingTop="26dp"
        android:paddingEnd="28dp">

        <us.jhangy.textify.StepTrackView
            android:id="@+id/track"
            android:layout_width="match_parent"
            android:layout_height="wrap_content" />

        <TextView
            android:id="@+id/title"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:layout_marginTop="20dp" android:layout_marginBottom="10dp"
            android:letterSpacing="-0.01"
            android:textColor="#1E1B4B" android:textSize="26sp" android:textStyle="bold" app:lineHeight="33.3sp" />

        <TextView
            android:id="@+id/lead"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:textColor="#4C4380" android:textSize="15sp" app:lineHeight="24sp" />

        <us.jhangy.textify.WrapLayout
            android:id="@+id/facts"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:layout_marginTop="18dp">
            <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:paddingStart="12dp" android:paddingTop="7dp" android:paddingEnd="12dp" android:paddingBottom="7dp"
                android:text="@string/onboard_fact_1" android:textColor="#4C1D95" android:textSize="12.5sp" />
            <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:paddingStart="12dp" android:paddingTop="7dp" android:paddingEnd="12dp" android:paddingBottom="7dp"
                android:text="@string/onboard_fact_2" android:textColor="#4C1D95" android:textSize="12.5sp" />
            <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:paddingStart="12dp" android:paddingTop="7dp" android:paddingEnd="12dp" android:paddingBottom="7dp"
                android:text="@string/onboard_fact_3" android:textColor="#4C1D95" android:textSize="12.5sp" />
        </us.jhangy.textify.WrapLayout>

        <Space android:id="@+id/spacer" android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1" />

        <!-- paddingBottom = 34dp + navigation-bar inset, set in code -->
        <LinearLayout
            android:id="@+id/actions"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:paddingTop="16dp">
            <TextView
                android:id="@+id/later"
                android:layout_width="wrap_content" android:layout_height="56dp"
                android:gravity="center"
                android:paddingStart="20dp" android:paddingEnd="20dp"
                android:text="@string/onboard_later"
                android:textColor="#4C1D95" android:textSize="15sp" />
            <TextView
                android:id="@+id/cta"
                android:layout_width="0dp" android:layout_height="56dp" android:layout_weight="1"
                android:layout_marginStart="10dp"
                android:gravity="center"
                android:textColor="#FFFFFF" android:textSize="16.5sp" android:textStyle="bold" />
        </LinearLayout>
    </LinearLayout>
    </ScrollView>

    <!-- bottom margin = 56dp + navigation-bar inset, set in code -->
    <LinearLayout
        android:id="@+id/ready"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|center_horizontal"
        android:alpha="0"
        android:gravity="center_vertical"
        android:orientation="horizontal">
        <TextView
            android:layout_width="wrap_content" android:layout_height="wrap_content"
            android:text="@string/onboard_ready" android:textColor="#6B5FA8" android:textSize="14sp" />
        <View android:id="@+id/dot1" android:layout_width="6dp" android:layout_height="6dp" android:layout_marginStart="2dp" android:layout_marginEnd="2dp" android:alpha="0.3" />
        <View android:id="@+id/dot2" android:layout_width="6dp" android:layout_height="6dp" android:layout_marginStart="2dp" android:layout_marginEnd="2dp" android:alpha="0.3" />
        <View android:id="@+id/dot3" android:layout_width="6dp" android:layout_height="6dp" android:layout_marginStart="2dp" android:layout_marginEnd="2dp" android:alpha="0.3" />
    </LinearLayout>
</FrameLayout>
```
All message TextViews carry identical attributes except text, `layout_marginTop` (first has none), and tag `line_me` + `layout_gravity="end"` for the outgoing lines 3 and 6. No backgrounds or text colours on `demoScreen`, `avatar`, messages or `demoChip` (CaptureDemoView sets them, plan "XML contracts"); `demoScreen` must stay a direct child of `CaptureDemoView`.
Line heights: 10.5×1.45 = 15.2sp, 26×1.28 = 33.3sp, 15×1.6 = 24sp.

#### Step B1.2 — `MainActivity.kt` router + consent (replace whole file; B2 fills the onboarding methods)

Full file (B1 writes all of it; B2 owns the bodies marked `// B2`, which are given in step B2.1):

```kotlin
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
```
`WrapLayout` lives in `MainActivity.kt` (owned file; no new file allowed by the ownership matrix). `ponytail:` its only user is the facts row; no AndroidX flexbox dependency added.

#### Step B2.1 — onboarding bodies (§3.1–§3.3)

Replace the three `/* B2 */` stubs with:

```kotlin
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
            .setStartDelay(INTRO_STAGGER * (i + 1)).setDuration(INTRO_IN).setInterpolator(EMPHASIZED)
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
    view.animate().alpha(0f).setStartDelay(0L).setDuration(COPY_OUT).setInterpolator(EASE_IN).withEndAction {
        view.setText(res)
        view.animate().alpha(1f).setDuration(COPY_IN).setInterpolator(EASE_OUT)
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
```
Notes:
- `ui` after a recreation (rotation) restarts at INTRO/DENIED from the saved `ctaTapped`; GRANTED is re-derived by `refreshState()` in `onResume`.
- The GRANTED state is terminal: `refreshState` returns early, `grantedHold` fires `requestCapture()` once (`captureRequested` guard).
- Short screens (lead ruling): `fitSheet` moves the sheet up from 448dp only as far as its content needs (it may cover the lower part of the stage box); the `ScrollView` makes the rest reachable when even the full height is too small.

#### R2-B acceptance
- [ ] `grep -n "scriptGroup\|startButton\|stopButton\|Prefs\." java/us/jhangy/textify/MainActivity.kt res/layout/activity_main.xml` prints nothing
- [ ] `grep -c "SDK_INT" java/us/jhangy/textify/MainActivity.kt` prints `2`, and the two lines contain `TIRAMISU` and `UPSIDE_DOWN_CAKE`
- [ ] `grep -n "setView(" java/us/jhangy/textify/MainActivity.kt` prints nothing
- [ ] `grep -c "= 360L\|= 60L\|= 120L\|= 220L\|= 300L\|= 900L" java/us/jhangy/textify/MainActivity.kt` prints ≥ `6` (introIn, stagger, copyOut, copyIn/state fade, appClose/recheck, grantedHold)
- [ ] `grep -n "style=" res/layout/activity_main.xml` prints nothing
- [ ] `grep -A6 '<ScrollView' res/layout/activity_main.xml | grep -c 'android:id="@+id/sheet"\|android:fillViewport="true"'` prints `2`, and `grep -n "fun fitSheet\|addOnLayoutChangeListener" java/us/jhangy/textify/MainActivity.kt` shows both
- [ ] `grep -c 'android:tag="line"' res/layout/activity_main.xml` prints `4`, `grep -c 'android:tag="line_me"' res/layout/activity_main.xml` prints `2`; `grep -c 'android:tag="demoScreen"\|android:tag="demoChip"\|android:tag="avatar"' res/layout/activity_main.xml` prints `3`
- [ ] `grep -n "demoScreen\|demoChip" java/us/jhangy/textify/MainActivity.kt` prints nothing (CaptureDemoView styles them)
- [ ] `grep -n "DemoSpec.ONBOARDING\|HaloStyle.page(this, HaloStyle.ONBOARDING_SPOTS)\|SettingsActivity.start(this)\|CaptureService.isRunning\|createConfigForDefaultDisplay" java/us/jhangy/textify/MainActivity.kt` shows all five
- [ ] round-2 gate green

---

### R2-C Settings screen (judgment: 2 tickets — C1 frame + permission card, C2 language card)

**Owned files:** `java/us/jhangy/textify/SettingsActivity.kt` (new), `res/layout/activity_settings.xml` (new).
**Deps:** R1-A `ic_back`; R1-B (`withAlpha`, `Context.dpToPx`, `GlowDrawable`, `CssGradientDrawable`, `HaloStyle`); R1-C (`CaptureDemoView`, `DemoSpec.SETTINGS`, `StepTrackView`); R1-D (`LanguagePreviewView`, `LanguageToggleView`, `SpeedLanesView`); R2-A `Prefs.scripts/setScripts`, `CaptureService.setSettingsVisible`; R2-E strings + `Theme.Textify.Translucent` + manifest entry.
**Produces:** `SettingsActivity.start` (frozen table).
**Baton:** C1 = layout top bar, permission card, footer + activity frame/entry/exit/permission (steps C1.1–C1.3); C2 = language-card layout block + behaviour (steps C2.1–C2.2). If the holder hits the baton limit, split at C1/C2; the layout file stays with one holder (C2 holder edits only the `langCard` block).

#### Step C1.1 — `res/layout/activity_settings.xml` (new; §7.1–§7.4)

Structure and every attribute (ids are frozen for the activity):

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Settings (spec §7): mist page, top bar, scrollable cards (permission, language), footer. -->
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/root"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <LinearLayout
        android:id="@+id/content"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical">

        <!-- .st-top padding 10/12/4, gap 6 -->
        <LinearLayout
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:gravity="center_vertical" android:orientation="horizontal"
            android:paddingStart="12dp" android:paddingTop="10dp" android:paddingEnd="12dp" android:paddingBottom="4dp">
            <FrameLayout
                android:id="@+id/back"
                android:layout_width="48dp" android:layout_height="48dp"
                android:clickable="true" android:focusable="true"
                android:contentDescription="@string/settings_back">
                <ImageView android:layout_width="24dp" android:layout_height="24dp" android:layout_gravity="center"
                    android:importantForAccessibility="no" android:src="@drawable/ic_back" />
            </FrameLayout>
            <TextView
                android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:layout_marginStart="6dp"
                android:letterSpacing="0.02" android:text="@string/settings_title"
                android:textColor="#1E1B4B" android:textSize="28sp" android:textStyle="bold" />
        </LinearLayout>

        <ScrollView
            android:id="@+id/scroll"
            android:layout_width="match_parent" android:layout_height="0dp" android:layout_weight="1"
            android:clipChildren="false" android:clipToPadding="false"
            android:scrollbars="none">
            <!-- body padding 6/18/28, gap 16 -->
            <LinearLayout
                android:id="@+id/body"
                android:layout_width="match_parent" android:layout_height="wrap_content"
                android:clipChildren="false" android:clipToPadding="false"
                android:orientation="vertical"
                android:paddingStart="18dp" android:paddingTop="6dp" android:paddingEnd="18dp" android:paddingBottom="28dp">

                <!-- ===== permission card (§7.2) ===== -->
                <LinearLayout
                    android:id="@+id/permCard"
                    android:layout_width="match_parent" android:layout_height="wrap_content"
                    android:clipChildren="false" android:clipToPadding="false"
                    android:orientation="vertical"
                    android:paddingStart="16dp" android:paddingTop="16dp" android:paddingEnd="16dp" android:paddingBottom="18dp">

                    <TextView
                        android:layout_width="wrap_content" android:layout_height="wrap_content"
                        android:layout_marginStart="4dp" android:layout_marginEnd="4dp" android:layout_marginBottom="12dp"
                        android:letterSpacing="0.06" android:text="@string/perm_section"
                        android:textColor="#6B5FA8" android:textSize="13sp" android:textStyle="bold" />

                    <us.jhangy.textify.CaptureDemoView
                        android:id="@+id/demo"
                        android:layout_width="match_parent" android:layout_height="178dp"
                        android:importantForAccessibility="noHideDescendants">
                        <LinearLayout
                            android:tag="demoScreen"
                            android:layout_width="176dp" android:layout_height="200dp"
                            android:layout_gravity="top|center_horizontal" android:layout_marginTop="18dp"
                            android:orientation="vertical">
                            <LinearLayout
                                android:layout_width="match_parent" android:layout_height="30dp"
                                android:background="#FFFFFF" android:gravity="center_vertical" android:orientation="horizontal"
                                android:paddingStart="8dp" android:paddingEnd="8dp">
                                <View android:tag="avatar" android:layout_width="16dp" android:layout_height="16dp" />
                                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
                                    android:layout_marginStart="6dp" android:text="@string/demo_header"
                                    android:textColor="#1D2330" android:textSize="10sp" android:textStyle="bold" />
                            </LinearLayout>
                            <!-- padding 8, gap 6; max width 86% of 160dp = 138dp; bubble padding 7/4; 9.5sp × 1.45 = 13.8sp -->
                            <LinearLayout
                                android:layout_width="match_parent" android:layout_height="wrap_content"
                                android:orientation="vertical" android:padding="8dp">
                                <!-- five TextViews, each:
                                     wrap_content, maxWidth 138dp, paddingStart/End 7dp, paddingTop/Bottom 4dp,
                                     textSize 9.5sp, app:lineHeight 13.8sp, no textColor / background (CaptureDemoView sets them),
                                     layout_marginTop 6dp except the first; incoming = android:tag="line",
                                     outgoing = android:tag="line_me" + layout_gravity end.
                                     Texts in order: demo_line_1, demo_line_2, demo_line_3 (line_me), demo_line_4, demo_line_6 (line_me). -->
                            </LinearLayout>
                        </LinearLayout>
                        <TextView
                            android:tag="demoChip"
                            android:layout_width="wrap_content" android:layout_height="wrap_content"
                            android:layout_gravity="center"
                            android:paddingStart="14dp" android:paddingTop="8dp" android:paddingEnd="14dp" android:paddingBottom="8dp"
                            android:singleLine="true" android:text="@string/demo_chip"
                            android:textColor="#9A3412" android:textSize="12.5sp" android:textStyle="bold" />
                    </us.jhangy.textify.CaptureDemoView>

                    <us.jhangy.textify.StepTrackView
                        android:id="@+id/track"
                        android:layout_width="match_parent" android:layout_height="wrap_content"
                        android:layout_marginStart="4dp" android:layout_marginTop="16dp" android:layout_marginEnd="4dp" />

                    <!-- .pstat margin 14/4/0 -->
                    <TextView
                        android:id="@+id/statusTitle"
                        android:layout_width="match_parent" android:layout_height="wrap_content"
                        android:layout_marginStart="4dp" android:layout_marginTop="14dp" android:layout_marginEnd="4dp"
                        android:textColor="#1E1B4B" android:textSize="19sp" android:textStyle="bold" />
                    <TextView
                        android:id="@+id/statusText"
                        android:layout_width="match_parent" android:layout_height="wrap_content"
                        android:layout_marginStart="4dp" android:layout_marginTop="3dp" android:layout_marginEnd="4dp"
                        android:textColor="#6B5FA8" android:textSize="13sp" app:lineHeight="20.2sp" />

                    <TextView
                        android:id="@+id/cta"
                        android:layout_width="match_parent" android:layout_height="52dp"
                        android:layout_marginTop="14dp" android:gravity="center"
                        android:text="@string/perm_cta" android:textColor="#FFFFFF" android:textSize="16sp" android:textStyle="bold"
                        android:visibility="gone" />

                    <!-- .prow margin-top 12, padding 10/10/10/16, gap 12 -->
                    <LinearLayout
                        android:id="@+id/switchRow"
                        android:layout_width="match_parent" android:layout_height="wrap_content"
                        android:layout_marginTop="12dp"
                        android:clickable="true" android:focusable="true"
                        android:gravity="center_vertical" android:orientation="horizontal"
                        android:paddingStart="16dp" android:paddingTop="10dp" android:paddingEnd="10dp" android:paddingBottom="10dp"
                        android:visibility="gone">
                        <TextView
                            android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1"
                            android:layout_marginEnd="12dp" android:text="@string/perm_switch"
                            android:textColor="#1E1B4B" android:textSize="14.5sp" android:textStyle="bold" />
                        <FrameLayout
                            android:id="@+id/switchTrack"
                            android:layout_width="52dp" android:layout_height="32dp"
                            android:clipChildren="false" android:importantForAccessibility="no">
                            <!-- on position: x = 4 + 20 -->
                            <View android:id="@+id/switchThumb"
                                android:layout_width="24dp" android:layout_height="24dp"
                                android:layout_gravity="center_vertical|start" android:layout_marginStart="24dp" />
                        </FrameLayout>
                    </LinearLayout>
                </LinearLayout>

                <!-- ===== language card (§7.4) — written in step C2.1 ===== -->

                <TextView
                    android:layout_width="match_parent" android:layout_height="wrap_content"
                    android:layout_marginTop="18dp" android:gravity="center"
                    android:text="@string/settings_foot" android:textColor="#6B5FA8" android:textSize="12.5sp" />
            </LinearLayout>
        </ScrollView>
    </LinearLayout>
</FrameLayout>
```
Footer `marginTop 18` = body gap 16 + `.foot` margin-top 2. 13×1.55 = 20.2sp.
The XML comment listing the five `line` TextViews is an instruction: write five real `TextView` elements there (no comment left in place), same pattern as R2-B step B1.1 with the settings values.

#### Step C1.2 — `SettingsActivity.kt` (new): frame, entry/exit, permission card, shared helpers

```kotlin
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
```
`Rect` import is used by C2 (`requestRectangleOnScreen`).

#### Step C1.3 — verify the C1 markers (greps in the acceptance list for `setSettingsVisible`, `openingSystemSettings`, `320`, `260`).

#### Step C2.1 — language-card block in `activity_settings.xml`

Replace the comment `<!-- ===== language card (§7.4) — written in step C2.1 ===== -->` with:

```xml
<LinearLayout
    android:id="@+id/langCard"
    android:layout_width="match_parent" android:layout_height="wrap_content"
    android:layout_marginTop="16dp"
    android:clipChildren="false" android:clipToPadding="false"
    android:orientation="vertical"
    android:paddingStart="16dp" android:paddingTop="16dp" android:paddingEnd="16dp" android:paddingBottom="18dp">

    <!-- .sec with small hint (weight 500, alpha .8, letter-spacing 0), gap 8 -->
    <LinearLayout
        android:layout_width="wrap_content" android:layout_height="wrap_content"
        android:layout_marginStart="4dp" android:layout_marginEnd="4dp" android:layout_marginBottom="12dp"
        android:orientation="horizontal">
        <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
            android:letterSpacing="0.06" android:text="@string/lang_section"
            android:textColor="#6B5FA8" android:textSize="13sp" android:textStyle="bold" />
        <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
            android:layout_marginStart="8dp" android:alpha="0.8" android:text="@string/lang_section_hint"
            android:textColor="#6B5FA8" android:textSize="13sp" />
    </LinearLayout>

    <us.jhangy.textify.LanguagePreviewView
        android:id="@+id/preview"
        android:layout_width="match_parent" android:layout_height="214dp"
        android:padding="10dp" android:clipChildren="false" android:clipToPadding="false"
        android:importantForAccessibility="noHideDescendants">
        <!-- .shot: padding 10/12/0, children spaced by 8dp top margins; background, scrim, avatar/row/tag styling and tag position are set by LanguagePreviewView -->
        <LinearLayout
            android:tag="previewShot"
            android:layout_width="match_parent" android:layout_height="match_parent"
            android:orientation="vertical"
            android:paddingStart="12dp" android:paddingTop="10dp" android:paddingEnd="12dp">
            <LinearLayout android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:gravity="center_vertical" android:orientation="horizontal">
                <View android:tag="avatar" android:layout_width="24dp" android:layout_height="24dp" />
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
                    android:layout_marginStart="8dp" android:text="@string/preview_header"
                    android:textColor="#1D2330" android:textSize="12.5sp" android:textStyle="bold" />
            </LinearLayout>
            <!-- row = .pln: padding 10/5; text 13sp × 1.35 = 17.6sp; tag 9.5sp bold #7C3AED padding 6/1 -->
            <FrameLayout android:tag="zh"
                android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_marginTop="8dp"
                android:layout_gravity="start" android:clipChildren="false"
                android:paddingStart="10dp" android:paddingTop="5dp" android:paddingEnd="10dp" android:paddingBottom="5dp">
                <TextView android:tag="text" android:layout_width="wrap_content" android:layout_height="wrap_content"
                    android:text="@string/preview_zh" android:textColor="#1D2330" android:textSize="13sp" app:lineHeight="17.6sp" />
                <TextView android:tag="tag" android:layout_width="wrap_content" android:layout_height="wrap_content"
                    android:paddingStart="6dp" android:paddingTop="1dp" android:paddingEnd="6dp" android:paddingBottom="1dp"
                    android:text="@string/tag_zh" android:textColor="#7C3AED" android:textSize="9.5sp" android:textStyle="bold" />
            </FrameLayout>
            <!-- rowEn (tag "en", layout_gravity end, preview_en / tag_en),
                 rowJa (tag "ja", start, preview_ja / tag_ja),
                 rowKo (tag "ko", end, preview_ko / tag_ko): identical attributes to the zh row except tag, gravity, texts. -->
        </LinearLayout>
    </us.jhangy.textify.LanguagePreviewView>

    <!-- .tg margin-top 14, gap 10, toggles 78dp, glyph 20sp/800, sub 11.5sp/600, gap 2 -->
    <LinearLayout
        android:layout_width="match_parent" android:layout_height="wrap_content"
        android:layout_marginTop="14dp" android:clipChildren="false" android:orientation="horizontal">
        <us.jhangy.textify.LanguageToggleView
            android:id="@+id/toggleZh"
            android:layout_width="0dp" android:layout_height="78dp" android:layout_weight="1"
            android:clickable="true" android:focusable="true">
            <LinearLayout android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:layout_gravity="center" android:gravity="center_horizontal" android:orientation="vertical">
                <TextView android:tag="glyph" android:layout_width="wrap_content" android:layout_height="wrap_content"
                    android:text="@string/lang_zh" android:textSize="20sp" android:textStyle="bold" />
                <TextView android:tag="sub" android:layout_width="wrap_content" android:layout_height="wrap_content"
                    android:layout_marginTop="2dp" android:text="@string/lang_zh_sub" android:textSize="11.5sp" android:textStyle="bold" />
            </LinearLayout>
        </us.jhangy.textify.LanguageToggleView>
        <!-- toggleJa (lang_ja / lang_ja_sub) and toggleKo (lang_ko / lang_ko_sub): same, plus android:layout_marginStart="10dp" -->
    </LinearLayout>

    <!-- .notice height 54 -->
    <FrameLayout
        android:id="@+id/notice"
        android:layout_width="match_parent" android:layout_height="54dp" android:clipChildren="false">
        <TextView android:id="@+id/noteRest"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:layout_marginStart="4dp" android:layout_marginTop="16dp" android:layout_marginEnd="4dp"
            android:text="@string/lang_latin_note" android:textColor="#6B5FA8" android:textSize="12.5sp" />
        <LinearLayout android:id="@+id/noteBlock"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:layout_marginTop="8dp" android:alpha="0" android:translationY="-8dp"
            android:orientation="horizontal"
            android:paddingStart="14dp" android:paddingTop="10dp" android:paddingEnd="14dp" android:paddingBottom="10dp">
            <View android:id="@+id/noteBar" android:layout_width="6dp" android:layout_height="match_parent" />
            <TextView android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1"
                android:layout_marginStart="10dp" android:text="@string/lang_block"
                android:textColor="#1E1B4B" android:textSize="13sp" android:textStyle="bold" />
        </LinearLayout>
    </FrameLayout>

    <!-- .tl padding 14/14/12, corner 20 -->
    <LinearLayout
        android:id="@+id/timeline"
        android:layout_width="match_parent" android:layout_height="wrap_content"
        android:orientation="vertical"
        android:paddingStart="14dp" android:paddingTop="14dp" android:paddingEnd="14dp" android:paddingBottom="12dp">
        <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="horizontal">
            <TextView android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1"
                android:text="@string/speed_label" android:textColor="#6B5FA8" android:textSize="13sp" android:textStyle="bold" />
            <TextView android:id="@+id/speedValue" android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:textColor="#4C1D95" android:textSize="17sp" android:textStyle="bold" />
        </LinearLayout>
        <us.jhangy.textify.SpeedLanesView
            android:id="@+id/lanes"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:layout_marginTop="10dp" android:layout_marginBottom="6dp" />
        <!-- 12.5sp × 1.55 = 19.4sp, #1E1B4B 70% = #B31E1B4B -->
        <TextView android:id="@+id/speedDesc"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:textColor="#B31E1B4B" android:textSize="12.5sp" app:lineHeight="19.4sp" />
    </LinearLayout>
</LinearLayout>
```
Placeholders in comments (`rowEn/rowJa/rowKo`, `toggleJa/toggleKo`) are instructions: write the real elements, leave no comment-only stand-ins. Final ids: `toggleZh toggleJa toggleKo` (rows have tags only). The language card's top margin is the body gap 16.

#### Step C2.2 — language behaviour in `SettingsActivity.kt` (§7.4)

Replace `private fun setupLanguageCard() { /* C2 */ }` with:

```kotlin
private val autoHide = Runnable { hideBlock() }

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
    b.noteBlock.animate().alpha(1f).translationY(0f).setDuration(MSG_IN).setInterpolator(EASE_OUT)
    b.noteRest.animate().alpha(0f).setDuration(NOTE_FADE)
    b.notice.requestRectangleOnScreen(Rect(0, 0, b.notice.width, b.notice.height))
    main.postDelayed(autoHide, MSG_IN + BLOCK_HOLD)
}

/** msgOut 160 ease-in (alpha -> 0, translationY -> -8dp); resting text back. No-op when already hidden. */
private fun hideBlock() {
    main.removeCallbacks(autoHide)
    if (b.noteBlock.alpha == 0f && b.noteRest.alpha == 1f) return
    b.noteBlock.animate().alpha(0f).translationY(-8 * d).setDuration(MSG_OUT).setInterpolator(EASE_IN)
    b.noteRest.animate().alpha(1f).setDuration(NOTE_FADE)
}
```
(`autoHide` is a property; declare it with the other fields at the top of the class.)

#### R2-C acceptance
- [ ] `grep -n "setSettingsVisible" java/us/jhangy/textify/SettingsActivity.kt` shows hits inside `onStart`, `onStop`, `onDestroy` (exactly 3 lines)
- [ ] `grep -c "openingSystemSettings" java/us/jhangy/textify/SettingsActivity.kt` prints ≥ `3`
- [ ] `grep -c "= 320L\|= 260L\|= 1500L\|= 220L\|= 160L\|= 200L" java/us/jhangy/textify/SettingsActivity.kt` prints ≥ `6`
- [ ] `grep -n "Prefs.setScripts" java/us/jhangy/textify/SettingsActivity.kt` hits, and `grep -B3 "toggle.reject()" java/us/jhangy/textify/SettingsActivity.kt` shows `if (next.isEmpty())`
- [ ] `grep -c "SDK_INT" java/us/jhangy/textify/SettingsActivity.kt` prints `0`
- [ ] `grep -c "fun start(ctx: Context)" java/us/jhangy/textify/SettingsActivity.kt` prints `1`
- [ ] `grep -n "demoScreen\|demoChip\|previewShot\|cssGradient\|MistBackground(" java/us/jhangy/textify/SettingsActivity.kt` prints nothing (R1 views style their children; page via `HaloStyle.page`)
- [ ] `grep -c 'android:tag="line"' res/layout/activity_settings.xml` prints `3` and `grep -c 'android:tag="line_me"' res/layout/activity_settings.xml` prints `2`; `grep -c 'android:tag="demoScreen"\|android:tag="demoChip"\|android:tag="previewShot"' res/layout/activity_settings.xml` prints `3`; `grep -c 'android:tag="\(zh\|en\|ja\|ko\)"' res/layout/activity_settings.xml` prints `4`; `grep -c 'android:tag="glyph"\|android:tag="sub"' res/layout/activity_settings.xml` prints `6`
- [ ] `grep -n "<!-- rowEn\|<!-- toggleJa\|<!-- five TextViews" res/layout/activity_settings.xml` prints nothing (instruction comments replaced by real elements)
- [ ] `grep -n "NestedScrollView" res/layout/activity_settings.xml` prints nothing
- [ ] round-2 gate green

---

### R2-D Session settings button (mechanical: 1 ticket)

**Owned files:** `java/us/jhangy/textify/SessionActivity.kt`, `res/layout/activity_session.xml`.
**Deps:** R1-A `ic_session_settings`; R1-B `withAlpha`; R2-A `Ocr.recognize(…, Set<Script>, …)`, `Prefs.scripts`, `CaptureService.setSettingsVisible`; R2-C `SettingsActivity.start`; R2-E string `session_settings`.

#### Step D.1 — `activity_session.xml` (§4)
Insert after the `@id/close` FrameLayout (before `@id/hint`), and update the header comment's layer list to "screenshot, scrim, pill container, edge glow, X, settings, hint":
```xml
<FrameLayout
    android:id="@+id/settings"
    android:layout_width="40dp"
    android:layout_height="40dp"
    android:layout_gravity="top|end"
    android:layout_marginTop="44dp"
    android:layout_marginEnd="68dp"
    android:background="@drawable/session_x_bg"
    android:clickable="true"
    android:contentDescription="@string/session_settings"
    android:elevation="4dp"
    android:focusable="true">

    <ImageView
        android:layout_width="20dp"
        android:layout_height="20dp"
        android:layout_gravity="center"
        android:importantForAccessibility="no"
        android:src="@drawable/ic_session_settings" />
</FrameLayout>
```

#### Step D.2 — `SessionActivity.kt` edits (§4)
1. Delete line 42 `private fun argb(...)`; in `scrimDrawable()` replace both `argb(` with `withAlpha(` (same argument order).
2. Line 100: `Ocr.recognize(this, bmp, Prefs.scripts(this)) { lines -> runOnUiThread { onOcr(lines) } }`.
3. After line 84 (`b.close.setOnClickListener { close() }`) add `b.settings.setOnClickListener { openSettings() }`.
4. Line 92 initial state: also `b.settings.apply { alpha = 0f; scaleX = 0.6f; scaleY = 0.6f; rotation = -90f; isEnabled = false }`.
5. `showClose()` becomes (X at 0, settings at btnStagger 40):
   ```kotlin
   private fun showClose() {
       listOf(b.close, b.settings).forEachIndexed { i, btn ->
           val delay = i * BTN_STAGGER
           btn.isEnabled = true
           btn.fade(1f, XIN, EMPHASIZED, delay)
           btn.animate().scaleX(1f).scaleY(1f).rotation(0f)
               .setStartDelay(delay).setDuration(XIN).setInterpolator(X_OVERSHOOT)
       }
   }
   ```
6. `fade` gains a delay: `private fun View.fade(to: Float, ms: Long, curve: TimeInterpolator, delay: Long = 0L)` with `startDelay = delay` inside the `apply`.
7. New method after `close()`:
   ```kotlin
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
   ```
   `setStartDelay(0L)` is required: `ViewPropertyAnimator` keeps the 40 ms delay set in `showClose()`.
8. Companion: add `private const val SESSION_OUT = 300L` (sessionOut) and `private const val BTN_STAGGER = 40L` (btnStagger).
9. Class doc: append "Settings button = session fades out and SettingsActivity opens (bubble stays hidden)."

#### R2-D acceptance
- [ ] `grep -n '@+id/settings' res/layout/activity_session.xml` hits and `grep -A6 '@+id/settings' res/layout/activity_session.xml | grep -c 'layout_marginEnd="68dp"'` prints `1`
- [ ] `grep -n "private fun argb\|Prefs.script(" java/us/jhangy/textify/SessionActivity.kt` prints nothing
- [ ] `grep -A4 "withEndAction {$" java/us/jhangy/textify/SessionActivity.kt` shows, in this order, `CaptureService.setSettingsVisible(true)`, `SettingsActivity.start(this)`, `endSession()`, `finish()`
- [ ] `grep -n "BTN_STAGGER = 40L" java/us/jhangy/textify/SessionActivity.kt` hits; `grep -n "SESSION_OUT = 300L" java/us/jhangy/textify/SessionActivity.kt` hits
- [ ] `grep -n "Prefs.scripts(this)" java/us/jhangy/textify/SessionActivity.kt` hits
- [ ] round-2 gate green

---

### R2-E Resources, manifest, README (mechanical: 1 ticket)

**Owned files:** `res/values/strings.xml`, `res/values/themes.xml`, `AndroidManifest.xml`, `README.md`.
**Deps:** names frozen in §10 only.

#### Step E.1 — `res/values/strings.xml` (replace whole file, §10.1; values verbatim)

```xml
<resources>
    <string name="app_name">Textify</string>
    <string name="notif_title">Textify 正在執行</string>
    <string name="notif_text">輕觸浮動泡泡即可擷取畫面上的文字；把泡泡拖到底部的 ✕ 可停止。</string>
    <string name="notif_stop">停止</string>
    <string name="session_close">關閉擷取</string>
    <string name="session_settings">設定</string>
    <string name="session_no_text">沒有偵測到文字</string>
    <string name="session_no_text_sub">點 ✕ 關閉</string>
    <string name="toast_started">Textify 已啟動 · 點一下泡泡即可擷取文字</string>
    <string name="toast_consent_denied">未允許擷取畫面，Textify 已關閉</string>
    <string name="toast_later">開啟權限後才能使用泡泡</string>
    <string name="toast_bubble_hidden">未授權懸浮權限，泡泡暫時無法顯示</string>
    <string name="onboard_title">一點泡泡，\n畫面上的字都能複製</string>
    <string name="onboard_lead">泡泡要能浮在其他 App 上層，需要你開啟「顯示在其他應用程式上層」權限。</string>
    <string name="onboard_denied_lead">系統設定裡 Textify 的開關仍是關閉的。在清單中點 Textify，打開開關後按返回即可。</string>
    <string name="onboard_granted_lead">最後一步：系統會詢問是否允許擷取畫面。Android 每次啟動泡泡都會問一次。</string>
    <string name="onboard_cta_retry">再開一次設定</string>
    <string name="onboard_later">稍後</string>
    <string name="onboard_fact_1">只在點泡泡時讀取畫面</string>
    <string name="onboard_fact_2">文字辨識在裝置上完成</string>
    <string name="onboard_fact_3">拖到底部 ✕ 即關閉</string>
    <string name="onboard_ready">準備螢幕擷取</string>
    <string name="step_overlay">顯示泡泡</string>
    <string name="step_capture">擷取畫面</string>
    <string name="demo_header">設計小組</string>
    <string name="demo_line_1">下週的 workshop 資料好了嗎？</string>
    <string name="demo_line_2">地點：松仁路 100 號 12F</string>
    <string name="demo_line_3">好，晚點傳 slides 給大家</string>
    <string name="demo_line_4">Wi-Fi：Textify-2026</string>
    <string name="demo_line_5">報名截止 10/18 (Sat)</string>
    <string name="demo_line_6">收到，謝謝！</string>
    <string name="demo_chip">沒有權限，泡泡無法出現</string>
    <string name="perm_section">懸浮泡泡權限</string>
    <string name="perm_granted_title">泡泡已就位</string>
    <string name="perm_granted_text">已允許顯示在其他應用程式上層。每次啟動泡泡時，系統仍會詢問是否允許擷取畫面。</string>
    <string name="perm_denied_title">泡泡還出不來</string>
    <string name="perm_denied_text">需要開啟「顯示在其他應用程式上層」，泡泡才能浮在其他 App 上。</string>
    <string name="perm_cta">開啟顯示權限</string>
    <string name="perm_switch">允許顯示在其他應用程式上層</string>
    <string name="settings_title">設定</string>
    <string name="settings_back">返回</string>
    <string name="settings_foot">新設定會在下次擷取時生效</string>
    <string name="lang_section">辨識語言</string>
    <string name="lang_section_hint">可多選，至少一種</string>
    <string name="lang_zh">中文</string>
    <string name="lang_zh_sub">繁體 · 简体</string>
    <string name="lang_ja">日本語</string>
    <string name="lang_ja_sub">日文</string>
    <string name="lang_ko">한국어</string>
    <string name="lang_ko_sub">韓文</string>
    <string name="lang_latin_note">英文 ABC 在任何一種語言下都能辨識</string>
    <string name="lang_block">至少保留一種語言，才能辨識畫面上的文字</string>
    <string name="preview_header">旅行群組</string>
    <string name="preview_zh">週五下午三點在車站集合</string>
    <string name="preview_en">Flight JL 802 · Gate 34B</string>
    <string name="preview_ja">東京駅 八重洲口で待ち合わせ</string>
    <string name="preview_ko">감사합니다, 내일 봐요!</string>
    <string name="tag_zh">中</string>
    <string name="tag_en">ABC</string>
    <string name="tag_ja">日</string>
    <string name="tag_ko">韓</string>
    <string name="speed_label">辨識時間</string>
    <string name="speed_fast">最快</string>
    <string name="speed_slower">稍慢</string>
    <string name="speed_desc_one">只跑 1 個辨識模型，結果最快出現。</string>
    <string name="speed_desc_many">%1$d 個模型同時辨識同一張截圖；語言越多，辨識稍慢。</string>
</resources>
```
66 names = §10.1 table (rows with `/` expanded). Deleted (§10.1 last paragraph): `start stop script_label script_chinese script_japanese script_korean grant_overlay bubble_started consent_denied hint`.

#### Step E.2 — `res/values/themes.xml` (replace whole file, §10.2)

```xml
<resources>
    <!-- Light only (spec §0.3). Selection handles / controls tinted a2 violet. -->
    <style name="Theme.Textify" parent="Theme.AppCompat.Light.NoActionBar">
        <item name="colorControlActivated">#7C3AED</item>
        <item name="android:colorControlActivated">#7C3AED</item>
    </style>

    <!-- MainActivity, SessionActivity, SettingsActivity: translucent (fades reveal the app underneath),
         no window animation, no preview window (no flash before the first frame). -->
    <style name="Theme.Textify.Translucent">
        <item name="android:windowIsTranslucent">true</item>
        <item name="android:windowBackground">@android:color/transparent</item>
        <item name="android:windowAnimationStyle">@null</item>
        <item name="android:windowDisablePreview">true</item>
        <item name="android:windowNoTitle">true</item>
    </style>
</resources>
```
`Theme.Textify.Translucent` inherits `Theme.Textify` by the dotted name (so it keeps `colorControlActivated`).

#### Step E.3 — `AndroidManifest.xml` (§10.3)
1. `<application>`: add `android:roundIcon="@mipmap/ic_launcher"` after `android:icon`.
2. `MainActivity`: add `android:excludeFromRecents="true"` and `android:theme="@style/Theme.Textify.Translucent"`; keep the launcher intent filter.
3. `SessionActivity`: `android:theme="@style/Theme.Textify.Translucent"`; nothing else changes.
4. New entry after `SessionActivity`:
   ```xml
   <!-- Settings (spec §7.1): own task like the session, so finishing returns to the app the user was in;
        configChanges not set: it re-creates on rotation (state lives in prefs / the permission). -->
   <activity
       android:name=".SettingsActivity"
       android:excludeFromRecents="true"
       android:exported="false"
       android:taskAffinity=""
       android:theme="@style/Theme.Textify.Translucent" />
   ```
Permissions unchanged.

#### Step E.4 — `README.md` (§10.4)
Replace the "How it works" and "OCR languages" sections with (keep every other section verbatim):

```markdown
## How it works
1. Launching Textify opens `MainActivity`, which only routes: if the bubble is already
   running it opens `SettingsActivity`; if the "display over other apps" permission is
   missing it shows a one-screen onboarding with a looping demo and a shortcut to that
   system setting; otherwise it asks for screen-capture consent. On consent
   `CaptureService` starts and the app closes; without consent it closes with a toast.
2. `CaptureService` (foreground, type `mediaProjection`) keeps one VirtualDisplay +
   ImageReader for its whole lifetime and shows a draggable bubble. Tapping the
   bubble fades it out, takes the latest frame and opens `SessionActivity`.
   Dragging the bubble onto the bottom target ends the service.
3. `SessionActivity` freezes the screen: the screenshot is shown 1:1 under hidden
   system bars with an edge glow while `Ocr` runs, then every recognised line
   becomes a pill. Tap a pill to copy the line (the pill shines and flashes);
   long-press for partial selection with the system copy menu. X or back closes
   the session and the bubble returns. The settings button next to X closes the
   session and opens `SettingsActivity`.
4. `SettingsActivity` shows the overlay-permission state (with a shortcut to the system
   setting) and the OCR language selection. The bubble stays hidden while it is open
   and returns when you leave it. Changes apply from the next capture.

## OCR languages
Multi-select, at least one: Chinese (Traditional and Simplified), Japanese, Korean —
chosen in Settings. Every model also reads Latin/English. All selected models run in
parallel on the same screenshot; where two lines overlap (IoU > 0.5) the higher-confidence
one is kept (`OcrMerge.kt`). All run fully offline. See `Ocr.kt`.
`// ponytail:` ML Kit picks its own compute backend (CPU/GPU via NNAPI); it
exposes no NPU/int4 control. Upgrade path if speed matters: swap in a
LiteRT int4 model with a hardware delegate.
```

#### R2-E acceptance
- [ ] `grep -rn "Prefs.script(\|setScript(\|CaptureService.stop\|scriptGroup\|startButton\|stopButton\|ic_launcher_bg\|ic_launcher_fg\|ic_launcher_round\|ic_menu_crop\|Theme.Textify.Session\|DayNight\|3F6FFF" app/src README.md` prints nothing (§13.3; needs all R2 units)
- [ ] `grep -c '<string name=' res/values/strings.xml` prints `66`; for each deleted name `n` in the list above, `grep -c "name=\"$n\"" res/values/strings.xml` prints `0`
- [ ] `grep -c 'Theme.Textify.Translucent' AndroidManifest.xml` prints `3`; `grep -n 'android:name=".SettingsActivity"\|android:roundIcon="@mipmap/ic_launcher"' AndroidManifest.xml` shows both
- [ ] `grep -rn "DayNight\|Theme.Textify.Session" app/src` prints nothing
- [ ] `grep -n "Start\|Stop" README.md` prints nothing inside the "How it works" / "OCR languages" sections
- [ ] round-2 gate green

---

### R2 reviewer (gate + whole-feature acceptance)

Role: one reviewer (fresh context) plus the gate-runner (haiku, runs commands only, reports raw output). The reviewer does not fix code.

#### Round-2 convergence gate
1. Start condition: all five R2 units report `READY_FOR_SIGNOFF`; every writer has broadcast `SETTLED`. From then until the gate-runner confirms completion, zero writes to the tree (gitignored paths included).
2. Gate-runner runs, from the repo root, and records start/end time and `RC=$?` captured by the command itself into `docs/logs/settings-redesign-r2-gate-<n>.log` (`n` = gate attempt 1, 2, 3):
   ```bash
   JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug testDebugUnitTest > docs/logs/settings-redesign-r2-gate-<n>.log 2>&1; RC=$?; echo "RC=$RC" >> docs/logs/settings-redesign-r2-gate-<n>.log
   ```
   If it would exceed the foreground limit, the runner splits it into `assembleDebug` and `testDebugUnitTest` (two logs), never backgrounds it.
3. Routing: every compile/test error is routed to the owner of the file named in the error (`e: file:///…/<File>.kt:<line>` or AAPT `…/res/<path>`), using this table. The owner fixes only its own files; a fix that needs another unit's file is sent to that unit's owner via the lead.

   | file | owner |
   |---|---|
   | `CaptureService.kt`, `Ocr.kt`, `Prefs.kt` | R2-A |
   | `MainActivity.kt` (incl. `WrapLayout`), `activity_main.xml` | R2-B |
   | `SettingsActivity.kt`, `activity_settings.xml` | R2-C |
   | `SessionActivity.kt`, `activity_session.xml` | R2-D |
   | `strings.xml`, `themes.xml`, `AndroidManifest.xml`, `README.md` | R2-E |
   | any round-1 file (`Glow.kt`, `EdgeGlowView.kt`, `PillView.kt`, `CaptureDemoView.kt`, `StepTrackView.kt`, `Language*View.kt`, `SpeedLanesView.kt`, `OcrMerge.kt`, `OcrMergeTest.kt`, `app/build.gradle.kts`, R1 drawables/layouts) | not fixable in round 2: lead decides (interface mismatch = BLOCKED, report to the user) |

   Error caused by a frozen-interface mismatch (caller and callee disagree): the side that deviates from the "Frozen interfaces (round 2)" table fixes it; the table is never edited during the round.
4. **At most 2 fix cycles.** Cycle = routed fixes → all owners `SETTLED` → gate re-run. If the third gate run (after fix cycle 2) is still red, stop: the lead reports the failure trajectory (each gate log path, the errors per cycle, who fixed what) to the user; no further cycle without user approval.
5. Fixes reuse the original owner when their baton allows; otherwise a fresh member receives a baton file with the previous failure output.

#### Whole-feature acceptance (after the gate is green; spec §13)
Reviewer runs (read-only) and records the output:
1. §13.1 = gate log `RC=0`.
2. §13.2: gate log contains `OcrMergeTest` with 5 tests passed (`app/build/test-results/testDebugUnitTest/TEST-us.jhangy.textify.OcrMergeTest.xml`: `tests="5" failures="0" errors="0" skipped="0"`).
3. §13.3: `grep -rn "Prefs.script(\|setScript(\|CaptureService.stop\|scriptGroup\|startButton\|stopButton\|ic_launcher_bg\|ic_launcher_fg\|ic_launcher_round\|ic_menu_crop\|Theme.Textify.Session\|DayNight\|3F6FFF" app/src README.md` → no output.
4. §13.4:
   ```bash
   cd app/src/main
   for n in start stop script_label script_chinese script_japanese script_korean grant_overlay bubble_started consent_denied hint; do
     grep -rnw "R.string.$n\|@string/$n\|name=\"$n\"" . ; done            # expect no output
   for n in $(grep -o 'name="[a-z_0-9]*"' res/values/strings.xml | cut -d'"' -f2); do
     c=$(grep -rnw "R.string.$n\|@string/$n" . | grep -v 'res/values/strings.xml' | wc -l)
     [ "$c" -ge 1 ] || echo "UNUSED $n"; done                                # expect no output
   ```
5. §13.5: `grep -rn "SDK_INT" app/src/main/java` → exactly two lines, both in `MainActivity.kt` (`TIRAMISU`, `UPSIDE_DOWN_CAKE`).
6. §13.6: `grep -F` of each §9 path string in its drawable (R1-A files; re-run to catch round-2 regressions).
7. Interface check: each row of "Frozen interfaces (round 2)" greps exactly in its owner file (copy the signature as the `grep -F` pattern).
8. Ownership: `git diff --name-only <round-2 start commit>..HEAD` lists only files owned by R2 units (matrix in the outline) plus `docs/logs/`.
9. Code reading (no tools beyond Read): `SettingsActivity` lifecycle against §7.1 (`onStart` true; `onStop` false unless `openingSystemSettings`; `onDestroy` false when last call was true); `CaptureService.applySettingsVisible` against §5 (cancel pending show, busy guard, `canDrawOverlays` branch, 120 ms delay, toast); `SessionActivity.openSettings` order against §4.

Reviewer report: per item pass/fail with the command output line; findings outside the acceptance go to the parking lot, not into new acceptance items.

#### Hand-off to the user (§13.7)
The lead hands the §13.7 on-device checklist to the user verbatim, plus the three device-only **VERIFY** items: §6.2 confidence comparability and three-model memory/time, §12 consent-dialog wording / API 34 picker, §12 overlay revoked while the service runs.

#### Parking lot seeded by this plan (not round-2 work)
- §7.2 switch "off" visuals and thumb travel are not built (the row is only shown while granted).
- Copy-in curve for the onboarding copy swap is not given by [P2]; CSS ease-out is used.

## Self-review

1. Spec coverage: §2 → R2-B; §3 → R1-C (demo, track) + R2-B; §4 → R1-A (icon) + R2-D; §5 → R2-A; §6 → R1-E + R2-A; §7 → R1-B/C/D + R2-C; §8 → R1-B/C/D; §9 → R1-A; §10 → R2-E; §11 → R2-B (two branches) + R2-A (removed checks); §13 → R1/R2 reviewers.
2. Shell: every commit snippet uses `set -- <paths>` + `"$@"`, which splits identically in zsh (the agents' shell) and bash.
3. Placeholder scan: `grep -n "TBD\|TODO\|implement later\|fill in" docs/superpowers/plans/2026-10-11-settings-redesign-plan.md` → only line 38 (the "never `TODO()`" rule) and this line.
4. Type consistency: round-2 code uses only the members listed in "Frozen interfaces (round 1)" (checked by name: `withAlpha`, `dpToPx`, `CssGradientDrawable(.solid)`, `GlowDrawable`, `HaloStyle.*`, `DemoSpec.ONBOARDING/SETTINGS`, `CaptureDemoView.configure/setGranted/pause/resume/flash`, `StepTrackView.State/setLabels/setState`, `LanguagePreviewView.setScripts`, `LanguageToggleView.checked/setChecked/reject`, `SpeedLanesView.setLabels/setScripts`, `OcrCandidate`, `mergeOcr`).
