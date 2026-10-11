# Settings redesign — execution plan (agent team)

Outline (units, ownership matrix, gates, risks): `docs/superpowers/plans/2026-10-11-settings-redesign-outline.md`.
Implementation spec (all values): `docs/superpowers/specs/2026-10-11-settings-redesign-impl-spec.md` (cited as §n).
Detailed plan (steps per unit): `docs/superpowers/plans/2026-10-11-settings-redesign-plan.md` — each unit's steps are in the section titled with the unit id (`R1-A` … `R2-E`). This file says only how the lead runs the team; it does not repeat unit steps.

Path shorthands used below: `J/` = `app/src/main/java/us/jhangy/textify/`, `R/` = `app/src/main/res/`. Repo root: `/Users/jhangyu/project/textify`.

## 1. Convergence contract (frozen — only the user may change it)

**End state.** The app launches into onboarding or settings per §2/§7, captures with multi-language merged OCR (§6), shows the new icons and bubble glyph (§9), and `assembleDebug testDebugUnitTest` is green on `main` with spec §13.1–§13.6 passing.

**In scope.** The 10 units of the outline (R1-A … R1-E, R2-A … R2-E) with exactly the files of the outline's ownership matrix; the two round handoff docs; gate outputs under `scripts/tmp/verify/`.

**Out of scope.** `ScreenGrabber.kt`, `R/drawable/bubble_bg.xml`, `R/drawable/dismiss_*.xml`, `R/drawable/ic_dismiss_x.xml`, `R/drawable/ic_session_close.xml`, `R/drawable/session_hint_bg.xml`, `R/drawable/session_x_bg.xml`, `R/layout/dismiss_target.xml` (untouched list of the outline); any change to the spec, outline or detailed plan; dark theme; any `SDK_INT` branch outside §11; release/signing/Play Store work; the §13.7 on-device run itself (handed to the user).

**Acceptance criteria** (all run from the repo root at the final round-2 commit; "gate" = §3 procedure):
1. (§13.1) Gate output file contains `RC=0` for `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug testDebugUnitTest`.
2. (§13.2) The same gate output shows `OcrMergeTest` executed with the five §13.2 cases passing (`app/build/test-results/testDebugUnitTest/TEST-us.jhangy.textify.OcrMergeTest.xml` has `tests="5"` and `failures="0"`), and the reviewer's IoU mutation (§5) made the test run fail.
3. (§13.3) `grep -rn "Prefs.script(\|setScript(\|CaptureService.stop\|scriptGroup\|startButton\|stopButton\|ic_launcher_bg\|ic_launcher_fg\|ic_launcher_round\|ic_menu_crop\|Theme.Textify.Session\|DayNight\|3F6FFF" app/src README.md` → no output.
4. (§13.4) Every string name in §10.1's deleted list has zero hits under `app/src`; every string defined in §10.1 has ≥ 1 reference under `app/src/main` outside `strings.xml`.
5. (§13.5) `grep -rn "SDK_INT" app/src/main/java` → exactly the two §11 branches (both in `MainActivity.kt`).
6. (§13.6) Every §9 path-data string is found by `grep -F` in its file.
7. Round-1 exit gate output contains `RC=0`.
8. Ownership: for each round, every path in `git diff --name-only <round-start>..<round-end>` (excluding `docs/`) belongs to that round's units per the outline matrix, and `git diff --name-only <R1-start>..HEAD -- <out-of-scope paths above>` is empty.

§13.7 (on-device checklist) is copied into the round-2 handoff and handed to the user; it is not a team acceptance criterion.

**Round budget.** 2 rounds. Each round: at most 2 review→fix cycles after the first review. Budget exhausted with any criterion failing → the lead stops and reports the failure trail to the user (no third cycle, no third round).

**Parking lot.** Anything found during a round that is not a failing acceptance criterion or an R3 must-ask item (security hole, wrong premise, irreversible risk) goes to the round handoff's parking-lot section; it does not enter the round.

## 2. Rounds, rosters, dispatch prompts

### 2.0 Lead procedure per round
1. `git rev-parse --abbrev-ref HEAD` = `main`; record `ROUND_START=$(git rev-parse HEAD)` in the round handoff doc.
2. Create one task per unit (TaskCreate) and one for the reviewer.
3. Spawn members with `name` = roster name, `team_name` = session team, `subagent_type` = `podium:team-implementer` (implementers) / `podium:team-reviewer` (reviewer), `model` = roster model. No `isolation`, no `fork`.
4. Paste the member's skeleton below, with `<ROUND_START>` replaced by the recorded hash and the contract (§1 end state + acceptance criteria) pasted **verbatim** above the skeleton.
5. Sign off a unit only after re-running its acceptance greps yourself; close its task after sign-off.

### 2.1 Common prompt blocks (pasted inside every skeleton where marked)

**[GIT-RULES]** — paste verbatim:
> Shared-tree git rules: no stash (incl. stash list), reset, checkout --, clean, git add -A; commit only own files with `git commit -- <own paths>`; check branch is main before commit; git status --porcelain after. Stage with `git add <own paths>` only. For deletions use `git rm <path>` on your own paths only. Never push.

**[REPORT]** — paste verbatim:
> Report via SendMessage to `team-lead` (fallback `main` if team-lead is unreachable). Only: (1) conclusions with `file:line`; (2) list of changed files; (3) each acceptance item pass/fail with the actual command output (counts / empty); (4) uncertain or unfinished items, stated explicitly. No full files, no diffs; any artefact over 30 lines goes to a file and you report its path. No invented code names. Last line exactly `READY_FOR_SIGNOFF` or `BLOCKED`. Do not mark your task completed; the lead signs off.

