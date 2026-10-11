# Textify settings / permission / language / icon mockup brief

## Product context
Textify is an Android app (minSdk 26). A floating bubble (overlay window) sits over any app; tapping it
freezes the screen (`SessionActivity`) and shows every OCR line as a selectable pill. The capture session UI
is done. The remaining screens (overlay-permission onboarding, OCR language setting, app icon) are bare
defaults and must be redesigned to match the session's visual style.

## New navigation (user-approved, do not change)
1. Launch app icon:
   - overlay permission missing -> show the permission onboarding screen; once granted, continue automatically.
   - permission present -> system screen-capture consent dialog appears immediately (Android requires it on
     every service start; it cannot be skipped). Accept -> bubble appears, app UI closes itself (no page stays).
     Deny -> short toast, app closes.
   - bubble already running -> open the settings screen.
2. Session screen: a new **settings button** sits next to the existing X (top-right). Tap -> session fades out,
   settings screen opens, bubble hidden. Leaving settings -> bubble returns; new settings apply from the next capture.
3. The old main screen (Start/Stop buttons) is removed. Stopping the service stays as "drag bubble onto bottom X".
4. OCR language is **multi-select**: Chinese (also covers English/Latin), Japanese, Korean. At least one must
   stay selected. More languages = slower recognition (each selected model runs on the same screenshot).

## Style source of truth (mandatory)
`docs/design/mockups/a2-halo.html`, direction "Halo". Read its header comment and copy palette / timing values.
- Palette: lavender #A78BFA, pink #F472B6, peach #FDBA74, aqua #67E8F9, periwinkle #818CF8, violet #7C3AED,
  plum #4C1D95, ink #1E1B4B, mist #FAF5FF, frost #FFFFFF.
- **Light theme only. No dark theme, no dark-mode toggle, no dark backgrounds.**
- No solid outline strokes as frames — use faint fills, soft glows, gradients (same rule as the session).
- Fades/transitions <= 400 ms.
- Existing bubble glyph `app/src/main/res/drawable/ic_scan.xml`: viewport 24, stroke #7C3AED width 2 round caps,
  four corner brackets + a "T" (path `M4,8V6a2,2 0,0 1,2 -2h2M16,4h2a2,2 0,0 1,2 2v2M20,16v2a2,2 0,0 1,-2 2h-2M8,20H6a2,2 0,0 1,-2 -2v-2M8.5,9h7M12,9v7`).
  Session X button: white #FFFFFF@86% circle (`session_x_bg.xml`).

## UI copy
All UI text in Traditional Chinese (zh-TW). Language names may show native sample glyphs (中文 / 日本語 / 한국어 / ABC).

## Must be implementable natively
Android Views/Canvas: gradient shaders (Linear/Sweep/Radial), alpha/scale/translate animators, RenderEffect blur
(API 31+ only — give a graceful non-blur look for API 26–30). Avoid CSS-only effects (backdrop-filter as sole
carrier, mix-blend tricks with no Canvas equivalent). Icons must be expressible as Android VectorDrawable
(paths, linear/radial gradients) — the app icon as an adaptive icon (108dp canvas, 72dp safe zone, foreground +
background layers) plus a monochrome layer for Android 13 themed icons; notification icon must be single-colour
white-on-transparent.

## Deliverables (per designer: exactly 3 mockups, genuinely different visual directions within the Halo family)
Output dir `docs/design/mockups/settings/`, filename `<item>-<n>-<direction-slug>.html`, item ∈
`permission | language | icon | settings`, n ∈ 1..3.

| item | must show |
|---|---|
| permission | Why the overlay permission is needed; simulated "go to system settings" hop; return states "granted" and "still not granted"; transition from granted to the system screen-capture consent dialog (draw a mock system dialog). |
| language | Multi-select of 中文(含英文) / 日本語 / 한국어 with sample glyphs; speed hint that grows with selection count; attempt to deselect the last one is blocked with feedback. Shown as a standalone screen. |
| icon | One icon language based on the scan glyph, covering: adaptive app icon (show circle/squircle/rounded-square masks + Android 13 themed monochrome on a light wallpaper home screen), the bubble glyph inside the 56dp bubble over a light app screen, the status-bar/notification small icon in a light notification shade. Show the vector shapes large too. |
| settings | Single merged settings screen (permission status section + language multi-select section); the settings button next to the session X (frozen-screen session with Halo residual glow and pills, recreate from a2-halo.html); transition from tapping it -> session fades -> settings opens; leaving settings -> bubble fades back in. |

Format rules for every file:
- Single self-contained `.html` (inline CSS/JS, no network, no external fonts/images; system font stack).
- Phone frame ~412x915 CSS px, centred, light page background.
- Interactive and animated, with a "Replay" control re-running the main flow.
- Top-of-file HTML comment: direction name, one-line concept, palette hex values (+ alphas), exact timing values (ms),
  native-implementation notes (which shader/animator/drawable, API 26–30 fallback).
