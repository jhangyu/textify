# Capture session — planning contract (frozen by lead)

Spec: `docs/superpowers/specs/2026-10-11-capture-session-design.md`. Visual source: `docs/design/mockups/a2-halo.html` (header comment = all values).

## End state
Bubble tap opens a frozen-screen session per spec with a2 visuals; pills copy/select; X/back close; repeat captures work on Android 14+; bubble drag-to-bottom stops the app; `ResultActivity` gone.

## Planning areas (one planner each)
- **Area S — Service side**: `CaptureService.kt` (persistent VirtualDisplay/ImageReader fix, tap capture flow, bubble fade out/in, drag-to-dismiss target + magnet + stop), `Ocr.kt`, bubble/dismiss drawables & layouts.
- **Area U — Session UI side**: `SessionActivity.kt` (new), `EdgeGlowView.kt` (new), pill line view (new), toast/hint/X, layouts, `themes.xml`, `strings.xml`, `AndroidManifest.xml`, deletion of `ResultActivity.kt` + `activity_result.xml`, README update.

## Frozen cross-area interfaces (both areas MUST use exactly these)
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
Strings needed by Area S are added by Area U (Area S lists them in its plan).

## Team budget for execution
Two rounds, 4–5 members per round in total across both areas: Area S gets 2 members per round, Area U gets 2–3 per round. Within a round, no two members own the same file. Members in the same round run in parallel. Round 2 may depend on round 1 outputs.

## Fixed acceptance (whole feature)
1. `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug` exit 0.
2. `grep -r ResultActivity app/src` empty.
3. Interfaces above exist with exact signatures.
4. User's on-device checklist in spec §Testing passes.

Round budget: 2 execution rounds + 1 fix round.

## Amendments (user decisions 2026-10-11 — override spec AND both plans where they conflict)
1. **No in-app copy toast on any API level.** Copy feedback = pill shine + pressed-overlay flash only (Android 13+ system clipboard preview is the text confirmation). Remove toast view, its drawable/layout parts, `TOAST_*` constants and the `copied` string. One code path, no `SDK_INT` check.
2. **X button appears at capture start** (as in a2 mockup), fading in with xIn 240 when the session opens, not at OCR done. X during OCR cancels the session (same close path as after OCR).