**[COMMON-REDLINES]** — paste verbatim:
> Edit only your owned files. Never edit the spec, outline, detailed plan, this execution plan, or another member's files. Need a new cross-unit interface, a string resource you do not own, or a value the spec does not give → STOP and report `BLOCKED` with `file:line` and reason; do not invent it. No `Build.VERSION.SDK_INT` branch unless §11 lists it. Do not run Gradle (the reviewer runs the gate). No sleep, no polling loops. Third-party API: check the version in `app/build.gradle.kts` before writing a call and use the context7 docs for that version. Clauses are verified in code/environment, not inferred; if the code contradicts the lead, report it.
> When your last commit is in, send `SETTLED <your name> <commit hash>` to `team-lead`, then make **zero writes** to the working tree until the lead says the gate window is closed.

### 2.2 Round 1 — foundations (build green at exit)

| name | unit | model | owned files (exact, from outline matrix) |
|---|---|---|---|
| `r1-impl-a-sonnet` | R1-A icons + bubble glyph | sonnet | `R/drawable/ic_launcher_background.xml` (new), `R/drawable/ic_launcher_foreground.xml` (new), `R/drawable/ic_launcher_monochrome.xml` (new), `R/drawable/ic_scan.xml`, `R/drawable/avd_scan_in.xml` (new), `R/drawable/ic_stat_textify.xml` (new), `R/drawable/ic_session_settings.xml` (new), `R/drawable/ic_back.xml` (new), `R/drawable/ic_check.xml` (new); delete `R/drawable/ic_launcher_bg.xml`, `R/drawable/ic_launcher_fg.xml`; `R/mipmap-anydpi-v26/ic_launcher.xml`; delete `R/mipmap-anydpi-v26/ic_launcher_round.xml`; `R/layout/bubble.xml`, `J/Bubble.kt` |
| `r1-impl-b-opus` | R1-B Halo primitives | opus | `J/Glow.kt` (new), `J/EdgeGlowView.kt`, `J/PillView.kt` |
| `r1-impl-c-opus` | R1-C capture demo + step track | opus | `J/CaptureDemoView.kt` (new), `J/StepTrackView.kt` (new) |
| `r1-impl-d-opus` | R1-D language controls | opus | `J/LanguagePreviewView.kt` (new), `J/LanguageToggleView.kt` (new), `J/SpeedLanesView.kt` (new) |
| `r1-impl-e-opus` | R1-E OCR merge + test infra | opus | `J/OcrMerge.kt` (new), `app/src/test/java/us/jhangy/textify/OcrMergeTest.kt` (new), `app/build.gradle.kts` |
| `round-1-reviewer-opus` | R1 review + gate | opus | none in `app/`; writes only `scripts/tmp/verify/*`, `scripts/tmp/mutation/*`, `docs/logs/settings-redesign/round-1-review.md` |

**Dispatch order.**
1. Spawn `r1-impl-b-opus` first. Its ticket 1 ends with a commit that contains the §8.1/§8.3 frozen signatures (bodies may be minimal compiling stubs without placeholder markers) and a `SIGNATURES <hash>` message to the lead.
2. In the same batch spawn `r1-impl-a-sonnet` and `r1-impl-e-opus` (no deps).
3. On `SIGNATURES <hash>`: spawn `r1-impl-c-opus` and `r1-impl-d-opus`, giving them the hash so they code against the landed signatures.
4. Spawn `round-1-reviewer-opus` together with step 1 so it can review increments as commits land (static review needs no gate window); it starts the gate only when the lead forwards all five `SETTLED` messages.

Model choice: R1-A is byte-for-byte copying of path data and a small animator hook (mechanical → sonnet); R1-B/C/D are graphics code with timing tables and shared-API design (judgment → opus); R1-E is a numeric merge with an exact boundary and the first test setup (judgment → opus).

#### Skeleton `r1-impl-a-sonnet`
```
ROLE: WORKER — you may NOT spawn agents, teams, or workflows. If the task needs delegation, STOP and report back to leader.

You are r1-impl-a-sonnet, unit R1-A (icons and bubble glyph) of the Textify settings redesign, repo /Users/jhangyu/project/textify, branch main.

GOAL + MOTIVATION
Create every VectorDrawable of spec §9 and the bubble glyph entrance, and delete the old launcher art. Later units reference these drawables by name (ic_stat_textify, ic_session_settings, ic_back, ic_check, ic_scan), so names and path data must be exact. The old app must keep building after your change.

READ FIRST
- docs/superpowers/plans/2026-10-11-settings-redesign-plan.md, section R1-A (steps) and its global constraints.
- docs/superpowers/specs/2026-10-11-settings-redesign-impl-spec.md §0, §9 (copy path data byte-for-byte).
- The convergence contract pasted above this prompt.

OWNED FILES (only these)
app/src/main/res/drawable/{ic_launcher_background,ic_launcher_foreground,ic_launcher_monochrome,ic_scan,avd_scan_in,ic_stat_textify,ic_session_settings,ic_back,ic_check}.xml;
delete app/src/main/res/drawable/{ic_launcher_bg,ic_launcher_fg}.xml;
app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml; delete app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml;
app/src/main/res/layout/bubble.xml; app/src/main/java/us/jhangy/textify/Bubble.kt
Tickets: 2 (T1 icons + deletions, T2 bubble glyph + entrance). Commit after each ticket.

ACCEPTANCE (mechanical, run from repo root)
1. grep -F finds each §9 pathData string in its file.
2. test ! -e for the three deleted files; grep -rn "ic_launcher_bg\|ic_launcher_fg\|ic_launcher_round" app/src → empty.
3. grep -n 'android:padding="9dp"' app/src/main/res/layout/bubble.xml hits, and its src is @drawable/avd_scan_in.
4. grep -n "AnimatedVectorDrawable" Bubble.kt hits inside fadeIn.
5. ic_launcher.xml contains <monochrome.
6. xmllint --noout on every owned XML file → exit 0.
(The round-1 build is run by round-1-reviewer-opus.)

RED LINES
[COMMON-REDLINES]
[GIT-RULES]
Do not touch AndroidManifest.xml (roundIcon is added by R2-E in round 2) or any string resource.

REPORT
[REPORT]
```

