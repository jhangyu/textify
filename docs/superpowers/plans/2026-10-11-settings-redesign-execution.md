# Settings redesign — execution plan (agent team)

Detailed plan (binding for files, interfaces, steps and unit acceptance): `docs/superpowers/plans/2026-10-11-settings-redesign-plan.md` (cited as **plan**). Where the plan and the outline differ, the plan wins.
Outline (approved shape and risks): `docs/superpowers/plans/2026-10-11-settings-redesign-outline.md`.
Implementation spec (values): `docs/superpowers/specs/2026-10-11-settings-redesign-impl-spec.md` (cited as §n).
This file says only how the lead runs the team. It does not repeat unit steps or acceptance commands; every skeleton points to the plan heading that holds them.

Path shorthands below: `J/` = `app/src/main/java/us/jhangy/textify/`, `R/` = `app/src/main/res/`. Repo root: `/Users/jhangyu/project/textify`.

## 1. Convergence contract (frozen — only the user may change it)

**End state.** The app launches into onboarding or settings per §2/§7, captures with multi-language merged OCR (§6), shows the new icons and bubble glyph (§9), and `assembleDebug testDebugUnitTest` is green on `main` with spec §13.1–§13.6 passing.

**In scope.** The 10 units R1-A … R1-E, R2-A … R2-E with exactly the files of the plan's "File map" (round 1) and the per-unit "Owned files" lines (round 2), mirrored in the roster tables below; the two round handoff docs; gate outputs under `scripts/tmp/verify/`.

**Out of scope.** `ScreenGrabber.kt`, `R/drawable/bubble_bg.xml`, `R/drawable/dismiss_*.xml`, `R/drawable/ic_dismiss_x.xml`, `R/drawable/ic_session_close.xml`, `R/drawable/session_hint_bg.xml`, `R/drawable/session_x_bg.xml`, `R/layout/dismiss_target.xml` (untouched list of the outline); any change to the spec, outline or plan; dark theme; any `SDK_INT` branch outside §11; release/signing/Play Store work; the §13.7 on-device run itself (handed to the user).

**Acceptance criteria** (run from the repo root at the final round-2 commit; "gate" = §3 procedure):
1. (§13.1) Gate output file contains `RC=0` for `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug testDebugUnitTest`.
2. (§13.2) `app/build/test-results/testDebugUnitTest/TEST-us.jhangy.textify.OcrMergeTest.xml` from that gate has `tests="5" failures="0" errors="0" skipped="0"`, and the round-1 IoU mutation (§5) made `iouExactlyHalfKeepsBoth` fail.
3. (§13.3) `grep -rn "Prefs.script(\|setScript(\|CaptureService.stop\|scriptGroup\|startButton\|stopButton\|ic_launcher_bg\|ic_launcher_fg\|ic_launcher_round\|ic_menu_crop\|Theme.Textify.Session\|DayNight\|3F6FFF" app/src README.md` → no output.
4. (§13.4) Plan "Whole-feature acceptance" item 4 loops print nothing (no deleted string name remains; no §10.1 string is unused).
5. (§13.5) `grep -rn "SDK_INT" app/src/main/java` → exactly two lines, both in `MainActivity.kt` (`TIRAMISU`, `UPSIDE_DOWN_CAKE`).
6. (§13.6) Every §9 path-data string is found by `grep -F` in its drawable.
7. Round-1 exit gate output contains `RC=0`, and the plan's "Round-1 compatibility contract" `git diff --stat` check prints nothing.
8. Ownership: for each round, every path in `git diff --name-only <round-start>..<round-end> -- . ':!docs'` belongs to that round's roster table below, and `git diff --name-only <R1_START>..HEAD -- <out-of-scope paths above>` is empty.

§13.7 (on-device checklist) plus the plan's three device-only VERIFY items are copied into the round-2 handoff and handed to the user; they are not team acceptance criteria.

**Round budget.** 2 rounds. Each round: at most 2 review→fix cycles after the first review. Budget exhausted with any criterion failing → the lead stops and reports the failure trail to the user (no third cycle, no third round).

**Parking lot.** Anything found during a round that is not a failing acceptance criterion or an R3 must-ask item (security hole, wrong premise, irreversible risk) goes to the round handoff's parking-lot section; it does not enter the round.

## 2. Rounds, rosters, dispatch prompts

### 2.0 Lead procedure per round
1. `git rev-parse --abbrev-ref HEAD` = `main`; record `ROUND_START=$(git rev-parse HEAD)` in the round handoff doc (round 1: also `R1_START`).
2. Create one task per unit (TaskCreate) and one for the reviewer.
3. Spawn members with `name` = roster name, `team_name` = session team, `subagent_type` = `podium:team-implementer` (implementers) / `podium:team-reviewer` (reviewers), `model` = roster model. No `isolation`, no `fork`.
4. Paste the member's skeleton below, replacing `<ROUND_START>`, `<R1_START>`, `<R1B_SIGNATURES_HASH>` with recorded hashes, and paste the contract (§1 end state + acceptance criteria) **verbatim** above the skeleton.
5. Sign off a unit only after re-running its plan acceptance list yourself; close its task after sign-off.

### 2.1 Common prompt blocks (pasted inside every skeleton where marked)

**[GIT-RULES]** — paste verbatim:
> Shared-tree git rules: no stash (incl. stash list), reset, checkout --, clean, git add -A; commit only own files with `git commit -- <own paths>`; check branch is main before commit; git status --porcelain after. Stage with `git add <own paths>` only. Deletions: `git rm <path>` on your own paths, committed with the same pathspec. Never push. Commit message ends with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

**[REPORT]** — paste verbatim:
> Report via SendMessage to `team-lead` (fallback `main` if team-lead is unreachable). Only: (1) conclusions with `file:line`; (2) list of changed files; (3) each acceptance item pass/fail with the actual command output (counts / empty); (4) uncertain or unfinished items, stated explicitly. No full files, no diffs; any artefact over 30 lines goes to a file and you report its path. No invented code names. Last line exactly `READY_FOR_SIGNOFF` or `BLOCKED`. Do not mark your task completed; the lead signs off.

