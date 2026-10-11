# Settings redesign — plan outline (for lead review; not the full plan)

Spec: `docs/superpowers/specs/2026-10-11-settings-redesign-impl-spec.md` (cited as §n). Every value an implementer needs is in the spec; units below only fix ownership, interfaces and checks.
Build: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug` (+ `testDebugUnitTest` from round 2). Paths are relative to `app/src/main/` unless they start with `app/`, `docs/` or `README`.

## Shape
- 2 rounds × (5 implementers + 1 reviewer). Round 1 = additive foundations: nothing existing calls them yet, old app keeps working, build green at round-1 exit. Round 2 = switch-over: screens, engine and resources change together and converge at round-2 exit.
- Within a round, units are parallel and code only against interfaces frozen in this outline / the spec (same rule as the capture-session precedent). Round 2 consumes only round-1 outputs plus round-2 frozen interfaces.
- No file is owned by two units (matrix at the end). Strings are added only by R2-E; round-1 views take all text from their callers' layouts, so round 1 needs no string resources.

## Round 1 — foundations (build green at exit)

### R1-A Icons and bubble glyph (mechanical)
- Purpose: all VectorDrawables of §9 and the bubble entrance; delete the old launcher art.
- Owns: `res/drawable/ic_launcher_background.xml` (new), `res/drawable/ic_launcher_foreground.xml` (new), `res/drawable/ic_launcher_monochrome.xml` (new), `res/mipmap-anydpi-v26/ic_launcher.xml`, delete `res/drawable/ic_launcher_bg.xml`, `res/drawable/ic_launcher_fg.xml`, `res/mipmap-anydpi-v26/ic_launcher_round.xml`; `res/drawable/ic_scan.xml`, `res/drawable/avd_scan_in.xml` (new), `res/layout/bubble.xml`, `java/.../Bubble.kt`; `res/drawable/ic_stat_textify.xml`, `ic_session_settings.xml`, `ic_back.xml`, `ic_check.xml` (all new).
- Deps: none.
- Produces: drawable names `ic_launcher_background|foreground|monochrome`, `ic_scan`, `avd_scan_in`, `ic_stat_textify`, `ic_session_settings`, `ic_back`, `ic_check`.
- Acceptance: (1) `grep -F` finds each §9 pathData string in its file; (2) `test ! -e` for the three deleted files and `grep -rn "ic_launcher_bg\|ic_launcher_fg\|ic_launcher_round" app/src` empty; (3) `grep -n 'android:padding="9dp"' res/layout/bubble.xml` hits and `@drawable/avd_scan_in` is its src; (4) `grep -n "AnimatedVectorDrawable" Bubble.kt` hits inside `fadeIn`; (5) `ic_launcher.xml` contains `<monochrome`; (6) round-1 build.

### R1-B Halo primitives (judgment: 2 tickets)
- Purpose: one shared implementation of glows, pills, mist backgrounds and the halo recipe (§8.1, §8.3) so later views do not fork drawing code.
- Owns: `java/.../Glow.kt` (new), `java/.../EdgeGlowView.kt`, `java/.../PillView.kt`.
- Deps: none.
- Produces (frozen, §8.1/§8.3 exact signatures): `withAlpha`, `glowBitmap`, `PillPainter`, `GlowDrawable`, `MistBackground`, `MistSpot`, `HaloStyle.{cta,card,chip,ONBOARDING_SPOTS,SETTINGS_SPOTS}`, `HaloRecipe` (+ `SESSION`, `ONBOARDING_MINI`, `SETTINGS_MINI`, `PREVIEW_EDGE`), `EdgeGlowView.recipe/fadeOut/showSettled/clear`.
- Acceptance: (1) the four `HaloRecipe` constants carry exactly the §8.1 table numbers (grep); (2) `SESSION` equals the old constants — `grep -n "HALO_BLUR_DP\|CORE_BLUR_DP\|CORNER_DP" EdgeGlowView.kt` shows no remaining standalone constants; (3) `grep -n "private fun argb\|renderGlow" PillView.kt` empty and PillView draws through `PillPainter`; (4) `grep -rn "SDK_INT\|RenderEffect\|setShadowLayer" Glow.kt EdgeGlowView.kt PillView.kt` empty; (5) round-1 build; (6) reviewer compares a session capture before/after on device or by code reading: identical pill and halo parameters.
- Critical path: R1-D and R1-E compile against these signatures; dispatch first, and land the signatures in its first commit.

### R1-C Capture demo + step track (judgment: 2 tickets)
- Purpose: the looping miniature capture and the 2-node track, shared by onboarding and settings (§8.2, §8.4).
- Owns: `java/.../CaptureDemoView.kt` (new), `java/.../StepTrackView.kt` (new).
- Deps: R1-B frozen API (`HaloRecipe`, `EdgeGlowView`, `PillPainter`, `GlowDrawable`, `withAlpha`), R1-A drawable name `ic_scan`, `ic_check` (resource names only).
- Produces: `CaptureDemoView.{configure, setGranted, pause, resume, flash}`, `DemoSpec.{ONBOARDING, SETTINGS}`, `StepTrackView.{State, setState, setLabels}`; XML child ids `demoScreen`, `demoChip`, tag `line`.
- Acceptance: (1) `grep -c "INFINITE" CaptureDemoView.kt` = 1 and `grep -n "4800" CaptureDemoView.kt` hits (one loop animator drives every element); (2) DemoSpec constants equal §8.2 numbers (grep); (3) no `SDK_INT`; (4) round-1 build; (5) reviewer reads keyframe table against §8.2.

### R1-D Language controls (judgment: 2 tickets)
- Purpose: preview, toggles and lanes of the language card (§8.5–§8.7).
- Owns: `java/.../LanguagePreviewView.kt`, `java/.../LanguageToggleView.kt`, `java/.../SpeedLanesView.kt` (all new).
- Deps: R1-B frozen API; existing `Script` enum (unchanged in round 2).
- Produces: `LanguagePreviewView.setScripts`, `LanguageToggleView.{checked, setChecked, reject}`, `SpeedLanesView.{setScripts, setLabels}`; XML tags `zh|en|ja|ko`, `text`, `tag`, `glyph`, `sub`.
- Acceptance: (1) lane widths `0.58f|0.64f|0.70f`, durations `240|360|380|260|200|300|140` present (grep); (2) `HapticFeedbackConstants.VIRTUAL_KEY` used, `REJECT` absent; (3) `en` row lit for any non-empty set (reviewer reads `setScripts`); (4) no `SDK_INT`; (5) round-1 build.

### R1-E Engine: OCR merge + prefs set, test infra (judgment: 1 ticket)
- Purpose: multi-select scripts and the parallel-recognize + IoU merge (§6), with the project's first unit test.
- Owns: `app/src/test/java/us/jhangy/textify/OcrMergeTest.kt` (new), `app/build.gradle.kts`, `java/.../OcrMerge.kt` (new — holds `OcrCandidate` + `mergeOcr()` so round 1 does not touch `Ocr.kt`'s callers).
- Deps: none.
- Produces: `internal data class OcrCandidate(...)`, `internal fun mergeOcr(candidates: List<OcrCandidate>): List<OcrCandidate>`. `testImplementation("junit:junit:4.13.2")`.
- Acceptance: (1) `./gradlew testDebugUnitTest --tests us.jhangy.textify.OcrMergeTest` green with the five §13.2 cases, and red when the IoU threshold is changed to `>=` (reviewer mutation on a copy); (2) round-1 build.

### R1 reviewer
- Reviews R1-A…E against spec §8, §9, §6.2; runs (or has the gate-runner run) `assembleDebug testDebugUnitTest`; checks the ownership matrix with `git diff --name-only <round-start>..HEAD`.

## Round 2 — switch-over (build + tests green at exit)

### R2-A Service + Ocr/Prefs switch (judgment: 2 tickets)
- Owns: `java/.../CaptureService.kt`, `java/.../Ocr.kt`, `java/.../Prefs.kt`.
- Deps: R1-E `mergeOcr`/`OcrCandidate`; R1-A `ic_stat_textify`; R2-E string names (§10.1).
- Produces (frozen §5, §6): `CaptureService.isRunning`, `setSettingsVisible(Boolean)`, `onSessionEnded()`, `start(...)`; `Prefs.scripts/setScripts`; `Ocr.recognize(ctx, bitmap, scripts: Set<Script>, onResult)`.
- Acceptance: (1) `grep -n "fun stop\|ic_menu_crop\|SDK_INT\|KEY_SCRIPT\|fun script(" CaptureService.kt Prefs.kt Ocr.kt` empty; (2) `grep -n "createScreenCapture" CaptureService.kt` empty (consent lives in MainActivity); (3) `grep -n "confidence" Ocr.kt` hits; (4) round-2 gate.

### R2-B Launch flow + onboarding (judgment: 2 tickets)
- Owns: `java/.../MainActivity.kt` (rewrite), `res/layout/activity_main.xml` (rewrite).
- Deps: R1-B/R1-C views and styles; R2-A `CaptureService.isRunning/start`; R2-C `SettingsActivity.start`; R2-E strings + `Theme.Textify.Translucent`.
- Acceptance: (1) `grep -n "scriptGroup\|startButton\|stopButton\|Prefs\." MainActivity.kt activity_main.xml` empty; (2) `grep -n "SDK_INT" MainActivity.kt` = exactly the two §11 branches; (3) `grep -n "setView(" MainActivity.kt` empty (text toasts only); (4) timings `360|60|120|220|300|900` present; (5) round-2 gate.

### R2-C Settings screen (judgment: 2 tickets — permission card, language card)
- Owns: `java/.../SettingsActivity.kt` (new), `res/layout/activity_settings.xml` (new).
- Deps: R1-B, R1-C, R1-D, R1-A (`ic_back`); R2-A `Prefs`, `CaptureService.setSettingsVisible`; R2-E strings/theme.
- Produces: `SettingsActivity.start(ctx: Context)`.
- Acceptance: (1) `grep -n "setSettingsVisible" SettingsActivity.kt` in `onStart`, `onStop`, `onDestroy`; (2) `grep -n "openingSystemSettings" SettingsActivity.kt` ≥ 3 hits; (3) timings `320|260|1500|220|160|200` present; (4) `grep -n "Prefs.setScripts" SettingsActivity.kt` hits and the last-one guard calls `reject()`; (5) no `SDK_INT`; (6) round-2 gate.

### R2-D Session settings button
- Owns: `java/.../SessionActivity.kt`, `res/layout/activity_session.xml`.
- Deps: R1-A `ic_session_settings`; R1-B `withAlpha`; R2-A `Ocr.recognize(Set)`, `Prefs.scripts`, `CaptureService.setSettingsVisible`; R2-C `SettingsActivity.start`; R2-E strings.
- Acceptance: (1) `activity_session.xml` has `@+id/settings` with `layout_marginEnd="68dp"`; (2) `grep -n "private fun argb\|Prefs.script(" SessionActivity.kt` empty; (3) the end action order `setSettingsVisible(true)` → `SettingsActivity.start` → `endSession()` → `finish()` is literally in that sequence (grep -A); (4) `BTN_STAGGER = 40L` present; (5) round-2 gate.

### R2-E Resources, manifest, README
- Owns: `res/values/strings.xml`, `res/values/themes.xml`, `AndroidManifest.xml`, `README.md`.
- Deps: names frozen in spec §10 (no code deps).
- Acceptance: (1) spec §13.3 grep empty; (2) every §10.1 name present, every deleted name absent (`grep -c`); (3) manifest has `SettingsActivity`, `roundIcon="@mipmap/ic_launcher"`, three `Theme.Textify.Translucent` uses; (4) `grep -n "DayNight\|Theme.Textify.Session" app/src` empty; (5) README has no "Start"/"Stop" button wording; (6) round-2 gate.

### R2 reviewer
- Runs the whole-feature acceptance spec §13.1–§13.6 (gate-runner executes `assembleDebug testDebugUnitTest`), checks round-2 cross-unit interfaces against §5/§6/§7.1, and hands the §13.7 on-device checklist to the user.

## Ownership matrix (each file exactly once)
| file | unit |
|---|---|
| `res/drawable/ic_launcher_background.xml`, `ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`, `ic_scan.xml`, `avd_scan_in.xml`, `ic_stat_textify.xml`, `ic_session_settings.xml`, `ic_back.xml`, `ic_check.xml`; delete `ic_launcher_bg.xml`, `ic_launcher_fg.xml` | R1-A |
| `res/mipmap-anydpi-v26/ic_launcher.xml`; delete `res/mipmap-anydpi-v26/ic_launcher_round.xml` | R1-A |
| `res/layout/bubble.xml`, `Bubble.kt` | R1-A |
| `Glow.kt`, `EdgeGlowView.kt`, `PillView.kt` | R1-B |
| `CaptureDemoView.kt`, `StepTrackView.kt` | R1-C |
| `LanguagePreviewView.kt`, `LanguageToggleView.kt`, `SpeedLanesView.kt` | R1-D |
| `OcrMerge.kt`, `app/src/test/.../OcrMergeTest.kt`, `app/build.gradle.kts` | R1-E |
| `CaptureService.kt`, `Ocr.kt`, `Prefs.kt` | R2-A |
| `MainActivity.kt`, `res/layout/activity_main.xml` | R2-B |
| `SettingsActivity.kt`, `res/layout/activity_settings.xml` | R2-C |
| `SessionActivity.kt`, `res/layout/activity_session.xml` | R2-D |
| `res/values/strings.xml`, `res/values/themes.xml`, `AndroidManifest.xml`, `README.md` | R2-E |
Untouched: `ScreenGrabber.kt`, `res/drawable/bubble_bg.xml`, `dismiss_*.xml`, `ic_dismiss_x.xml`, `ic_session_close.xml`, `session_hint_bg.xml`, `session_x_bg.xml`, `res/layout/dismiss_target.xml`.

## Round gates
- Round-1 exit: `assembleDebug testDebugUnitTest` exit 0 (old app still runs unchanged except new icons/bubble glyph).
- Round-2 exit: spec §13.1–§13.6 all pass; round-2 units cannot build alone (they switch shared interfaces together), so their per-unit checks are greps and the build is checked once at the gate.

## Risks for the lead
1. Round 1 has within-round compile deps (R1-C, R1-D → R1-B signatures). Mitigation: R1-B lands its signatures first; alternative is moving R1-C/R1-D into round 2, which would make round 2 seven units.
2. R2-C is the largest unit (two cards + transitions). If its holder hits the baton limit, split by card at hand-off (layout ownership stays with one holder).
3. Spec §6.2 VERIFY items (confidence scale, three models in parallel) can only be closed on device after round 2.