#### Skeleton `r1-impl-b-opus`
```
ROLE: WORKER — you may NOT spawn agents, teams, or workflows. If the task needs delegation, STOP and report back to leader.

You are r1-impl-b-opus, unit R1-B (Halo primitives) of the Textify settings redesign, repo /Users/jhangyu/project/textify, branch main. You are on the critical path.

GOAL + MOTIVATION
Build one shared implementation of glows, pills, mist backgrounds and the halo recipe (§8.1, §8.3) so the demo, language and settings views never fork drawing code. The session capture must look identical afterwards: HaloRecipe.SESSION reproduces the current EdgeGlowView constants, and SessionActivity (not yours) must still compile unchanged.

READ FIRST
- docs/superpowers/plans/2026-10-11-settings-redesign-plan.md, section R1-B.
- Spec §0, §8.1, §8.3; current J/EdgeGlowView.kt, J/PillView.kt, J/SessionActivity.kt (caller of activate()/settle()).
- The convergence contract pasted above this prompt.

OWNED FILES (only these)
app/src/main/java/us/jhangy/textify/Glow.kt (new), EdgeGlowView.kt, PillView.kt

TICKETS (2)
T1 — FIRST COMMIT: the frozen public signatures exactly as §8.1/§8.3 (withAlpha, glowBitmap, PillPainter, GlowDrawable, MistBackground, MistSpot, HaloStyle.{cta,card,chip,ONBOARDING_SPOTS,SETTINGS_SPOTS}, HaloRecipe + SESSION/ONBOARDING_MINI/SETTINGS_MINI/PREVIEW_EDGE, EdgeGlowView.recipe/fadeOut/showSettled/clear), compiling. Immediately after the commit SendMessage team-lead: "SIGNATURES <commit hash>". R1-C and R1-D wait for this.
T2 — full bodies; PillView draws through PillPainter; EdgeGlowView recipe-driven. Signatures from T1 must not change; if one must, STOP and report.

ACCEPTANCE (mechanical, run from repo root)
1. The four HaloRecipe constants carry exactly the §8.1 table numbers (grep each row).
2. grep -n "HALO_BLUR_DP\|CORE_BLUR_DP\|CORNER_DP" EdgeGlowView.kt → empty.
3. grep -n "private fun argb\|renderGlow" PillView.kt → empty; PillView references PillPainter.
4. grep -rn "SDK_INT\|RenderEffect\|setShadowLayer" Glow.kt EdgeGlowView.kt PillView.kt → empty.
5. git diff <ROUND_START>..HEAD -- app/src/main/java/us/jhangy/textify/SessionActivity.kt → empty, and every public member SessionActivity uses from EdgeGlowView/PillView still exists (list them with file:line).
(Round-1 build and the session before/after parameter comparison are done by round-1-reviewer-opus.)

RED LINES
[COMMON-REDLINES]
[GIT-RULES]

REPORT
[REPORT]
```

#### Skeleton `r1-impl-c-opus`
```
ROLE: WORKER — you may NOT spawn agents, teams, or workflows. If the task needs delegation, STOP and report back to leader.

You are r1-impl-c-opus, unit R1-C (capture demo + step track) of the Textify settings redesign, repo /Users/jhangyu/project/textify, branch main.

GOAL + MOTIVATION
Build the looping miniature capture (CaptureDemoView, §8.2) and the 2-node progress track (StepTrackView, §8.4). Onboarding (R2-B) and settings (R2-C) both embed them in round 2, so the public API and XML child ids are frozen: CaptureDemoView.{configure, setGranted, pause, resume, flash}, DemoSpec.{ONBOARDING, SETTINGS}, StepTrackView.{State, setState, setLabels}; child ids demoScreen, demoChip, tag "line". The views take no string resources (all text comes from callers).

READ FIRST
- docs/superpowers/plans/2026-10-11-settings-redesign-plan.md, section R1-C.
- Spec §0, §8.1–§8.4.
- R1-B signatures landed at commit <R1B_SIGNATURES_HASH> (git show <hash> -- app/src/main/java/us/jhangy/textify/Glow.kt app/src/main/java/us/jhangy/textify/EdgeGlowView.kt). Code only against those signatures; bodies may still be in progress.
- Drawables ic_scan and ic_check (R1-A) are used by name only.
- The convergence contract pasted above this prompt.

OWNED FILES (only these)
app/src/main/java/us/jhangy/textify/CaptureDemoView.kt (new), StepTrackView.kt (new)
Tickets: 2 (T1 CaptureDemoView, T2 StepTrackView). Commit after each.

ACCEPTANCE (mechanical, run from repo root)
1. grep -c "INFINITE" CaptureDemoView.kt = 1 and grep -n "4800" CaptureDemoView.kt hits (one loop animator drives every element).
2. DemoSpec.ONBOARDING / DemoSpec.SETTINGS constants equal the §8.2 numbers (grep each).
3. grep -n "SDK_INT" in both files → empty.
4. Every R1-B symbol you call exists in the R1-B commit (list symbol → Glow.kt/EdgeGlowView.kt line).
(Round-1 build and the keyframe-table read against §8.2 are done by round-1-reviewer-opus.)

RED LINES
[COMMON-REDLINES]
[GIT-RULES]
Do not edit Glow.kt / EdgeGlowView.kt / PillView.kt; a missing or wrong R1-B signature → report BLOCKED naming the symbol.

REPORT
[REPORT]
```