**[COMMON-REDLINES]** — paste verbatim:
> Follow the plan's "Global Constraints" (and in round 2 the "Round-2 rules"). Edit only your owned files. Never edit the spec, outline, plan, this execution plan, or another member's files. Code only against the plan's "Frozen interfaces" tables; a missing or disagreeing interface, a string you do not own, or a value neither plan nor spec gives → STOP and report `BLOCKED` with `file:line` and reason; do not invent or adapt silently. No `Build.VERSION.SDK_INT` outside the two §11 branches. Do not run Gradle (the round reviewer runs the gate). No sleep, no polling loops. Third-party API: check the version in `app/build.gradle.kts` before writing a call and use the context7 docs for that version. Clauses are verified in code/environment, not inferred; if the code contradicts the lead, report it.
> When your last commit is in, send `SETTLED <your name> <commit hash>` to `team-lead`, then make **zero writes** to the working tree until the lead says the gate window is closed.

### 2.2 Round 1 — foundations (build green at exit)

| name | unit | model | owned files (exact, from plan "File map" + unit "Files") |
|---|---|---|---|
| `r1-impl-a-sonnet` | R1-A icons + bubble glyph | sonnet | create `R/drawable/ic_launcher_background.xml`, `R/drawable/ic_launcher_foreground.xml`, `R/drawable/ic_launcher_monochrome.xml`, `R/drawable/avd_scan_in.xml`, `R/drawable/ic_stat_textify.xml`, `R/drawable/ic_session_settings.xml`, `R/drawable/ic_back.xml`, `R/drawable/ic_check.xml`, `R/interpolator/emphasized.xml`; modify `R/mipmap-anydpi-v26/ic_launcher.xml`, `R/drawable/ic_scan.xml`, `R/layout/bubble.xml`, `J/Bubble.kt`; delete `R/drawable/ic_launcher_bg.xml`, `R/drawable/ic_launcher_fg.xml`, `R/mipmap-anydpi-v26/ic_launcher_round.xml` |
| `r1-impl-b-opus` | R1-B Halo primitives | opus | create `J/Glow.kt`; modify `J/EdgeGlowView.kt`, `J/PillView.kt` |
| `r1-impl-c-opus` | R1-C capture demo + step track | opus | create `J/CaptureDemoView.kt`, `J/StepTrackView.kt` |
| `r1-impl-d-opus` | R1-D language controls | opus | create `J/LanguagePreviewView.kt`, `J/LanguageToggleView.kt`, `J/SpeedLanesView.kt` |
| `r1-impl-e-opus` | R1-E OCR merge + first unit test | opus | create `J/OcrMerge.kt`, `app/src/test/java/us/jhangy/textify/OcrMergeTest.kt`; modify `app/build.gradle.kts` |
| `round-1-reviewer-opus` | R1 review + gate | opus | none in `app/`; writes only `scripts/tmp/verify/*`, `scripts/tmp/mutation/*`, `docs/logs/settings-redesign/round-1-review.md` |

**Dispatch order.**
1. Spawn `r1-impl-b-opus`, `r1-impl-a-sonnet`, `r1-impl-e-opus` and `round-1-reviewer-opus` in one batch. R1-B's ticket 1 ends with a commit that contains every `Glow.kt` / `EdgeGlowView.kt` row of plan "Frozen interfaces (round 1)" and a `SIGNATURES <hash>` message to the lead.
2. On `SIGNATURES <hash>`: spawn `r1-impl-c-opus` and `r1-impl-d-opus` with that hash.
3. The reviewer reviews increments as commits land; it starts the gate only when the lead forwards all five `SETTLED` messages.

Model choice: R1-A is byte-for-byte resource copying (plan: mechanical) → sonnet; R1-B/C/D/E are plan "judgment" units (graphics with timing tables and shared API; numeric merge with an exact boundary) → opus.

#### Skeleton `r1-impl-a-sonnet`
```
ROLE: WORKER — you may NOT spawn agents, teams, or workflows. If the task needs delegation, STOP and report back to leader.

You are r1-impl-a-sonnet, unit R1-A (icons and bubble glyph) of the Textify settings redesign, repo /Users/jhangyu/project/textify, branch main. Round start commit: <ROUND_START>.

GOAL + MOTIVATION
Create every VectorDrawable of spec §9, the emphasized interpolator and the bubble glyph entrance, and delete the old launcher art. Later units use these names (ic_stat_textify, ic_session_settings, ic_back, ic_check, ic_scan, @interpolator/emphasized), so names and path data must be exact. The old app must keep building.

READ FIRST
- Plan: "Global Constraints", "File map", and "Unit R1-A" (Files, Interfaces, Behavior, Constraints, Acceptance criteria, Steps 1–12).
- Spec §0, §9.
- The convergence contract pasted above this prompt.

OWNED FILES (only these)
create app/src/main/res/drawable/{ic_launcher_background,ic_launcher_foreground,ic_launcher_monochrome,avd_scan_in,ic_stat_textify,ic_session_settings,ic_back,ic_check}.xml, app/src/main/res/interpolator/emphasized.xml;
modify app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml, app/src/main/res/drawable/ic_scan.xml, app/src/main/res/layout/bubble.xml, app/src/main/java/us/jhangy/textify/Bubble.kt;
delete app/src/main/res/drawable/ic_launcher_bg.xml, app/src/main/res/drawable/ic_launcher_fg.xml, app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml.
Tickets (plan): T1 = Steps 1–6, 9 (icons + deletions); T2 = Steps 7, 8, 10, 11 (bubble glyph + entrance). Commit after each.

ACCEPTANCE (mechanical)
Every checkbox of plan "Unit R1-A" → "Acceptance criteria", run exactly as written, except the round-1 build gate (run by round-1-reviewer-opus). Plus: xmllint --noout on every owned XML file → exit 0.

RED LINES
[COMMON-REDLINES]
[GIT-RULES]
Do not touch AndroidManifest.xml (roundIcon is R2-E's) or any string resource.

REPORT
[REPORT]
```

