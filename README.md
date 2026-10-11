# Textify

Vendor-independent on-screen text extraction for Android. A floating bubble
captures the current screen via MediaProjection and runs on-device OCR
(Google ML Kit) — no root, no system UID, no vendor framework. Works on any
Android 8+ device.

## How it works
1. `MainActivity` requests the overlay permission + screen-capture consent, lets
   you pick the OCR language, then starts `CaptureService`.
2. `CaptureService` (foreground, type `mediaProjection`) keeps one VirtualDisplay +
   ImageReader for its whole lifetime and shows a draggable bubble. Tapping the
   bubble fades it out, takes the latest frame and opens `SessionActivity`.
   Dragging the bubble onto the bottom target stops the service.
3. `SessionActivity` freezes the screen: the screenshot is shown 1:1 under hidden
   system bars with an edge glow while `Ocr` runs, then every recognised line
   becomes a pill. Tap a pill to copy the line (the pill shines and flashes);
   long-press for partial selection with the system copy menu. X or back closes
   the session and the bubble returns.

## OCR languages
Chinese model (also covers Latin/English), Japanese, Korean — switch in the app.
All run fully offline. See `Ocr.kt`.
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