#### Skeleton `r1-impl-d-opus`
```
ROLE: WORKER — you may NOT spawn agents, teams, or workflows. If the task needs delegation, STOP and report back to leader.

You are r1-impl-d-opus, unit R1-D (language controls) of the Textify settings redesign, repo /Users/jhangyu/project/textify, branch main.

GOAL + MOTIVATION
Build the preview, toggles and speed lanes of the settings language card (§8.5–§8.7). R2-C wires them in round 2, so the API and XML tags are frozen: LanguagePreviewView.setScripts, LanguageToggleView.{checked, setChecked, reject}, SpeedLanesView.{setScripts, setLabels}; XML tags zh|en|ja|ko, text, tag, glyph, sub. The views use the existing Script enum (unchanged) and take no string resources.

READ FIRST
- docs/superpowers/plans/2026-10-11-settings-redesign-plan.md, section R1-D.
- Spec §0, §8.3, §8.5–§8.7; J/Prefs.kt (Script enum, read only).
- R1-B signatures landed at commit <R1B_SIGNATURES_HASH>. Code only against those signatures.
- The convergence contract pasted above this prompt.

OWNED FILES (only these)
app/src/main/java/us/jhangy/textify/LanguagePreviewView.kt (new), LanguageToggleView.kt (new), SpeedLanesView.kt (new)
Tickets: 2 (T1 LanguagePreviewView + LanguageToggleView, T2 SpeedLanesView). Commit after each.

ACCEPTANCE (mechanical, run from repo root)
1. Lane widths 0.58f|0.64f|0.70f and durations 240|360|380|260|200|300|140 present (grep each).
2. grep -n "HapticFeedbackConstants.VIRTUAL_KEY" hits; grep -n "REJECT" → empty.
3. LanguagePreviewView.setScripts lights the en row for any non-empty set (cite file:line).
4. grep -n "SDK_INT" in the three files → empty.
5. Every R1-B symbol you call exists in the R1-B commit (symbol → file:line).
(Round-1 build is run by round-1-reviewer-opus.)

RED LINES
[COMMON-REDLINES]
[GIT-RULES]
Do not edit Prefs.kt (R2-A owns it in round 2) or any R1-B file.

REPORT
[REPORT]
```

#### Skeleton `r1-impl-e-opus`
```
ROLE: WORKER — you may NOT spawn agents, teams, or workflows. If the task needs delegation, STOP and report back to leader.

You are r1-impl-e-opus, unit R1-E (OCR merge + test infra) of the Textify settings redesign, repo /Users/jhangyu/project/textify, branch main.

GOAL + MOTIVATION
Implement the multi-script merge of §6.2 as a pure function so R2-A can run three recognizers in parallel and merge their lines, and add the project's first JVM unit test. Frozen output: internal data class OcrCandidate(...) and internal fun mergeOcr(candidates: List<OcrCandidate>): List<OcrCandidate> in OcrMerge.kt; testImplementation("junit:junit:4.13.2"). Ocr.kt and its callers are not touched in round 1.

READ FIRST
- docs/superpowers/plans/2026-10-11-settings-redesign-plan.md, section R1-E.
- Spec §6, §13.2; app/build.gradle.kts.
- The convergence contract pasted above this prompt.

OWNED FILES (only these)
app/src/main/java/us/jhangy/textify/OcrMerge.kt (new), app/src/test/java/us/jhangy/textify/OcrMergeTest.kt (new), app/build.gradle.kts
Tickets: 1. Commit once.

ACCEPTANCE (mechanical, run from repo root)
1. OcrMergeTest has exactly the five §13.2 cases (overlap > 0.5 keeps higher confidence; equal confidence keeps longer text; IoU exactly 0.5 keeps both; disjoint both kept; output sorted top then left) — list test names with file:line.
2. grep -n 'testImplementation("junit:junit:4.13.2")' app/build.gradle.kts hits.
3. The IoU comparison is a single strict `> 0.5` expression (cite file:line) so the reviewer's `>=` mutation hits exactly one place.
4. grep -n "SDK_INT" OcrMerge.kt → empty.
(Running testDebugUnitTest and the mutation are done by round-1-reviewer-opus.)

RED LINES
[COMMON-REDLINES]
[GIT-RULES]
Do not edit Ocr.kt or Prefs.kt (R2-A, round 2). junit 4.13.2 is the version frozen by the outline; do not change it.

REPORT
[REPORT]
```