#### Skeleton `r1-impl-b-opus`
```
ROLE: WORKER — you may NOT spawn agents, teams, or workflows. If the task needs delegation, STOP and report back to leader.

You are r1-impl-b-opus, unit R1-B (Halo primitives) of the Textify settings redesign, repo /Users/jhangyu/project/textify, branch main. Round start commit: <ROUND_START>. You are on the critical path.

GOAL + MOTIVATION
Build one shared implementation of CSS-like glows, CSS angle gradients, Halo pills, mist page backgrounds and parameterised halos so the demo, language, onboarding and settings code never forks drawing code. The session must render exactly as before: HaloRecipe.SESSION reproduces today's EdgeGlowView constants, and SessionActivity (not yours) keeps compiling unchanged (plan "Round-1 compatibility contract").

READ FIRST
- Plan: "Global Constraints", "Frozen interfaces (round 1)" (Glow.kt, EdgeGlowView.kt, PillView.kt rows — these win over spec §8 code blocks), "Round-1 compatibility contract", and "Unit R1-B".
- Spec §0, §8.1, §8.3; current J/EdgeGlowView.kt, J/PillView.kt, J/SessionActivity.kt (read only).
- The convergence contract pasted above this prompt.

OWNED FILES (only these)
create app/src/main/java/us/jhangy/textify/Glow.kt; modify EdgeGlowView.kt, PillView.kt (same directory).

TICKETS (plan)
T1 = Steps 1–2, 4, 5: Glow.kt + EdgeGlowView.kt as the FIRST COMMIT, containing every frozen Glow.kt / EdgeGlowView.kt signature of plan "Frozen interfaces (round 1)" — withAlpha, Context.dpToPx, GLOW_SCALE, glowPad, glowBitmap, PillPainter, CssGradientDrawable (+ solid), GlowDrawable, MistSpot, MistBackground, HaloStyle (MIST, ONBOARDING_SPOTS, SETTINGS_SPOTS, page, cta, card, chip), HaloRecipe (+ SESSION, ONBOARDING_MINI, SETTINGS_MINI, PREVIEW_EDGE), EdgeGlowView recipe/activate/settle/fadeOut/showSettled/clear. Bodies complete or minimal-but-compiling, never placeholder-throwing bodies. Immediately after that commit SendMessage team-lead: "SIGNATURES <commit hash>". R1-C and R1-D wait for it.
T2 = Steps 3 + 6: PillView on PillPainter, second commit. T1 signatures must not change; if one must, STOP and report.

ACCEPTANCE (mechanical)
Every checkbox of plan "Unit R1-B" → "Acceptance criteria", run exactly as written (the first-commit signature loop is run against the T1 commit), except the round-1 build gate (run by round-1-reviewer-opus).

RED LINES
[COMMON-REDLINES]
[GIT-RULES]
Never edit SessionActivity.kt or activity_session.xml.

REPORT
[REPORT]
```

#### Skeleton `r1-impl-c-opus`
```
ROLE: WORKER — you may NOT spawn agents, teams, or workflows. If the task needs delegation, STOP and report back to leader.

You are r1-impl-c-opus, unit R1-C (capture demo + step track) of the Textify settings redesign, repo /Users/jhangyu/project/textify, branch main. Round start commit: <ROUND_START>.

GOAL + MOTIVATION
Build the looping miniature capture (CaptureDemoView, §8.2) and the 2-node progress track (StepTrackView, §8.4). Onboarding (R2-B) and settings (R2-C) embed them in round 2, so the API is frozen in plan "Frozen interfaces (round 1)" (DemoSpec + ONBOARDING/SETTINGS, CaptureDemoView.configure/setGranted/pause/resume/flash, StepTrackView.State/setLabels/setState) and the XML contract is frozen in plan "XML contracts of the round-1 views": children are found by android:tag — demoScreen, avatar, line, line_me, demoChip — never by id. The views take no string resources.

READ FIRST
- Plan: "Global Constraints", "Frozen interfaces (round 1)", "XML contracts of the round-1 views", "Unit R1-C".
- Spec §0, §8.1–§8.4.
- R1-B signatures commit <R1B_SIGNATURES_HASH>: git show <R1B_SIGNATURES_HASH> -- app/src/main/java/us/jhangy/textify/Glow.kt app/src/main/java/us/jhangy/textify/EdgeGlowView.kt. Code only against those signatures; bodies may still be in progress.
- Drawables bubble_bg, ic_scan, ic_check are used by name only.
- The convergence contract pasted above this prompt.

OWNED FILES (only these)
create app/src/main/java/us/jhangy/textify/CaptureDemoView.kt, StepTrackView.kt (same directory).
Tickets (plan): T1 CaptureDemoView, T2 StepTrackView. Commit after each.

ACCEPTANCE (mechanical)
Every checkbox of plan "Unit R1-C" → "Acceptance criteria", run exactly as written, except the round-1 build gate (run by round-1-reviewer-opus). Plus: every R1-B symbol you call exists in <R1B_SIGNATURES_HASH> (symbol → file:line).

RED LINES
[COMMON-REDLINES]
[GIT-RULES]
Do not edit Glow.kt / EdgeGlowView.kt / PillView.kt; a missing or wrong R1-B signature → BLOCKED naming the symbol.

REPORT
[REPORT]
```

