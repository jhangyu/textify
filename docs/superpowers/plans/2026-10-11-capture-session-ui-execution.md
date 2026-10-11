# Capture Session — Area U (Session UI) Execution Plan

Implementation plan: `docs/superpowers/plans/2026-10-11-capture-session-ui-plan.md` (tasks 1–6, complete code per task).
Contract: `docs/superpowers/plans/2026-10-11-capture-session-contract.md` (budget: Area U = 2–3 members per round, 2 rounds + 1 fix round).
Area S counterpart: `docs/superpowers/plans/2026-10-11-capture-session-service-execution.md`.

## Rules for every unit (paste into each dispatch prompt)

- First line of the prompt: `ROLE: WORKER — you may NOT spawn agents, teams, or workflows. If the task needs delegation, STOP and report back to leader.`
- Read the plan's **Global Constraints**, **Evidence** and your tasks' skeleton + steps before writing code. Copy the code from the plan; if it does not compile or contradicts the frozen contract, stop and report `file:line` + reason. Do not invent a cross-area interface.
- Edit only the unit's owned files. Never edit Area S files: `CaptureService.kt`, `Ocr.kt`, `ScreenGrabber.kt`, `Bubble.kt`, `res/layout/bubble.xml`, `res/layout/dismiss_target.xml`, `res/drawable/{bubble_bg,ic_scan,dismiss_scrim,dismiss_target_bg,dismiss_glow,ic_dismiss_x}.xml`. Never edit `MainActivity.kt`, `Prefs.kt`, `app/build.gradle.kts`, the contract, the spec or the mockups.
- Do not run Gradle (exceeds the foreground limit). The lead's gate-runner runs the build after round 2 of both areas.
- Git: no `stash` (including `stash list`), `reset`, `checkout --`, `clean`, `git add -A`. Commit only if the dispatch prompt authorizes it: `git add <own files>` then `git commit -m … -- <own files>`; `git rev-parse --abbrev-ref HEAD` before, `git status --porcelain -- <own files>` after (must be empty).
- No sleep/polling. Report via SendMessage to the lead (fallback `main`): conclusions with `file:line`, changed files, each acceptance command with its actual output, uncertain items; last line `READY_FOR_SIGNOFF` or `BLOCKED`.

## Ownership matrix

| File | Round 1 | Round 2 |
|---|---|---|
| `app/src/main/java/us/jhangy/textify/EdgeGlowView.kt` | U-R1-A | U-R2-B (fix-only) |
| `app/src/main/java/us/jhangy/textify/PillView.kt` | U-R1-B | U-R2-B (fix-only) |
| `app/src/main/java/us/jhangy/textify/SessionActivity.kt` | U-R1-C | U-R2-A |
| `app/src/main/res/layout/activity_session.xml` | U-R1-C | U-R2-B (fix-only) |
| `app/src/main/res/drawable/session_x_bg.xml` | U-R1-C | U-R2-B (fix-only) |
| `app/src/main/res/drawable/session_toast_bg.xml` | U-R1-C | U-R2-B (fix-only) |
| `app/src/main/res/drawable/session_hint_bg.xml` | U-R1-C | U-R2-B (fix-only) |
| `app/src/main/res/drawable/ic_session_close.xml` | U-R1-C | U-R2-B (fix-only) |
| `app/src/main/res/values/themes.xml` | U-R1-C | U-R2-B (fix-only) |
| `app/src/main/res/values/strings.xml` | U-R1-C | U-R2-B |
| `app/src/main/AndroidManifest.xml` | U-R1-C | U-R2-B (fix-only) |
| `app/src/main/java/us/jhangy/textify/ResultActivity.kt` (delete) | U-R1-C | — |
| `app/src/main/res/layout/activity_result.xml` (delete) | U-R1-C | — |
| `README.md` | — | U-R2-B |

All files are Area U per the contract ("`SessionActivity.kt`, `EdgeGlowView.kt`, pill view, toast/hint/X, layouts, `themes.xml`, `strings.xml`, `AndroidManifest.xml`, deletion of `ResultActivity.kt` + `activity_result.xml`, README update"). No file has two owners in the same round. No Area U file is owned by Area S in either round (Area S file list above, from its execution plan).

## Round 1 (parallel; no dependency between units — interfaces are frozen in the plan's Stage 1 skeletons)