#### Skeleton `round-1-reviewer-opus`
```
ROLE: WORKER — you may NOT spawn agents, teams, or workflows. If the task needs delegation, STOP and report back to leader.

You are round-1-reviewer-opus, reviewer and gate runner of round 1 of the Textify settings redesign, repo /Users/jhangyu/project/textify, branch main. Round start commit: <ROUND_START>.

GOAL + MOTIVATION
Independently verify R1-A…R1-E against spec §8, §9, §6.2 and the outline, and run the round-1 gate, so round 2 starts from a green, correctly owned foundation. You did not write any of it.

READ FIRST
- This execution plan §3 (gate procedure) and §5 (review scope, round 1) — follow them exactly.
- Outline (ownership matrix, unit acceptance), detailed plan sections R1-A…R1-E, spec §6.2, §8, §9.
- The convergence contract pasted above this prompt.

WORK
1. Static review per §5 as commits land (no gate window needed).
2. When team-lead forwards all five SETTLED messages: run the gate per §3, then the OcrMerge mutation per §5.
3. Findings → docs/logs/settings-redesign/round-1-review.md (each: severity blocker|should-fix|nit, file:line, criterion or spec §, evidence). Do not fix code.

ACCEPTANCE (mechanical)
1. Every unit acceptance item of R1-A…R1-E re-run, each with pass/fail + actual output.
2. Gate output file scripts/tmp/verify/<ts>.txt exists with START, END and RC= lines.
3. Mutation output file exists; original OcrMerge.kt restored from the copy and `git diff --quiet <last R1-E commit> -- app/src/main/java/us/jhangy/textify/OcrMerge.kt; echo $?` → 0.
4. Ownership check output per §5.

RED LINES
Never edit files under app/ except the transient mutation of OcrMerge.kt described in §5 (restore only from your copy). Write only scripts/tmp/verify/*, scripts/tmp/mutation/*, docs/logs/settings-redesign/round-1-review.md. Do not commit (the lead commits docs/logs).
[GIT-RULES]
No sleep, no polling loops: interleave liveness checks with review work as §3 describes.

REPORT
[REPORT]
Additionally report: gate output path + its RC line, mutation output path + RC, blocker count.
```

### 2.3 Round 2 — switch-over (build + tests green at exit)

Starts only after the round-1 handoff (§7) is committed with criterion 7 passing.

| name | unit | model | owned files (exact, from outline matrix) |
|---|---|---|---|
| `r2-impl-a-opus` | R2-A service + Ocr/Prefs switch | opus | `J/CaptureService.kt`, `J/Ocr.kt`, `J/Prefs.kt` |
| `r2-impl-b-opus` | R2-B launch flow + onboarding | opus | `J/MainActivity.kt` (rewrite), `R/layout/activity_main.xml` (rewrite) |
| `r2-impl-c-opus` | R2-C settings screen | opus | `J/SettingsActivity.kt` (new), `R/layout/activity_settings.xml` (new) |
| `r2-impl-d-sonnet` | R2-D session settings button | sonnet | `J/SessionActivity.kt`, `R/layout/activity_session.xml` |
| `r2-impl-e-sonnet` | R2-E resources, manifest, README | sonnet | `R/values/strings.xml`, `R/values/themes.xml`, `app/src/main/AndroidManifest.xml`, `README.md` |
| `round-2-reviewer-opus` | R2 review + gate | opus | none in `app/`; writes only `scripts/tmp/verify/*`, `docs/logs/settings-redesign/round-2-review.md` |

**Dispatch order.** All five implementers in one batch; round-2 cross-unit interfaces are frozen in spec §5, §6, §7.1, §10 (`CaptureService.isRunning/setSettingsVisible/onSessionEnded/start`, `Prefs.scripts/setScripts`, `Ocr.recognize(ctx, bitmap, scripts: Set<Script>, onResult)`, `SettingsActivity.start(ctx)`, §10.1 string names, `Theme.Textify.Translucent`). Nobody waits for another unit's commit. Spawn `round-2-reviewer-opus` in the same batch for incremental static review.

Model choice: R2-A (concurrency of parallel recognizers, service lifecycle), R2-B (launch-state machine + two §11 branches), R2-C (two cards, transitions, settings visibility contract) are judgment-dense → opus; R2-D is a bounded button + fixed call order and R2-E is copying frozen resource content → sonnet.

#### Skeleton `r2-impl-a-opus`
```
ROLE: WORKER — you may NOT spawn agents, teams, or workflows. If the task needs delegation, STOP and report back to leader.

You are r2-impl-a-opus, unit R2-A (service + Ocr/Prefs switch) of the Textify settings redesign, repo /Users/jhangyu/project/textify, branch main. Round start commit: <ROUND_START>.

GOAL + MOTIVATION
Switch the engine to multi-select scripts with parallel recognition merged by R1-E's mergeOcr, and give CaptureService the API the new screens need. Frozen (§5, §6): CaptureService.isRunning, setSettingsVisible(Boolean), onSessionEnded(), start(...); Prefs.scripts / setScripts; Ocr.recognize(ctx, bitmap, scripts: Set<Script>, onResult). Screen-capture consent moves to MainActivity (R2-B). Round-2 units switch together, so the module will not build until all five are in — that is expected; the reviewer runs the gate.

READ FIRST
- docs/superpowers/plans/2026-10-11-settings-redesign-plan.md, section R2-A.
- Spec §0, §5, §6, §10.1 (string names you reference), §11; J/OcrMerge.kt (R1-E output, read only); R1-A drawable ic_stat_textify.
- docs/logs/settings-redesign/round-1-handoff.md (known limitations).
- The convergence contract pasted above this prompt.

OWNED FILES (only these)
app/src/main/java/us/jhangy/textify/CaptureService.kt, Ocr.kt, Prefs.kt
Tickets: 2 (T1 Prefs + Ocr, T2 CaptureService). Commit after each.

ACCEPTANCE (mechanical, run from repo root)
1. grep -n "fun stop\|ic_menu_crop\|SDK_INT\|KEY_SCRIPT\|fun script(" CaptureService.kt Prefs.kt Ocr.kt → empty.
2. grep -n "createScreenCapture" CaptureService.kt → empty.
3. grep -n "confidence" Ocr.kt hits; Ocr.kt calls mergeOcr (file:line).
4. Every frozen signature above present verbatim (grep each, file:line).
(Round-2 gate is run by round-2-reviewer-opus.)

RED LINES
[COMMON-REDLINES]
[GIT-RULES]
Do not edit OcrMerge.kt / OcrMergeTest.kt (round-1 output); a needed change → BLOCKED. Strings are R2-E's: reference only §10.1 names.

REPORT
[REPORT]
```