#### Skeleton `r1-impl-d-opus`
```
ROLE: WORKER — you may NOT spawn agents, teams, or workflows. If the task needs delegation, STOP and report back to leader.

You are r1-impl-d-opus, unit R1-D (language controls) of the Textify settings redesign, repo /Users/jhangyu/project/textify, branch main. Round start commit: <ROUND_START>.

GOAL + MOTIVATION
Build the preview, toggles and speed lanes of the settings language card (§7.4, §8.5–§8.7). R2-C wires them in round 2, so the API is frozen in plan "Frozen interfaces (round 1)" (LanguagePreviewView.setScripts(scripts, animate), LanguageToggleView.checked/setChecked(value, animate)/reject, SpeedLanesView.setLabels(zh, ja, ko)/setScripts(scripts, animate)) and the XML contract in plan "XML contracts of the round-1 views": children found by android:tag — previewShot, avatar, zh, en, ja, ko, text, tag (preview); glyph, sub (toggle). The views use the existing Script enum (unchanged) and take no string resources.

READ FIRST
- Plan: "Global Constraints", "Frozen interfaces (round 1)", "XML contracts of the round-1 views", "Unit R1-D".
- Spec §0, §7.4, §8.3, §8.5–§8.7; J/Prefs.kt (Script enum, read only).
- R1-B signatures commit <R1B_SIGNATURES_HASH>. Code only against those signatures.
- The convergence contract pasted above this prompt.

OWNED FILES (only these)
create app/src/main/java/us/jhangy/textify/LanguagePreviewView.kt, LanguageToggleView.kt, SpeedLanesView.kt (same directory).
Tickets (plan): T1 LanguagePreviewView + LanguageToggleView, T2 SpeedLanesView. Commit after each.

ACCEPTANCE (mechanical)
Every checkbox of plan "Unit R1-D" → "Acceptance criteria", run exactly as written, except the round-1 build gate (run by round-1-reviewer-opus). Plus: every R1-B symbol you call exists in <R1B_SIGNATURES_HASH> (symbol → file:line).

RED LINES
[COMMON-REDLINES]
[GIT-RULES]
Do not edit Prefs.kt (R2-A, round 2) or any R1-B file.

REPORT
[REPORT]
```

#### Skeleton `r1-impl-e-opus`
```
ROLE: WORKER — you may NOT spawn agents, teams, or workflows. If the task needs delegation, STOP and report back to leader.

You are r1-impl-e-opus, unit R1-E (OCR merge + first unit test) of the Textify settings redesign, repo /Users/jhangyu/project/textify, branch main. Round start commit: <ROUND_START>.

GOAL + MOTIVATION
Implement the multi-script merge of §6.2 as pure Kotlin (no android.*) so R2-A can merge the parallel recognizers' lines, and add the project's first JVM unit test. Frozen (plan "Frozen interfaces (round 1)"): internal data class OcrCandidate(text, left, top, right, bottom, confidence), internal fun iou(a, b): Float, internal fun mergeOcr(candidates): List<OcrCandidate>; testImplementation("junit:junit:4.13.2"). Ocr.kt and its callers are not touched in round 1.

READ FIRST
- Plan: "Global Constraints", "Frozen interfaces (round 1)", "Unit R1-E".
- Spec §6, §13.2; app/build.gradle.kts.
- The convergence contract pasted above this prompt.

OWNED FILES (only these)
create app/src/main/java/us/jhangy/textify/OcrMerge.kt, app/src/test/java/us/jhangy/textify/OcrMergeTest.kt; modify app/build.gradle.kts.
Tickets (plan): 1. Commit once.

ACCEPTANCE (mechanical)
Every checkbox of plan "Unit R1-E" → "Acceptance criteria"; you run the grep item; the test run and the `> IOU_LIMIT` → `>= IOU_LIMIT` red check are run by round-1-reviewer-opus. Plus: list the five test method names with file:line, and cite the single `> IOU_LIMIT` comparison (file:line) so the mutation hits exactly one place.

RED LINES
[COMMON-REDLINES]
[GIT-RULES]
Do not edit Ocr.kt or Prefs.kt (R2-A, round 2). Do not change the junit version.

REPORT
[REPORT]
```

#### Skeleton `round-1-reviewer-opus`
```
ROLE: WORKER — you may NOT spawn agents, teams, or workflows. If the task needs delegation, STOP and report back to leader.

You are round-1-reviewer-opus, reviewer and gate runner of round 1 of the Textify settings redesign, repo /Users/jhangyu/project/textify, branch main. Round start commit: <ROUND_START>.

GOAL + MOTIVATION
Independently verify R1-A…R1-E against the plan and spec §6.2, §8, §9, and run the round-1 gate, so round 2 starts from a green, correctly owned foundation. You did not write any of it.

READ FIRST
- This execution plan §3 (gate procedure) and §5 (review scope, round 1) — follow them exactly.
- Plan: "Frozen interfaces (round 1)", "XML contracts of the round-1 views", "Round-1 compatibility contract", "File map", units R1-A…R1-E, "R1 reviewer".
- Spec §6.2, §8, §9.
- The convergence contract pasted above this prompt.

WORK
1. Static review per §5 as commits land (no gate window needed).
2. When team-lead forwards all five SETTLED messages: run the gate per §3, then the OcrMerge mutation per §5.
3. Findings → docs/logs/settings-redesign/round-1-review.md (each: severity blocker|should-fix|nit, file:line, criterion or spec §, owning member, evidence). Do not fix code.

ACCEPTANCE (mechanical)
1. Plan "R1 reviewer" items 1–6 each pass/fail with actual output (every unit acceptance list re-run).
2. Gate output file scripts/tmp/verify/<ts>.txt with START, END and RC= lines; OcrMergeTest XML shows 5 testcases.
3. Mutation output file exists with iouExactlyHalfKeepsBoth failing; OcrMerge.kt restored from the copy and `git diff --quiet -- app/src/main/java/us/jhangy/textify/OcrMerge.kt; echo $?` → 0.
4. Ownership and compatibility outputs per §5.

RED LINES
Never edit files under app/ except the transient mutation of OcrMerge.kt described in §5 (restore only from your copy). Write only scripts/tmp/verify/*, scripts/tmp/mutation/*, docs/logs/settings-redesign/round-1-review.md (via Bash; you have no Write tool). Do not commit (the lead commits docs/logs).
[GIT-RULES]
No sleep, no polling loops: interleave liveness checks with review work as §3 describes.

REPORT
[REPORT]
Additionally report: gate output path + RC line, mutation output path + RC, blocker list with owners.
```

