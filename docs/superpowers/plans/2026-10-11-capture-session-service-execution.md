# Capture Session — Area S Execution Plan

Implementation plan: `docs/superpowers/plans/2026-10-11-capture-session-service-plan.md` (tasks, full code, doc evidence E1–E5).
Contract: `docs/superpowers/plans/2026-10-11-capture-session-contract.md` (budget: Area S = 2 members per round, 2 rounds + 1 fix round).

## Rules for every unit

- First line of every dispatch prompt: `ROLE: WORKER — you may NOT spawn agents, teams, or workflows. If the task needs delegation, STOP and report back to leader.`
- Read first: the implementation plan sections "Doc evidence", "Global Constraints", "File map", and the unit's tasks (spec block + steps).
- Edit only the unit's owned files. Never edit Area U files (`SessionActivity.kt`, `EdgeGlowView.kt`, pill view, toast/hint/X layouts, `themes.xml`, `strings.xml`, `AndroidManifest.xml`, `ResultActivity.kt`, `activity_result.xml`, `README.md`) or `MainActivity.kt`, `Prefs.kt`, `app/build.gradle.kts`.
- Need a cross-area interface change or a new string → STOP and report; do not invent one.
- Do not run gradle. The build gate is run by the lead's gate-runner after both areas finish round 2.
- Git: no `stash` (incl. `stash list`), `reset`, `checkout --`, `clean`, `git add -A`. Commit only if the dispatch prompt authorizes it: `git add <own files>` then `git commit -m … -- <own files>`.
- No sleep/polling. Report to the lead: conclusions with `file:line`, changed files, acceptance pass/fail per command, open items; last line `READY_FOR_SIGNOFF` or `BLOCKED`.

## Ownership matrix

| File | Round 1 | Round 2 |
|---|---|---|
| `app/src/main/java/us/jhangy/textify/Ocr.kt` | S-R1-A | S-R2-B (fix-only) |
| `app/src/main/java/us/jhangy/textify/ScreenGrabber.kt` (new) | S-R1-A | S-R2-B (fix-only) |
| `app/src/main/java/us/jhangy/textify/Bubble.kt` (new) | S-R1-B | S-R2-B (fix-only) |
| `app/src/main/res/drawable/bubble_bg.xml` | S-R1-B | S-R2-B (fix-only) |
| `app/src/main/res/drawable/ic_scan.xml` (new) | S-R1-B | S-R2-B (fix-only) |
| `app/src/main/res/drawable/dismiss_scrim.xml` (new) | S-R1-B | S-R2-B (fix-only) |
| `app/src/main/res/drawable/dismiss_target_bg.xml` (new) | S-R1-B | S-R2-B (fix-only) |
| `app/src/main/res/drawable/dismiss_glow.xml` (new) | S-R1-B | S-R2-B (fix-only) |
| `app/src/main/res/drawable/ic_dismiss_x.xml` (new) | S-R1-B | S-R2-B (fix-only) |
| `app/src/main/res/layout/bubble.xml` | S-R1-B | S-R2-B (fix-only) |
| `app/src/main/res/layout/dismiss_target.xml` (new) | S-R1-B | S-R2-B (fix-only) |
| `app/src/main/java/us/jhangy/textify/CaptureService.kt` | — (untouched) | S-R2-A |

No file has two owners in the same round. All files are Area S per the contract (`CaptureService.kt`, `Ocr.kt`, bubble/dismiss drawables & layouts; `ScreenGrabber.kt` and `Bubble.kt` are the split-out parts of `CaptureService`).

## Round 1 (parallel; no dependency between S-R1-A and S-R1-B)

### Unit S-R1-A — capture pipeline + OCR

- Role: implementer. Model: `sonnet`.
- Plan tasks: Task 1 (Ocr), Task 2 (ScreenGrabber).
- Owned files: `Ocr.kt`, `ScreenGrabber.kt`.
- Dependencies: none. Produces `internal fun Context.realMetrics(): DisplayMetrics` that S-R1-B's `Bubble.kt` calls (signature frozen in the plan; no wait needed).
- Acceptance (run from `/Users/jhangyu/project/textify`; expected values in comments):

```bash
O=app/src/main/java/us/jhangy/textify/Ocr.kt
G=app/src/main/java/us/jhangy/textify/ScreenGrabber.kt
grep -c "data class OcrLine(val text: String, val box: Rect)" $O                                              # 1
grep -c "fun recognize(ctx: Context, bitmap: Bitmap, script: Script, onResult: (List<OcrLine>) -> Unit)" $O  # 1
grep -c "emptyList()" $O                                                                                       # 2
grep -c "boundingBox" $O                                                                                       # 1
grep -c "createVirtualDisplay(" $G                                                                             # 1
grep -c "fun grab(onFrame: (Bitmap?) -> Unit)" $G                                                              # 1
grep -c "internal fun Context.realMetrics(): DisplayMetrics" $G                                                # 1
grep -cE "display\.resize\(|display\.setSurface\(" $G                                                          # 2
grep -c "rewind()" $G                                                                                          # 1
```

### Unit S-R1-B — bubble + dismiss target