#### Skeleton `r2-impl-b-opus`
```
ROLE: WORKER — you may NOT spawn agents, teams, or workflows. If the task needs delegation, STOP and report back to leader.

You are r2-impl-b-opus, unit R2-B (launch flow + onboarding) of the Textify settings redesign, repo /Users/jhangyu/project/textify, branch main. Round start commit: <ROUND_START>.

GOAL + MOTIVATION
Rewrite MainActivity and activity_main.xml into the launch flow of §2 and the onboarding screen of §3 (looping demo, step track, overlay grant, screen-capture consent, text toasts). The old Start/Stop/script picker disappears. You consume R1-B/R1-C views, R2-A CaptureService.isRunning/start, R2-C SettingsActivity.start, R2-E strings + Theme.Textify.Translucent by their frozen names; they land in parallel and the build is checked only at the round-2 gate.

READ FIRST
- docs/superpowers/plans/2026-10-11-settings-redesign-plan.md, section R2-B.
- Spec §0, §2, §3, §8.1–§8.4, §10.1, §11.
- docs/logs/settings-redesign/round-1-handoff.md.
- The convergence contract pasted above this prompt.

OWNED FILES (only these)
app/src/main/java/us/jhangy/textify/MainActivity.kt (rewrite), app/src/main/res/layout/activity_main.xml (rewrite)
Tickets: 2 (T1 launch flow, T2 onboarding layout + animation). Commit after each.

ACCEPTANCE (mechanical, run from repo root)
1. grep -n "scriptGroup\|startButton\|stopButton\|Prefs\." MainActivity.kt activity_main.xml → empty.
2. grep -n "SDK_INT" MainActivity.kt → exactly the two §11 branches (>= 33 POST_NOTIFICATIONS, >= 34 MediaProjectionConfig).
3. grep -n "setView(" MainActivity.kt → empty.
4. Timings 360|60|120|220|300|900 present (grep each).
5. xmllint --noout activity_main.xml → exit 0.

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
Build SettingsActivity + activity_settings.xml (§7): permission card and language card with their transitions, bubble hidden while settings is visible, last-language guard. Frozen output: SettingsActivity.start(ctx: Context) (called by R2-B and R2-D). You consume R1-A ic_back, R1-B/C/D views, R2-A Prefs + CaptureService.setSettingsVisible, R2-E strings/theme by frozen names.

READ FIRST
- docs/superpowers/plans/2026-10-11-settings-redesign-plan.md, section R2-C.
- Spec §0, §7, §8, §10.1.
- docs/logs/settings-redesign/round-1-handoff.md.
- The convergence contract pasted above this prompt.

OWNED FILES (only these)
app/src/main/java/us/jhangy/textify/SettingsActivity.kt (new), app/src/main/res/layout/activity_settings.xml (new)
Tickets: 2 (T1 activity shell + permission card, T2 language card). Commit after each. If you reach the baton limit before T2 is done, hand off per execution plan §6; layout ownership stays with one holder at a time.

ACCEPTANCE (mechanical, run from repo root)
1. setSettingsVisible called in onStart, onStop and onDestroy (grep -n with the enclosing function lines).
2. grep -c "openingSystemSettings" SettingsActivity.kt ≥ 3.
3. Timings 320|260|1500|220|160|200 present (grep each).
4. grep -n "Prefs.setScripts" hits; the last-one guard calls reject() (file:line).
5. grep -n "SDK_INT" SettingsActivity.kt → empty.
6. xmllint --noout activity_settings.xml → exit 0.

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
Add the settings button to the capture session (§4) and switch SessionActivity to the multi-script OCR API. The button hands off to settings in a fixed order so the bubble stays hidden: setSettingsVisible(true) → SettingsActivity.start → endSession() → finish(). You consume R1-A ic_session_settings, R1-B withAlpha, R2-A Ocr.recognize(Set)/Prefs.scripts/CaptureService.setSettingsVisible, R2-C SettingsActivity.start, R2-E strings by frozen names.

READ FIRST
- docs/superpowers/plans/2026-10-11-settings-redesign-plan.md, section R2-D.
- Spec §0, §4, §6.1, §8.3 (withAlpha), §10.1.
- The convergence contract pasted above this prompt.

OWNED FILES (only these)
app/src/main/java/us/jhangy/textify/SessionActivity.kt, app/src/main/res/layout/activity_session.xml
Tickets: 1. Commit once.

ACCEPTANCE (mechanical, run from repo root)
1. activity_session.xml has @+id/settings with layout_marginEnd="68dp".
2. grep -n "private fun argb\|Prefs.script(" SessionActivity.kt → empty.
3. grep -n -A4 "setSettingsVisible(true)" SessionActivity.kt shows SettingsActivity.start, endSession(), finish() in that order.
4. grep -n "BTN_STAGGER = 40L" hits.
5. xmllint --noout activity_session.xml → exit 0.

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
- docs/superpowers/plans/2026-10-11-settings-redesign-plan.md, section R2-E.
- Spec §0, §10, §13.3, §13.4.
- The convergence contract pasted above this prompt.

OWNED FILES (only these)
app/src/main/res/values/strings.xml, app/src/main/res/values/themes.xml, app/src/main/AndroidManifest.xml, README.md
Tickets: 2 (T1 strings + themes, T2 manifest + README). Commit after each.

ACCEPTANCE (mechanical, run from repo root)
1. Spec §13.3 grep → empty for your four files.
2. Every §10.1 name present in strings.xml and every §10.1 deleted name absent (grep -c per name).
3. Manifest: SettingsActivity declared, roundIcon="@mipmap/ic_launcher", grep -c "Theme.Textify.Translucent" = 3.
4. grep -rn "DayNight\|Theme.Textify.Session" app/src → empty.
5. README has no "Start"/"Stop" button wording (grep -n).
6. xmllint --noout on the three XML files → exit 0.

RED LINES
[COMMON-REDLINES]
[GIT-RULES]
References to strings from code are other units' work; do not edit .kt files to fix them — report unreferenced names instead.

REPORT
[REPORT]
```