### 2.3 Round 2 — switch-over (build + tests green at exit)

Starts only after the round-1 handoff (§7) is committed with criterion 7 passing.

| name | unit | model | owned files (exact, from plan unit "Owned files") |
|---|---|---|---|
| `r2-impl-a-opus` | R2-A service + Ocr/Prefs switch | opus | `J/CaptureService.kt`, `J/Ocr.kt`, `J/Prefs.kt` |
| `r2-impl-b-opus` | R2-B launch flow + onboarding | opus | `J/MainActivity.kt` (rewrite; includes `WrapLayout`), `R/layout/activity_main.xml` (rewrite) |
| `r2-impl-c-opus` | R2-C settings screen | opus | `J/SettingsActivity.kt` (new), `R/layout/activity_settings.xml` (new) |
| `r2-impl-d-sonnet` | R2-D session settings button | sonnet | `J/SessionActivity.kt`, `R/layout/activity_session.xml` |
| `r2-impl-e-sonnet` | R2-E resources, manifest, README | sonnet | `R/values/strings.xml`, `R/values/themes.xml`, `app/src/main/AndroidManifest.xml`, `README.md` |
| `round-2-reviewer-opus` | R2 review + gate | opus | none in `app/`; writes only `scripts/tmp/verify/*`, `docs/logs/settings-redesign/round-2-review.md` |

No round-2 unit owns `app/build.gradle.kts` or any round-1 file (plan "Merge resolutions").

**Dispatch order.** All six members in one batch. Every cross-unit name is frozen in plan "Frozen interfaces (round 2)" (produced in round 2 + consumed from round 1, incl. the string-name list); nobody waits for another unit's commit.

Model choice: R2-A, R2-B, R2-C are plan "judgment" units (service lifecycle + parallel recognizers; launch state machine + two §11 branches; two cards + settings-visibility contract) → opus; R2-D and R2-E are plan "mechanical" units → sonnet.

#### Skeleton `r2-impl-a-opus`
```
ROLE: WORKER — you may NOT spawn agents, teams, or workflows. If the task needs delegation, STOP and report back to leader.

You are r2-impl-a-opus, unit R2-A (service + Ocr/Prefs switch) of the Textify settings redesign, repo /Users/jhangyu/project/textify, branch main. Round start commit: <ROUND_START>.

GOAL + MOTIVATION
Switch the engine to multi-select scripts with parallel recognizers merged by R1-E's mergeOcr, and give CaptureService the API the new screens need (plan "Frozen interfaces (round 2)", rows owned by R2-A: Prefs.scripts/setScripts, Ocr.recognize(ctx, bitmap, scripts: Set<Script>, onResult), OcrLine unchanged, CaptureService.isRunning/setSettingsVisible/onSessionEnded/start(ctx, code, data); stop removed). Screen-capture consent moves to MainActivity (R2-B). Round-2 units switch together, so the module will not build until all five are in; the reviewer runs the gate.

READ FIRST
- Plan: "Global Constraints", "Round-2 rules", "Frozen interfaces (round 2)", "R2-A Service + Ocr/Prefs switch" (Steps A1.1, A1.2, A2.1).
- Spec §0, §5, §6, §10.1, §11; J/OcrMerge.kt (read only).
- docs/logs/settings-redesign/round-1-handoff.md (known limitations).
- The convergence contract pasted above this prompt.

OWNED FILES (only these)
app/src/main/java/us/jhangy/textify/CaptureService.kt, Ocr.kt, Prefs.kt (same directory).
Tickets (plan): A1 = Prefs + Ocr; A2 = CaptureService. Commit after each.

ACCEPTANCE (mechanical)
Every checkbox of plan "R2-A acceptance", run exactly as written from app/src/main (plan path convention), red before the change and green after, except "round-2 gate green" (run by round-2-reviewer-opus).

RED LINES
[COMMON-REDLINES]
[GIT-RULES]
Do not edit OcrMerge.kt / OcrMergeTest.kt / app/build.gradle.kts; a needed change → BLOCKED. Strings are R2-E's: reference only plan-listed names.

REPORT
[REPORT]
```

#### Skeleton `r2-impl-b-opus`
```
ROLE: WORKER — you may NOT spawn agents, teams, or workflows. If the task needs delegation, STOP and report back to leader.

You are r2-impl-b-opus, unit R2-B (launch flow + onboarding) of the Textify settings redesign, repo /Users/jhangyu/project/textify, branch main. Round start commit: <ROUND_START>.

GOAL + MOTIVATION
Rewrite MainActivity and activity_main.xml into the launch flow of §2 and the onboarding screen of §3 (looping demo, step track, overlay grant, screen-capture consent, text toasts). The old Start/Stop/script picker disappears. You consume R1-B/R1-C views (CaptureDemoView children by android:tag), R2-A CaptureService.isRunning/start, R2-C SettingsActivity.start, R2-E strings + Theme.Textify.Translucent, all by the frozen names in plan "Frozen interfaces (round 2)"; they land in parallel and the build is checked only at the round-2 gate.

READ FIRST
- Plan: "Global Constraints", "Round-2 rules", "Frozen interfaces (round 2)", "R2-B Launch flow + onboarding" (Steps B1.1, B1.2, B2.1).
- Spec §0, §2, §3, §10.1, §11.
- docs/logs/settings-redesign/round-1-handoff.md.
- The convergence contract pasted above this prompt.

OWNED FILES (only these)
app/src/main/java/us/jhangy/textify/MainActivity.kt (rewrite; WrapLayout lives here), app/src/main/res/layout/activity_main.xml (rewrite).
Tickets (plan): B1 = layout + router/consent; B2 = onboarding bodies. Commit after each.

ACCEPTANCE (mechanical)
Every checkbox of plan "R2-B acceptance", run exactly as written from app/src/main, red before and green after, except "round-2 gate green" (run by round-2-reviewer-opus). Plus: xmllint --noout res/layout/activity_main.xml → exit 0.

RED LINES
[COMMON-REDLINES]
[GIT-RULES]

REPORT
[REPORT]
```