- Role: implementer. Model: `sonnet`.
- Plan tasks: Task 3 (resources), Task 4 (Bubble controller).
- Owned files: `Bubble.kt`, `res/drawable/{bubble_bg,ic_scan,dismiss_scrim,dismiss_target_bg,dismiss_glow,ic_dismiss_x}.xml`, `res/layout/{bubble,dismiss_target}.xml`.
- Dependencies: none to start. Uses `Context.realMetrics()` from S-R1-A by its frozen signature.
- Acceptance:

```bash
R=app/src/main/res
B=app/src/main/java/us/jhangy/textify/Bubble.kt
ls $R/drawable/{bubble_bg,ic_scan,dismiss_scrim,dismiss_target_bg,dismiss_glow,ic_dismiss_x}.xml $R/layout/{bubble,dismiss_target}.xml >/dev/null; echo ls=$?   # ls=0
grep -c 'android:id="@+id/bubbleIcon"' $R/layout/bubble.xml                                  # 1
grep -cE '@\+id/(dismissTarget|dismissGlow)' $R/layout/dismiss_target.xml                    # 2
grep -c "#4D4C1D95" $R/drawable/dismiss_scrim.xml                                             # 1
grep -c "#F3EDFF" $R/drawable/bubble_bg.xml                                                   # 1
grep -c "#7C3AED" $R/drawable/ic_scan.xml                                                     # 1
xmllint --noout $R/drawable/{bubble_bg,ic_scan,dismiss_scrim,dismiss_target_bg,dismiss_glow,ic_dismiss_x}.xml $R/layout/{bubble,dismiss_target}.xml; echo xml=$?   # xml=0
grep -c "class Bubble(private val ctx: Context, private val onTap: () -> Unit, private val onDismiss: () -> Unit)" $B   # 1
grep -cE "fun (show|fadeIn|remove)\(\)|fun fadeOut\(onHidden: \(\) -> Unit\)" $B           # 4
for v in 180L 300L 220L 160L 360L 280L 120L 104f; do grep -q "$v" $B || echo MISSING $v; done   # no output
grep -c "PathInterpolator(.34f, 1.56f, .64f, 1f)" $B                                          # 1
grep -c "Build.VERSION" $B                                                                    # 0
```

### Round 1 exit

- Both units `READY_FOR_SIGNOFF`; lead re-runs both acceptance blocks.
- No build gate after round 1: `CaptureService.kt` still calls the old `Ocr` signature and `ResultActivity` until S-R2-A rewrites it, so the module does not compile between rounds. This is expected.

## Round 2 (parallel; both depend on round 1 sign-off)

### Unit S-R2-A — CaptureService wiring

- Role: implementer. Model: `sonnet`.
- Plan task: Task 5.
- Owned files: `CaptureService.kt`.
- Dependencies: S-R1-A and S-R1-B signed off; Area U round 1 delivered `SessionActivity` with `companion object { fun start(ctx: Context, shot: Bitmap); fun finishActive() }` (needed for the build gate, not for writing the file).
- Acceptance:

```bash
C=app/src/main/java/us/jhangy/textify/CaptureService.kt
grep -c "fun onSessionEnded()" $C                                          # 1
grep -cE "ResultActivity|captureOnce|runOcr|createVirtualDisplay|Toast" $C  # 0
grep -c "SessionActivity.start(this, shot)" $C                             # 1
grep -c "SessionActivity.finishActive()" $C                                # 1
awk '/registerCallback/{a=NR} /ScreenGrabber\(this/{b=NR} END{exit !(a && b && a<b)}' $C; echo order=$?   # order=0
grep -rc "createVirtualDisplay(" app/src/main/java | grep -v ":0"          # exactly: app/src/main/java/us/jhangy/textify/ScreenGrabber.kt:1
```

### Unit S-R2-B — cross-check + build-fix owner of round-1 files

- Role: reviewer-fixer (fresh context, did not write round 1). Model: `sonnet`.
- Plan task: Task 6.
- Owned files (fix-only): `Ocr.kt`, `ScreenGrabber.kt`, `Bubble.kt`, and the 8 round-1 resource files. Never `CaptureService.kt` (S-R2-A) or Area U files.
- Dependencies: round 1 signed off. Starts in parallel with S-R2-A with the static review (Task 6 Steps 1–2); asks the lead for the build gate once S-R2-A and Area U round 2 report ready (Task 6 Step 3).
- Acceptance:
  - Round-1 acceptance blocks of S-R1-A and S-R1-B re-run with the expected values.
  - Static review items (a)–(e) of Task 6 Step 2 each answered pass/fail with `file:line`.
  - Gate-runner output (run by the lead, not this unit): `cd /Users/jhangyu/project/textify && JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug; echo RC=$?` → `RC=0`; `grep -r ResultActivity app/src; echo GREP_RC=$?` → `GREP_RC=1`.
  - Compile errors in owned files fixed; errors in other files reported with `file:line` + compiler message.

## Fix round (contract: 1)

Build-gate or device-checklist failures in Area S files go to one fresh `sonnet` member per failing file group (same ownership split as round 2), with the failure output pasted verbatim. Escalate to `opus` only if the same failure survives one fix attempt.
