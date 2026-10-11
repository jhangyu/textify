# Capture Session — Area S (Service Side) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use podium:team-spawn (recommended) or podium:team-fable to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Work-unit split, owners and round order: `docs/superpowers/plans/2026-10-11-capture-session-service-execution.md`.

**Goal:** Make the service side of the capture session work: one long-lived VirtualDisplay + ImageReader (fixes repeat capture on Android 14+), bubble tap → fade out → grab frame → `SessionActivity.start`, bubble fade back in on `CaptureService.onSessionEnded()`, Messenger-style drag-to-dismiss target that stops the service, and `Ocr` returning lines with bounding boxes.

**Architecture:** `CaptureService` becomes a thin owner/wiring layer. Frame capture moves to a new `ScreenGrabber` (one `createVirtualDisplay` call per `MediaProjection`, latest frame always held, resize instead of re-create on rotation). Bubble window, touch handling, fades, magnet, snap and the dismiss-target window move to a new `Bubble` class. `Ocr` maps ML Kit `Text.TextBlock.lines` to `OcrLine(text, box)`.

**Tech Stack:** Kotlin, Android Views + `WindowManager` overlays (`TYPE_APPLICATION_OVERLAY`), `MediaProjection` / `VirtualDisplay` / `ImageReader`, ML Kit text-recognition 16.0.1 (chinese/japanese/korean bundled), compileSdk 36, minSdk 26.

**Decisions (hard-to-reverse only):**
- Capture pipeline lifetime — alternatives: (1) new VirtualDisplay per tap (current code), (2) one VirtualDisplay for the service lifetime, (3) re-ask consent per tap. Driver: Android 14+ throws `SecurityException` on the 2nd `createVirtualDisplay` (evidence E1). Choice: (2). Drawback: a frame listener runs on every screen change for the service lifetime (cheap: acquire + close, no pixel copy); one `Image` buffer stays held.
- Clean frame (bubble not in shot) — alternatives: (1) fixed delay after hide, (2) first frame produced after the fade-out ends, with a 300 ms fallback to the newest held frame. Choice: (2). Driver: deterministic, no guessed delay. Drawback: a frame composed just before the fade ended can, in theory, arrive late; the bubble is then at alpha ≈ 0 (eased curve), so it is invisible.
- Split `CaptureService` into `ScreenGrabber.kt` + `Bubble.kt` — driver: two members per round need disjoint files; each file has one responsibility.

Plan gate: `/Users/jhangyu/.claude/plugins/cache/jhangyu/podium/1.1.0/protocols/review.md` §L0 Plan gate.

## Doc evidence (verified 2026-10-11; do not re-derive from memory)

- **E1 — Android 14+ single `createVirtualDisplay` per `MediaProjection` (item a).** https://developer.android.com/about/versions/14/behavior-changes-14 §"User consent required for each MediaProjection capture session": *"For apps targeting Android 14 (API level 34) or higher, a `SecurityException` is thrown by `MediaProjection#createVirtualDisplay` in either of the following scenarios: … Your app invokes `MediaProjection#createVirtualDisplay` multiple times on the same `MediaProjection` instance."* Same page, §"Handle configuration changes": *"1. Invoke `VirtualDisplay#resize` with the new width and height. 2. Provide a new `Surface` with the new width and height to `VirtualDisplay#setSurface`."* §"Register a callback": *"If your app doesn't register this callback, `MediaProjection#createVirtualDisplay` throws an `IllegalStateException`."* Also https://developer.android.com/media/grow/media-projection: *"A session is a single call to `createVirtualDisplay()`. A `MediaProjection` token must be used only once to make the call."* App targetSdk = 36 (`app/build.gradle.kts`), so the rule applies.
- **E2 — ML Kit `Text.TextBlock.lines` / `Line.boundingBox` (item b).** Guide https://developers.google.com/ml-kit/vision/text-recognition/v2/android lists `com.google.mlkit:text-recognition-chinese:16.0.1` (same as `app/build.gradle.kts`) and the Kotlin sample `for (block in result.textBlocks) { … for (line in block.lines) { val lineText = line.text … val lineFrame = line.boundingBox`. Reference https://developers.google.com/android/reference/com/google/mlkit/vision/text/Text.TextBlock: `public synchronized List<Text.Line> getLines ()`; `Text.Line`: `public Rect getBoundingBox ()` — returns `null` when nothing found. Installed-jar check (`javap` on `play-services-mlkit-text-recognition-common-19.1.0` classes.jar, the transitive dep of text-recognition-chinese 16.0.1 per its POM): `Text$TextBlock.getLines(): java.util.List<Text$Line>`, `Text$TextBase.getBoundingBox(): android.graphics.Rect`, `Text.getTextBlocks()`. Coordinate space: not stated in the docs; with `InputImage.fromBitmap(bitmap, 0)` the boxes are taken as bitmap pixels (verified on device by checklist item "pills appear over each line").
- **E3 — Platform API levels** (from `platforms/android-36/data/api-versions.xml` of the local SDK `/opt/homebrew/share/android-commandlinetools`): `ImageReader.newInstance(IIII)` 19, `acquireLatestImage()` 19, `setOnImageAvailableListener` 19; `VirtualDisplay.resize(III)` 21, `setSurface` 20, `release` 19; `MediaProjection.createVirtualDisplay` 21, `registerCallback` 21; `PathInterpolator(FFFF)` 21; `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY` 26; `Display.getRealMetrics` 17 (deprecated 31, still present in 36 → one code path for API 26–36). All ≤ minSdk 26: no per-API branch needed.
- **E4 — ML Kit listener thread.** https://developers.google.com/android/reference/com/google/android/gms/tasks/Task `addOnSuccessListener(OnSuccessListener)`: *"The listener will be called on the main application thread."*
- **E5 — Untrusted touches (Android 12+).** https://developer.android.com/about/versions/12/behavior-changes-all#untrusted-touch-events: pass-through touches are allowed for *"Invisible windows. The window's root view is GONE or INVISIBLE"* and *"Interactions within your app"*; *"Windows of type TYPE_APPLICATION_OVERLAY aren't trusted."* → the dismiss-target window keeps its root `INVISIBLE` when not dragging; the hidden bubble window sets its root `INVISIBLE` and `FLAG_NOT_TOUCHABLE`.
- **Not retrieved:** `ImageReader.acquireLatestImage` behaviour text (developer.android.com page truncated in fetch). The design does not depend on it: the held image is closed *before* each acquire, so at most 1 of `maxImages = 2` is acquired at any time.

## Global Constraints

- Package `us.jhangy.textify`; sources in `app/src/main/java/us/jhangy/textify/`; resources in `app/src/main/res/`.
- compileSdk 36, minSdk 26, targetSdk 36. No new dependencies. Do not edit `app/build.gradle.kts`.
- One code path for API 26–36: no `Build.VERSION.SDK_INT` branches in new code (project rule; spec §Platform-consistency).
- Visual/motion values come from `docs/design/mockups/a2-halo.html` (CSS px = dp). Timings (ms): bubbleOut 180, bubbleIn 300, dzIn 220, magnet 160, snap 360, dismiss 280; held-press 120 / scale .92; magnet radius 104 dp; dismiss target centre 100 dp above bottom; drag start threshold 6 dp; snap edge 10 dp, y clamp [60 dp, screenH − 200 dp]; home position x = screenW − 56 − 10 dp, y = 330 dp. Easing: `.bub` and magnet `cubic-bezier(.2,0,0,1)`, snap `cubic-bezier(.34,1.56,.64,1)`, `.dz` opacity `ease-out`.
- Colours: bubble `radial-gradient(circle at 50% 38%, #fff 55%, #F3EDFF)`, icon `#7C3AED`; dismiss scrim `#4C1D95` 30 % → 0 (bottom → top, 260 dp tall); target 64 dp circle `#FFFFFF` 92 %, X icon `#1E1B4B`; hot glow pink `#F472B6` 55 % + lavender `#A78BFA` 45 %.
- Frozen cross-area interfaces (contract, exact):
  - `data class OcrLine(val text: String, val box: android.graphics.Rect)` and `object Ocr { fun recognize(ctx: Context, bitmap: Bitmap, script: Script, onResult: (List<OcrLine>) -> Unit) }` — failure ⇒ `emptyList()`. (Area S owns.)
  - `SessionActivity.start(ctx: Context, shot: Bitmap)` and `SessionActivity.finishActive()` (Area U owns; Area S only calls them).
  - `CaptureService.onSessionEnded()` — companion, no-op if service not running (Area S owns). The spec's name `showBubble()` is superseded by the contract name `onSessionEnded()`.