### Unit U-R1-A — Edge glow renderer
- Role: implementer (`podium:team-implementer`). Model: **opus** (judgment-heavy graphics: shader composition, software mask, frame loop).
- Plan tasks: Task 3.
- Owned files: `EdgeGlowView.kt` (create).
- Dependencies: none.
- Acceptance (run from `/Users/jhangyu/project/textify`):
  ```bash
  F=app/src/main/java/us/jhangy/textify/EdgeGlowView.kt
  grep -cE 'fun activate\(\)|fun settle\(\)' $F                          # 2
  grep -c 'ComposeShader' $F                                             # >= 1
  grep -cE 'RenderEffect|SDK_INT|setShadowLayer' $F                      # 0
  grep -c 'isHardwareAccelerated' $F                                     # 1
  grep -cE '0xFFA78BFA|0xFFF472B6|0xFFFDBA74|0xFF67E8F9|0xFF818CF8' $F    # 1
  grep -c '0.2f, 0.38f, 0.56f, 0.76f' $F                                 # 1
  grep -cE 'GLOW_IN = 350L|GLOW_SETTLE = 400L|BLOOM = 400f|GLOW_LOOP = 2400f|BREATH = 1200f|RESIDUAL_SPIN = 0.12f|HALO_BLUR_DP = 30f|CORE_BLUR_DP = 7f|CORNER_DP = 36f' $F   # 9
  ```

### Unit U-R1-B — Pill view
- Role: implementer (`podium:team-implementer`). Model: **sonnet**.
- Plan tasks: Task 4.
- Owned files: `PillView.kt` (create).
- Dependencies: none (uses `OcrLine(text: String, box: Rect)` from the contract; it compiles once Area S round 1 lands `Ocr.kt`).
- Acceptance:
  ```bash
  F=app/src/main/java/us/jhangy/textify/PillView.kt
  grep -cE 'var onTap: \(\(pill: PillView, dismissOnly: Boolean\) -> Unit\)\?|var selectionActive: \(\) -> Boolean|var selecting|fun playIn\(delayMs: Long\)|fun playCopied\(\)' $F   # 5
  grep -cE 'setShadowLayer|SDK_INT|setOnClickListener' $F     # 0
  grep -c 'GestureDetector' $F                                 # >= 1
  grep -c 'saveLayerAlpha' $F                                  # 1
  grep -cE 'const val PAD_X_DP = 7f|const val PAD_Y_DP = 3f|BOX_IN = 260L|FLASH = 380L|PRESS_FADE = 140L|SHINE_BAND = 0.48f|SELECTING = 0.45f|GLOW_R_DP = 12f' $F   # 8
  ```

### Unit U-R1-C — Session shell + resources
- Role: implementer (`podium:team-implementer`). Model: **sonnet** (mostly mechanical: XML + shell activity copied from the plan).
- Plan tasks: Task 1, then Task 2 (same unit: the shell needs the layout binding and theme from Task 1).
- Owned files: `SessionActivity.kt` (create), `activity_session.xml` (create), `session_x_bg.xml`, `session_toast_bg.xml`, `session_hint_bg.xml`, `ic_session_close.xml` (create), `themes.xml`, `strings.xml`, `AndroidManifest.xml` (modify), `ResultActivity.kt`, `activity_result.xml` (delete).
- Dependencies: none (the layout references `us.jhangy.textify.EdgeGlowView` by name only; U-R1-A creates it in parallel).
- Acceptance:
  ```bash
  test ! -e app/src/main/java/us/jhangy/textify/ResultActivity.kt && test ! -e app/src/main/res/layout/activity_result.xml; echo "deleted_rc=$?"   # deleted_rc=0
  grep -rl ResultActivity app/src/main/AndroidManifest.xml app/src/main/res        # no output
  grep -c 'Theme.Textify.Session' app/src/main/res/values/themes.xml app/src/main/AndroidManifest.xml   # 1 and 1
  grep -c 'Theme.Textify.Transparent' app/src/main/res/values/themes.xml           # 0
  grep -E 'name="(copy|close)"' app/src/main/res/values/strings.xml                # no output
  grep -cE 'name="(app_name|notif_title|stop|no_text)"' app/src/main/res/values/strings.xml   # 4
  grep -cE 'name="(copied|session_no_text|session_no_text_sub|session_close)"' app/src/main/res/values/strings.xml   # 4
  grep -c 'us.jhangy.textify.EdgeGlowView' app/src/main/res/layout/activity_session.xml   # 1
  grep -cE 'android:id="@\+id/(root|shot|scrim|pills|glow|close|hint|hintOrb|toast|toastOrb|toastText)"' app/src/main/res/layout/activity_session.xml   # 11
  grep -c 'android:screenOrientation' app/src/main/AndroidManifest.xml             # 0
  F=app/src/main/java/us/jhangy/textify/SessionActivity.kt
  grep -cE 'fun start\(ctx: Context, shot: Bitmap\)|fun finishActive\(\)' $F       # 2
  grep -c 'CaptureService.onSessionEnded()' $F                                     # 1
  grep -c 'SDK_INT' $F                                                            # 0
  ```
  Note: `grep -r ResultActivity app/src` still matches `CaptureService.kt` until Area S round 2; that file is not this unit's.

### Round 1 exit
- Lead signs off U-R1-A/B/C on their acceptance outputs (no build gate: `CaptureService.kt` still uses the old `Ocr` signature and `ResultActivity` until Area S round 2 — same statement as the Area S execution plan).
- Lead spot-checks: `EdgeGlowView.kt` against plan Task 3 code; `PillView.kt` `onTouchEvent` passes every event to `super.onTouchEvent`.