#### Skeleton `r2-impl-c-opus`
```
ROLE: WORKER — you may NOT spawn agents, teams, or workflows. If the task needs delegation, STOP and report back to leader.

You are r2-impl-c-opus, unit R2-C (settings screen) of the Textify settings redesign, repo /Users/jhangyu/project/textify, branch main. Round start commit: <ROUND_START>.

GOAL + MOTIVATION
Build SettingsActivity + activity_settings.xml (§7): permission card and language card with their transitions, bubble hidden while settings is visible, last-language guard. Frozen output: SettingsActivity.start(ctx: Context) (called by R2-B and R2-D). You consume R1-A ic_back, R1-B/C/D views (children by android:tag per plan "XML contracts of the round-1 views"), R2-A Prefs + CaptureService.setSettingsVisible, R2-E strings/theme, all by the names in plan "Frozen interfaces (round 2)".

READ FIRST
- Plan: "Global Constraints", "Round-2 rules", "Frozen interfaces (round 2)", "R2-C Settings screen" (Steps C1.1–C1.3, C2.1, C2.2).
- Spec §0, §7, §10.1.
- docs/logs/settings-redesign/round-1-handoff.md.
- The convergence contract pasted above this prompt.

OWNED FILES (only these)
app/src/main/java/us/jhangy/textify/SettingsActivity.kt (new), app/src/main/res/layout/activity_settings.xml (new).
Tickets (plan): C1 = frame + permission card; C2 = language card. Commit after each. If you reach the baton limit before C2 is done, hand off per execution plan §6; both files move to the successor together.

ACCEPTANCE (mechanical)
Every checkbox of plan "R2-C acceptance", run exactly as written from app/src/main, red before and green after, except "round-2 gate green" (run by round-2-reviewer-opus). Plus: xmllint --noout res/layout/activity_settings.xml → exit 0.

RED LINES
[COMMON-REDLINES]
[GIT-RULES]

REPORT
[REPORT]
```

#### Skeleton `r2-impl-d-sonnet`
```
ROLE: WORKER — you may NOT spawn agents, teams, or workflows. If the task needs delegation, STOP and report back to leader.

You are r2-impl-d-sonnet, unit R2-D (session settings button) of the Textify settings redesign, repo /Users/jhangyu/project/textify, branch main. Round start commit: <ROUND_START>.

GOAL + MOTIVATION
Add the settings button to the capture session (§4) and switch SessionActivity to the multi-script OCR API. The button hands off to settings in a fixed order so the bubble stays hidden: CaptureService.setSettingsVisible(true) → SettingsActivity.start(this) → endSession() → finish(). You consume R1-A ic_session_settings, R1-B withAlpha, R2-A Ocr.recognize(Set)/Prefs.scripts/CaptureService.setSettingsVisible, R2-C SettingsActivity.start, R2-E strings, all by the names in plan "Frozen interfaces (round 2)".

READ FIRST
- Plan: "Global Constraints", "Round-2 rules", "Frozen interfaces (round 2)", "R2-D Session settings button" (Steps D.1, D.2).
- Spec §0, §4, §6.1, §10.1.
- The convergence contract pasted above this prompt.

OWNED FILES (only these)
app/src/main/java/us/jhangy/textify/SessionActivity.kt, app/src/main/res/layout/activity_session.xml.
Tickets (plan): 1. Commit once.

ACCEPTANCE (mechanical)
Every checkbox of plan "R2-D acceptance", run exactly as written from app/src/main, red before and green after, except "round-2 gate green" (run by round-2-reviewer-opus). Plus: xmllint --noout res/layout/activity_session.xml → exit 0.

RED LINES
[COMMON-REDLINES]
[GIT-RULES]

REPORT
[REPORT]
```

#### Skeleton `r2-impl-e-sonnet`
```
ROLE: WORKER — you may NOT spawn agents, teams, or workflows. If the task needs delegation, STOP and report back to leader.

You are r2-impl-e-sonnet, unit R2-E (resources, manifest, README) of the Textify settings redesign, repo /Users/jhangyu/project/textify, branch main. Round start commit: <ROUND_START>.

GOAL + MOTIVATION
Replace strings.xml with the complete §10.1 content, themes.xml per §10.2, the manifest per §10.3 and README per §10.4. Every other round-2 unit references these names; old names must be gone entirely (unreleased project, no compatibility).

READ FIRST
- Plan: "Global Constraints", "Round-2 rules", "Frozen interfaces (round 2)" (string-name list, themes, manifest), "R2-E Resources, manifest, README" (Steps E.1–E.4).
- Spec §0, §10, §13.3, §13.4.
- The convergence contract pasted above this prompt.

OWNED FILES (only these)
app/src/main/res/values/strings.xml, app/src/main/res/values/themes.xml, app/src/main/AndroidManifest.xml, README.md.
Tickets (plan): 1. Commit once.

ACCEPTANCE (mechanical)
Every checkbox of plan "R2-E acceptance", run exactly as written from app/src/main (the §13.3 item and the README item use repo-root paths), red before and green after, except "round-2 gate green" (run by round-2-reviewer-opus). The §13.3 item needs all R2 units; report its output as observed. Plus: xmllint --noout on the three XML files → exit 0.

RED LINES
[COMMON-REDLINES]
[GIT-RULES]
Code references to strings are other units' work; do not edit .kt or layout files — report unreferenced names instead.

REPORT
[REPORT]
```