- Strings: Area S needs **no new strings**. Area S keeps using existing `app_name`, `notif_title`, `stop` — Area U must not delete them. After this plan Area S no longer references `no_text` (Area U decides whether it stays).
- Area S must not touch Area U files: `SessionActivity.kt`, `EdgeGlowView.kt`, pill view, toast/hint/X layouts, `themes.xml`, `strings.xml`, `AndroidManifest.xml`, `ResultActivity.kt`, `activity_result.xml`, `README.md`. Also not `MainActivity.kt`, `Prefs.kt`, `app/build.gradle.kts`.
- No unit-test harness exists (no test dependencies; adding one needs `app/build.gradle.kts`, out of scope). Mechanical checks are grep markers per task + the whole-feature build gate `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug` (exit 0) + the on-device checklist (spec §Testing).
- Build gate is run only by the lead's gate-runner after both areas finish round 2 (cross-area compile dependency: `CaptureService` calls `SessionActivity`, which Area U creates). Implementation members do not run gradle.
- Shared-tree git rules: no `git stash` (incl. `stash list`), `reset`, `checkout --`, `clean`, `git add -A`. Commit only when the dispatch prompt authorizes it, with explicit `git add <own files>` and `git commit -m … -- <own files>`.

## File map

| File | Status | Responsibility | Task |
|---|---|---|---|
| `app/src/main/java/us/jhangy/textify/Ocr.kt` | modify | ML Kit → `List<OcrLine>` | 1 |
| `app/src/main/java/us/jhangy/textify/ScreenGrabber.kt` | create | single VirtualDisplay + ImageReader, latest frame, resize, `Image → Bitmap`; `Context.realMetrics()` | 2 |
| `app/src/main/res/drawable/bubble_bg.xml` | modify | a2 bubble radial gradient | 3 |
| `app/src/main/res/drawable/ic_scan.xml` | create | a2 scan icon (vector) | 3 |
| `app/src/main/res/layout/bubble.xml` | modify | 12 dp shadow margin + 56 dp disc `@id/bubbleIcon` | 3 |
| `app/src/main/res/layout/dismiss_target.xml` | create | scrim + glow + target circle | 3 |
| `app/src/main/res/drawable/dismiss_scrim.xml` | create | `#4C1D95` 30 % → 0 gradient | 3 |
| `app/src/main/res/drawable/dismiss_target_bg.xml` | create | white 92 % circle | 3 |
| `app/src/main/res/drawable/dismiss_glow.xml` | create | hot glow radial gradient | 3 |
| `app/src/main/res/drawable/ic_dismiss_x.xml` | create | X icon `#1E1B4B` | 3 |
| `app/src/main/java/us/jhangy/textify/Bubble.kt` | create | bubble + dismiss windows, touch, fades, magnet, snap, dismiss | 4 |
| `app/src/main/java/us/jhangy/textify/CaptureService.kt` | modify | projection/FGS owner; wires grabber, bubble, session | 5 |

---

### Task 1: Ocr returns lines with boxes

**Files:**
- Modify: `app/src/main/java/us/jhangy/textify/Ocr.kt` (whole file, 25 lines)

**Interfaces:**
- Consumes: `enum class Script { CHINESE, JAPANESE, KOREAN }` (`Prefs.kt`, unchanged); ML Kit `Text.textBlocks`, `TextBlock.lines`, `Line.text`, `Line.boundingBox` (E2).
- Produces (frozen, contract):
  - `data class OcrLine(val text: String, val box: android.graphics.Rect)`
  - `object Ocr { fun recognize(ctx: Context, bitmap: Bitmap, script: Script, onResult: (List<OcrLine>) -> Unit) }`

