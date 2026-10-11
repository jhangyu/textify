# Capture session (frozen screen + selectable text) — design

## Goal
Tapping the floating bubble freezes the current screen into a full-screen session where every OCR line becomes a selectable, copyable pill over the original screenshot. An X at top-right ends the session. The bubble can be dragged to a bottom target (Messenger style) to stop the whole app.

Visual and motion source of truth: `docs/design/mockups/a2-halo.html` (direction "Halo"). Its header comment holds the palette, alphas, timings and glow recipe; copy those values, do not invent new ones. Behaviour requirements: `docs/design/mockup-brief.md` §Confirmed requirements.

## Decisions (user-approved)
- One text box per OCR **line**; box = a2 pill (radius = height/2, LinearGradient #A78BFA@20% → #F472B6@16%, soft glow #A78BFA@28% r12dp), screenshot stays visible under it. No solid outline strokes anywhere.
- Single tap on a pill copies the whole line. Long-press gives partial selection with system handles + system copy menu.
- Close via X (top-right) or system back. Tapping empty space does nothing.
- No "copy all" button.
- Copy feedback: shine sweep across the pill + pressed overlay flash (flash 380 ms). No in-app toast on any API level (Android 13+ shows the system clipboard preview).
- No text / OCR failure: centred "No text detected" capsule hint (hintIn 300); user closes with X.
- Bubble hidden during the session; fades back in (bubbleIn 300) when it ends. MediaProjection consent is kept between sessions.
- Bubble drag-to-dismiss: dismiss target fades in at bottom-centre (100 dp above bottom) while dragging (dzIn 220); magnet within 104 dp (magnet 160, snap 360); release on target → bubble fades (dismiss 280) and `CaptureService` stops (projection released, notification gone).
- Dark mockups `b1`–`b3` stay in the repo; they are not used.

## Architecture
Same process, three units plus the existing ones.

| Unit | Change | Responsibility |
|---|---|---|
| `CaptureService` | modify | Owns projection, **one long-lived VirtualDisplay + ImageReader** (see Fix below), bubble, drag-to-dismiss target. On tap: fade bubble out, take latest frame, put bitmap in `SessionActivity`'s in-memory holder, start the activity. Exposes `showBubble()` for the activity to call on session end. |
| `Ocr` | modify | Return ML Kit lines (text + bounding `Rect`) instead of a joined string. Empty list = no text; failure = empty list. |
| `SessionActivity` | new, replaces `ResultActivity` | Full-screen, system bars hidden, layout in display cutout, so the screenshot maps 1:1 to screen pixels and OCR rects need no transform. Layers bottom→top: screenshot `ImageView`; scrim view (RadialGradient #1E1B4B 5% centre → 16% corners); pill container; `EdgeGlowView`; X button; hint. Runs OCR, then reveals pills. On finish: fade out (closeOut 300), call `CaptureService.showBubble()`, recycle bitmap. |
| `EdgeGlowView` | new | Draws a2's two-pass rotating SweepGradient round-rect halo (activation: halo + core, breathing; settled: halo only at width 26 dp, alpha .22, 12% rotation speed). |
| Pill line view | new (small `TextView` subclass or a TextView + background drawable) | `textIsSelectable`, single line, text colour transparent, highlight #7C3AED@26%, auto-size uniform so glyph extents roughly match the line rect (selection handles land near the real glyphs). Padding 7×3 dp around the OCR rect. Draws the pill background, press scale 1.03, copy shine (translating white LinearGradient clipped to the pill path). |

Deleted: `ResultActivity.kt`, `res/layout/activity_result.xml`, unused strings (`copy`, `close`). README "How it works" updated.

Why an Activity, not an overlay window: the text-selection toolbar (ActionMode) and clipboard focus require a real window owned by an Activity; a `WindowManager` overlay view gets no floating copy menu. The app already holds the overlay permission, which exempts it from background-activity-start limits (the current `ResultActivity` launch already relies on this).

## Data flow
1. Bubble tap → bubble fades out (bubbleOut 180). After it is gone, grab the latest frame from the persistent `ImageReader` (so the bubble is not in the shot).
2. Store bitmap in `SessionActivity` holder (in-memory; too large for an Intent) → start activity with no window animation.
3. Activity shows screenshot immediately; `EdgeGlowView` blooms in (glowIn 350, bloom 400); scrim fades in after scrimDelay 150 (scrimIn 300). X fades in (xIn 240) and can cancel during OCR. OCR starts.
4. OCR done → scrim out (scrimOut 300), glow settles (glowSettle 400), pills fade/scale in with stagger `delay_i = round(i/(n-1) * 140)`, each boxIn 260. Empty result → hint instead of pills.
5. Tap pill → copy line to clipboard, shine + flash. Long-press → system selection.
6. X / back → closeOut 300 → finish → `CaptureService.showBubble()` (bubbleIn 300).
7. Projection stopped by the system → service stops and finishes any open session.

## Fix included: reuse of MediaProjection on Android 14+
Current `captureOnce()` creates a new VirtualDisplay on every tap. On Android 14+ calling `createVirtualDisplay` more than once on the same `MediaProjection` throws `SecurityException`, so the second capture fails. Change: create one VirtualDisplay + ImageReader right after projection is granted, keep it for the service lifetime, take `acquireLatestImage()` on tap, release both in `onDestroy`. Verify the API 14 rule against official docs during implementation.

## Platform-consistency constraint
Project rule: no per-API-level divergent code paths unless the alternative is provably impossible. The a2 header suggests RenderEffect blur on API 31+ and stacked strokes below. Prefer one path for API 26–36 (e.g. blurred halo mask rendered once into an ALPHA_8 bitmap and coloured by the rotating SweepGradient at draw time, re-rendered only when width changes meaningfully). If a single path is shown to be impossible or too slow, stop and report with evidence before adding a branch.

## Error handling
- No frame available on tap (reader returned null) → bubble fades back, toast "No text found." is not shown; just retry on next tap.
- OCR failure → treated as no text (hint).
- Bitmap is recycled in `onDestroy` of the activity; the holder is cleared when read.

## Testing / acceptance
1. `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug` succeeds.
2. `grep -r ResultActivity app/src` returns nothing.
3. Manual on-device checklist (user):
   - tap bubble → halo bloom, faint scrim, pills appear over each line, residual faint halo stays;
   - tap pill → clipboard holds that line, shine + flash, no in-app toast;
   - X visible from capture start; X during OCR cancels;
   - long-press pill → handles + system copy menu, partial copy works;
   - empty-space tap does nothing; X closes; back closes; bubble reappears;
   - capture twice or more in a row (Android 14+ device) without error;
   - screen with no text → "No text detected" hint;
   - drag bubble to bottom target → magnet, release → bubble gone, notification gone;
   - every fade ≤ 400 ms (visual check against a2 mockup).