#### Skeleton `round-2-reviewer-opus`
```
ROLE: WORKER — you may NOT spawn agents, teams, or workflows. If the task needs delegation, STOP and report back to leader.

You are round-2-reviewer-opus, reviewer and gate runner of round 2 of the Textify settings redesign, repo /Users/jhangyu/project/textify, branch main. Round start commit: <ROUND_START>. Round-1 start commit: <R1_START>.

GOAL + MOTIVATION
Prove the whole feature meets spec §13.1–§13.6 and the convergence contract, check round-2 cross-unit interfaces against §5/§6/§7.1, and produce the §13.7 on-device checklist for the user. You did not write any of it.

READ FIRST
- This execution plan §1 (contract), §3 (gate), §4 (convergence), §5 (review scope, round 2) — follow them exactly.
- Outline, detailed plan sections R2-A…R2-E, spec §5, §6, §7.1, §10, §11, §13.
- docs/logs/settings-redesign/round-1-handoff.md.

WORK
1. Static review per §5 as commits land.
2. When team-lead forwards all five SETTLED messages: run the gate per §3; evaluate criteria 1–6 and 8.
3. Findings → docs/logs/settings-redesign/round-2-review.md (severity, file:line, criterion or spec §, owning member per outline matrix, compiler/test message verbatim). Do not fix code.

ACCEPTANCE (mechanical)
1. Contract criteria 1–6 and 8 each pass/fail with actual output.
2. Every unit acceptance item of R2-A…R2-E re-run with pass/fail.
3. Gate output file with START, END, RC= lines.
4. Each failure assigned to exactly one owning member (outline matrix).

RED LINES
Never edit files under app/ or README.md. Write only scripts/tmp/verify/*, docs/logs/settings-redesign/round-2-review.md. Do not commit (the lead commits docs/logs).
[GIT-RULES]
No sleep, no polling loops: interleave liveness checks with review work as §3 describes.

REPORT
[REPORT]
Additionally report: gate output path + RC line, criteria table, blocker list with owners.
```

## 3. Gate procedure (both rounds; run by the round reviewer)

1. **Open the window.** The lead forwards five `SETTLED <member> <hash>` messages to the reviewer and confirms to all writers "gate window open — zero writes". From now until the reviewer reports the gate result and the lead announces "gate window closed", no member writes anything in the working tree (including gitignored paths), except the reviewer's own files under `scripts/tmp/` and `docs/logs/settings-redesign/`.
2. **Pre-check.** `git rev-parse --abbrev-ref HEAD` = `main`; `git status --porcelain` empty (ignoring `scripts/tmp/`); record `GATE_HEAD=$(git rev-parse HEAD)`.
3. **Run detached** (Gradle exceeds the 30 s foreground limit; never run it in the foreground, never pipe it):
   ```bash
   cd /Users/jhangyu/project/textify
   mkdir -p scripts/tmp/verify
   TS=$(date +%Y%m%d-%H%M%S); OUT=scripts/tmp/verify/$TS.txt
   nohup sh -c 'echo "START $(date -u +%FT%TZ) HEAD $(git rev-parse HEAD)"; JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug testDebugUnitTest; echo "RC=$?"; echo "END $(date -u +%FT%TZ)"' > "$OUT" 2>&1 &
   echo "$OUT"
   ```
   `RC=$?` is the line immediately after the Gradle command inside the same shell; there is no pipe between them.
4. **First liveness check within 5 minutes of launch.** Alive = both: (a) `wc -c "$OUT"` grew since launch, and (b) the log already shows a non-trivial step (`> Task :app:` line or `Configure project`). Either missing → dead: `pgrep -fl "gradlew assembleDebug"`, report the log tail to the lead as `BLOCKED`. No sleep and no polling loop: launch, do the static review items, and run the check between review items so it falls inside the 5-minute window.
5. **Completion.** The run is finished only when `grep -n "^RC=" "$OUT"` hits and `pgrep -f "gradlew assembleDebug testDebugUnitTest"` returns nothing. A harness completion notice is not evidence. Before calling the log truncated, re-read it and compare size.
6. **Evidence.** Report `$OUT`, the `START … HEAD` line, the `RC=` line, and for failures the first compiler/test error lines verbatim. `scripts/tmp/` is gitignored; the lead copies the RC line and HEAD into the round handoff.
7. **Close the window.** Reviewer reports; lead announces "gate window closed". Writers resume only after that message.

## 4. Round-2 convergence

- Round-2 units cannot build alone (they switch shared interfaces together). Per-unit checks are greps; the build is checked only at the round-2 gate.
- Each gate failure is routed by the lead to the member that owns the failing file (outline matrix), with the reviewer's error lines pasted verbatim and the previous attempts' failure trail. A failure in a file owned by nobody in round 2 (a round-1 file) goes to a fresh member of the round-1 unit's model, owning only that file for the cycle.
- Fix cycle = owners fix and commit → `SETTLED` → reviewer re-runs the gate (§3) and the affected criteria. **At most 2 fix cycles.** Still failing after cycle 2 → the lead stops, writes the failure trail (attempts, error output, rejected hypotheses) into `round-2-handoff.md`, and reports to the user. No third cycle.
- Same failure surviving one fix by a sonnet member → the next cycle's fix goes to an opus member with the full trail.
- Round 1 uses the same cycle rule (≤ 2 fix cycles); round 1 must reach criterion 7 before round 2 starts.