#### Skeleton `round-2-reviewer-opus`
```
ROLE: WORKER — you may NOT spawn agents, teams, or workflows. If the task needs delegation, STOP and report back to leader.

You are round-2-reviewer-opus, reviewer and gate runner of round 2 of the Textify settings redesign, repo /Users/jhangyu/project/textify, branch main. Round start commit: <ROUND_START>. Round-1 start commit: <R1_START>.

GOAL + MOTIVATION
Prove the whole feature meets spec §13.1–§13.6 and the convergence contract, check round-2 cross-unit interfaces against plan "Frozen interfaces (round 2)", and produce the §13.7 checklist for the user. You did not write any of it.

READ FIRST
- This execution plan §1 (contract), §3 (gate), §4 (convergence), §5 (review scope, round 2) — follow them exactly.
- Plan: "Round-2 rules", "Frozen interfaces (round 2)", units R2-A…R2-E, "R2 reviewer" (routing table, "Whole-feature acceptance" items 1–9, "Hand-off to the user").
- Spec §4, §5, §6, §7.1, §10, §11, §13.
- docs/logs/settings-redesign/round-1-handoff.md.

WORK
1. Static review per §5 as commits land.
2. When team-lead forwards all five SETTLED messages: run the gate per §3; then plan "Whole-feature acceptance" items 1–9.
3. Findings → docs/logs/settings-redesign/round-2-review.md (severity, file:line, criterion or spec §, owning member per the §4 routing table, compiler/test message verbatim). Do not fix code.

ACCEPTANCE (mechanical)
1. Contract criteria 1–6 and 8 each pass/fail with actual output.
2. Plan "Whole-feature acceptance" items 1–9 and every R2-A…R2-E acceptance list re-run, each pass/fail.
3. Gate output file with START, END, RC= lines.
4. Each failure assigned to exactly one owning member (§4 routing table).

RED LINES
Never edit files under app/ or README.md. Write only scripts/tmp/verify/*, docs/logs/settings-redesign/round-2-review.md (via Bash; you have no Write tool). Do not commit (the lead commits docs/logs).
[GIT-RULES]
No sleep, no polling loops: interleave liveness checks with review work as §3 describes.

REPORT
[REPORT]
Additionally report: gate output path + RC line, criteria table, blocker list with owners.
```

## 3. Gate procedure (both rounds; run by the round reviewer)

1. **Open the window.** The lead forwards five `SETTLED <member> <hash>` messages to the reviewer and tells all writers "gate window open — zero writes". From then until the lead announces "gate window closed", no member writes anything in the working tree (gitignored paths included), except the reviewer's own files under `scripts/tmp/` and `docs/logs/settings-redesign/`.
2. **Pre-check.** `git rev-parse --abbrev-ref HEAD` = `main`; `git status --porcelain` empty apart from `scripts/tmp/`; record `GATE_HEAD=$(git rev-parse HEAD)`.
3. **Run detached** (Gradle exceeds the 30 s foreground limit; never run it in the foreground, never pipe it):
   ```bash
   cd /Users/jhangyu/project/textify
   mkdir -p scripts/tmp/verify
   TS=$(date +%Y%m%d-%H%M%S); OUT=scripts/tmp/verify/$TS.txt
   nohup sh -c 'echo "START $(date -u +%FT%TZ) HEAD $(git rev-parse HEAD)"; JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug testDebugUnitTest; echo "RC=$?"; echo "END $(date -u +%FT%TZ)"' > "$OUT" 2>&1 &
   echo "$OUT"
   ```
   `RC=$?` is the line immediately after the Gradle command inside the same shell; there is no pipe between them.
4. **First liveness check within 5 minutes of launch.** Alive = both: (a) `wc -c "$OUT"` grew since launch, and (b) the log already shows a non-trivial step (`> Task :app:` line or `Configure project`). Either missing → dead: `pgrep -fl "gradlew assembleDebug"`, report the log tail to the lead as `BLOCKED`. No sleep and no polling loop: launch, do static review items, and run the check between items so it falls inside the 5-minute window.
5. **Completion.** Finished only when `grep -n "^RC=" "$OUT"` hits and `pgrep -f "gradlew assembleDebug testDebugUnitTest"` returns nothing. A harness completion notice is not evidence. Before calling the log truncated, re-read it and compare size.
6. **Evidence.** Report `$OUT`, the `START … HEAD` line, the `RC=` line, and for failures the first compiler/test error lines verbatim. `scripts/tmp/` is gitignored; the lead copies the RC and HEAD lines into the round handoff.
7. **Close the window.** Reviewer reports; lead announces "gate window closed". Writers resume only after that message.

## 4. Convergence and fix cycles

- Round-2 units cannot build alone (they switch shared interfaces together). Per-unit checks are greps; the build is checked only at the round-2 gate.
- Routing (plan "R2 reviewer" → "Round-2 convergence gate" item 3): each error goes to the member that owns the file named in the error (roster table of the round), with the reviewer's error lines pasted verbatim and the previous attempts' failure trail. A frozen-interface mismatch is fixed by the side that deviates from the plan's table; the table is never edited during the round.
- A round-2 error in a round-1 file is not fixable in round 2: the lead decides; an interface mismatch there is `BLOCKED` and reported to the user.
- Round 1: errors route to the owner of the failing file per the round-1 roster (plan "R1 reviewer", last line).
- Fix cycle = owners fix and commit → `SETTLED` → reviewer re-runs the gate (§3) and the affected checks. **At most 2 fix cycles per round.** Still failing after cycle 2 → the lead stops, writes the failure trail (each gate output path, errors per cycle, who fixed what, rejected hypotheses) into the round handoff, and reports to the user. No third cycle.
- Same failure surviving one fix by a sonnet member → the next fix goes to an opus member with the full trail.
- Round 1 must reach criterion 7 before round 2 starts.

## 5. Reviewer scope

