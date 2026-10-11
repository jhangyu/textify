# Settings / permission / language / icon redesign — design

Status: design approved; mockup phase. Mockup brief (requirements + style rules): `docs/design/settings-mockup-brief.md`.

## Decisions
1. Launch flow: no overlay permission -> permission onboarding; permission present -> system capture consent ->
   bubble, app UI finishes; consent denied -> toast + finish; bubble already running -> settings screen.
2. Session screen gets a settings button next to X. Tap -> session closes (fade), settings opens, bubble hidden;
   leaving settings -> bubble shows again; changes apply from the next capture (no re-OCR of the current shot).
3. Permission + language merged into one settings screen is a candidate (designer "settings"); separate screens
   are also mocked (designers "permission", "language"). User picks after the mockups.
4. Old `MainActivity` Start/Stop UI is deleted. Stop = drag bubble to dismiss target (unchanged).
5. OCR language becomes multi-select (Chinese incl. Latin, Japanese, Korean), min one. Implementation: run every
   selected recognizer on the same bitmap in parallel; merge lines — when two lines overlap (IoU > 0.5) keep the
   higher-confidence one (verify `Text.Line` confidence API in the installed ML Kit version before implementing;
   fallback: keep the longer text). `Prefs` stores a set instead of a single script.
6. One icon language derived from `ic_scan.xml` (corner brackets + T): adaptive launcher icon + themed monochrome,
   bubble glyph, notification small icon.
7. UI copy switches to Traditional Chinese.
8. Light theme only (Halo palette from `docs/design/mockups/a2-halo.html`). No dark theme.

## Out of scope (this phase)
Implementation; app-UI language switching; dark theme.