**Behavior:**
- One `OcrLine` per ML Kit line across all blocks; `box` = `line.boundingBox` in bitmap pixels (rotation 0).
- Lines with `null` box or blank text are dropped.
- Order: by `box.top`, then `box.left` (the mockup's reveal order; Area U staggers in list order).
- `onResult` is called exactly once, on the main thread (E4). Recognizer failure ⇒ `emptyList()`. A synchronous throw from `process` (e.g. recycled bitmap) ⇒ `emptyList()` too.
- Recognizer is closed after completion.

**Constraints:**
- Exact signature above; `ctx` stays in the signature even though unused (contract).
- No coroutines, no new deps.

**Acceptance criteria:**
- [ ] `grep -c "data class OcrLine(val text: String, val box: Rect)" app/src/main/java/us/jhangy/textify/Ocr.kt` → `1` (with `import android.graphics.Rect`).
- [ ] `grep -c "fun recognize(ctx: Context, bitmap: Bitmap, script: Script, onResult: (List<OcrLine>) -> Unit)" app/src/main/java/us/jhangy/textify/Ocr.kt` → `1`.
- [ ] `grep -c "emptyList()" app/src/main/java/us/jhangy/textify/Ocr.kt` → `2` (failure listener + synchronous catch).
- [ ] `grep -c "boundingBox" app/src/main/java/us/jhangy/textify/Ocr.kt` → `≥1`.
- [ ] Build gate (Task 6) passes.

### Task 2: ScreenGrabber — one VirtualDisplay per projection

**Files:**
- Create: `app/src/main/java/us/jhangy/textify/ScreenGrabber.kt`

**Interfaces:**
- Consumes: `android.media.projection.MediaProjection` (already `registerCallback`-ed by the caller — E1 requires it before `createVirtualDisplay`).
- Produces:
  - `internal fun Context.realMetrics(): android.util.DisplayMetrics` — full display size incl. system bars (`Display.getRealMetrics`, E3). Used by `Bubble` (Task 4).
  - `class ScreenGrabber(private val ctx: Context, projection: MediaProjection, private val handler: Handler)`
    - constructor calls `projection.createVirtualDisplay(...)` **exactly once**; throws if it fails (caller catches).
    - `fun grab(onFrame: (Bitmap?) -> Unit)` — calls `onFrame` exactly once on `handler`'s thread with the first frame produced after the call, or after `GRAB_TIMEOUT_MS = 300` the newest held frame, or `null` if no frame was ever received. Caller owns the returned `Bitmap` (Area U recycles it).
    - `fun release()` — drops a pending callback without calling it, closes held image, releases display, closes reader. Call once.

**Behavior:**
- Construction: `ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)` at real display size; `createVirtualDisplay("textify-cap", w, h, dpi, VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler)`.
- Frame listener (on `handler`): close the held image first, then `acquireLatestImage()`; keep it as `latest`. If a grab is pending and a frame was acquired, deliver.
- `grab`: if the real display size differs from the reader size (rotation), follow E1 §"Handle configuration changes": new reader, `display.resize(w, h, dpi)`, `display.setSurface(newReader.surface)`, close old reader, drop `latest`. Never call `createVirtualDisplay` again.
- `Image → Bitmap`: rewind the plane buffer (the same `latest` image may be converted twice if no new frame arrived between two grabs), copy into a bitmap of width `w + rowPadding/pixelStride`, crop to `w × h` when padded. Conversion failure ⇒ `null`.
- Stale listener after resize (`r !== reader`) is ignored.

**Constraints:**
- Exactly one call site of `createVirtualDisplay(` in `app/src/main/java` (this constructor).
- All callbacks on `handler` (CaptureService passes the main-looper handler) — no extra threads.

**Acceptance criteria:**
- [ ] `grep -rc "createVirtualDisplay(" app/src/main/java | grep -v ":0"` → exactly one line: `.../ScreenGrabber.kt:1`.
- [ ] `grep -c "fun grab(onFrame: (Bitmap?) -> Unit)" app/src/main/java/us/jhangy/textify/ScreenGrabber.kt` → `1`.
- [ ] `grep -c "internal fun Context.realMetrics(): DisplayMetrics" app/src/main/java/us/jhangy/textify/ScreenGrabber.kt` → `1`.
- [ ] `grep -cE "display\.resize\(|display\.setSurface\(" app/src/main/java/us/jhangy/textify/ScreenGrabber.kt` → `2`.
- [ ] `grep -c "rewind()" app/src/main/java/us/jhangy/textify/ScreenGrabber.kt` → `1`.
- [ ] Build gate (Task 6) passes.

### Task 3: Bubble and dismiss-target resources (a2 look)

**Files:**
- Modify: `app/src/main/res/drawable/bubble_bg.xml`, `app/src/main/res/layout/bubble.xml`
- Create: `app/src/main/res/drawable/ic_scan.xml`, `app/src/main/res/layout/dismiss_target.xml`, `app/src/main/res/drawable/dismiss_scrim.xml`, `app/src/main/res/drawable/dismiss_target_bg.xml`, `app/src/main/res/drawable/dismiss_glow.xml`, `app/src/main/res/drawable/ic_dismiss_x.xml`

**Interfaces:**
- Produces (ids consumed by Task 4):
  - `R.layout.bubble` — root `FrameLayout` (padding 12 dp, `clipToPadding=false`) with child `ImageView` `@+id/bubbleIcon` 56×56 dp, background `@drawable/bubble_bg`, src `@drawable/ic_scan`, elevation 6 dp, `contentDescription="@string/app_name"`.
  - `R.layout.dismiss_target` — root `FrameLayout` (background `@drawable/dismiss_scrim`, `importantForAccessibility="noHideDescendants"`) with `View` `@+id/dismissGlow` (184 dp, bottom|center, marginBottom 8 dp, alpha 0) and `ImageView` `@+id/dismissTarget` (64 dp, bottom|center, marginBottom 68 dp, elevation 6 dp). Both centres are 100 dp above the window bottom.

**Behavior:**
- Values are from a2 (`.bub`, `.dz`, `.dzt`, `.dzt.hot`, `ICON_SCAN`, `ICON_X`); see Global Constraints.
- Bubble pink outer glow (`0 0 22px rgba(244,114,182,.35)`) is approximated by the elevation shadow only: a coloured shadow needs `outlineSpotShadowColor` (API 28+), which would be a per-API branch.

**Constraints:**
- Names prefixed `dismiss_` / `ic_dismiss_` / `ic_scan` so they cannot collide with Area U resources.
- No string resources added.

**Acceptance criteria:**
- [ ] All 8 files exist: `ls app/src/main/res/drawable/{bubble_bg,ic_scan,dismiss_scrim,dismiss_target_bg,dismiss_glow,ic_dismiss_x}.xml app/src/main/res/layout/{bubble,dismiss_target}.xml` exit 0.
- [ ] `grep -c 'android:id="@+id/bubbleIcon"' app/src/main/res/layout/bubble.xml` → `1`; `grep -cE '@\+id/(dismissTarget|dismissGlow)' app/src/main/res/layout/dismiss_target.xml` → `2`.
- [ ] `grep -c "#4D4C1D95" app/src/main/res/drawable/dismiss_scrim.xml` → `1`; `grep -c "#F3EDFF" app/src/main/res/drawable/bubble_bg.xml` → `1`; `grep -c "#7C3AED" app/src/main/res/drawable/ic_scan.xml` → `1`.
- [ ] `xmllint --noout` on all 8 files exit 0.
- [ ] Build gate (Task 6) passes (aapt resource linking).

### Task 4: Bubble controller (fades, drag, magnet, snap, dismiss)

**Files:**
- Create: `app/src/main/java/us/jhangy/textify/Bubble.kt`

**Interfaces:**
- Consumes: `Context.realMetrics()` (Task 2); `R.layout.bubble`, `R.id.bubbleIcon`, `R.layout.dismiss_target`, `R.id.dismissTarget`, `R.id.dismissGlow` (Task 3).
- Produces:
  - `class Bubble(private val ctx: Context, private val onTap: () -> Unit, private val onDismiss: () -> Unit)`
    - `fun show()` — adds both overlay windows (dismiss zone first, root `INVISIBLE`), places bubble at home, fades it in (bubbleIn 300). Idempotent.
    - `fun fadeOut(onHidden: () -> Unit)` — bubble untouchable at once; alpha 1→0, scale →0.7 over bubbleOut 180; at end root `INVISIBLE` and `onHidden()`.
    - `fun fadeIn()` — root `VISIBLE`, touchable; alpha →1, scale →1 over bubbleIn 300.
    - `fun remove()` — cancels animations, removes both windows. Idempotent. End actions of cancelled animations do not run.
  - `onTap` is called on the main thread for a tap (no drag) or an accessibility click while not hidden.
  - `onDismiss` is called once, after the dismiss animation (280 ms), when the bubble is released inside the magnet radius.

**Behavior (a2 bubble JS, lines 391–408, and CSS lines 106–121):**
- DOWN: cancel running move animation; remember raw touch, window x/y, bubble centre on screen (`getLocationOnScreen`); press scale .92 over 120 ms.
- MOVE: drag starts once the finger moved > 6 dp → show zone (zone alpha 0→1 over dzIn 220 ease-out; target translateY 28 dp→0, scale .9→1 over 220 emphasized). While dragging: if the distance from the would-be bubble centre to the target centre < 104 dp → magnet: animate window so the bubble centre lands on the target centre (160 ms emphasized), target scale 1.2 + glow alpha 1 (160 ms); while inside, the bubble stays pinned. Leaving the radius → target back to scale 1 / glow 0, bubble follows the finger again.
- UP: press scale back to 1 (120 ms). No drag → `performClick()` → `onTap` (only if not hidden). In magnet → dismiss: bubble alpha 0 + scale .3, target alpha 0 + scale .4, glow 0 (all 280 ms), bubble untouchable, then `onDismiss()`. Else → zone fades out (220 ms, then root `INVISIBLE`) and the bubble snaps (360 ms, overshoot curve) to the nearer side edge, y clamped.
- CANCEL: same as UP but never taps.
- Target centre is computed from the untransformed layout (`zone` screen location + `target.left/top` + half size), so the target's own animation does not move the magnet point.
- Window params: bubble `WRAP_CONTENT`, `TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE`, `PixelFormat.TRANSLUCENT`, gravity `TOP|START`; zone `MATCH_PARENT × 260 dp`, gravity `BOTTOM`, `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`.
- Window x/y address the 80 dp frame (12 dp shadow margin around the 56 dp disc): home x = screenW − 56 dp − 10 dp − 12 dp, y = 330 dp − 12 dp; snap x = 10 dp − 12 dp (left) or screenW − frame − (10 dp − 12 dp) (right); y clamp [60 dp − 12 dp, screenH − 200 dp − 12 dp].

**Constraints:**
- Timings and radii only from Global Constraints; all in a `private companion object` with a comment naming a2 as the source.
- Easing via `PathInterpolator` (E3) with the exact cubic-bezier values.
- No `Build.VERSION` checks.

**Acceptance criteria:**
- [ ] `grep -c "class Bubble(private val ctx: Context, private val onTap: () -> Unit, private val onDismiss: () -> Unit)" app/src/main/java/us/jhangy/textify/Bubble.kt` → `1`.
- [ ] `grep -cE "fun (show|fadeIn|remove)\(\)|fun fadeOut\(onHidden: \(\) -> Unit\)" app/src/main/java/us/jhangy/textify/Bubble.kt` → `4`.
- [ ] Each of `180L 300L 220L 160L 360L 280L 120L 104f` appears in `Bubble.kt`: `for v in 180L 300L 220L 160L 360L 280L 120L 104f; do grep -q "$v" app/src/main/java/us/jhangy/textify/Bubble.kt || echo MISSING $v; done` prints nothing.
- [ ] `grep -c "PathInterpolator(.34f, 1.56f, .64f, 1f)" app/src/main/java/us/jhangy/textify/Bubble.kt` → `1`.
- [ ] `grep -c "Build.VERSION" app/src/main/java/us/jhangy/textify/Bubble.kt` → `0`.
- [ ] Build gate (Task 6) passes; device checklist "drag bubble to bottom target → magnet, release → bubble gone, notification gone".

### Task 5: CaptureService wiring

**Files:**
- Modify: `app/src/main/java/us/jhangy/textify/CaptureService.kt` (whole file, 213 lines → rewrite)

**Interfaces:**
- Consumes: `ScreenGrabber(ctx, projection, handler)`, `grab`, `release` (Task 2); `Bubble(ctx, onTap, onDismiss)`, `show`, `fadeOut`, `fadeIn`, `remove` (Task 4); `SessionActivity.start(ctx: Context, shot: Bitmap)`, `SessionActivity.finishActive()` (Area U, contract).
- Produces (frozen, contract): `companion object { fun onSessionEnded() }` — main thread; no-op if service not running. Existing `CaptureService.start(ctx: Context, code: Int, data: Intent)` and `CaptureService.stop(ctx: Context)` stay unchanged (used by `MainActivity`).

**Behavior:**
- `onStartCommand`: `ACTION_STOP` → `stopSelf()`. Otherwise `startForeground` first (Android 14 FGS rule, unchanged). If already running (`projection != null`) → ignore the new intent. Get projection; `registerCallback` **before** creating `ScreenGrabber` (E1); construct `ScreenGrabber` inside `runCatching` — failure ⇒ `stopSelf()`. Create and `show()` the `Bubble`. Return `START_NOT_STICKY` (a projection cannot be restored after a process restart).
- Tap → if `busy` return; `busy = true`; `bubble.fadeOut { grabber.grab { shot -> … } }`. `shot == null` ⇒ `busy = false`, `bubble.fadeIn()`, no toast (spec §Error handling). Else `SessionActivity.start(this, shot)`.
- `onSessionEnded()` → `busy = false`, `bubble.fadeIn()` (bubbleIn 300 lives in `Bubble`).
- Dismiss → `stopSelf()`.
- Projection stopped by system (`Callback.onStop`) → `stopSelf()`.
- `onDestroy`: `instance = null` first (so `onSessionEnded` from the finishing activity is a no-op), `SessionActivity.finishActive()`, `bubble.remove()`, `grabber.release()`, unregister callback, `projection.stop()`. FGS notification goes away with the service.
- Removed: `captureOnce`, `runOcr`, old `showBubble`, `ResultActivity` reference, `Toast`/`no_text` usage. OCR now runs in `SessionActivity` (contract).

**Constraints:**
- Static `instance` is set in `onCreate`, cleared in `onDestroy` (no leak past service lifetime).
- No `Build.VERSION` checks added (existing `startForegroundService` branch in `start()` stays untouched).

**Acceptance criteria:**
- [ ] `grep -c "fun onSessionEnded()" app/src/main/java/us/jhangy/textify/CaptureService.kt` → `1`.
- [ ] `grep -cE "ResultActivity|captureOnce|runOcr|createVirtualDisplay|Toast" app/src/main/java/us/jhangy/textify/CaptureService.kt` → `0`.
- [ ] `grep -c "SessionActivity.start(this, shot)" …/CaptureService.kt` → `1`; `grep -c "SessionActivity.finishActive()" …/CaptureService.kt` → `1`.
- [ ] Line of `registerCallback` < line of `ScreenGrabber(`: `awk '/registerCallback/{a=NR} /ScreenGrabber\(this/{b=NR} END{exit !(a && b && a<b)}' app/src/main/java/us/jhangy/textify/CaptureService.kt` exit 0.
- [ ] Build gate (Task 6) passes; device checklist "capture twice or more in a row (Android 14+) without error", "X closes … bubble reappears".

### Task 6: Area S build gate and cross-check

**Files:**
- Fix-only ownership of Task 1–4 files (`Ocr.kt`, `ScreenGrabber.kt`, `Bubble.kt`, the 8 resource files). No new files.

**Interfaces:**
- Consumes: everything above; Area U's `SessionActivity` companion.
- Produces: nothing new.

**Behavior:**
- Static review of Task 1–4 output against this plan (signatures, values, E1/E5 rules) before the build exists; fix deviations in owned files only.
- After the lead's gate-runner runs the build gate: fix compile/link errors located in owned files; report errors located in Area U files or `CaptureService.kt` to the lead with file:line.

**Constraints:**
- Never edit `CaptureService.kt` (Task 5 owner, same round) or any Area U file.

**Acceptance criteria:**
- [ ] All Task 1–4 grep criteria re-run green (commands listed in the execution plan, unit S-R2-B).
- [ ] `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug` exit 0 (run by gate-runner).
- [ ] `grep -r ResultActivity app/src` empty (whole-feature criterion; Area S part: no hit in Area S files).

---
## Implementation steps (Stage 2, per task — expand strictly against the spec blocks above)

No unit-test harness exists (see Global Constraints), so the "failing test" step of each task is a grep check that is red before the change and green after. Run every command from the repo root `/Users/jhangyu/project/textify`.

### Task 1 steps — Ocr

- [ ] **Step 1: See the check red**

Run: `grep -c "data class OcrLine(val text: String, val box: Rect)" app/src/main/java/us/jhangy/textify/Ocr.kt`
Expected: `0`

- [ ] **Step 2: Replace `Ocr.kt` with**

```kotlin
package us.jhangy.textify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

/** One recognised text line. [box] is in screenshot (bitmap) pixels. */
data class OcrLine(val text: String, val box: Rect)

/** On-device OCR. The recognizer for the chosen script runs fully offline. */
object Ocr {
    /** Calls [onResult] once on the main thread, lines sorted top-to-bottom then left-to-right. Failure => emptyList(). */
    fun recognize(ctx: Context, bitmap: Bitmap, script: Script, onResult: (List<OcrLine>) -> Unit) {
        val recognizer: TextRecognizer = when (script) {
            Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        }
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    onResult(
                        result.textBlocks.flatMap { it.lines }
                            .mapNotNull { line -> line.boundingBox?.let { OcrLine(line.text, it) } }
                            .filter { it.text.isNotBlank() }
                            .sortedWith(compareBy({ it.box.top }, { it.box.left }))
                    )
                }
                .addOnFailureListener { onResult(emptyList()) }
                .addOnCompleteListener { recognizer.close() }
        } catch (e: Exception) {
            // e.g. recycled bitmap: InputImage.fromBitmap / process throws synchronously
            recognizer.close()
            onResult(emptyList())
        }
    }
}
```

- [ ] **Step 3: See the checks green**

Run:
```bash
F=app/src/main/java/us/jhangy/textify/Ocr.kt
grep -c "data class OcrLine(val text: String, val box: Rect)" $F   # 1
grep -c "fun recognize(ctx: Context, bitmap: Bitmap, script: Script, onResult: (List<OcrLine>) -> Unit)" $F   # 1
grep -c "emptyList()" $F   # 2
grep -c "boundingBox" $F   # 1
```
Expected: `1`, `1`, `2`, `1`.

- [ ] **Step 4: Commit (only if the dispatch prompt authorizes commits)**

```bash
git add app/src/main/java/us/jhangy/textify/Ocr.kt
git commit -m "feat(ocr): return OCR lines with bounding boxes" -- app/src/main/java/us/jhangy/textify/Ocr.kt
```

### Task 2 steps — ScreenGrabber

- [ ] **Step 1: See the check red**

Run: `test -f app/src/main/java/us/jhangy/textify/ScreenGrabber.kt; echo $?`
Expected: `1`

- [ ] **Step 2: Create `ScreenGrabber.kt`**

```kotlin
package us.jhangy.textify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.util.DisplayMetrics
import android.view.WindowManager

/** Full display size including system bars. getRealMetrics is deprecated in 31 but present through 36: one path for 26-36. */
internal fun Context.realMetrics(): DisplayMetrics = DisplayMetrics().also {
    @Suppress("DEPRECATION")
    (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(it)
}

/**
 * One long-lived VirtualDisplay + ImageReader per MediaProjection.
 * Android 14+ throws SecurityException on a second virtual-display creation for the same projection
 * (developer.android.com/about/versions/14/behavior-changes-14), so the display is created exactly
 * once here and only resized + given a new Surface on rotation, as that page prescribes.
 * The caller must have called projection.registerCallback() before constructing this.
 */
class ScreenGrabber(private val ctx: Context, projection: MediaProjection, private val handler: Handler) {

    private var reader: ImageReader
    private val display: VirtualDisplay
    private var latest: Image? = null
    private var pending: ((Bitmap?) -> Unit)? = null
    private val timeout = Runnable { deliver() }

    init {
        val m = ctx.realMetrics()
        reader = newReader(m)
        display = projection.createVirtualDisplay(
            "textify-cap", m.widthPixels, m.heightPixels, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler
        ) ?: throw IllegalStateException("createVirtualDisplay returned null")
    }

    private fun newReader(m: DisplayMetrics): ImageReader =
        ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ r -> onImage(r) }, handler)
        }

    // ponytail: wakes on every screen change for the service lifetime (acquire + close, no copy);
    // detach the listener between grabs if battery ever matters.
    private fun onImage(r: ImageReader) {
        if (r !== reader) return               // stale reader after a resize
        latest?.close()                        // close first: at most 1 of maxImages=2 acquired at any time
        latest = null
        latest = r.acquireLatestImage()
        if (pending != null && latest != null) deliver()
    }

    /**
     * Calls [onFrame] once on the handler thread with the first frame produced after this call,
     * or after GRAB_TIMEOUT_MS the newest held frame, or null if no frame was ever received.
     * The caller owns the returned Bitmap.
     */
    fun grab(onFrame: (Bitmap?) -> Unit) {
        val m = ctx.realMetrics()
        if (m.widthPixels != reader.width || m.heightPixels != reader.height) {
            val old = reader
            latest?.close()
            latest = null
            reader = newReader(m)
            display.resize(m.widthPixels, m.heightPixels, m.densityDpi)
            display.setSurface(reader.surface)
            old.close()
        }
        pending = onFrame
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, GRAB_TIMEOUT_MS)
    }

    private fun deliver() {
        val cb = pending ?: return
        pending = null
        handler.removeCallbacks(timeout)
        cb(latest?.let { runCatching { toBitmap(it) }.getOrNull() })
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val w = image.width
        val h = image.height
        val rowPadding = plane.rowStride - plane.pixelStride * w
        val buffer = plane.buffer
        buffer.rewind()                        // the same held image may be converted by two grabs
        val padded = Bitmap.createBitmap(w + rowPadding / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        if (rowPadding == 0) return padded
        return Bitmap.createBitmap(padded, 0, 0, w, h).also { padded.recycle() }
    }

    /** Drops a pending grab without calling it and frees the capture pipeline. Call once. */
    fun release() {
        handler.removeCallbacks(timeout)
        pending = null
        latest?.close()
        latest = null
        display.release()
        reader.close()
    }

    private companion object {
        /** Wait cap for a fresh frame after the bubble fade-out (not a fade). Calibration knob. */
        const val GRAB_TIMEOUT_MS = 300L
    }
}
```

- [ ] **Step 3: See the checks green**

Run:
```bash
F=app/src/main/java/us/jhangy/textify/ScreenGrabber.kt
grep -rc "createVirtualDisplay(" app/src/main/java | grep -v ":0"   # see note
grep -c "fun grab(onFrame: (Bitmap?) -> Unit)" $F   # 1
grep -c "internal fun Context.realMetrics(): DisplayMetrics" $F   # 1
grep -cE "display\.resize\(|display\.setSurface\(" $F   # 2
grep -c "rewind()" $F   # 1
```
Expected: `1`, `1`, `2`, `1` for the last four. The first command lists `ScreenGrabber.kt:1` and, until Task 5 lands in round 2, also `CaptureService.kt:1` (old code); after Task 5 only `ScreenGrabber.kt:1`.

- [ ] **Step 4: Commit (only if authorized)**

```bash
git add app/src/main/java/us/jhangy/textify/ScreenGrabber.kt
git commit -m "feat(capture): single long-lived VirtualDisplay per projection" -- app/src/main/java/us/jhangy/textify/ScreenGrabber.kt
```

### Task 3 steps — Resources

- [ ] **Step 1: See the check red**

Run: `grep -c 'bubbleIcon' app/src/main/res/layout/bubble.xml`
Expected: `0`

- [ ] **Step 2: Replace `app/src/main/res/drawable/bubble_bg.xml` with**

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- a2 .bub: radial-gradient(circle at 50% 38%, #fff 55%, #F3EDFF) on a 56dp disc -->
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <gradient
        android:type="radial"
        android:centerX="0.5"
        android:centerY="0.38"
        android:gradientRadius="45dp"
        android:startColor="#FFFFFF"
        android:centerColor="#FFFFFF"
        android:endColor="#F3EDFF" />
</shape>
```

- [ ] **Step 3: Create `app/src/main/res/drawable/ic_scan.xml`** (a2 `ICON_SCAN`, stroke #7C3AED, 26dp)

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="26dp"
    android:height="26dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:pathData="M4,8V6a2,2 0,0 1,2 -2h2M16,4h2a2,2 0,0 1,2 2v2M20,16v2a2,2 0,0 1,-2 2h-2M8,20H6a2,2 0,0 1,-2 -2v-2M8.5,9h7M12,9v7"
        android:fillColor="#00000000"
        android:strokeColor="#7C3AED"
        android:strokeWidth="2"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
</vector>
```

- [ ] **Step 4: Replace `app/src/main/res/layout/bubble.xml` with**

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- 12dp margin keeps the elevation shadow inside the WRAP_CONTENT overlay window. -->
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:clipToPadding="false"
    android:padding="12dp">

    <ImageView
        android:id="@+id/bubbleIcon"
        android:layout_width="56dp"
        android:layout_height="56dp"
        android:background="@drawable/bubble_bg"
        android:contentDescription="@string/app_name"
        android:elevation="6dp"
        android:padding="15dp"
        android:src="@drawable/ic_scan" />
</FrameLayout>
```

- [ ] **Step 5: Create `app/src/main/res/drawable/dismiss_scrim.xml`** (a2 `.dz`: #4C1D95 30% at bottom → 0 at top; 0x4D = round(0.30·255))

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <gradient
        android:angle="90"
        android:startColor="#4D4C1D95"
        android:endColor="#004C1D95" />
</shape>
```

- [ ] **Step 6: Create `app/src/main/res/drawable/dismiss_target_bg.xml`** (a2 `.dzt`: white 92%; 0xEB = round(0.92·255))

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="#EBFFFFFF" />
</shape>
```

- [ ] **Step 7: Create `app/src/main/res/drawable/dismiss_glow.xml`** (a2 `.dzt.hot`: pink #F472B6 55% (0x8C) inner, lavender #A78BFA 45% (0x73) outer, fading to 0 at 92dp = 32dp disc radius + 50px blur + 10px spread)

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <gradient
        android:type="radial"
        android:gradientRadius="92dp"
        android:startColor="#8CF472B6"
        android:centerColor="#73A78BFA"
        android:endColor="#00A78BFA" />
</shape>
```

- [ ] **Step 8: Create `app/src/main/res/drawable/ic_dismiss_x.xml`** (a2 `ICON_X`, stroke #1E1B4B, 20dp)

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="20dp"
    android:height="20dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:pathData="M6,6L18,18M18,6L6,18"
        android:fillColor="#00000000"
        android:strokeColor="#1E1B4B"
        android:strokeWidth="2.2"
        android:strokeLineCap="round" />
</vector>
```

- [ ] **Step 9: Create `app/src/main/res/layout/dismiss_target.xml`** (window is 260dp tall, bottom-anchored; target centre 68 + 32 = 100dp and glow centre 8 + 92 = 100dp above the bottom)

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/dismiss_scrim"
    android:clipChildren="false"
    android:importantForAccessibility="noHideDescendants">

    <View
        android:id="@+id/dismissGlow"
        android:layout_width="184dp"
        android:layout_height="184dp"
        android:layout_gravity="bottom|center_horizontal"
        android:layout_marginBottom="8dp"
        android:alpha="0"
        android:background="@drawable/dismiss_glow" />

    <ImageView
        android:id="@+id/dismissTarget"
        android:layout_width="64dp"
        android:layout_height="64dp"
        android:layout_gravity="bottom|center_horizontal"
        android:layout_marginBottom="68dp"
        android:background="@drawable/dismiss_target_bg"
        android:elevation="6dp"
        android:importantForAccessibility="no"
        android:scaleType="center"
        android:src="@drawable/ic_dismiss_x" />
</FrameLayout>
```

- [ ] **Step 10: See the checks green**

Run:
```bash
R=app/src/main/res
ls $R/drawable/{bubble_bg,ic_scan,dismiss_scrim,dismiss_target_bg,dismiss_glow,ic_dismiss_x}.xml $R/layout/{bubble,dismiss_target}.xml >/dev/null; echo ls=$?
grep -c 'android:id="@+id/bubbleIcon"' $R/layout/bubble.xml                    # 1
grep -cE '@\+id/(dismissTarget|dismissGlow)' $R/layout/dismiss_target.xml      # 2
grep -c "#4D4C1D95" $R/drawable/dismiss_scrim.xml                               # 1
grep -c "#F3EDFF" $R/drawable/bubble_bg.xml                                     # 1
grep -c "#7C3AED" $R/drawable/ic_scan.xml                                       # 1
xmllint --noout $R/drawable/{bubble_bg,ic_scan,dismiss_scrim,dismiss_target_bg,dismiss_glow,ic_dismiss_x}.xml $R/layout/{bubble,dismiss_target}.xml; echo xml=$?
```
Expected: `ls=0`, `1`, `2`, `1`, `1`, `1`, `xml=0`.

- [ ] **Step 11: Commit (only if authorized)**

```bash
P="app/src/main/res/drawable/bubble_bg.xml app/src/main/res/drawable/ic_scan.xml app/src/main/res/drawable/dismiss_scrim.xml app/src/main/res/drawable/dismiss_target_bg.xml app/src/main/res/drawable/dismiss_glow.xml app/src/main/res/drawable/ic_dismiss_x.xml app/src/main/res/layout/bubble.xml app/src/main/res/layout/dismiss_target.xml"
git add $P
git commit -m "feat(bubble): a2 bubble and dismiss-target resources" -- $P
```

### Task 4 steps — Bubble controller

- [ ] **Step 1: See the check red**

Run: `test -f app/src/main/java/us/jhangy/textify/Bubble.kt; echo $?`
Expected: `1`

- [ ] **Step 2: Create `Bubble.kt`**

```kotlin
package us.jhangy.textify

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
import android.view.WindowManager.LayoutParams.MATCH_PARENT
import android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
import android.view.WindowManager.LayoutParams.WRAP_CONTENT
import android.view.animation.PathInterpolator
import kotlin.math.hypot

/**
 * Floating bubble + Messenger-style bottom dismiss target, two TYPE_APPLICATION_OVERLAY windows.
 * All motion values come from docs/design/mockups/a2-halo.html (header comment, bubble CSS and JS).
 * Hidden windows keep their root INVISIBLE so pass-through touches are not blocked on Android 12+.
 */
@SuppressLint("InflateParams", "ClickableViewAccessibility")
class Bubble(private val ctx: Context, private val onTap: () -> Unit, private val onDismiss: () -> Unit) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dp = ctx.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    private val bubble: View = LayoutInflater.from(ctx).inflate(R.layout.bubble, null)
    private val icon: View = bubble.findViewById(R.id.bubbleIcon)
    private val zone: View = LayoutInflater.from(ctx).inflate(R.layout.dismiss_target, null)
    private val target: View = zone.findViewById(R.id.dismissTarget)
    private val glow: View = zone.findViewById(R.id.dismissGlow)

    private val bubbleLp = overlayParams(WRAP_CONTENT, WRAP_CONTENT, FLAG_NOT_FOCUSABLE)
        .apply { gravity = Gravity.TOP or Gravity.START }
    private val zoneLp = overlayParams(MATCH_PARENT, px(ZONE_DP), FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCHABLE)
        .apply { gravity = Gravity.BOTTOM }

    private var added = false
    private var hidden = false
    private var moveAnim: ValueAnimator? = null

    // drag state
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var startCx = 0f
    private var startCy = 0f
    private var moved = false
    private var magnet = false

    init {
        icon.setOnClickListener { if (!hidden) onTap() }   // tap and accessibility click share one path
        icon.setOnTouchListener { _, e -> onTouch(e); true }
    }

    private fun overlayParams(w: Int, h: Int, flags: Int) =
        WindowManager.LayoutParams(w, h, TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT)

    /** Adds both windows and fades the bubble in at its home position. Idempotent. */
    fun show() {
        if (added) return
        val m = ctx.realMetrics()
        bubbleLp.x = m.widthPixels - px(BUBBLE_DP + EDGE_DP + PAD_DP)
        bubbleLp.y = px(HOME_Y_DP - PAD_DP)
        zone.visibility = View.INVISIBLE
        wm.addView(zone, zoneLp)
        wm.addView(bubble, bubbleLp)
        added = true
        icon.alpha = 0f
        icon.scaleX = HIDDEN_SCALE
        icon.scaleY = HIDDEN_SCALE
        fadeIn()
    }

    /** bubbleOut 180: untouchable at once, then alpha 0 / scale .7, then [onHidden]. */
    fun fadeOut(onHidden: () -> Unit) {
        hidden = true
        setTouchable(false)
        icon.animate().alpha(0f).scaleX(HIDDEN_SCALE).scaleY(HIDDEN_SCALE)
            .setDuration(BUBBLE_OUT).setInterpolator(EMPHASIZED)
            .withEndAction {
                bubble.visibility = View.INVISIBLE
                onHidden()
            }.start()
    }

    /** bubbleIn 300: visible and touchable, alpha 1 / scale 1. */
    fun fadeIn() {
        hidden = false
        bubble.visibility = View.VISIBLE
        setTouchable(true)
        icon.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(BUBBLE_IN).setInterpolator(EMPHASIZED).start()
    }

    /** Removes both windows immediately. Cancelled animations do not run their end actions. Idempotent. */
    fun remove() {
        if (!added) return
        added = false
        moveAnim?.cancel()
        icon.animate().cancel()
        zone.animate().cancel()
        target.animate().cancel()
        glow.animate().cancel()
        runCatching { wm.removeView(bubble) }
        runCatching { wm.removeView(zone) }
    }

    private fun setTouchable(on: Boolean) {
        bubbleLp.flags = if (on) bubbleLp.flags and FLAG_NOT_TOUCHABLE.inv() else bubbleLp.flags or FLAG_NOT_TOUCHABLE
        if (added) wm.updateViewLayout(bubble, bubbleLp)
    }

    private fun onTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                moveAnim?.cancel()
                downX = e.rawX
                downY = e.rawY
                startX = bubbleLp.x
                startY = bubbleLp.y
                val loc = IntArray(2).also(icon::getLocationOnScreen)
                startCx = loc[0] + icon.width / 2f
                startCy = loc[1] + icon.height / 2f
                moved = false
                magnet = false
                icon.animate().scaleX(HELD_SCALE).scaleY(HELD_SCALE)
                    .setDuration(HELD).setInterpolator(EMPHASIZED).start()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!moved && hypot(dx, dy) > DRAG_SLOP_DP * dp) {
                    moved = true
                    showZone()
                }
                if (!moved) return
                val (tx, ty) = targetCentre()
                val near = hypot(startCx + dx - tx, startCy + dy - ty) < MAGNET_DP * dp
                if (near) {
                    if (!magnet) {
                        magnet = true
                        setHot(true)
                        moveTo(startX + (tx - startCx).toInt(), startY + (ty - startCy).toInt(), MAGNET, EMPHASIZED)
                    }
                } else {
                    if (magnet) {
                        magnet = false
                        setHot(false)
                    }
                    moveAnim?.cancel()
                    place(startX + dx.toInt(), startY + dy.toInt())
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                icon.animate().scaleX(1f).scaleY(1f).setDuration(HELD).setInterpolator(EMPHASIZED).start()
                when {
                    !moved -> if (e.actionMasked == MotionEvent.ACTION_UP) icon.performClick()
                    magnet -> dismiss()
                    else -> {
                        hideZone()
                        snapToEdge()
                    }
                }
            }
        }
    }

    /** Target centre on screen from the untransformed layout, so the target's own animation does not move it. */
    private fun targetCentre(): Pair<Float, Float> {
        val loc = IntArray(2).also(zone::getLocationOnScreen)
        return (loc[0] + target.left + target.width / 2f) to (loc[1] + target.top + target.height / 2f)
    }

    private fun showZone() {
        zone.animate().cancel()
        target.animate().cancel()
        glow.animate().cancel()
        zone.alpha = 0f
        zone.visibility = View.VISIBLE
        target.alpha = 1f
        target.translationY = DZ_RISE_DP * dp
        target.scaleX = DZ_START_SCALE
        target.scaleY = DZ_START_SCALE
        glow.alpha = 0f
        zone.animate().alpha(1f).setDuration(DZ_IN).setInterpolator(EASE_OUT).start()
        target.animate().translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(DZ_IN).setInterpolator(EMPHASIZED).start()
    }

    private fun hideZone() {
        zone.animate().alpha(0f).setDuration(DZ_IN).setInterpolator(EASE_OUT)
            .withEndAction { zone.visibility = View.INVISIBLE }.start()
    }

    private fun setHot(on: Boolean) {
        val s = if (on) HOT_SCALE else 1f
        target.animate().scaleX(s).scaleY(s).setDuration(MAGNET).setInterpolator(EMPHASIZED).start()
        glow.animate().alpha(if (on) 1f else 0f).setDuration(MAGNET).setInterpolator(EMPHASIZED).start()
    }

    /** Release on target: bubble alpha 0 / scale .3, target alpha 0 / scale .4 (dismiss 280), then [onDismiss]. */
    private fun dismiss() {
        hidden = true
        setTouchable(false)
        icon.animate().alpha(0f).scaleX(GONE_SCALE).scaleY(GONE_SCALE)
            .setDuration(DISMISS).setInterpolator(EMPHASIZED).start()
        glow.animate().alpha(0f).setDuration(DISMISS).start()
        target.animate().alpha(0f).scaleX(TARGET_GONE_SCALE).scaleY(TARGET_GONE_SCALE)
            .setDuration(DISMISS).setInterpolator(EMPHASIZED)
            .withEndAction { onDismiss() }.start()
    }

    /** Release elsewhere: snap 360 with overshoot to the nearer side edge, y clamped. */
    private fun snapToEdge() {
        val m = ctx.realMetrics()
        val frame = bubble.width
        val edge = px(EDGE_DP - PAD_DP)
        val x = if (bubbleLp.x + frame / 2 < m.widthPixels / 2) edge else m.widthPixels - frame - edge
        val y = bubbleLp.y.coerceIn(px(TOP_MIN_DP - PAD_DP), m.heightPixels - px(BOTTOM_GAP_DP + PAD_DP))
        moveTo(x, y, SNAP, OVERSHOOT)
    }

    private fun moveTo(x: Int, y: Int, ms: Long, curve: TimeInterpolator) {
        moveAnim?.cancel()
        val x0 = bubbleLp.x
        val y0 = bubbleLp.y
        moveAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = curve
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                place(x0 + ((x - x0) * f).toInt(), y0 + ((y - y0) * f).toInt())
            }
            start()
        }
    }

    private fun place(x: Int, y: Int) {
        bubbleLp.x = x
        bubbleLp.y = y
        if (added) wm.updateViewLayout(bubble, bubbleLp)
    }

    private companion object {
        // a2-halo.html timings (ms)
        const val BUBBLE_OUT = 180L
        const val BUBBLE_IN = 300L
        const val DZ_IN = 220L
        const val MAGNET = 160L
        const val SNAP = 360L
        const val DISMISS = 280L
        const val HELD = 120L
        // a2-halo.html sizes (CSS px == dp) and scales
        const val MAGNET_DP = 104f
        const val DRAG_SLOP_DP = 6f
        const val DZ_RISE_DP = 28f
        const val BUBBLE_DP = 56
        const val PAD_DP = 12          // shadow margin in layout/bubble.xml
        const val EDGE_DP = 10
        const val HOME_Y_DP = 330
        const val TOP_MIN_DP = 60
        const val BOTTOM_GAP_DP = 200
        const val ZONE_DP = 260
        const val HELD_SCALE = .92f
        const val HIDDEN_SCALE = .7f
        const val GONE_SCALE = .3f
        const val TARGET_GONE_SCALE = .4f
        const val DZ_START_SCALE = .9f
        const val HOT_SCALE = 1.2f
        // a2-halo.html easing curves
        val EMPHASIZED = PathInterpolator(.2f, 0f, 0f, 1f)      // cubic-bezier(.2,0,0,1)
        val OVERSHOOT = PathInterpolator(.34f, 1.56f, .64f, 1f) // cubic-bezier(.34,1.56,.64,1)
        val EASE_OUT = PathInterpolator(0f, 0f, .58f, 1f)       // CSS ease-out
    }
}
```

- [ ] **Step 3: See the checks green**

Run:
```bash
F=app/src/main/java/us/jhangy/textify/Bubble.kt
grep -c "class Bubble(private val ctx: Context, private val onTap: () -> Unit, private val onDismiss: () -> Unit)" $F   # 1
grep -cE "fun (show|fadeIn|remove)\(\)|fun fadeOut\(onHidden: \(\) -> Unit\)" $F   # 4
for v in 180L 300L 220L 160L 360L 280L 120L 104f; do grep -q "$v" $F || echo MISSING $v; done   # no output
grep -c "PathInterpolator(.34f, 1.56f, .64f, 1f)" $F   # 1
grep -c "Build.VERSION" $F   # 0
```
Expected: `1`, `4`, (nothing), `1`, `0`.

- [ ] **Step 4: Commit (only if authorized)**

```bash
git add app/src/main/java/us/jhangy/textify/Bubble.kt
git commit -m "feat(bubble): a2 bubble fades, drag, magnet, snap and dismiss target" -- app/src/main/java/us/jhangy/textify/Bubble.kt
```

### Task 5 steps — CaptureService wiring

Precondition: Task 2 (`ScreenGrabber.kt`) and Task 4 (`Bubble.kt`) exist; Area U has created `SessionActivity` with `companion object { fun start(ctx: Context, shot: Bitmap); fun finishActive() }`. If `SessionActivity.kt` is missing, still write the file below (it is the frozen contract) and report it in the hand-back.

- [ ] **Step 1: See the check red**

Run: `grep -c "fun onSessionEnded()" app/src/main/java/us/jhangy/textify/CaptureService.kt`
Expected: `0`

- [ ] **Step 2: Replace `CaptureService.kt` with**

```kotlin
package us.jhangy.textify

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Foreground service that owns the MediaProjection, one long-lived capture pipeline
 * ([ScreenGrabber]) and the floating [Bubble]. A bubble tap fades the bubble out, grabs the
 * next frame and opens [SessionActivity]; the bubble fades back in on [onSessionEnded].
 * Dragging the bubble onto the bottom target stops the service.
 */
class CaptureService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var grabber: ScreenGrabber? = null
    private var bubble: Bubble? = null
    private var busy = false   // true from bubble tap until the session ends (or the grab fails)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stopSelf() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // On Android 14+ the FGS (type mediaProjection) must be running before
        // getMediaProjection() is called.
        startForeground(NOTIF_ID, buildNotification())
        if (projection != null) return START_NOT_STICKY   // already running: keep the current session

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (code == 0 || data == null) {
            stopSelf(); return START_NOT_STICKY
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        if (proj == null) { stopSelf(); return START_NOT_STICKY }
        // Must precede the VirtualDisplay creation inside ScreenGrabber, else IllegalStateException.
        proj.registerCallback(projectionCallback, main)
        projection = proj
        grabber = runCatching { ScreenGrabber(this, proj, main) }.getOrElse {
            stopSelf(); return START_NOT_STICKY
        }
        bubble = Bubble(this, onTap = { capture() }, onDismiss = { stopSelf() }).also { it.show() }
        // A projection cannot be restored after a process restart, so never ask to be restarted.
        return START_NOT_STICKY
    }

    private fun capture() {
        if (busy) return
        val g = grabber ?: return
        val b = bubble ?: return
        busy = true
        b.fadeOut {
            g.grab { shot ->
                if (shot == null) {
                    // spec: no frame -> bubble fades back, no toast; user retries
                    busy = false
                    b.fadeIn()
                } else {
                    SessionActivity.start(this, shot)
                }
            }
        }
    }

    private fun sessionEnded() {
        busy = false
        bubble?.fadeIn()
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "Textify", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, 0, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.notif_title))
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        instance = null   // first: the finishing session's onSessionEnded() becomes a no-op
        SessionActivity.finishActive()
        bubble?.remove()
        bubble = null
        grabber?.release()
        grabber = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "textify"
        private const val NOTIF_ID = 1
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val ACTION_STOP = "us.jhangy.textify.STOP"

        // Set in onCreate, cleared in onDestroy: never outlives the service.
        @SuppressLint("StaticFieldLeak")
        private var instance: CaptureService? = null

        /** Called by SessionActivity (main thread) when its session finishes. No-op if the service is not running. */
        fun onSessionEnded() {
            instance?.sessionEnded()
        }

        fun start(ctx: Context, code: Int, data: Intent) {
            val i = Intent(ctx, CaptureService::class.java)
                .putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
```

Note: `buildNotification` and `start()` keep their existing `Build.VERSION` checks unchanged (pre-existing code, not new branches; out of this plan's scope).

- [ ] **Step 3: See the checks green**

Run:
```bash
F=app/src/main/java/us/jhangy/textify/CaptureService.kt
grep -c "fun onSessionEnded()" $F                                         # 1
grep -cE "ResultActivity|captureOnce|runOcr|createVirtualDisplay|Toast" $F  # 0
grep -c "SessionActivity.start(this, shot)" $F                            # 1
grep -c "SessionActivity.finishActive()" $F                               # 1
awk '/registerCallback/{a=NR} /ScreenGrabber\(this/{b=NR} END{exit !(a && b && a<b)}' $F; echo order=$?   # order=0
grep -rc "createVirtualDisplay(" app/src/main/java | grep -v ":0"         # only ScreenGrabber.kt:1
```
Expected: `1`, `0`, `1`, `1`, `order=0`, one line `app/src/main/java/us/jhangy/textify/ScreenGrabber.kt:1`.

- [ ] **Step 4: Commit (only if authorized)**

```bash
git add app/src/main/java/us/jhangy/textify/CaptureService.kt
git commit -m "feat(capture): wire grabber, bubble and SessionActivity into CaptureService" -- app/src/main/java/us/jhangy/textify/CaptureService.kt
```

### Task 6 steps — Area S cross-check and build gate

- [ ] **Step 1: Re-run Task 1–4 checks** (commands in Task 1 Step 3, Task 2 Step 3, Task 3 Step 10, Task 4 Step 3). Every expected value must match. Fix deviations in owned files only.

- [ ] **Step 2: Static review against this plan** — read `Ocr.kt`, `ScreenGrabber.kt`, `Bubble.kt` and the 8 resources; confirm: (a) `createVirtualDisplay` only in the `ScreenGrabber` constructor; (b) held image closed before every `acquireLatestImage`; (c) `buffer.rewind()` before `copyPixelsFromBuffer`; (d) hidden bubble = root `INVISIBLE` + `FLAG_NOT_TOUCHABLE`, zone root `INVISIBLE` when not dragging (E5); (e) every timing constant equals the a2 header value.

- [ ] **Step 3: Request the build gate** — send to the lead: "Area S ready for build gate". The lead's gate-runner runs:

```bash
cd /Users/jhangyu/project/textify && JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug; echo RC=$?
grep -r ResultActivity app/src; echo GREP_RC=$?
```
Expected: `RC=0`; `GREP_RC=1` (no match).

- [ ] **Step 4: Fix build errors located in owned files**; report errors in `CaptureService.kt` (Task 5 owner) or Area U files to the lead with `file:line` and the compiler message.

---

## On-device verification (user; spec §Testing items owned by Area S)

- Capture twice or more in a row on an Android 14+ device without error (E1 fix).
- Bubble hidden during the session; X / back → bubble fades back in (300 ms).
- Drag bubble: target fades in at bottom centre; near it the bubble magnetises and the target grows + glows; release → bubble and target fade (280 ms), notification gone.
- Release away from the target → bubble snaps to the nearer side edge with a slight overshoot.
- Rotate the device, then tap the bubble → session screenshot matches the rotated screen (resize path).

## Known limitations (accepted)

- Bubble pink outer glow approximated by the neutral elevation shadow (coloured shadow is API 28+ only; one-path rule).
- Dismiss target window is anchored at the bottom of the app-visible area; on devices where overlay windows sit above the navigation bar, the target centre is 100 dp above the navigation bar, not the physical screen edge.
- If `SessionActivity` fails to start (background-activity-start block), the bubble stays hidden until the service is stopped; spec relies on the overlay-permission exemption.
- Snap left edge lands at 10 − 12 = −2 dp window x; the system may clamp the window to x = 0 (visual gap 12 dp instead of 10 dp).

## Self-review

1. **Spec coverage:** persistent VD/ImageReader + Android 14 fix → Task 2/5; tap flow (bubbleOut → frame → holder → activity) → Task 4/5; bubble fade-in on session end → Task 4/5 (`onSessionEnded`); drag-to-dismiss target + magnet + stop → Task 3/4/5; `Ocr` lines → Task 1; projection stopped by system → finish session → Task 5 (`onDestroy` → `finishActive`); no frame on tap → bubble back, no toast → Task 5; strings → none new (Global Constraints). Session UI, edge glow, pills, toast, manifest, `ResultActivity` deletion → Area U (not here).
2. **Placeholder scan:** no placeholder markers or cross-task "same as" references in this file.
3. **Type consistency:** `realMetrics()` (Task 2 produces, Task 4 consumes); `Bubble(ctx, onTap, onDismiss)` / `show` / `fadeOut(onHidden)` / `fadeIn` / `remove` (Task 4 produces, Task 5 consumes); `ScreenGrabber(ctx, projection, handler)` / `grab((Bitmap?) -> Unit)` / `release` (Task 2 → Task 5); contract signatures copied verbatim.