Dimensions both rounds: **spec conformance** (values and signatures against the plan tables and cited spec §§), **architecture** (one implementation per shared concern, no forked drawing code, frozen signatures unchanged, android:tag XML contracts honoured, no extra abstraction), **techdebt** (dead code or old names left behind, `SDK_INT` outside §11, compatibility shims beyond the plan's round-1 compatibility contract, placeholder markers).

**Ownership check (both rounds):**
```bash
git diff --name-only <ROUND_START>..HEAD -- . ':!docs' > scripts/tmp/verify/owners-r<N>.txt; echo RC=$?
```
Every listed path must be in that round's roster table (§2.2 / §2.3); any other path is a blocker. Round 2 additionally: `git diff --name-only <R1_START>..HEAD -- app/src/main/java/us/jhangy/textify/ScreenGrabber.kt app/src/main/res/drawable/bubble_bg.xml 'app/src/main/res/drawable/dismiss_*.xml' app/src/main/res/drawable/ic_dismiss_x.xml app/src/main/res/drawable/ic_session_close.xml app/src/main/res/drawable/session_hint_bg.xml app/src/main/res/drawable/session_x_bg.xml app/src/main/res/layout/dismiss_target.xml` → empty.

**Round 1 specifics:** plan "R1 reviewer" items 1–6 (ownership, compatibility-contract `git diff --stat`, every unit acceptance list re-run, build gate with 5 OcrMergeTest testcases, mutation, code reading: `HaloRecipe` = §8.1, `DemoSpec` and keyframes = §8.2, `PillView` diff touches only glow/fill/argb lines, `EdgeGlowView` SESSION = old constants).

**OcrMerge IoU mutation (round 1, after a green gate, still inside the gate window):**
```bash
F=app/src/main/java/us/jhangy/textify/OcrMerge.kt
mkdir -p scripts/tmp/mutation && cp "$F" scripts/tmp/mutation/OcrMerge.kt.orig
sed -i '' 's/> IOU_LIMIT/>= IOU_LIMIT/' "$F"; grep -c ">= IOU_LIMIT" "$F"   # expect 1
MOUT=scripts/tmp/mutation/$(date +%Y%m%d-%H%M%S).txt
nohup sh -c 'JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew testDebugUnitTest --tests us.jhangy.textify.OcrMergeTest; echo "RC=$?"' > "$MOUT" 2>&1 &
# liveness + completion exactly as §3 steps 4–5; expected: RC non-zero and iouExactlyHalfKeepsBoth fails
cp scripts/tmp/mutation/OcrMerge.kt.orig "$F"
git diff --quiet -- "$F"; echo RESTORED_RC=$?   # 0
```
Restore only from the copy, never with git. `RESTORED_RC` ≠ 0 → report `BLOCKED` immediately.

**Round 2 specifics:** contract criteria 1–6 and 8; plan "Whole-feature acceptance" items 1–9 (incl. item 7: each "Frozen interfaces (round 2)" row greps exactly in its owner file; item 9: code reading of `SettingsActivity` lifecycle vs §7.1, `CaptureService.applySettingsVisible` vs §5, `SessionActivity.openSettings` order vs §4); every R2-A…R2-E acceptance list re-run; the §13.7 checklist plus the plan's three device-only VERIFY items for the handoff (verbatim, one checkbox per item).

Severity: **blocker** = build/test failure, compatibility break, wrong spec value, failing contract criterion or unit acceptance item, ownership violation; **should-fix / nit** → parking lot. The reviewer adds no acceptance criteria. At most two review rounds per deliverable (§4 cycles).

## 6. Baton rules

- Ticket limit per holder: judgment-dense member (every `-opus` implementer) ≤ 2 tickets; mechanical member (every `-sonnet` implementer) ≤ 4 tickets. Plan tickets: R1-A 2, R1-B 2, R1-C 2, R1-D 2, R1-E 1, R2-A 2, R2-B 2, R2-C 2, R2-D 1, R2-E 1. A fix-cycle assignment counts as one ticket.
- At the limit with work left: the holder writes `docs/logs/settings-redesign/baton-<member>-<k>.md` (`<member>` = roster name, `<k>` = 1, 2, … per handoff from that member) containing the convergence contract pasted verbatim, completed work with commit hashes and acceptance output, the failure trail (attempts, error output, rejected hypotheses), the remaining plan steps, and the red lines; commits it with `git commit -- <that file>`; reports `READY_FOR_HANDOFF <path>`.
- The lead spawns a fresh member named `<original-name>-b<k>` (unique in the session) with the same model and file ownership; the first line after ROLE in its prompt is `Read docs/logs/settings-redesign/baton-<member>-<k>.md first.` The previous holder makes no further writes.
- R2-C split (outline risk 2): if `r2-impl-c-opus` hands off after C1, the successor takes C2 and both files; ownership never splits by file within a round.

## 7. Round close

The lead writes `docs/logs/settings-redesign/round-N-handoff.md` after the round's last gate and commits it with `git commit -- <that file> docs/logs/settings-redesign/round-N-review.md`. Contents:
1. `ROUND_START` and round-end commit hash (`git rev-parse HEAD`); every piece of evidence below is bound to that end hash.
2. Gate: output path, `START … HEAD` line, `RC=` line; round 1 also the mutation `RC=` and `RESTORED_RC=0`.
3. Contract criteria table (round 1: criteria 7 and 8; round 2: criteria 1–6 and 8) with pass/fail and evidence line.
4. Per member: unit, commits, acceptance pass/fail, baton docs if any.
5. Known limitations (stated with evidence and the follow-up), including the device-only VERIFY items.
6. Parking lot (should-fix / nit findings, out-of-round discoveries, and the plan's "Parking lot seeded by this plan"), presented to the user once at the end.
7. Round 2 only: the §13.7 on-device checklist and the three VERIFY items for the user.
8. Shutdown of the round's members per `~/.claude/reference/team-shutdown-protocol.md` before the next round's spawn.