## Round 2 (parallel; both depend on round 1 sign-off)

### Unit U-R2-A — Session choreography
- Role: implementer (`podium:team-implementer`). Model: **sonnet** (code is fully specified in the plan; escalate to opus only per the fix-round rule).
- Plan tasks: Task 5.
- Owned files: `SessionActivity.kt` (replace with the Task 5 version).
- Dependencies: U-R1-A (`EdgeGlowView.activate/settle`), U-R1-B (`PillView` API), U-R1-C (binding ids, theme, strings) signed off; Area S `Ocr.kt` with `OcrLine` (Area S round 1).
- Acceptance:
  ```bash
  F=app/src/main/java/us/jhangy/textify/SessionActivity.kt
  grep -cE 'fun start\(ctx: Context, shot: Bitmap\)|fun finishActive\(\)' $F       # 2
  grep -c 'CaptureService.onSessionEnded()' $F                                     # 1
  grep -c 'SDK_INT' $F                                                            # 0
  grep -c 'Ocr.recognize(this, bmp, Prefs.script(this))' $F                        # 1
  grep -c 'customSelectionActionModeCallback' $F                                   # >= 1
  grep -cE 'SCRIM_DELAY = 150L|SCRIM_IN = 300L|SCRIM_OUT = 300L|XIN = 240L|HINT_IN = 300L|TOAST_IN = 220L|TOAST_HOLD = 1500L|TOAST_OUT = 160L|CLOSE_OUT = 300L|BOX_STAGGER = 140f' $F   # 10
  ```

### Unit U-R2-B — README, string cleanup, cross-check + build-fix owner of round-1 files
- Role: reviewer-fixer (`podium:team-implementer`, fresh context, did not write round 1). Model: **sonnet**.
- Plan tasks: Task 6; plus a static cross-check of round-1 files against the plan (Tasks 1, 3, 4 acceptance blocks re-run; code compared with the plan code blocks) and fixing compile errors reported by the build gate in its owned files.
- Owned files: `README.md`, `strings.xml` (Task 6 edits); fix-only: `EdgeGlowView.kt`, `PillView.kt`, `activity_session.xml`, the four `session_*`/`ic_session_close` drawables, `themes.xml`, `AndroidManifest.xml`. Never `SessionActivity.kt` (U-R2-A) or Area S files.
- Dependencies: round 1 signed off. Starts in parallel with U-R2-A on Task 6 + static cross-check; asks the lead for the build gate once U-R2-A and Area S round 2 report ready; fixes compile errors in owned files, reports errors in other files with `file:line` + compiler message.
- Acceptance:
  ```bash
  grep -c ResultActivity README.md                                    # 0
  grep -c SessionActivity README.md                                   # >= 1
  grep -c 'name="no_text"' app/src/main/res/values/strings.xml       # 0
  # after Area S round 2 and U-R2-A are done:
  grep -rn 'ResultActivity\|no_text' app/src                          # no output
  JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug; echo "RC=$?"   # run by the gate-runner, RC=0
  ```

### Round 2 exit (whole feature, contract "Fixed acceptance")
1. Gate-runner (haiku, runs only, does not judge): `cd /Users/jhangyu/project/textify && JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug; echo "RC=$?"` → `RC=0`.
2. `grep -r ResultActivity app/src` → no output.
3. Contract signatures: `grep -cE 'fun start\(ctx: Context, shot: Bitmap\)|fun finishActive\(\)' app/src/main/java/us/jhangy/textify/SessionActivity.kt` → `2` (plus Area S's own checks for `Ocr`/`onSessionEnded`).
4. User runs the spec §Testing on-device checklist. Items to watch for Area U: glow smoothness during activation (software mask per frame; knob `MASK_SCALE`), halo softness vs. mockup (knobs `HALO_BLUR_DP`, `CORE_BLUR_DP`), pill/glyph alignment of selection handles, floating copy menu shown with hidden system bars.

## Fix round (contract: 1)

Build-gate or device-checklist failures in Area U files go to one fresh `sonnet` member per failing file group, same split as round 2 (`SessionActivity.kt` | everything else of Area U), with the failure output pasted verbatim and the previous attempt's trail. Failures in `EdgeGlowView.kt` rendering (blank/blocky/slow glow) go to a fresh `opus` member. Escalate any other file to `opus` only if the same failure survives one fix attempt. If the fix round's budget is used and acceptance still fails, stop and report the failure trail to the user.

## Open user decision (non-blocking)

- Android 13+ shows its own clipboard confirmation; Google recommends removing in-app copy toasts on 13+ (plan Evidence B5). The plan follows the spec (a2 toast on all API levels, one code path). Changing it needs an API-level branch, which the project rule allows only with user approval.