## 5. Reviewer scope

Dimensions both rounds: **spec conformance** (values and signatures against the cited spec §§), **architecture** (one implementation per shared concern, no forked drawing code, frozen signatures unchanged, no extra abstraction), **techdebt** (dead code or old names left behind, `SDK_INT` outside §11, compatibility shims, placeholder markers).

**Ownership check (both rounds):**
```bash
git diff --name-only <ROUND_START>..HEAD -- . ':!docs' > scripts/tmp/verify/owners-r<N>.txt; echo RC=$?
```
Every listed path must belong to a unit of this round per the outline matrix; any other path is a blocker. Round 2 additionally: `git diff --name-only <R1_START>..HEAD -- app/src/main/java/us/jhangy/textify/ScreenGrabber.kt app/src/main/res/drawable/bubble_bg.xml 'app/src/main/res/drawable/dismiss_*.xml' app/src/main/res/drawable/ic_dismiss_x.xml app/src/main/res/drawable/ic_session_close.xml app/src/main/res/drawable/session_hint_bg.xml app/src/main/res/drawable/session_x_bg.xml app/src/main/res/layout/dismiss_target.xml` → empty.

**Round 1 specifics:** re-run every R1 unit acceptance; read the CaptureDemoView keyframe table against §8.2; compare `HaloRecipe.SESSION` and PillView parameters with the round-start `EdgeGlowView.kt`/`PillView.kt` (`git show <ROUND_START>:<path>`) — identical numbers; confirm `SessionActivity.kt` unchanged and compiling at the gate.

**OcrMerge IoU mutation (round 1, after a green gate, still inside the gate window):**
```bash
F=app/src/main/java/us/jhangy/textify/OcrMerge.kt
mkdir -p scripts/tmp/mutation && cp "$F" scripts/tmp/mutation/OcrMerge.kt.orig
# edit the single IoU comparison from `> 0.5` to `>= 0.5` in $F (sed on that one line), then:
MOUT=scripts/tmp/mutation/$(date +%Y%m%d-%H%M%S).txt
nohup sh -c 'JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew testDebugUnitTest --tests us.jhangy.textify.OcrMergeTest; echo "RC=$?"' > "$MOUT" 2>&1 &
# liveness + completion exactly as §3 steps 4–5; expected: RC non-zero, the IoU-exactly-0.5 case fails
cp scripts/tmp/mutation/OcrMerge.kt.orig "$F"
git diff --quiet -- "$F"; echo RESTORED_RC=$?   # 0
```
Restore only from the copy, never with git. `RESTORED_RC` ≠ 0 → report `BLOCKED` immediately.

**Round 2 specifics:** contract criteria 1–6 and 8; round-2 cross-unit interfaces against §5/§6/§7.1 (each frozen signature found verbatim at its definition and every call site matches it); every unit acceptance of R2-A…R2-E re-run; produce the §13.7 checklist for the handoff (copied verbatim from the spec, one checkbox per item).

Severity: **blocker** = a contract criterion or a unit acceptance item fails, or ownership is violated; **should-fix / nit** → parking lot. Reviewer adds no acceptance criteria. At most two review rounds per deliverable (§4 cycles).

## 6. Baton rules

- Ticket limit per holder: judgment-dense member (every `-opus` implementer) ≤ 2 tickets; mechanical member (every `-sonnet` implementer) ≤ 4 tickets. A fix-cycle assignment counts as one ticket.
- At the limit with work left: the holder writes `docs/logs/settings-redesign/baton-<member>-<k>.md` (`<member>` = roster name, `<k>` = 1, 2, … per handoff from that member) containing the convergence contract pasted verbatim, completed work with commit hashes and acceptance output, the failure trail (attempts, error output, rejected hypotheses), the remaining steps by plan unit section, and the red lines; commits it with `git commit -- <that file>`; reports `READY_FOR_HANDOFF <path>`.
- The lead spawns a fresh member named `<original-name>-b<k>` (unique in the session) with the same model and file ownership; the first line after ROLE in its prompt is `Read docs/logs/settings-redesign/baton-<member>-<k>.md first.` The previous holder makes no further writes.
- R2-C split (outline risk 2): if `r2-impl-c-opus` hands off after T1, the successor takes T2 (language card) and both files; ownership never splits by file within a round.

## 7. Round close

The lead writes `docs/logs/settings-redesign/round-N-handoff.md` after the round's last gate and commits it with `git commit -- <that file> docs/logs/settings-redesign/round-N-review.md`. Contents:
1. `ROUND_START` and round-end commit hash (`git rev-parse HEAD`); every piece of evidence below is bound to that end hash.
2. Gate: output path, `START … HEAD` line, `RC=` line; round 1 also the mutation `RC=` and `RESTORED_RC=0`.
3. Contract criteria table (round 1: criterion 7 + 8 for round 1; round 2: criteria 1–6, 8) with pass/fail and evidence line.
4. Per member: unit, commits, acceptance pass/fail, baton docs if any.
5. Known limitations (stated with evidence and the follow-up), including §6.2 VERIFY items that only a device run can close.
6. Parking lot (should-fix / nit findings and out-of-round discoveries), presented to the user once at the end.
7. Round 2 only: the §13.7 on-device checklist for the user.
8. Shutdown of the round's members per `~/.claude/reference/team-shutdown-protocol.md` before the next round's spawn.
