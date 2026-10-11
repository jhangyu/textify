# Textify

Vendor-independent on-screen text extraction for Android. A floating bubble
captures the current screen via MediaProjection and runs on-device OCR
(Google ML Kit) — no root, no system UID, no vendor framework. Works on any
Android 8+ device.

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
   system bars with an edge glow while `Ocr` runs, then every recognised paragraph
   becomes a pill. Tap a pill to copy the paragraph (the pill shines and flashes);
   long-press for partial selection with the system copy menu. X or back closes
   the session and the bubble returns. The settings button next to X closes the
   session and opens `SettingsActivity`.
4. `SettingsActivity` shows the overlay-permission state (with a shortcut to the system
   setting) and the OCR language selection. The bubble stays hidden while it is open
   and returns when you leave it. Changes apply from the next capture.

## OCR languages
Multi-select, at least one: Chinese (Traditional and Simplified), Japanese, Korean —
chosen in Settings. Every model also reads Latin/English. All selected models run in
parallel on the same screenshot; where two paragraphs overlap (IoU > 0.5) the higher-confidence
one is kept (`OcrMerge.kt`). All run fully offline. See `Ocr.kt`.
`// ponytail:` ML Kit picks its own compute backend (CPU/GPU via NNAPI); it
exposes no NPU/int4 control. Upgrade path if speed matters: swap in a
LiteRT int4 model with a hardware delegate.

## Build
Needs JDK 17 or 21 (not 25), Android SDK with platform 36.
```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```
`local.properties` must point `sdk.dir` at your Android SDK.

## Stack
AGP 8.13.2, Gradle 8.14.6, Kotlin 2.2.0, ML Kit text-recognition 16.0.1.
AGP 9.x + latest androidx were tried but 9.x's built-in Kotlin plugin conflicts
with the standard kotlin-android plugin; pinned to the proven 8.13 line.
