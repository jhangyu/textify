# On-device Translation + Paragraph OCR — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use podium:team-spawn (recommended) or podium:team-fable to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** OCR returns paragraphs instead of lines, and a translate button on the session screen replaces each paragraph in place with an on-device translation (ML Kit + OpenCC for 繁體中文), with per-paragraph status chips, a bottom panel for truncated boxes and a 「翻譯」 card in Settings.

**Architecture:** Pure Kotlin rules (merge, routing, fitting, colours, model rows) live in small JVM-tested files; `Translate.kt` is the only ML Kit translation / language-id caller and reports one `ParagraphState` per paragraph on the main thread; `PillView` gains a translated mode (solid card drawn in software) so one view per paragraph keeps the existing tap / long-press / copy wiring; `SessionActivity` and `SettingsActivity` only orchestrate. Visual source of truth: `docs/design/mockups/translation/proposal-2-prism-tide.html` (header comment), truncation mark from `proposal-1-quiet-reprint.html`.

**Tech Stack:** Kotlin 2.2.0, AGP 8.13.2, compileSdk 36, minSdk 26, targetSdk 36, androidx core-ktx 1.13.1, appcompat 1.7.0, ViewBinding, ML Kit text-recognition 16.0.1. New: `com.google.mlkit:translate:17.0.3`, `com.google.mlkit:language-id:17.0.6`, `com.github.houbb:opencc4j:1.14.0` (versions and APIs verified, see Global Constraints).

**Decisions (hard-to-reverse only):**
- APK size. Alternatives: (a) keep all four ABIs as today, (b) `abiFilters` arm64-v8a(+armeabi-v7a), (c) `useLegacyPackaging = true` (compressed `.so`). **Chosen by the user: `abiFilters` arm64-v8a only** (Task 4 Step 1b). Drawback: no x86 emulators, no 32-bit-only phones. Size context (measured from the registry artifacts): `translate-17.0.3.aar` carries `libtranslate_jni.so` of 11.6 / 16.4 / 17.3 / 17.4 MB (armeabi-v7a / arm64 / x86 / x86_64, 62.6 MB total, stored uncompressed by AGP for minSdk ≥ 23), `language-id-17.0.6` adds ≈ 4.7 MB of `.so`, OpenCC adds 2.86 MB of jars. The current debug APK is 48.98 MB (41.0 MB of it OCR `.so`), so the APK grows to roughly 118 MB.
- OpenCC port. Alternatives: OpenCC C++ via JNI, ML Kit `zh` only (Simplified), **opencc4j 1.14.0 (chosen)**. Drivers: pure Java so the conversion runs in JVM unit tests; s2twp-equivalent `ZhTwConverterUtil.toTraditional` verified on the JVM against the registry jar (`使用互联网`→`使用網際網路`, `软件和内存`→`軟體和記憶體`, already-Traditional text unchanged, `\n` kept). Size: opencc4j 0.56 MB + heaven 0.33 MB + nlp-common 1.97 MB = 2.86 MB compressed (< 5 MB budget); `jieba-analysis` is `<optional>` and is not pulled. Drawback: it loads ≈ 7 MB of dictionaries into memory on first use (runs off the main thread); Android compatibility is checked on device in Task 7.
- One view per paragraph. Alternatives: separate translated-card view stacked over `PillView`, **`PillView` with an original and a translated mode (chosen)**. Driver: tap / long-press selection / ActionMode / copy flash already live in `PillView`; mockup note 2 also specifies one view with two layers. Drawback: `PillView` grows; card painting is split into `TranslatedCard.kt` to keep it readable.
- Glow and blur. Alternatives: `setShadowLayer` / `RenderEffect` (mockup notes, API-gated), **software glow bitmaps from `Glow.kt` (chosen)**. Driver: final pick "one implementation for API 26+". Drawback: one small bitmap per glowing element, rebuilt on size change. The bottom panel is opaque 96 % white with no blur.
- Sheets and panel. Alternatives: `BottomSheetDialog` + `RecyclerView` (mockup notes 9–10), **plain in-window `LinearLayout` + `ScrollView` (chosen)**. Drivers: no new dependency; a dialog window would break "tap outside closes" over the overlay-style session; 59 rows do not need recycling. Drawback: rows are rebuilt on change.
- Wi-Fi-only. Alternatives: rely only on `DownloadConditions.requireWifi()`, **app checks `TRANSPORT_WIFI` before queuing and still passes `requireWifi()` (chosen)**. Driver: spec §2 "no download is queued" when not on Wi-Fi; ML Kit's behaviour without Wi-Fi is unknown and is observed on device in Task 7. Drawback: needs `ACCESS_NETWORK_STATE`.
- `ParagraphState.Done(text, source)` carries the ML Kit source code (spec lists `Done(text)`). Driver: the bottom panel header shows "source → target" (mockup `.lp`). Drawback: none beyond one field.
- Translation input. Alternatives: send OCR text with its `\n`, **replace `\n` by nothing for zh/ja and by one space otherwise (chosen)**. Driver: OCR line breaks are layout, not sentence breaks; ML Kit translates line by line otherwise. Drawback: real paragraph-internal line breaks are lost in the translation (the original keeps them).

Plan gate: `/Users/jhangyu/.claude/plugins/cache/jhangyu/podium/1.1.0/protocols/review.md` §L0 Plan gate.

Spec: `docs/superpowers/specs/2026-10-11-translation-design.md` (cited §n; "Final picks" = FP).

## Global Constraints

- Repo `/Users/jhangyu/project/textify`, branch `main`. Shorthand: `java/…` = `app/src/main/java/us/jhangy/textify/…`, `test/…` = `app/src/test/java/us/jhangy/textify/…`, `res/…` = `app/src/main/res/…`.
- Gradle (build and unit tests) exceeds the foreground limit: implementers never run it. The gate-runner member named in the dispatch runs it detached with raw output to `scripts/tmp/verify/<task>-<timestamp>.txt` and `echo "RC=$?"` written right after Gradle; the implementer broadcasts `SETTLED` first and writes nothing during the run. Every task's gate command: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug testDebugUnitTest`. Pass = `RC=0`. Test counts come from `app/build/test-results/testDebugUnitTest/TEST-*.xml` (`grep -c "<testcase"` per file).
- Verified dependencies (registry + API checked 2026-10-11; do not change versions without re-verifying):
  - `com.google.mlkit:translate:17.0.3` — Google Maven `maven-metadata.xml` latest; developers.google.com/ml-kit/language/translation/android shows the same; API checked with `javap` on the AAR: `TranslateLanguage.getAllLanguages(): List<String>`, `TranslateLanguage.fromLanguageTag(String): String?`, `TranslatorOptions.Builder().setSourceLanguage/setTargetLanguage/build`, `Translation.getClient(options): Translator`, `Translator.translate(String): Task<String>`, `Translator.close()`, `TranslateRemoteModel.Builder(lang).build()`, `TranslateRemoteModel.getLanguage()`.
  - `com.google.mlkit:common:18.11.0` (transitive): `RemoteModelManager.getInstance()`, `download(RemoteModel, DownloadConditions): Task<Void>`, `deleteDownloadedModel(RemoteModel): Task<Void>`, `getDownloadedModels(Class<T>): Task<Set<T>>`, `isModelDownloaded(RemoteModel): Task<Boolean>`, `DownloadConditions.Builder().requireWifi().build()`.
  - `com.google.mlkit:language-id:17.0.6` — Google Maven latest; official guide shows 17.0.6 (bundled model, no download); `LanguageIdentification.getClient(): LanguageIdentifier`, `identifyLanguage(String): Task<String>`, `LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG = "und"`, default confidence threshold 0.5.
  - `com.github.houbb:opencc4j:1.14.0` — Maven Central `maven-metadata.xml` latest (lastUpdated 2025-09-05); context7 `/houbb/opencc4j`: `ZhTwConverterUtil.toTraditional(String): String` ("使用互联网" → "使用網際網路"). Pulls `heaven:0.13.0` and `nlp-common:0.0.5`.
  - `com.google.android.gms:play-services-tasks:18.2.0` (transitive): `Tasks.whenAll(Collection<Task<*>>): Task<Void>`.
  - Existing `androidx.core:core-ktx:1.13.1` provides `androidx.core.widget.TextViewCompat.setLineHeight(TextView, Int)`.
- One implementation for API 26–36 (project policy, FP). No new `Build.VERSION` checks. Never use `RenderEffect`, `setShadowLayer`, `outlineSpotShadowColor`, `TextView.setLineHeight` (API 28; use `TextViewCompat.setLineHeight`), weight-specific typefaces. Glows only through `Glow.kt` (`glowBitmap`, `GlowDrawable`, `PillPainter`).
- Unreleased project: replaced code is deleted with its tests and docs (`OcrLine` disappears entirely; `grep -rn "OcrLine" app README.md` must print nothing after Task 1). No compatibility shims.
- Pure logic (merge, routing, OpenCC-unchanged rule, fitting, colours, model rows) lives in files without Android imports and has JVM tests in `test/…`.
- Units: CSS px = dp, CSS font px = sp; CSS weight ≥ 600 → bold. Colours `#RRGGBB a%` = `withAlpha(a, 0xRRGGBB)` (`Glow.kt`). CSS easings: emphasized `PathInterpolator(.2f,0f,0f,1f)`, ease-out `(0f,0f,.58f,1f)`, ease-in `(.42f,0f,1f,1f)`, decelerate `(0f,0f,.2f,1f)`, accelerate `(.4f,0f,1f,1f)`, fast-out-slow-in `(.4f,0f,.2f,1f)`.
- Palette (prism-tide header): lavender #A78BFA, pink #F472B6, peach #FDBA74, aqua #67E8F9, periwinkle #818CF8, violet #7C3AED, plum #4C1D95, ink #1E1B4B, mist #FAF5FF, secondary text #6B5FA8, failed text #9A3412, waiting grey #8A86A0, CTA gradient #7C3AED → #EC4899.
- Timings (ms, prism-tide header): tide 400 (per-box delay = 400 × centreY / H × 0.85), tideBack 320 (delay = 320 × (1 − centreY / H) × 0.8), flip 240, arrive 320, btnState 240, chipIn 220, chipOut 160, flash 380, panelIn 300, panelOut 240, copyOk 200, copyOkHold 1400, sheetIn 300, sheetOut 240, rowOut/rowIn 260, sparkle 380, toggle 200, boxIn 260, xIn 240, spin 900, shimmer 1200, previewLoop 3200, btnStagger 40. Every fade ≤ 400.
- UI copy is zh-TW in `res/values/strings.xml` (exact strings given per task). Language names come from `Languages.kt`.
- No extra toast or dialog for translation results or failures (§3); the per-paragraph chips are the report. Reduced motion is left to the system animator duration scale (spinners are `ValueAnimator`s and keep turning only when the scale is non-zero).
- On-device steps need a device on `adb` (`adb devices` lists one). Without a device, report the on-device acceptance items as `BLOCKED` (the lead asks the user); never mark them passed from reasoning. Report on-device observations in the sign-off report; do not create proof files.
- Git (shared tree): `git rev-parse --abbrev-ref HEAD` prints `main`; stage only own files with `git add <paths>`; commit with `git commit -m "…" -- <paths>`; deletions with `git rm <path>` in the same pathspec commit; `git status --porcelain -- <paths>` empty afterwards. Forbidden: `git stash` (including `stash list`), `reset`, `checkout --`, `clean`, `add -A`, `push`. Commit messages end with `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- No sleep or polling; wait for messages. Report `READY_FOR_SIGNOFF` or `BLOCKED`; the lead signs off and closes tickets.

## File map

| File | Responsibility | Task |
|---|---|---|
| `java/OcrMerge.kt` | `Box`, paragraph `OcrCandidate`, `paragraphConfidence`, IoU merge | 1 |
| `java/Ocr.kt` | ML Kit text recognition → `OcrBlock` paragraphs | 1 |
| `java/TextFit.kt` | pure layout/colour math: `lineMetrics`, `fitTextSize`, `averageColor`, `textColorFor`, `edgePoints` | 2, 6 |
| `java/PillView.kt` | one paragraph box: original (transparent multi-line selectable text) and translated mode | 1, 2, 6 |
| `java/TranslateRules.kt` | pure routing and state rules, `TargetLang`, `ParagraphState`, `Chip`, `ModelState` | 3 |
| `java/OpenCc.kt` | opencc4j wrapper (s2twp), never throws | 3 |
| `java/Languages.kt` | display names, disc glyphs and colours | 3 |
| `java/Prefs.kt` | target language and Wi-Fi-only prefs | 4 |
| `java/Translate.kt` | ML Kit language-id + translation + model downloads (`Translate`, `TranslateRun`) | 4 |
| `java/SpinnerRingView.kt` | Halo spinner ring | 5 |
| `java/StatusChipView.kt` | per-paragraph status chip | 5 |
| `java/TranslatedCard.kt` | `edgeColor`, `CardPainter` (solid card, glows, underline, "⋯" mark) | 6 |
| `java/SessionActivity.kt` + `res/layout/activity_session.xml` | translate button, tide, state → view mapping, panel wiring | 1, 2, 7, 9 |
| `java/TranslationPanel.kt` | bottom panel for truncated boxes | 9 |
| `java/SettingsActivity.kt` + `res/layout/activity_settings.xml` | 「翻譯」 card, language sheet | 8, 10 |
| `java/TranslatePreviewView.kt` | looping tide preview in the 「翻譯」 card | 10 |
| `test/OcrMergeTest.kt`, `test/TextFitTest.kt`, `test/TranslateRulesTest.kt`, `test/OpenCcTest.kt` | JVM tests | 1, 2, 3, 6 |

## Spec coverage

| Spec section | Tasks |
|---|---|
| Decisions 1–8 | 4 (engine, INTERNET, README wording), 6–7 (in-place display, panel in 9), 1–2 (paragraphs), 3–4 + 8 (one target, per-paragraph detection), 4 + 8 (auto download, Wi-Fi switch), 7 (button only), 3 (OpenCC), 8 (model states, delete) |
| §1 OCR in paragraphs | 1, 2 |
| §2 Translation engine | 3, 4, 7 (on-device checks) |
| §3 Session screen | 5, 6, 7, 9 |
| §4 Settings | 4 (prefs), 8, 10 |
| §5 Error handling | 3 (OpenCC fallback), 4 (identification failure, download failure, per-paragraph failure, late results), 7 |
| §6 Testing | 1, 2, 3, 6 (JVM), 7, 8, 9 (on device) |
| Final picks | 3 (OpenCC-unchanged rule), 6 ("⋯" fade mark, software glow), 7 (button spins only while Translating), 8 (delete without dialog), 9 (opaque panel, no blur), 10 (prism-tide preview) |

---

### Task 1: Paragraph OCR data model and merge

**Files:**
- Modify: `java/OcrMerge.kt` (whole file)
- Modify: `java/Ocr.kt` (data class, `recognize` result mapping, `candidates`)
- Modify: `java/PillView.kt:32` and `init` (constructor parameter `line: OcrLine` → `block: OcrBlock`)
- Modify: `java/SessionActivity.kt:104-140,166-169` (`OcrLine` → `OcrBlock`, `copyLine` → `copyText`)
- Modify: `README.md` ("How it works" step 3, "OCR languages" paragraph)
- Test: `test/OcrMergeTest.kt` (whole file)

**Interfaces:**
- Consumes: ML Kit `Text.TextBlock.getLines()`, `getBoundingBox()`, `Text.Line.getConfidence()` (existing dependency).
- Produces:
  - `internal data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)` with `val width: Int`, `val height: Int` (in `OcrMerge.kt`).
  - `internal data class OcrCandidate(val text: String, val box: Box, val lines: List<Box>, val confidence: Float)`.
  - `internal fun paragraphConfidence(lineConfidences: List<Float>): Float`.
  - `internal fun iou(a: Box, b: Box): Float`.
  - `internal fun mergeOcr(candidates: List<OcrCandidate>): List<OcrCandidate>`.
  - `data class OcrBlock(val text: String, val box: Rect, val lines: List<Rect>)` (in `Ocr.kt`; `OcrLine` deleted).
  - `internal fun Rect.toBox(): Box` (in `Ocr.kt`).
  - `Ocr.recognize(ctx: Context, bitmap: Bitmap, scripts: Set<Script>, onResult: (List<OcrBlock>) -> Unit)`.
  - `class PillView(context: Context, val block: OcrBlock)`.
  - `SessionActivity.copyText(pill: PillView, text: String)` (private; renamed from `copyLine`).

**Behavior:**
- `candidates()` iterates `Text.textBlocks` (not lines). Per block: keep lines with a non-null bounding box and non-blank text; drop the block when none remain. Paragraph text = kept lines' text joined with `"\n"`. Box = `block.boundingBox`, or the union of the kept line boxes when the block box is null. `lines` = kept line boxes in ML Kit order (top to bottom). Confidence = mean of kept lines' `Text.Line.getConfidence()` (blocks have no confidence).
- Merge is unchanged in spirit (spec §1): highest confidence first, ties → longer text, then input order; a paragraph whose box overlaps an already kept one with IoU > 0.5 is dropped; the result is sorted by box top, then left. The kept candidate keeps its own `lines`.
- `PillView` and `SessionActivity` only follow the rename in this task: a pill still renders one line (multi-line comes in Task 2); tap copies `block.text` (the whole paragraph, with its `\n`).
- README step 3 says "every recognised paragraph becomes a pill. Tap a pill to copy the paragraph"; the OCR languages paragraph says "where two paragraphs overlap (IoU > 0.5)".
- Known risk kept from spec §1: ML Kit may merge two columns or split a paragraph; no custom clustering.

**Constraints:**
- `OcrMerge.kt` stays free of Android imports.
- `OcrLine` is deleted everywhere (code, README); no alias.

**Acceptance criteria:**
- [ ] Gate `RC=0`; `TEST-us.jhangy.textify.OcrMergeTest.xml` has 7 `<testcase` entries: `overlapAboveHalfKeepsHigherConfidence`, `equalConfidenceKeepsLongerText`, `iouExactlyHalfKeepsBoth`, `disjointParagraphsBothKept`, `resultSortedTopThenLeft`, `winnerKeepsItsLines`, `paragraphConfidenceIsLineMean`, and 0 failures.
- [ ] `grep -rn "OcrLine" app README.md` prints nothing.
- [ ] `grep -n "textBlocks" app/src/main/java/us/jhangy/textify/Ocr.kt` prints one line and `grep -n "flatMap { it.lines }" app/src/main/java/us/jhangy/textify/Ocr.kt` prints nothing.
- [ ] `grep -c "recognised paragraph" README.md` prints `1`.


**Steps:**

- [ ] **Step 1: Write the failing test** — replace `test/OcrMergeTest.kt` with:

```kotlin
package us.jhangy.textify

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrMergeTest {

    private fun c(text: String, l: Int, t: Int, r: Int, b: Int, conf: Float) =
        OcrCandidate(text, Box(l, t, r, b), listOf(Box(l, t, r, b)), conf)

    @Test
    fun overlapAboveHalfKeepsHigherConfidence() {
        // IoU = 60 / 100 = 0.6
        val out = mergeOcr(listOf(c("low", 0, 0, 10, 10, 0.4f), c("high", 0, 0, 10, 6, 0.9f)))
        assertEquals(listOf("high"), out.map { it.text })
    }

    @Test
    fun equalConfidenceKeepsLongerText() {
        val out = mergeOcr(listOf(c("ab", 0, 0, 10, 10, 0.5f), c("abcd", 1, 0, 10, 10, 0.5f)))
        assertEquals(listOf("abcd"), out.map { it.text })
    }

    @Test
    fun iouExactlyHalfKeepsBoth() {
        // IoU = 50 / 100 = 0.5 -> not > 0.5 -> both kept
        val out = mergeOcr(listOf(c("a", 0, 0, 10, 10, 0.9f), c("b", 0, 0, 10, 5, 0.1f)))
        assertEquals(setOf("a", "b"), out.map { it.text }.toSet())
    }

    @Test
    fun disjointParagraphsBothKept() {
        val out = mergeOcr(listOf(c("a", 0, 0, 10, 10, 0.9f), c("b", 0, 20, 10, 30, 0.9f)))
        assertEquals(2, out.size)
    }

    @Test
    fun resultSortedTopThenLeft() {
        val out = mergeOcr(
            listOf(
                c("bottom", 0, 50, 10, 60, 0.9f),
                c("topRight", 40, 0, 50, 10, 0.1f),
                c("topLeft", 0, 0, 10, 10, 0.5f),
            ),
        )
        assertEquals(listOf("topLeft", "topRight", "bottom"), out.map { it.text })
    }

    @Test
    fun winnerKeepsItsLines() {
        val twoLines = OcrCandidate("a\nb", Box(0, 0, 10, 20), listOf(Box(0, 0, 10, 10), Box(0, 10, 10, 20)), 0.9f)
        val out = mergeOcr(listOf(c("x", 0, 0, 10, 20, 0.1f), twoLines))
        assertEquals(listOf(twoLines), out)
    }

    @Test
    fun paragraphConfidenceIsLineMean() {
        assertEquals(0.5f, paragraphConfidence(listOf(0.25f, 0.75f)), 1e-6f)
        assertEquals(0f, paragraphConfidence(emptyList()), 0f)
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run (gate-runner, detached per Global Constraints): `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew testDebugUnitTest --tests us.jhangy.textify.OcrMergeTest; echo "RC=$?"`
Expected: `RC=1`, compile error `Unresolved reference 'Box'` (or `'paragraphConfidence'`).

- [ ] **Step 3: Replace `java/OcrMerge.kt`**

```kotlin
package us.jhangy.textify

import kotlin.math.max
import kotlin.math.min

/** Integer rect in screenshot pixels. Pure Kotlin so merge and layout math run on the JVM test runner. */
internal data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** One recognised paragraph (ML Kit TextBlock) from one script model; [lines] = per-line boxes, top to bottom. */
internal data class OcrCandidate(
    val text: String,
    val box: Box,
    val lines: List<Box>,
    val confidence: Float,
)

/** Paragraph confidence = mean of its lines' Text.Line.getConfidence() (blocks have none); 0 without lines. */
internal fun paragraphConfidence(lineConfidences: List<Float>): Float =
    if (lineConfidences.isEmpty()) 0f else lineConfidences.sum() / lineConfidences.size

/** Intersection over union of two integer rects; 0 when they do not overlap or the union is empty. */
internal fun iou(a: Box, b: Box): Float {
    val iw = min(a.right, b.right) - max(a.left, b.left)
    val ih = min(a.bottom, b.bottom) - max(a.top, b.top)
    if (iw <= 0 || ih <= 0) return 0f
    val inter = iw.toLong() * ih
    val areaA = a.width.toLong() * a.height
    val areaB = b.width.toLong() * b.height
    val union = areaA + areaB - inter
    return if (union <= 0L) 0f else inter.toFloat() / union
}

private const val IOU_LIMIT = 0.5f

/**
 * Merges the paragraphs of several recognizers run on the same screenshot (translation spec §1): highest
 * confidence first (ties: longer text, then input order); a paragraph overlapping an already kept one with
 * IoU > 0.5 is dropped. Equal confidences (e.g. models that report 0) fall through to "longer text wins".
 * Result sorted top-to-bottom, then left-to-right.
 */
internal fun mergeOcr(candidates: List<OcrCandidate>): List<OcrCandidate> {
    val ranked = candidates.sortedWith(
        compareByDescending<OcrCandidate> { it.confidence }.thenByDescending { it.text.length },
    )   // sortedWith is stable: input order breaks remaining ties
    val kept = ArrayList<OcrCandidate>(ranked.size)
    for (c in ranked) if (kept.none { iou(it.box, c.box) > IOU_LIMIT }) kept += c
    return kept.sortedWith(compareBy({ it.box.top }, { it.box.left }))
}
```

- [ ] **Step 4: Replace `java/Ocr.kt`**

```kotlin
package us.jhangy.textify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

/**
 * One recognised paragraph (ML Kit TextBlock). [text] = its lines joined with "\n"; [box] and the per-line
 * [lines] (top to bottom) are in screenshot (bitmap) pixels.
 */
data class OcrBlock(val text: String, val box: Rect, val lines: List<Rect>)

internal fun Rect.toBox(): Box = Box(left, top, right, bottom)

private fun Box.toRect(): Rect = Rect(left, top, right, bottom)

/** On-device OCR. Every selected script model runs fully offline, all in parallel on the same image. */
object Ocr {
    /**
     * Calls [onResult] exactly once on the main thread after every recognizer completed; overlapping paragraphs
     * are merged by [mergeOcr] (sorted top, then left). A failing recognizer contributes no paragraphs.
     */
    fun recognize(ctx: Context, bitmap: Bitmap, scripts: Set<Script>, onResult: (List<OcrBlock>) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val image = try {
            InputImage.fromBitmap(bitmap, 0)
        } catch (e: Exception) {                 // e.g. recycled bitmap
            main.post { onResult(emptyList()) }
            return
        }
        if (scripts.isEmpty()) { main.post { onResult(emptyList()) }; return }
        val parts = arrayOfNulls<List<OcrCandidate>>(scripts.size)   // touched on the main thread only
        var pending = scripts.size
        fun done(i: Int, found: List<OcrCandidate>) = main.post {
            parts[i] = found
            if (--pending == 0) {
                onResult(mergeOcr(parts.flatMap { it.orEmpty() })
                    .map { c -> OcrBlock(c.text, c.box.toRect(), c.lines.map { it.toRect() }) })
            }
        }
        scripts.forEachIndexed { i, script ->
            val recognizer = client(script)
            try {
                recognizer.process(image)
                    .addOnSuccessListener { done(i, candidates(it)) }
                    .addOnFailureListener { done(i, emptyList()) }
                    .addOnCompleteListener { recognizer.close() }
            } catch (e: Exception) {             // synchronous throw from process()
                recognizer.close()
                done(i, emptyList())
            }
        }
    }

    private fun client(script: Script): TextRecognizer = when (script) {
        Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    /** Translation spec §1: one candidate per TextBlock; blank or box-less lines dropped; confidence = mean line confidence. */
    private fun candidates(result: Text): List<OcrCandidate> = result.textBlocks.mapNotNull { block ->
        val lines = block.lines.filter { it.boundingBox != null && it.text.isNotBlank() }
        if (lines.isEmpty()) return@mapNotNull null
        val boxes = lines.map { it.boundingBox!!.toBox() }
        val box = block.boundingBox?.toBox() ?: Box(
            boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom },
        )
        OcrCandidate(lines.joinToString("\n") { it.text }, box, boxes, paragraphConfidence(lines.map { it.confidence }))
    }
}
```

- [ ] **Step 5: Follow the rename in `java/PillView.kt`**

Replace the class kdoc's first sentence `One OCR line as an a2 "Halo" pill.` with `One OCR paragraph as an a2 "Halo" pill (single line until Task 2 of the translation plan).`, then:

```kotlin
class PillView(context: Context, val line: OcrLine) : AppCompatTextView(context) {
```
→
```kotlin
class PillView(context: Context, val block: OcrBlock) : AppCompatTextView(context) {
```
and in `init`:
```kotlin
        text = line.text
```
→
```kotlin
        text = block.text
```
```kotlin
        setAutoSizeTextTypeUniformWithConfiguration(4, max(5, line.box.height()), 1, TypedValue.COMPLEX_UNIT_PX)
```
→
```kotlin
        setAutoSizeTextTypeUniformWithConfiguration(4, max(5, block.lines.maxOf { it.height() }), 1, TypedValue.COMPLEX_UNIT_PX)
```

- [ ] **Step 6: Follow the rename in `java/SessionActivity.kt`**

In the class kdoc replace `then one selectable pill per OCR line. Tap pill = copy line,` with `then one selectable pill per OCR paragraph. Tap pill = copy paragraph,`. Replace the OCR call:

```kotlin
        Ocr.recognize(this, bmp, Prefs.scripts(this)) { lines -> runOnUiThread { onOcr(lines) } }
```
→
```kotlin
        Ocr.recognize(this, bmp, Prefs.scripts(this)) { found -> runOnUiThread { onOcr(found) } }
```
Replace `onOcr` and `showPills` (from `private fun onOcr(lines: List<OcrLine>) {` to the closing brace of `showPills`) with:

```kotlin
    private fun onOcr(found: List<OcrBlock>) {
        ocrRunning = false
        if (isDestroyed) {                    // session already gone: the bitmap is ours to free now
            shot?.recycle()
            shot = null
            return
        }
        if (closing) return
        b.root.doOnLayout {
            b.scrim.animate().cancel()
            b.scrim.animate().alpha(0f).setStartDelay(0L).setDuration(SCRIM_OUT).setInterpolator(EASE_IN)
            b.glow.settle()
            if (found.isEmpty()) showHint() else showPills(found)
        }
    }

    private fun showPills(found: List<OcrBlock>) {
        val padX = (PillView.PAD_X_DP * resources.displayMetrics.density).roundToInt()
        val padY = (PillView.PAD_Y_DP * resources.displayMetrics.density).roundToInt()
        val sorted = found.sortedWith(compareBy({ it.box.top }, { it.box.left }))
        val n = sorted.size
        sorted.forEachIndexed { i, block ->
            val pill = PillView(this, block)
            pill.layoutParams = FrameLayout.LayoutParams(block.box.width() + 2 * padX, block.box.height() + 2 * padY).apply {
                leftMargin = block.box.left - padX - origin[0]
                topMargin = block.box.top - padY - origin[1]
            }
            pill.selectionActive = { actionMode != null }
            pill.onTap = { p, dismissOnly ->
                if (dismissOnly) actionMode?.finish() else copyText(p, p.block.text)
            }
            pill.customSelectionActionModeCallback = selectionCallback(pill)
            b.pills.addView(pill)
            // a2: box i delay = round(i/(n-1) * boxStagger)
            pill.playIn(if (n > 1) (i * BOX_STAGGER / (n - 1)).roundToLong() else 0L)
        }
    }
```
Rename `private fun copyLine(pill: PillView, text: String) {` to `private fun copyText(pill: PillView, text: String) {` (body unchanged).

- [ ] **Step 7: Update `README.md`**

In "How it works" step 3 replace `then every recognised line` / `becomes a pill. Tap a pill to copy the line (the pill shines and flashes);` with `then every recognised paragraph` / `becomes a pill. Tap a pill to copy the paragraph (the pill shines and flashes);` (keep the line wrapping style of the file). In "OCR languages" replace `where two lines overlap (IoU > 0.5) the higher-confidence` / `one is kept` with `where two paragraphs overlap (IoU > 0.5) the higher-confidence` / `one is kept`.

- [ ] **Step 8: Run the gate and the greps**

Send `SETTLED` to the gate-runner, which runs: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug testDebugUnitTest; echo "RC=$?"`
Expected: `RC=0`; `grep -c "<testcase" app/build/test-results/testDebugUnitTest/TEST-us.jhangy.textify.OcrMergeTest.xml` → `7`; `grep -c "<failure" …OcrMergeTest.xml` → `0`.
Then: `grep -rn "OcrLine" app README.md` → no output; `grep -n "textBlocks" app/src/main/java/us/jhangy/textify/Ocr.kt` → one line; `grep -c "recognised paragraph" README.md` → `1`.

- [ ] **Step 9: Commit**

```bash
git rev-parse --abbrev-ref HEAD   # main
git add app/src/main/java/us/jhangy/textify/OcrMerge.kt app/src/main/java/us/jhangy/textify/Ocr.kt app/src/main/java/us/jhangy/textify/PillView.kt app/src/main/java/us/jhangy/textify/SessionActivity.kt app/src/test/java/us/jhangy/textify/OcrMergeTest.kt README.md
git commit -m "feat(ocr): recognise paragraphs (TextBlock) instead of lines

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>" -- app/src/main/java/us/jhangy/textify/OcrMerge.kt app/src/main/java/us/jhangy/textify/Ocr.kt app/src/main/java/us/jhangy/textify/PillView.kt app/src/main/java/us/jhangy/textify/SessionActivity.kt app/src/test/java/us/jhangy/textify/OcrMergeTest.kt README.md
git status --porcelain -- app/src/main/java/us/jhangy/textify/OcrMerge.kt app/src/main/java/us/jhangy/textify/Ocr.kt app/src/main/java/us/jhangy/textify/PillView.kt app/src/main/java/us/jhangy/textify/SessionActivity.kt app/src/test/java/us/jhangy/textify/OcrMergeTest.kt README.md   # empty
```

### Task 2: Multi-line original-mode `PillView`

**Files:**
- Create: `java/TextFit.kt`
- Modify: `java/PillView.kt` (whole file)
- Test: `test/TextFitTest.kt`

**Interfaces:**
- Consumes: `Box`, `OcrBlock`, `Rect.toBox()` (Task 1); `PillPainter`, `withAlpha` (`Glow.kt`); `TextViewCompat.setLineHeight` (androidx core 1.13.1).
- Produces:
  - `internal data class LineMetrics(val textPx: Float, val pitchPx: Float)`.
  - `internal const val TEXT_TO_BOX = 0.85f`.
  - `internal fun lineMetrics(lines: List<Box>): LineMetrics`.
  - `PillView` public surface unchanged from Task 1 (`block`, `onTap`, `selectionActive`, `selecting`, `playIn`, `playCopied`, `PAD_X_DP`, `PAD_Y_DP`); corner radius becomes `min(12dp, h/2)` (prism-tide header).

**Behavior:**
- Original mode (spec §1): transparent, selectable, multi-line text over the screenshot. The text keeps the OCR line breaks and never re-wraps (`setHorizontallyScrolling(true)`, no `setSingleLine`). Text size = median OCR line height × `TEXT_TO_BOX`; line pitch = mean distance between consecutive line tops (one line: its height), applied with `TextViewCompat.setLineHeight`; horizontal scale `textScaleX` = sum of line box widths / sum of measured line widths, clamped to 0.5–2. Gravity top-start, padding `PAD_X_DP` / `PAD_Y_DP` as before. Auto-size is removed.
- Tap copies the whole paragraph (unchanged wiring); long-press gives system selection handles that should sit on the screenshot glyphs.
- Box look unchanged except corner radius = `min(12dp, h/2)`; overlay flash, shine and selecting look use the same radius.
- `TEXT_TO_BOX` is a calibration knob (`ponytail:` comment); this is the highest technical risk of §1 and is checked on device.

**Constraints:**
- `TextFit.kt` has no Android imports.
- No `Build.VERSION` checks; no `TextView.setLineHeight`.

**Acceptance criteria:**
- [ ] Gate `RC=0`; `TEST-us.jhangy.textify.TextFitTest.xml` has `singleLineUsesItsHeight` and `pitchIsMeanTopDistance`, 0 failures; OcrMergeTest still 7/7.
- [ ] `grep -nE "setSingleLine|setAutoSizeTextTypeUniformWithConfiguration" app/src/main/java/us/jhangy/textify/PillView.kt` prints nothing; `grep -n "TextViewCompat.setLineHeight" app/src/main/java/us/jhangy/textify/PillView.kt` prints one line.
- [ ] On device: capture a screen with a paragraph of ≥ 3 wrapped lines (for example Android Settings → About phone, or any article in Chrome). Each paragraph shows one pill covering all its lines; tap pastes (into any text field) the paragraph with its line breaks; long-press shows selection handles on the line that was pressed, not shifted by more than one line. Report the observed vertical drift (none / < half a line / ≥ half a line). If ≥ half a line, adjust `TEXT_TO_BOX` once (0.75 or 0.95) and report the value kept.


**Steps:**

- [ ] **Step 1: Write the failing test** — create `test/TextFitTest.kt`:

```kotlin
package us.jhangy.textify

import org.junit.Assert.assertEquals
import org.junit.Test

class TextFitTest {

    @Test
    fun singleLineUsesItsHeight() {
        val m = lineMetrics(listOf(Box(0, 0, 100, 20)))
        assertEquals(20f * TEXT_TO_BOX, m.textPx, 1e-4f)
        assertEquals(20f, m.pitchPx, 1e-4f)
    }

    @Test
    fun pitchIsMeanTopDistance() {
        // tops 0, 30, 60; heights 20, 24, 20 -> median height 20, pitch 30
        val m = lineMetrics(listOf(Box(0, 0, 100, 20), Box(0, 30, 90, 54), Box(0, 60, 80, 80)))
        assertEquals(20f * TEXT_TO_BOX, m.textPx, 1e-4f)
        assertEquals(30f, m.pitchPx, 1e-4f)
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run (gate-runner): `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew testDebugUnitTest --tests us.jhangy.textify.TextFitTest; echo "RC=$?"`
Expected: `RC=1`, `Unresolved reference 'lineMetrics'`.

- [ ] **Step 3: Create `java/TextFit.kt`**

```kotlin
package us.jhangy.textify

/*
 * Pure layout and colour math for paragraph boxes (translation spec §1, §3). No Android imports: runs on the
 * JVM test runner.
 */

/** Text size and line pitch (px) that put a paragraph's TextView lines onto the screenshot's OCR line boxes. */
internal data class LineMetrics(val textPx: Float, val pitchPx: Float)

// ponytail: glyph em ≈ 0.85 × OCR line-box height; calibration knob, tune on device if handles drift
internal const val TEXT_TO_BOX = 0.85f

/** [lines] top to bottom, non-empty. Size from the median line height; pitch = mean distance between line tops (1 line: its height). */
internal fun lineMetrics(lines: List<Box>): LineMetrics {
    require(lines.isNotEmpty())
    val heights = lines.map { it.height }.sorted()
    val median = heights[heights.size / 2].toFloat()
    val pitch = if (lines.size < 2) median else (lines.last().top - lines.first().top).toFloat() / (lines.size - 1)
    return LineMetrics(median * TEXT_TO_BOX, maxOf(pitch, 1f))
}
```

- [ ] **Step 4: Replace `java/PillView.kt`**

```kotlin
package us.jhangy.textify

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Bundle
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.animation.doOnEnd
import androidx.core.widget.TextViewCompat
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One OCR paragraph as an a2 "Halo" box. The paragraph's text is real, selectable and transparent, laid out
 * line by line on the screenshot's OCR line boxes (translation spec §1), so long-press gives system handles
 * + copy menu on the glyphs. Tap is detected with GestureDetector: a selectable TextView is focusable in touch
 * mode, so the first tap would only take focus and never reach an OnClickListener.
 */
@SuppressLint("ViewConstructor")
class PillView(context: Context, val block: OcrBlock) : AppCompatTextView(context) {

    /** Single tap. dismissOnly = a text selection was alive when the finger went down. */
    var onTap: ((pill: PillView, dismissOnly: Boolean) -> Unit)? = null

    /** Supplied by the activity: is any selection ActionMode alive right now? */
    var selectionActive: () -> Boolean = { false }

    /** Set by the activity while this pill's selection ActionMode is alive (a2 .box.selecting). */
    var selecting = false
        set(value) {
            field = value
            invalidate()
        }

    private val dp = resources.displayMetrics.density
    private var painter: PillPainter? = null
    private var corner = 0f
    private val overlay = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillPath = Path()
    private var overlayAlpha = 0f
    private var shineX = Float.NaN
    private var overlayAnim: ValueAnimator? = null
    private var shineAnim: ValueAnimator? = null
    private var dismissOnly = false

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onTap?.invoke(this@PillView, dismissOnly)
            return false
        }

        override fun onLongPress(e: MotionEvent) = press(false)

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            press(false)
            return false
        }
    })

    init {
        text = block.text
        setTextColor(Color.TRANSPARENT)
        highlightColor = withAlpha(0.26f, 0x7C3AED)
        setTextIsSelectable(true)
        includeFontPadding = false
        gravity = Gravity.START or Gravity.TOP
        val px = (PAD_X_DP * dp).roundToInt()
        val py = (PAD_Y_DP * dp).roundToInt()
        setPadding(px, py, px, py)
        applyOriginalLayout()
        alpha = 0f
        scaleX = 0.9f
        scaleY = 0.9f
    }

    /** Spec §1: keep the OCR line breaks; size and pitch from the OCR line boxes; squeeze glyphs to their widths. */
    private fun applyOriginalLayout() {
        val boxes = block.lines.map { it.toBox() }
        val m = lineMetrics(boxes)
        setHorizontallyScrolling(true)                    // never re-wrap: one TextView line per OCR line
        maxLines = Int.MAX_VALUE
        setLineSpacing(0f, 1f)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, m.textPx)
        TextViewCompat.setLineHeight(this, m.pitchPx.roundToInt())
        textScaleX = 1f
        val measured = block.text.split('\n').sumOf { paint.measureText(it).toDouble() }
        val target = boxes.sumOf { it.width }.toDouble()
        textScaleX = if (measured > 0.0) (target / measured).toFloat().coerceIn(0.5f, 2f) else 1f
    }

    /** a2 boxIn: opacity 0 -> 1 and scale .9 -> 1 over 260 ms, cubic-bezier(.2,0,0,1), after [delayMs]. */
    fun playIn(delayMs: Long) {
        animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(delayMs).setDuration(BOX_IN).setInterpolator(EMPHASIZED)
    }

    /** a2 copy feedback: overlay flash 1 -> 0 (ease-out) and a white shine sweep, both over flash 380 ms. */
    fun playCopied() {
        animateOverlay(1f, 0L, EMPHASIZED)
        animateOverlay(0f, FLASH, EASE_OUT)
        val band = width * SHINE_BAND
        shineAnim?.cancel()
        shineAnim = ValueAnimator.ofFloat(-1.1f * band, width.toFloat()).apply {
            duration = FLASH
            interpolator = SHINE_CURVE
            addUpdateListener { shineX = it.animatedValue as Float; invalidate() }
            doOnEnd { shineX = Float.NaN; invalidate() }
            start()
        }
    }

    private fun press(on: Boolean) {
        val s = if (on) 1.03f else 1f
        animate().scaleX(s).scaleY(s).setStartDelay(0L).setDuration(BOX_IN).setInterpolator(EMPHASIZED)
        animateOverlay(if (on) 0.65f else 0f, PRESS_FADE, CSS_EASE)
    }

    private fun animateOverlay(to: Float, ms: Long, curve: TimeInterpolator) {
        overlayAnim?.cancel()
        if (ms == 0L) {
            overlayAlpha = to
            invalidate()
            return
        }
        overlayAnim = ValueAnimator.ofFloat(overlayAlpha, to).apply {
            duration = ms
            interpolator = curve
            addUpdateListener { overlayAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility") // accessibility clicks are handled in performAccessibilityAction
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dismissOnly = selectionActive()
                if (!dismissOnly) press(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> press(false)
        }
        gestures.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_CLICK) {
            onTap?.invoke(this, false)
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        val fw = w.toFloat()
        val fh = h.toFloat()
        corner = min(CORNER_DP * dp, fh / 2f)                // prism-tide: radius = min(12dp, h/2)
        // a2 box: LinearGradient #A78BFA 20% -> #F472B6 16%, glow #A78BFA 28% r12
        painter = PillPainter(withAlpha(0.20f, 0xA78BFA), withAlpha(0.16f, 0xF472B6), withAlpha(0.28f, 0xA78BFA), GLOW_R_DP * dp, corner)
            .also { it.setSize(w, h) }
        pillPath.reset()
        pillPath.addRoundRect(0f, 0f, fw, fh, corner, corner, Path.Direction.CW)
        overlay.shader = LinearGradient(0f, 0f, fw, 0f, withAlpha(0.28f, 0x7C3AED), withAlpha(0.26f, 0xEC4899), Shader.TileMode.CLAMP)
        val band = fw * SHINE_BAND
        shine.shader = LinearGradient(
            0f, 0f, band, 0f,
            intArrayOf(withAlpha(0f, 0xFFFFFF), withAlpha(0.9f, 0xFFFFFF), withAlpha(0f, 0xFFFFFF)), null, Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val saved = canvas.save()
        canvas.translate(scrollX.toFloat(), scrollY.toFloat())   // keep the pill fixed while the text scrolls
        painter?.draw(canvas, alpha = if (selecting) SELECTING else 1f, withGlow = !selecting)   // a2 .box.selecting: fill x.45, no glow
        if (overlayAlpha > 0f) {
            val layer = canvas.saveLayerAlpha(0f, 0f, w, h, (overlayAlpha.coerceIn(0f, 1f) * 255f).roundToInt())
            canvas.drawRoundRect(0f, 0f, w, h, corner, corner, overlay)
            canvas.restoreToCount(layer)
        }
        if (!shineX.isNaN()) {
            val clip = canvas.save()
            canvas.clipPath(pillPath)
            canvas.translate(shineX, 0f)
            canvas.drawRect(0f, 0f, w * SHINE_BAND, h, shine)
            canvas.restoreToCount(clip)
        }
        canvas.restoreToCount(saved)
        super.onDraw(canvas)   // transparent glyphs + selection highlight
    }

    override fun onDetachedFromWindow() {
        overlayAnim?.cancel()
        shineAnim?.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        const val PAD_X_DP = 7f
        const val PAD_Y_DP = 3f
        private const val CORNER_DP = 12f
        private const val GLOW_R_DP = 12f
        private const val BOX_IN = 260L
        private const val FLASH = 380L
        private const val PRESS_FADE = 140L
        private const val SHINE_BAND = 0.48f
        private const val SELECTING = 0.45f
        private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)      // a2 boxIn
        private val CSS_EASE = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)  // CSS "ease" (pressing overlay)
        private val EASE_OUT = PathInterpolator(0f, 0f, 0.58f, 1f)       // CSS "ease-out" (copied flash)
        private val SHINE_CURVE = PathInterpolator(0.4f, 0f, 0.2f, 1f)   // a2 shine
    }
}
```

- [ ] **Step 5: Run the gate**

Send `SETTLED`; gate-runner: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug testDebugUnitTest; echo "RC=$?"`
Expected: `RC=0`; `TEST-us.jhangy.textify.TextFitTest.xml` → 2 testcases, 0 failures; `TEST-us.jhangy.textify.OcrMergeTest.xml` → 7, 0 failures.
Then: `grep -nE "setSingleLine|setAutoSizeTextTypeUniformWithConfiguration" app/src/main/java/us/jhangy/textify/PillView.kt` → no output; `grep -n "TextViewCompat.setLineHeight" app/src/main/java/us/jhangy/textify/PillView.kt` → one line.

- [ ] **Step 6: On-device check (spec §1 risk)**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew installDebug   # gate-runner, detached
adb shell am start -a android.intent.action.VIEW -d "https://en.wikipedia.org/wiki/Paragraph"
```
Start Textify, tap the bubble on the article. Check: each paragraph has one box covering all its lines; tap a box, paste into a multi-line text field (for example the compose box of Google Messages or a new note) → the whole paragraph appears with its line breaks; long-press a word on the third line of a paragraph → handles appear on that line. Report the vertical drift class (none / < half a line / ≥ half a line). If ≥ half a line, change `TEXT_TO_BOX` once to 0.75 or 0.95 (whichever reduces drift), re-install, re-check and report the kept value.

- [ ] **Step 7: Commit**

```bash
git rev-parse --abbrev-ref HEAD   # main
git add app/src/main/java/us/jhangy/textify/TextFit.kt app/src/main/java/us/jhangy/textify/PillView.kt app/src/test/java/us/jhangy/textify/TextFitTest.kt
git commit -m "feat(session): multi-line paragraph pills laid out on OCR line boxes

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>" -- app/src/main/java/us/jhangy/textify/TextFit.kt app/src/main/java/us/jhangy/textify/PillView.kt app/src/test/java/us/jhangy/textify/TextFitTest.kt
git status --porcelain -- app/src/main/java/us/jhangy/textify/TextFit.kt app/src/main/java/us/jhangy/textify/PillView.kt app/src/test/java/us/jhangy/textify/TextFitTest.kt   # empty
```

### Task 3: Translation rules, OpenCC wrapper and language names

**Files:**
- Create: `java/TranslateRules.kt`, `java/OpenCc.kt`, `java/Languages.kt`
- Modify: `app/build.gradle.kts` (dependencies block)
- Test: `test/TranslateRulesTest.kt`, `test/OpenCcTest.kt`

**Interfaces:**
- Consumes: `com.github.houbb.opencc4j.util.ZhTwConverterUtil.toTraditional(String): String` (opencc4j 1.14.0).
- Produces (all in package `us.jhangy.textify`):
  - `sealed class ParagraphState` with `data object Translating`, `data object Downloading`, `data object WaitingWifi`, `data object Unsupported`, `data object Failed`, `data object NotNeeded`, `data class Done(val text: String, val source: String)`.
  - `data class TargetLang(val key: String, val mlkit: String, val openCc: Boolean)`; companion: `const val ZH_HANT = "zh-Hant"`, `const val ZH_HANS = "zh-Hans"`, `const val DEFAULT = ZH_HANT`, `fun of(key: String): TargetLang`, `fun keys(mlkitLanguages: Collection<String>): List<String>`, `fun modelKeys(mlkitLanguages: Collection<String>): List<String>`.
  - `sealed class Route` with `data object NotNeeded`, `data object OpenCcOnly`, `data object Unsupported`, `data class MlKit(val source: String, val openCc: Boolean)`.
  - `const val UNDETERMINED = "und"`.
  - `internal fun route(detected: String, mlkitSource: String?, target: TargetLang): Route`.
  - `internal fun openCcOnlyResult(original: String, converted: String): ParagraphState`.
  - `internal fun flatten(text: String, source: String?): String`.
  - `enum class Chip { DOWNLOADING, WAITING_WIFI, UNSUPPORTED, FAILED }`; `internal fun chipFor(state: ParagraphState?): Chip?`.
  - `internal fun anyTranslating(states: Collection<ParagraphState?>): Boolean`.
  - `internal fun needsRun(state: ParagraphState?): Boolean`.
  - `enum class ModelState { BUILTIN, DONE, DOWNLOADING, WAITING_WIFI }`; `internal fun modelRows(downloaded: Set<String>, downloading: Set<String>, waiting: Set<String>): List<Pair<String, ModelState>>`.
  - `object OpenCc { fun toTaiwan(text: String): String }`.
  - `object Languages { fun zhName(code: String): String; fun nativeName(code: String): String; fun targetTitle(key: String): String; fun targetSub(key: String): String; fun modelTitle(code: String): String; fun modelSub(code: String): String; fun glyph(code: String): String; fun discColors(code: String): IntArray }`.

**Behavior:**
- `route` (spec §2 step 2): `detected == "und"` → `NotNeeded`; `mlkitSource == null` (ML Kit cannot translate the detected language) → `Unsupported`; `mlkitSource == target.mlkit` → `OpenCcOnly` when `target.openCc` (Chinese source, 繁體中文 target; also converts on-screen Simplified to Traditional), else `NotNeeded`; otherwise `MlKit(mlkitSource, target.openCc)`.
- `openCcOnlyResult` (FP): converted text equal to the original → `NotNeeded` (keeps the original look); otherwise `Done(converted, "zh")`.
- `flatten`: OCR `\n` → `""` when `source` is `zh` or `ja`, → `" "` otherwise (including `null` = before detection).
- `TargetLang.of`: `zh-Hant` → (`zh`, OpenCC on); `zh-Hans` → (`zh`, OpenCC off); any other key → (key, OpenCC off). `keys`: `zh-Hant`, `zh-Hans`, then `en`, `ja`, `ko` (those present), then the remaining ML Kit codes alphabetically; ML Kit `zh` itself is not listed. `modelKeys`: the same order with `zh-Hant` → `zh` and `zh-Hans` removed.
- `chipFor`: Downloading → `DOWNLOADING`, WaitingWifi → `WAITING_WIFI`, Unsupported → `UNSUPPORTED`, Failed → `FAILED`, anything else (incl. null) → null.
- `anyTranslating`: true when any state is `Translating` (FP: the button spins only then).
- `needsRun`: null, `WaitingWifi`, `Failed` → true (spec §2 retry on next press, §5 retry after download failure).
- `modelRows` (spec §4): first row is always `en` → `BUILTIN`; then every other code in downloaded ∪ downloading ∪ waiting, sorted by code; state priority DOWNLOADING > DONE > WAITING_WIFI.
- `OpenCc.toTaiwan`: `ZhTwConverterUtil.toTraditional`; on any `Exception` or `LinkageError` returns the input unchanged (spec §5). Blocking; callers run it off the main thread.
- `Languages`: names from `java.util.Locale` (`zhName` in zh-TW, `nativeName` in the language itself with first letter title-cased). Fixed labels: target `zh-Hant` → 「繁體中文」 / 「中文（台灣用語）」, `zh-Hans` → 「简体中文」 / 「簡體中文」; model `zh` → 「中文」 / 「繁體、簡體共用」. Glyph map from the mockup (`zh` 中, `en` A, `ja` あ, `ko` 한, `th` ก, `ru` Ру, `ar` ع, `hi` हि, …), otherwise the capitalised code. Disc colours: `zh` #A78BFA→#818CF8, `en` #7C3AED→#A78BFA, `ja` #F472B6→#FDBA74, `ko` #67E8F9→#818CF8, others from a fixed 10-pair list chosen by the code's hash.

**Constraints:**
- No Android imports in the three new files (they run in JVM tests).
- `implementation("com.github.houbb:opencc4j:1.14.0")` exactly; no jieba.

**Acceptance criteria:**
- [ ] Gate `RC=0`; `TEST-us.jhangy.textify.TranslateRulesTest.xml` has 12 testcases (names in Step 1) and `TEST-us.jhangy.textify.OpenCcTest.xml` has 3 (`simplifiedBecomesTaiwanPhrases`, `traditionalUnchanged`, `lineBreaksKept`), 0 failures.
- [ ] `grep -n "opencc4j:1.14.0" app/build.gradle.kts` prints one line.
- [ ] `grep -nE "^import android" app/src/main/java/us/jhangy/textify/{TranslateRules,OpenCc,Languages}.kt` prints nothing.


**Steps:**

- [ ] **Step 1: Write the failing tests** — create `test/TranslateRulesTest.kt`:

```kotlin
package us.jhangy.textify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslateRulesTest {

    private val hant = TargetLang.of(TargetLang.ZH_HANT)
    private val hans = TargetLang.of(TargetLang.ZH_HANS)
    private val en = TargetLang.of("en")

    @Test
    fun undeterminedIsNotNeeded() {
        assertEquals(Route.NotNeeded, route(UNDETERMINED, null, hant))
    }

    @Test
    fun unsupportedSource() {
        assertEquals(Route.Unsupported, route("la", null, hant))
    }

    @Test
    fun chineseToTraditionalIsOpenCcOnly() {
        assertEquals(Route.OpenCcOnly, route("zh", "zh", hant))
    }

    @Test
    fun chineseToSimplifiedIsNotNeeded() {
        assertEquals(Route.NotNeeded, route("zh", "zh", hans))
    }

    @Test
    fun sameLanguageIsNotNeeded() {
        assertEquals(Route.NotNeeded, route("ja", "ja", TargetLang.of("ja")))
    }

    @Test
    fun otherSourceUsesMlKitWithOpenCcForTraditional() {
        assertEquals(Route.MlKit("en", true), route("en", "en", hant))
        assertEquals(Route.MlKit("en", false), route("en", "en", hans))
        assertEquals(Route.MlKit("ja", false), route("ja", "ja", en))
    }

    @Test
    fun unchangedOpenCcOutputIsNotNeeded() {
        val traditional = "好，我週五晚上到。"
        assertEquals(ParagraphState.NotNeeded, openCcOnlyResult(traditional, OpenCc.toTaiwan(traditional)))
        assertEquals(ParagraphState.Done("使用網際網路", "zh"), openCcOnlyResult("使用互联网", OpenCc.toTaiwan("使用互联网")))
    }

    @Test
    fun flattenJoinsLines() {
        assertEquals("Heads up: the museum", flatten("Heads up:\nthe museum", null))
        assertEquals("Heads up: the museum", flatten("Heads up:\nthe museum", "en"))
        assertEquals("我已經把行程表更新", flatten("我已經把\n行程表更新", "zh"))
        assertEquals("チェックインは15時から", flatten("チェックインは\n15時から", "ja"))
        assertEquals("내일 아침", flatten("내일\n아침", "ko"))
    }

    @Test
    fun targetKeysOrder() {
        val ml = listOf("zh", "ko", "fr", "en", "af", "ja")
        assertEquals(listOf("zh-Hant", "zh-Hans", "en", "ja", "ko", "af", "fr"), TargetLang.keys(ml))
        assertEquals(listOf("zh", "en", "ja", "ko", "af", "fr"), TargetLang.modelKeys(ml))
    }

    @Test
    fun targetOfKeys() {
        assertEquals(TargetLang("zh-Hant", "zh", true), TargetLang.of("zh-Hant"))
        assertEquals(TargetLang("zh-Hans", "zh", false), TargetLang.of("zh-Hans"))
        assertEquals(TargetLang("fr", "fr", false), TargetLang.of("fr"))
        assertEquals("zh-Hant", TargetLang.DEFAULT)
    }

    @Test
    fun chipsButtonAndRetry() {
        assertEquals(Chip.DOWNLOADING, chipFor(ParagraphState.Downloading))
        assertEquals(Chip.WAITING_WIFI, chipFor(ParagraphState.WaitingWifi))
        assertEquals(Chip.UNSUPPORTED, chipFor(ParagraphState.Unsupported))
        assertEquals(Chip.FAILED, chipFor(ParagraphState.Failed))
        assertNull(chipFor(ParagraphState.Translating))
        assertNull(chipFor(ParagraphState.Done("x", "en")))
        assertNull(chipFor(null))
        assertTrue(anyTranslating(listOf(null, ParagraphState.Downloading, ParagraphState.Translating)))
        assertFalse(anyTranslating(listOf(ParagraphState.Downloading, ParagraphState.Done("x", "en"))))
        assertTrue(needsRun(null))
        assertTrue(needsRun(ParagraphState.WaitingWifi))
        assertTrue(needsRun(ParagraphState.Failed))
        assertFalse(needsRun(ParagraphState.Downloading))
        assertFalse(needsRun(ParagraphState.NotNeeded))
        assertFalse(needsRun(ParagraphState.Done("x", "en")))
    }

    @Test
    fun modelRowsBuiltinFirstAndPriority() {
        val rows = modelRows(downloaded = setOf("en", "zh", "ja"), downloading = setOf("ko", "ja"), waiting = setOf("fr", "zh"))
        assertEquals(
            listOf(
                "en" to ModelState.BUILTIN,
                "fr" to ModelState.WAITING_WIFI,
                "ja" to ModelState.DOWNLOADING,
                "ko" to ModelState.DOWNLOADING,
                "zh" to ModelState.DONE,
            ),
            rows,
        )
        assertEquals(listOf("en" to ModelState.BUILTIN), modelRows(emptySet(), emptySet(), emptySet()))
    }
}
```

Create `test/OpenCcTest.kt`:

```kotlin
package us.jhangy.textify

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCcTest {

    @Test
    fun simplifiedBecomesTaiwanPhrases() {
        assertEquals("使用網際網路", OpenCc.toTaiwan("使用互联网"))
        assertEquals("軟體和記憶體", OpenCc.toTaiwan("软件和内存"))
    }

    @Test
    fun traditionalUnchanged() {
        assertEquals("吉卜力美術館預約", OpenCc.toTaiwan("吉卜力美術館預約"))
    }

    @Test
    fun lineBreaksKept() {
        assertEquals("軟體\n記憶體", OpenCc.toTaiwan("软件\n内存"))
    }
}
```

(Expected values were verified on the JVM against the registry jars `opencc4j-1.14.0`, `heaven-0.13.0`, `nlp-common-0.0.5`.)

- [ ] **Step 2: Run them to make sure they fail**

Run (gate-runner): `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew testDebugUnitTest --tests us.jhangy.textify.TranslateRulesTest --tests us.jhangy.textify.OpenCcTest; echo "RC=$?"`
Expected: `RC=1`, `Unresolved reference 'TargetLang'` / `'OpenCc'`.

- [ ] **Step 3: Add the OpenCC dependency** — in `app/build.gradle.kts`, after the three `text-recognition` lines, add:

```kotlin

    // Traditional Chinese output (translation spec decision 7): OpenCC s2twp via the pure-Java opencc4j port.
    // 1.14.0 = Maven Central latest (2025-09-05); pulls heaven 0.13.0 + nlp-common 0.0.5 (~2.9 MB), jieba stays optional.
    implementation("com.github.houbb:opencc4j:1.14.0")
```

- [ ] **Step 4: Create `java/OpenCc.kt`**

```kotlin
package us.jhangy.textify

import com.github.houbb.opencc4j.util.ZhTwConverterUtil

/**
 * OpenCC "Simplified -> Taiwan standard with Taiwan phrases" (s2twp) through opencc4j (translation spec
 * decision 7). Blocking; the first call loads the dictionaries, so call it off the main thread.
 */
object OpenCc {
    /** [text] converted, or [text] unchanged when the conversion fails (spec §5: never fail the paragraph). */
    fun toTaiwan(text: String): String = try {
        ZhTwConverterUtil.toTraditional(text)
    } catch (e: Exception) {
        text
    } catch (e: LinkageError) {          // dictionary / class initialisation failure on a device
        text
    }
}
```

- [ ] **Step 5: Create `java/TranslateRules.kt`**

```kotlin
package us.jhangy.textify

/*
 * Pure translation rules (translation spec §2, §3, §4, final picks). No Android or ML Kit imports: everything
 * here runs on the JVM test runner; Translate.kt feeds it ML Kit results.
 */

/** Per-paragraph translation state (spec §2). */
sealed class ParagraphState {
    data object Translating : ParagraphState()
    data object Downloading : ParagraphState()
    data object WaitingWifi : ParagraphState()
    data object Unsupported : ParagraphState()
    data object Failed : ParagraphState()
    data object NotNeeded : ParagraphState()

    /** [source] = ML Kit code of the detected source language ("zh" for the OpenCC-only route). */
    data class Done(val text: String, val source: String) : ParagraphState()
}

/** Target language. [key] is what Prefs stores; [mlkit] = ML Kit target code; [openCc] = convert the result with OpenCC s2twp. */
data class TargetLang(val key: String, val mlkit: String, val openCc: Boolean) {
    companion object {
        const val ZH_HANT = "zh-Hant"
        const val ZH_HANS = "zh-Hans"
        const val DEFAULT = ZH_HANT

        /** 繁體中文 = ML Kit zh + OpenCC; 简体中文 = ML Kit zh as is; any other key is an ML Kit code. */
        fun of(key: String): TargetLang = when (key) {
            ZH_HANT -> TargetLang(key, "zh", true)
            ZH_HANS -> TargetLang(key, "zh", false)
            else -> TargetLang(key, key, false)
        }

        /** Target list (spec §2): zh-Hant, zh-Hans, then en, ja, ko, then the other ML Kit codes alphabetically. */
        fun keys(mlkitLanguages: Collection<String>): List<String> {
            val rest = mlkitLanguages.filter { it != "zh" }
            val first = listOf("en", "ja", "ko").filter { it in rest }
            return listOf(ZH_HANT, ZH_HANS) + first + (rest - first.toSet()).sorted()
        }

        /** Model list order for the pre-download sheet: the same order, one "zh" model for both Chinese targets. */
        fun modelKeys(mlkitLanguages: Collection<String>): List<String> =
            keys(mlkitLanguages).filter { it != ZH_HANS }.map { of(it).mlkit }
    }
}

/** What to do with one paragraph (spec §2 step 2). */
sealed class Route {
    data object NotNeeded : Route()
    data object OpenCcOnly : Route()
    data object Unsupported : Route()
    data class MlKit(val source: String, val openCc: Boolean) : Route()
}

/** ML Kit language-id tag for "undetermined" (LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG). */
const val UNDETERMINED = "und"

/**
 * [detected] = language-id tag ("und" when undetermined or identification failed, spec §5);
 * [mlkitSource] = TranslateLanguage.fromLanguageTag(detected), null when ML Kit cannot translate it.
 */
internal fun route(detected: String, mlkitSource: String?, target: TargetLang): Route = when {
    detected == UNDETERMINED -> Route.NotNeeded
    mlkitSource == null -> Route.Unsupported
    mlkitSource == target.mlkit -> if (target.openCc) Route.OpenCcOnly else Route.NotNeeded
    else -> Route.MlKit(mlkitSource, target.openCc)
}

/** Final pick: an OpenCC-only paragraph whose conversion changes nothing keeps the original look. */
internal fun openCcOnlyResult(original: String, converted: String): ParagraphState =
    if (converted == original) ParagraphState.NotNeeded else ParagraphState.Done(converted, "zh")

private val NO_SPACE_SOURCES = setOf("zh", "ja")

/** Text for language-id ([source] null) and ML Kit translate: OCR line breaks dropped for zh/ja, one space otherwise. */
internal fun flatten(text: String, source: String?): String =
    text.replace("\n", if (source in NO_SPACE_SOURCES) "" else " ")

/** Status chip kinds in a paragraph corner (spec §3). */
enum class Chip { DOWNLOADING, WAITING_WIFI, UNSUPPORTED, FAILED }

internal fun chipFor(state: ParagraphState?): Chip? = when (state) {
    ParagraphState.Downloading -> Chip.DOWNLOADING
    ParagraphState.WaitingWifi -> Chip.WAITING_WIFI
    ParagraphState.Unsupported -> Chip.UNSUPPORTED
    ParagraphState.Failed -> Chip.FAILED
    else -> null
}

/** Final pick: the translate button spins only while at least one paragraph is Translating. */
internal fun anyTranslating(states: Collection<ParagraphState?>): Boolean = states.any { it == ParagraphState.Translating }

/** Paragraphs a translate press (re)starts: never run, waiting for Wi-Fi, or failed (spec §2, §5). */
internal fun needsRun(state: ParagraphState?): Boolean =
    state == null || state == ParagraphState.WaitingWifi || state == ParagraphState.Failed

/** Settings model row states (spec §4). */
enum class ModelState { BUILTIN, DONE, DOWNLOADING, WAITING_WIFI }

/** Model rows: English first (built into ML Kit), then every other known model by code; downloading > done > waiting. */
internal fun modelRows(downloaded: Set<String>, downloading: Set<String>, waiting: Set<String>): List<Pair<String, ModelState>> {
    val others = (downloaded + downloading + waiting - "en").sorted()
    return listOf("en" to ModelState.BUILTIN) + others.map { code ->
        code to when (code) {
            in downloading -> ModelState.DOWNLOADING
            in downloaded -> ModelState.DONE
            else -> ModelState.WAITING_WIFI
        }
    }
}
```

- [ ] **Step 6: Create `java/Languages.kt`**

```kotlin
package us.jhangy.textify

import java.util.Locale

/** Display names, disc glyphs and disc colours for translation languages (prism-tide mockup LANGS). */
object Languages {
    private val TW: Locale = Locale.forLanguageTag("zh-TW")

    /** zh-TW name of an ML Kit code, e.g. "en" -> 英文, "ja" -> 日文, "zh" -> 中文. */
    fun zhName(code: String): String = Locale.forLanguageTag(code).getDisplayLanguage(TW).ifEmpty { code }

    /** Name in the language itself, first letter title-cased, e.g. "fr" -> Français, "ja" -> 日本語. */
    fun nativeName(code: String): String {
        val l = Locale.forLanguageTag(code)
        return l.getDisplayLanguage(l).ifEmpty { code }.replaceFirstChar { it.titlecase(l) }
    }

    /** Target sheet row title (and the value shown in the target row). */
    fun targetTitle(key: String): String = when (key) {
        TargetLang.ZH_HANT -> "繁體中文"
        TargetLang.ZH_HANS -> "简体中文"
        else -> nativeName(key)
    }

    /** Target sheet row subtitle. */
    fun targetSub(key: String): String = when (key) {
        TargetLang.ZH_HANT -> "中文（台灣用語）"
        TargetLang.ZH_HANS -> "簡體中文"
        else -> zhName(key)
    }

    /** Model row title: one Chinese model serves both Chinese targets. */
    fun modelTitle(code: String): String = if (code == "zh") "中文" else nativeName(code)

    fun modelSub(code: String): String = if (code == "zh") "繁體、簡體共用" else zhName(code)

    private val GLYPHS = mapOf(
        "zh" to "中", "en" to "A", "ja" to "あ", "ko" to "한", "th" to "ก", "ru" to "Ру", "ar" to "ع", "hi" to "हि",
        "el" to "Ελ", "he" to "א", "uk" to "Ук", "bg" to "Бг", "be" to "Бе", "mk" to "Мк", "ka" to "ქ", "fa" to "ف",
        "ur" to "ا", "bn" to "বা", "gu" to "ગુ", "kn" to "ಕ", "mr" to "म", "ta" to "த", "te" to "తె",
    )

    /** Disc glyph: script sample for non-Latin languages, otherwise the capitalised code ("fr" -> Fr). */
    fun glyph(code: String): String = GLYPHS[code] ?: code.replaceFirstChar { it.uppercaseChar() }

    private val FIXED = mapOf(
        "zh" to intArrayOf(0xFFA78BFA.toInt(), 0xFF818CF8.toInt()),
        "en" to intArrayOf(0xFF7C3AED.toInt(), 0xFFA78BFA.toInt()),
        "ja" to intArrayOf(0xFFF472B6.toInt(), 0xFFFDBA74.toInt()),
        "ko" to intArrayOf(0xFF67E8F9.toInt(), 0xFF818CF8.toInt()),
    )
    private val PAIRS = listOf(
        0xFFF472B6 to 0xFFA78BFA, 0xFF818CF8 to 0xFF67E8F9, 0xFFFDBA74 to 0xFFF472B6, 0xFF67E8F9 to 0xFFA78BFA,
        0xFFFDBA74 to 0xFFA78BFA, 0xFFA78BFA to 0xFF67E8F9, 0xFFF472B6 to 0xFF818CF8, 0xFF818CF8 to 0xFFF472B6,
        0xFF67E8F9 to 0xFFFDBA74, 0xFFFDBA74 to 0xFF67E8F9,
    )

    /** Two ARGB colours for the disc's 135° gradient (stable per code). */
    fun discColors(code: String): IntArray = FIXED[code] ?: PAIRS[Math.floorMod(code.hashCode(), PAIRS.size)]
        .let { (a, b) -> intArrayOf(a.toInt(), b.toInt()) }
}
```

- [ ] **Step 7: Run the gate**

Send `SETTLED`; gate-runner: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug testDebugUnitTest; echo "RC=$?"`
Expected: `RC=0`; `TEST-us.jhangy.textify.TranslateRulesTest.xml` → 12 testcases, `TEST-us.jhangy.textify.OpenCcTest.xml` → 3, 0 failures; earlier tests unchanged.
If `mergeDebugJavaResource` fails on a duplicate `META-INF/…` file from the new jars, add inside `android { }`:
```kotlin
    packaging {
        resources { excludes += "META-INF/<the file named in the error>" }
    }
```
re-run, and report the excluded path.
Then: `grep -n "opencc4j:1.14.0" app/build.gradle.kts` → one line; `grep -nE "^import android" app/src/main/java/us/jhangy/textify/TranslateRules.kt app/src/main/java/us/jhangy/textify/OpenCc.kt app/src/main/java/us/jhangy/textify/Languages.kt` → no output.

- [ ] **Step 8: Commit**

```bash
git rev-parse --abbrev-ref HEAD   # main
git add app/build.gradle.kts app/src/main/java/us/jhangy/textify/TranslateRules.kt app/src/main/java/us/jhangy/textify/OpenCc.kt app/src/main/java/us/jhangy/textify/Languages.kt app/src/test/java/us/jhangy/textify/TranslateRulesTest.kt app/src/test/java/us/jhangy/textify/OpenCcTest.kt
git commit -m "feat(translate): pure routing rules, OpenCC s2twp wrapper, language names

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>" -- app/build.gradle.kts app/src/main/java/us/jhangy/textify/TranslateRules.kt app/src/main/java/us/jhangy/textify/OpenCc.kt app/src/main/java/us/jhangy/textify/Languages.kt app/src/test/java/us/jhangy/textify/TranslateRulesTest.kt app/src/test/java/us/jhangy/textify/OpenCcTest.kt
git status --porcelain -- app/build.gradle.kts app/src/main/java/us/jhangy/textify/TranslateRules.kt app/src/main/java/us/jhangy/textify/OpenCc.kt app/src/main/java/us/jhangy/textify/Languages.kt app/src/test/java/us/jhangy/textify/TranslateRulesTest.kt app/src/test/java/us/jhangy/textify/OpenCcTest.kt   # empty
```

### Task 4: Translation engine, prefs, permissions and README

**Files:**
- Create: `java/Translate.kt`
- Modify: `java/Prefs.kt` (add four functions and two keys)
- Modify: `app/build.gradle.kts` (dependencies block)
- Modify: `app/src/main/AndroidManifest.xml` (two permissions)
- Modify: `README.md` (intro, new "Translation" section, OCR offline sentence, Stack line)

**Interfaces:**
- Consumes: `TargetLang`, `ParagraphState`, `Route`, `route`, `openCcOnlyResult`, `flatten`, `UNDETERMINED`, `OpenCc.toTaiwan` (Task 3).
- Produces:
  - `Prefs.target(ctx: Context): TargetLang` (default `TargetLang.DEFAULT`), `Prefs.setTarget(ctx: Context, key: String)`, `Prefs.wifiOnly(ctx: Context): Boolean` (default true), `Prefs.setWifiOnly(ctx: Context, on: Boolean)`.
  - `object Translate`: `var onModelsChanged: (() -> Unit)?`; `val downloading: Set<String>`; `val waitingWifi: Set<String>`; `fun languages(): List<String>`; `fun isOnWifi(ctx: Context): Boolean`; `fun download(ctx: Context, lang: String): Task<Void>?`; `fun delete(lang: String, onDone: () -> Unit)`; `fun downloaded(onResult: (Set<String>) -> Unit)`; `fun start(ctx: Context, paragraphs: Map<Int, String>, target: TargetLang, onState: (Int, ParagraphState) -> Unit): TranslateRun`.
  - `class TranslateRun { fun close() }`.

**Behavior:**
- `start` (spec §2): reports `Translating` for every given paragraph, identifies each paragraph's language with one `LanguageIdentifier` (input `flatten(text, null)`; failure → `und`, spec §5), then routes with `route(tag, TranslateLanguage.fromLanguageTag(tag), target)`:
  - `NotNeeded` / `Unsupported` → reported at once;
  - `OpenCcOnly` → OpenCC on a background thread, then `openCcOnlyResult(original, converted)`;
  - `MlKit(source, openCc)` → grouped by source language: one model check/download and one `Translator` per source. Models needed = {source, target.mlkit}. All on device → `Translating`, translate each paragraph's `flatten(text, source)`; success → `Done(result, source)` (OpenCC first when `openCc`; OpenCC failure returns the ML Kit text, spec §5); failure → only that paragraph `Failed`.
  - A model is missing → `download()`; if any needed model returns null (Wi-Fi-only, no Wi-Fi) the group becomes `WaitingWifi`; otherwise `Downloading` until `Tasks.whenAll` completes → translate, or `Failed` (spec §5; the language leaves the downloading set).
- `download` uses `RemoteModelManager` (survives the session closing), joins an in-flight download of the same language, never queues when `Prefs.wifiOnly` and `!isOnWifi` (adds the language to `waitingWifi`), passes `requireWifi()` when Wi-Fi-only is on, and removes a language from `waitingWifi` when its download starts. `downloading` / `waitingWifi` are process-wide and read by the session and Settings; `onModelsChanged` fires on every change.
- `isOnWifi`: active network has `NetworkCapabilities.TRANSPORT_WIFI`.
- `TranslateRun.close()`: drops all later callbacks (spec §5 "late results are ignored"), closes the identifier and translators; downloads continue.
- All callbacks run on the main thread (ML Kit `Task` listeners default to it; OpenCC results are posted back).
- README: intro adds on-device translation; OCR section says "OCR runs fully offline; translation runs offline after a one-time model download (~30 MB per language)"; new "Translation" section describes the button, routing and Settings; Stack line lists the three new artifacts.

**Constraints:**
- Only file calling `com.google.mlkit.nl.*`: `Translate.kt`.
- Manifest adds exactly `android.permission.INTERNET` and `android.permission.ACCESS_NETWORK_STATE`.
- No UI yet; the engine's on-device checks run in Task 7 (first caller).

**Acceptance criteria:**
- [ ] Gate `RC=0` (all earlier tests still pass).
- [ ] `grep -rln "com.google.mlkit.nl" app/src/main/java` prints only `app/src/main/java/us/jhangy/textify/Translate.kt`.
- [ ] `grep -cE "android.permission.(INTERNET|ACCESS_NETWORK_STATE)" app/src/main/AndroidManifest.xml` prints `2`.
- [ ] `grep -nE "mlkit:translate:17.0.3|mlkit:language-id:17.0.6" app/build.gradle.kts` prints two lines.
- [ ] `grep -n 'abiFilters += "arm64-v8a"' app/build.gradle.kts` prints one line; after the gate, `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -c "lib/arm64-v8a/"` is non-zero and `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -cE "lib/(x86|x86_64|armeabi-v7a)/"` prints `0`. Report the APK size.
- [ ] `grep -c "offline after a one-time model download" README.md` prints `1`; `grep -c "fully offline" README.md` prints `1` (only the OCR sentence).


**Steps:**

(No JVM test in this task: ML Kit does not run on the JVM. The routing it applies is covered by Task 3's tests; the engine is exercised on device in Task 7.)

- [ ] **Step 1: Add the ML Kit dependencies** — in `app/build.gradle.kts`, after the opencc4j line from Task 3, add:

```kotlin

    // On-device translation (translation spec §2): ML Kit translate (models downloaded on demand through
    // RemoteModelManager) + bundled language identification. Google Maven latest, verified 2026-10-11.
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
```

- [ ] **Step 1b: Package arm64 only (user decision)** — in `app/build.gradle.kts` `defaultConfig`, after `versionName = "0.2"`, add:

```kotlin
        // ponytail: arm64 only (user decision 2026-10-11) — the ML Kit .so libs would otherwise ship 4 ABIs (~118 MB APK).
        // Drops x86 emulators and 32-bit-only phones; add "armeabi-v7a" back if one of those is needed.
        ndk { abiFilters += "arm64-v8a" }
```

- [ ] **Step 2: Add the permissions** — in `app/src/main/AndroidManifest.xml`, after `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`, add:

```xml
    <!-- translation (spec §2): ML Kit model downloads + the Wi-Fi-only network check -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

- [ ] **Step 3: Replace `java/Prefs.kt`**

```kotlin
package us.jhangy.textify

import android.content.Context

/** ML Kit script models. Every model also reads Latin/English. */
enum class Script { CHINESE, JAPANESE, KOREAN }

object Prefs {
    private const val FILE = "textify"
    private const val SCRIPTS_KEY = "scripts"                 // StringSet of Script names
    private const val TARGET_KEY = "translate_target"         // TargetLang.key
    private const val WIFI_ONLY_KEY = "translate_wifi_only"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Selected scripts in enum order; unknown names dropped; empty or missing -> CHINESE. */
    fun scripts(ctx: Context): Set<Script> {
        val names = prefs(ctx).getStringSet(SCRIPTS_KEY, null).orEmpty()
        return Script.entries.filterTo(LinkedHashSet()) { it.name in names }.ifEmpty { setOf(Script.CHINESE) }
    }

    fun setScripts(ctx: Context, scripts: Set<Script>) {
        require(scripts.isNotEmpty())
        prefs(ctx).edit().putStringSet(SCRIPTS_KEY, scripts.mapTo(HashSet()) { it.name }).apply()
    }

    /** Translation target (translation spec §4); default 繁體中文. */
    fun target(ctx: Context): TargetLang = TargetLang.of(prefs(ctx).getString(TARGET_KEY, null) ?: TargetLang.DEFAULT)

    fun setTarget(ctx: Context, key: String) {
        prefs(ctx).edit().putString(TARGET_KEY, key).apply()
    }

    /** 只用 Wi-Fi 下載 (spec §4); default on. */
    fun wifiOnly(ctx: Context): Boolean = prefs(ctx).getBoolean(WIFI_ONLY_KEY, true)

    fun setWifiOnly(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(WIFI_ONLY_KEY, on).apply()
    }
}
```

- [ ] **Step 4: Create `java/Translate.kt`**

```kotlin
package us.jhangy.textify

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.Executors

/**
 * On-device translation engine (translation spec §2): the only code that calls ML Kit translation and
 * language identification. Main thread only; OpenCC runs on [openCcThread] and posts back.
 */
object Translate {
    private val main = Handler(Looper.getMainLooper())
    private val openCcThread = Executors.newSingleThreadExecutor()
    private val inFlight = HashMap<String, Task<Void>>()
    private val waiting = LinkedHashSet<String>()

    /** Fired on the main thread whenever [downloading] or [waitingWifi] changes; Settings sets it while resumed. */
    var onModelsChanged: (() -> Unit)? = null

    /** ML Kit codes whose model download is running, process-wide (the session screen and Settings both read it). */
    val downloading: Set<String> get() = inFlight.keys.toSet()

    /** ML Kit codes that needed a download while Wi-Fi-only was on and Wi-Fi was absent (spec §2). */
    val waitingWifi: Set<String> get() = waiting.toSet()

    /** Every ML Kit translation language code. */
    fun languages(): List<String> = TranslateLanguage.getAllLanguages()

    /** The active network is Wi-Fi (needs ACCESS_NETWORK_STATE). */
    fun isOnWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Starts (or joins) the model download for ML Kit code [lang] through RemoteModelManager, so it survives the
     * session screen closing. Returns null without queuing anything when Wi-Fi-only is on and Wi-Fi is absent;
     * [lang] is then listed in [waitingWifi] until a later download of it starts.
     */
    fun download(ctx: Context, lang: String): Task<Void>? {
        inFlight[lang]?.let { return it }
        val wifiOnly = Prefs.wifiOnly(ctx)
        if (wifiOnly && !isOnWifi(ctx)) {
            if (waiting.add(lang)) changed()
            return null
        }
        waiting.remove(lang)
        val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        val task = RemoteModelManager.getInstance().download(model(lang), conditions)
        inFlight[lang] = task
        task.addOnCompleteListener { inFlight.remove(lang); changed() }   // §5: failure also leaves the set
        changed()
        return task
    }

    /** Deletes the downloaded model of [lang] (no confirmation, final pick); [onDone] runs on success or failure. */
    fun delete(lang: String, onDone: () -> Unit) {
        RemoteModelManager.getInstance().deleteDownloadedModel(model(lang)).addOnCompleteListener { onDone() }
    }

    /** Downloaded model codes as ML Kit reports them now (spec §4: nothing stored separately); empty on failure. */
    fun downloaded(onResult: (Set<String>) -> Unit) {
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models -> onResult(models.mapTo(HashSet()) { it.language }) }
            .addOnFailureListener { onResult(emptySet()) }
    }

    /** Translates [paragraphs] (paragraph index -> text) into [target]; [onState] runs on the main thread until [TranslateRun.close]. */
    fun start(ctx: Context, paragraphs: Map<Int, String>, target: TargetLang, onState: (Int, ParagraphState) -> Unit): TranslateRun =
        TranslateRun(ctx.applicationContext, target, onState).also { it.begin(paragraphs) }

    internal fun model(lang: String): TranslateRemoteModel = TranslateRemoteModel.Builder(lang).build()

    /** OpenCC off the main thread (first use loads the dictionaries); [onResult] runs on the main thread. */
    internal fun openCc(text: String, onResult: (String) -> Unit) {
        openCcThread.execute {
            val out = OpenCc.toTaiwan(text)
            main.post { onResult(out) }
        }
    }

    private fun changed() {
        onModelsChanged?.invoke()
    }
}

/** One translate press (spec §2). Results after [close] are dropped (spec §5); downloads keep running. */
class TranslateRun internal constructor(
    private val app: Context,
    private val target: TargetLang,
    private val onState: (Int, ParagraphState) -> Unit,
) {
    private var closed = false
    private val identifier = LanguageIdentification.getClient()
    private var identifierOpen = true
    private val translators = ArrayList<Translator>()

    internal fun begin(paragraphs: Map<Int, String>) {
        paragraphs.keys.forEach { report(it, ParagraphState.Translating) }
        if (paragraphs.isEmpty()) {
            closeIdentifier()
            return
        }
        val detected = HashMap<Int, String>()
        for ((i, text) in paragraphs) {
            identifier.identifyLanguage(flatten(text, null)).addOnCompleteListener { t ->
                detected[i] = if (t.isSuccessful) t.result else UNDETERMINED   // §5: identification failure = undetermined
                if (detected.size == paragraphs.size) {
                    closeIdentifier()
                    dispatch(paragraphs, detected)
                }
            }
        }
    }

    private fun dispatch(texts: Map<Int, String>, detected: Map<Int, String>) {
        if (closed) return
        val groups = LinkedHashMap<String, MutableList<Int>>()   // §2 step 3: one model check + translator per source
        for ((i, tag) in detected) {
            val text = texts.getValue(i)
            when (val r = route(tag, TranslateLanguage.fromLanguageTag(tag), target)) {
                Route.NotNeeded -> report(i, ParagraphState.NotNeeded)
                Route.Unsupported -> report(i, ParagraphState.Unsupported)
                Route.OpenCcOnly -> Translate.openCc(text) { report(i, openCcOnlyResult(text, it)) }
                is Route.MlKit -> groups.getOrPut(r.source) { ArrayList() } += i
            }
        }
        for ((source, idx) in groups) translateGroup(source, idx, texts)
    }

    private fun translateGroup(source: String, idx: List<Int>, texts: Map<Int, String>) {
        whenModelsReady(listOf(source, target.mlkit).distinct(), idx) {
            idx.forEach { report(it, ParagraphState.Translating) }
            val translator = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target.mlkit).build(),
            )
            translators += translator
            for (i in idx) {
                translator.translate(flatten(texts.getValue(i), source)).addOnCompleteListener { t ->
                    if (!t.isSuccessful) {
                        report(i, ParagraphState.Failed)          // §5: only this paragraph fails
                        return@addOnCompleteListener
                    }
                    val out: String = t.result
                    if (target.openCc) Translate.openCc(out) { report(i, ParagraphState.Done(it, source)) }
                    else report(i, ParagraphState.Done(out, source))
                }
            }
        }
    }

    /** Checks every model in [langs]; downloads the missing ones; reports Downloading / WaitingWifi / Failed for [idx]; then [ready]. */
    private fun whenModelsReady(langs: List<String>, idx: List<Int>, ready: () -> Unit) {
        val manager = RemoteModelManager.getInstance()
        val missing = ArrayList<String>()
        var checked = 0
        for (lang in langs) {
            manager.isModelDownloaded(Translate.model(lang)).addOnCompleteListener { t ->
                if (!(t.isSuccessful && t.result == true)) missing += lang
                if (++checked < langs.size || closed) return@addOnCompleteListener
                if (missing.isEmpty()) {
                    ready()
                    return@addOnCompleteListener
                }
                val tasks = missing.map { Translate.download(app, it) }
                if (tasks.any { it == null }) {
                    idx.forEach { report(it, ParagraphState.WaitingWifi) }   // §2: nothing queued; next press retries
                    return@addOnCompleteListener
                }
                idx.forEach { report(it, ParagraphState.Downloading) }
                Tasks.whenAll(tasks.filterNotNull()).addOnCompleteListener { all ->
                    if (closed) return@addOnCompleteListener
                    if (all.isSuccessful) ready() else idx.forEach { report(it, ParagraphState.Failed) }   // §5
                }
            }
        }
    }

    private fun report(i: Int, state: ParagraphState) {
        if (!closed) onState(i, state)
    }

    private fun closeIdentifier() {
        if (!identifierOpen) return
        identifierOpen = false
        identifier.close()
    }

    /** Session ended: drop late results, free the identifier and translators (spec §2 lifecycle). */
    fun close() {
        if (closed) return
        closed = true
        closeIdentifier()
        translators.forEach { it.close() }
        translators.clear()
    }
}
```

- [ ] **Step 5: Update `README.md`**

Intro: after `Android 8+ device.` add the sentence `Recognised paragraphs can also be translated on device (Google ML Kit translation + OpenCC).`
"OCR languages": replace `one is kept (`OcrMerge.kt`). All run fully offline. See `Ocr.kt`.` with `one is kept (`OcrMerge.kt`). OCR runs fully offline; translation runs offline after a one-time model download (see Translation). See `Ocr.kt`.`
Insert before `## Build`:

```markdown
## Translation
The translate button next to the settings button (shown once OCR found text) replaces every
paragraph in place with its translation into the target language chosen in Settings (default
繁體中文); press it again for the original. Each paragraph's language is detected on device
(ML Kit language identification). Chinese paragraphs with target 繁體中文 only go through OpenCC
(s2twp, `opencc4j`); other languages go through ML Kit translation (plus OpenCC for 繁體中文).
Language models (~30 MB each) download automatically on first use, optionally only on Wi-Fi;
Settings lists, pre-downloads and deletes them. Rules: `TranslateRules.kt`; engine: `Translate.kt`.
```

Stack line: replace `ML Kit text-recognition 16.0.1.` with `ML Kit text-recognition 16.0.1, ML Kit translate 17.0.3, language-id 17.0.6, opencc4j 1.14.0.`

- [ ] **Step 6: Run the gate and the greps**

Send `SETTLED`; gate-runner: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug testDebugUnitTest; echo "RC=$?"`
Expected: `RC=0`; all four test files unchanged (OcrMergeTest 7, TextFitTest 2, TranslateRulesTest 12, OpenCcTest 3; 0 failures).
Then: `grep -rln "com.google.mlkit.nl" app/src/main/java` → only `…/Translate.kt`; `grep -cE "android.permission.(INTERNET|ACCESS_NETWORK_STATE)" app/src/main/AndroidManifest.xml` → `2`; `grep -nE "mlkit:translate:17.0.3|mlkit:language-id:17.0.6" app/build.gradle.kts` → two lines; `grep -c "offline after a one-time model download" README.md` → `1`; `grep -c "fully offline" README.md` → `1`.

- [ ] **Step 7: Commit**

```bash
git rev-parse --abbrev-ref HEAD   # main
git add app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/java/us/jhangy/textify/Prefs.kt app/src/main/java/us/jhangy/textify/Translate.kt README.md
git commit -m "feat(translate): ML Kit translation engine, model downloads, target/Wi-Fi prefs

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>" -- app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/java/us/jhangy/textify/Prefs.kt app/src/main/java/us/jhangy/textify/Translate.kt README.md
git status --porcelain -- app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/java/us/jhangy/textify/Prefs.kt app/src/main/java/us/jhangy/textify/Translate.kt README.md   # empty
```

### Task 5: Spinner ring and status chip views

**Files:**
- Create: `java/SpinnerRingView.kt`, `java/StatusChipView.kt`
- Create: `res/drawable/ic_chip_wifi.xml`, `res/drawable/ic_chip_unsupported.xml`, `res/drawable/ic_chip_failed.xml`
- Modify: `res/values/strings.xml` (four chip strings)

**Interfaces:**
- Consumes: `Chip` (Task 3); `GlowDrawable`, `CssGradientDrawable`, `withAlpha` (`Glow.kt`).
- Produces:
  - `class SpinnerRingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View` with `var strokePx: Float` (default 3dp).
  - `class StatusChipView(context: Context) : LinearLayout` with `var chip: Chip?` (private set) and `fun show(kind: Chip?, delayMs: Long = 0L)`.
  - Drawables `ic_chip_wifi`, `ic_chip_unsupported`, `ic_chip_failed` (24 viewport, black stroke, tinted at runtime).
  - Strings `chip_downloading` 「下載中…」, `chip_waiting_wifi` 「等待 Wi-Fi」, `chip_unsupported` 「不支援此語言」, `chip_failed` 「翻譯失敗」.

**Behavior:**
- Spinner (mockup note 7): `drawArc` 300° with a `SweepGradient` transparent → #A78BFA (60°) → #F472B6 (150°) → #FDBA74 (220°) → #67E8F9 (290°) → transparent (300°), round caps, rotation 0→360 over 900 ms, infinite, linear; runs only while shown and attached; cancelled on detach.
- Chip (mockup `.chip`, note 6): 20dp-high capsule, padding start 6dp / end 8dp, 4dp gap, 11sp bold label. Kinds: DOWNLOADING = 11dp spinner (2dp stroke) + 「下載中…」 in #1E1B4B on white 95 %; WAITING_WIFI = wifi icon + text #8A86A0 on white 92 %; UNSUPPORTED = slash icon + #6B5FA8 on white 92 %; FAILED = alert icon + #9A3412 on white 95 %. Shadow: `GlowDrawable` blur 12dp, dy 4dp, #7C3AED 22 % (FAILED: #FDBA74 45 %). `show(kind)`: chipIn 220 ms (alpha 0→1, translationY 4dp→0, scale 0.9→1, `PathInterpolator(.2,0,0,1.3)`) after `delayMs`; `show(null)` = chipOut 160 ms ease-in then `GONE`. Same kind twice = no-op. Not clickable (touches reach the box under it).

**Constraints:**
- Software drawing only; no `setShadowLayer`.

**Acceptance criteria:**
- [ ] Gate `RC=0`.
- [ ] `grep -cE "chip_(downloading|waiting_wifi|unsupported|failed)" app/src/main/res/values/strings.xml` prints `4`.
- [ ] `grep -n "SweepGradient" app/src/main/java/us/jhangy/textify/SpinnerRingView.kt` prints at least one line; `grep -n "setShadowLayer" app/src/main/java/us/jhangy/textify/*.kt` prints nothing.


**Steps:**

(No JVM test: both are Android views without branching logic beyond `Chip` → resources; `Chip` itself is tested in Task 3. They are checked visually in Task 7.)

- [ ] **Step 1: Create `java/SpinnerRingView.kt`**

```kotlin
package us.jhangy.textify

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Halo spinner (prism-tide note 7): 300° arc with a SweepGradient transparent -> lavender -> pink -> peach ->
 * aqua -> transparent, one turn per 900 ms. Used by the translate button, the 下載中… chip and Settings rows.
 */
class SpinnerRingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** Stroke width in px (default 3dp). */
    var strokePx: Float = 3f * resources.displayMetrics.density
        set(value) {
            field = value
            paint.strokeWidth = value
            rebuild()
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.ROUND
    }
    private val oval = RectF()
    private var angle = 0f
    private val spin: ValueAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = SPIN
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { angle = it.animatedValue as Float; invalidate() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    private fun rebuild() {
        if (width == 0 || height == 0) return
        val half = strokePx / 2f
        oval.set(half, half, width - half, height - half)
        paint.shader = SweepGradient(width / 2f, height / 2f, COLORS, STOPS)
    }

    override fun onDraw(canvas: Canvas) {
        val saved = canvas.save()
        canvas.rotate(angle, width / 2f, height / 2f)
        canvas.drawArc(oval, 0f, ARC, false, paint)
        canvas.restoreToCount(saved)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncSpin()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isAttachedToWindow) syncSpin()      // also called from the View constructor, before `spin` exists
    }

    override fun onDetachedFromWindow() {
        spin.cancel()
        super.onDetachedFromWindow()
    }

    /** Turn only while visible on screen. */
    private fun syncSpin() {
        if (isShown) {
            if (!spin.isStarted) spin.start()
        } else {
            spin.cancel()
        }
    }

    companion object {
        private const val SPIN = 900L
        private const val ARC = 300f
        private val COLORS = intArrayOf(
            0x00A78BFA, 0xFFA78BFA.toInt(), 0xFFF472B6.toInt(), 0xFFFDBA74.toInt(), 0xFF67E8F9.toInt(), 0x0067E8F9,
        )
        private val STOPS = floatArrayOf(0f, 60f / 360f, 150f / 360f, 220f / 360f, 290f / 360f, 300f / 360f)
    }
}
```

- [ ] **Step 2: Create the chip icons**

`res/drawable/ic_chip_wifi.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- prism-tide ICON.wifi; black, tinted at runtime -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="12dp" android:height="12dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path
        android:pathData="M2.5,9a14,14 0,0 1,19 0M5.5,12.5a9.5,9.5 0,0 1,13 0M8.8,16a5,5 0,0 1,6.4 0"
        android:strokeColor="#FF000000" android:strokeWidth="2.6" android:strokeLineCap="round" />
    <path
        android:pathData="M10.8,19.3a1.2,1.2 0,1 0,2.4 0a1.2,1.2 0,1 0,-2.4 0z"
        android:fillColor="#FF000000" />
</vector>
```

`res/drawable/ic_chip_unsupported.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- prism-tide ICON.uns; black, tinted at runtime -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="12dp" android:height="12dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path
        android:pathData="M12,3.5a8.5,8.5 0,1 0,0 17a8.5,8.5 0,1 0,0 -17zM6,6l12,12"
        android:strokeColor="#FF000000" android:strokeWidth="2.6" android:strokeLineCap="round" />
</vector>
```

`res/drawable/ic_chip_failed.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- prism-tide ICON.fail; black, tinted at runtime -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="12dp" android:height="12dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path
        android:pathData="M12,3a9,9 0,1 0,0 18a9,9 0,1 0,0 -18zM12,7.5v5.5M12,16.5v0.3"
        android:strokeColor="#FF000000" android:strokeWidth="2.8" android:strokeLineCap="round" />
</vector>
```

- [ ] **Step 3: Add the chip strings** — in `res/values/strings.xml`, before `</resources>`:

```xml
    <string name="chip_downloading">下載中…</string>
    <string name="chip_waiting_wifi">等待 Wi-Fi</string>
    <string name="chip_unsupported">不支援此語言</string>
    <string name="chip_failed">翻譯失敗</string>
```

- [ ] **Step 4: Create `java/StatusChipView.kt`**

```kotlin
package us.jhangy.textify

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Per-paragraph status chip (translation spec §3, prism-tide .chip / note 6). Not clickable: taps reach the box. */
@SuppressLint("ViewConstructor")
class StatusChipView(context: Context) : LinearLayout(context) {

    /** Kind currently shown (or fading in); null when hidden. */
    var chip: Chip? = null
        private set

    private val d = resources.displayMetrics.density
    private val spinner = SpinnerRingView(context).apply { strokePx = 2f * d }
    private val icon = ImageView(context)
    private val label = TextView(context).apply {
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        maxLines = 1
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding((6 * d).roundToInt(), 0, (8 * d).roundToInt(), 0)
        addView(spinner, LayoutParams((11 * d).roundToInt(), (11 * d).roundToInt()))
        addView(icon, LayoutParams((12 * d).roundToInt(), (12 * d).roundToInt()))
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = (4 * d).roundToInt() })
        isClickable = false
        alpha = 0f
        visibility = GONE
    }

    /** chipIn 220 (alpha, translationY 4dp -> 0, scale .9 -> 1, overshoot) after [delayMs]; null = chipOut 160 then GONE. */
    fun show(kind: Chip?, delayMs: Long = 0L) {
        if (kind == chip) return
        chip = kind
        animate().cancel()
        if (kind == null) {
            animate().alpha(0f).setStartDelay(delayMs).setDuration(CHIP_OUT).setInterpolator(EASE_IN)
                .withEndAction { visibility = GONE }
            return
        }
        bind(kind)
        visibility = VISIBLE
        if (alpha == 0f) {
            translationY = 4 * d
            scaleX = 0.9f
            scaleY = 0.9f
        }
        animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setStartDelay(delayMs).setDuration(CHIP_IN).setInterpolator(OVERSHOOT)
    }

    private fun bind(kind: Chip) {
        val downloading = kind == Chip.DOWNLOADING
        spinner.visibility = if (downloading) VISIBLE else GONE
        icon.visibility = if (downloading) GONE else VISIBLE
        val (text, color, fill) = when (kind) {
            Chip.DOWNLOADING -> Triple(R.string.chip_downloading, INK, 0.95f)
            Chip.WAITING_WIFI -> Triple(R.string.chip_waiting_wifi, WAITING, 0.92f)
            Chip.UNSUPPORTED -> Triple(R.string.chip_unsupported, SECONDARY, 0.92f)
            Chip.FAILED -> Triple(R.string.chip_failed, FAILED, 0.95f)
        }
        when (kind) {
            Chip.WAITING_WIFI -> icon.setImageResource(R.drawable.ic_chip_wifi)
            Chip.UNSUPPORTED -> icon.setImageResource(R.drawable.ic_chip_unsupported)
            Chip.FAILED -> icon.setImageResource(R.drawable.ic_chip_failed)
            Chip.DOWNLOADING -> Unit
        }
        icon.setColorFilter(color)
        label.setText(text)
        label.setTextColor(color)
        val shadow = if (kind == Chip.FAILED) withAlpha(0.45f, 0xFDBA74) else withAlpha(0.22f, 0x7C3AED)
        background = GlowDrawable(CssGradientDrawable.solid(withAlpha(fill, 0xFFFFFF), -1f), shadow, 12 * d, 4 * d, 0f, -1f)
    }

    companion object {
        private const val CHIP_IN = 220L
        private const val CHIP_OUT = 160L
        private val INK = 0xFF1E1B4B.toInt()
        private val WAITING = 0xFF8A86A0.toInt()
        private val SECONDARY = 0xFF6B5FA8.toInt()
        private val FAILED = 0xFF9A3412.toInt()
        private val OVERSHOOT = PathInterpolator(0.2f, 0f, 0f, 1.3f)   // prism-tide chipIn transform
        private val EASE_IN = PathInterpolator(0.42f, 0f, 1f, 1f)      // chipOut
    }
}
```

- [ ] **Step 5: Run the gate and the greps**

Send `SETTLED`; gate-runner: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug testDebugUnitTest; echo "RC=$?"` → `RC=0`, test counts unchanged.
Then: `grep -cE "chip_(downloading|waiting_wifi|unsupported|failed)" app/src/main/res/values/strings.xml` → `4`; `grep -n "SweepGradient" app/src/main/java/us/jhangy/textify/SpinnerRingView.kt` → ≥ 1 line; `grep -n "setShadowLayer" app/src/main/java/us/jhangy/textify/*.kt` → no output.

- [ ] **Step 6: Commit**

```bash
git rev-parse --abbrev-ref HEAD   # main
git add app/src/main/java/us/jhangy/textify/SpinnerRingView.kt app/src/main/java/us/jhangy/textify/StatusChipView.kt app/src/main/res/drawable/ic_chip_wifi.xml app/src/main/res/drawable/ic_chip_unsupported.xml app/src/main/res/drawable/ic_chip_failed.xml app/src/main/res/values/strings.xml
git commit -m "feat(session): Halo spinner ring and per-paragraph status chip

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>" -- app/src/main/java/us/jhangy/textify/SpinnerRingView.kt app/src/main/java/us/jhangy/textify/StatusChipView.kt app/src/main/res/drawable/ic_chip_wifi.xml app/src/main/res/drawable/ic_chip_unsupported.xml app/src/main/res/drawable/ic_chip_failed.xml app/src/main/res/values/strings.xml
git status --porcelain -- app/src/main/java/us/jhangy/textify/SpinnerRingView.kt app/src/main/java/us/jhangy/textify/StatusChipView.kt app/src/main/res/drawable/ic_chip_wifi.xml app/src/main/res/drawable/ic_chip_unsupported.xml app/src/main/res/drawable/ic_chip_failed.xml app/src/main/res/values/strings.xml   # empty
```

### Task 6: Translated mode of `PillView`

**Files:**
- Modify: `java/TextFit.kt` (append fitting and colour helpers)
- Create: `java/TranslatedCard.kt`
- Modify: `java/PillView.kt` (whole file)
- Test: `test/TextFitTest.kt` (append tests)

**Interfaces:**
- Consumes: `Box`, `Rect.toBox()` (Task 1); `lineMetrics`, `LineMetrics` (Task 2); `glowBitmap`, `glowPad`, `PillPainter`, `withAlpha` (`Glow.kt`).
- Produces:
  - `internal data class Fit(val textPx: Float, val truncated: Boolean)`; `internal fun fitTextSize(maxPx: Float, minPx: Float, stepPx: Float, availPx: Int, heightAt: (Float) -> Int): Fit`.
  - `internal fun averageColor(pixels: IntArray): Int`; `internal const val TEXT_DARK: Int = 0xFF111111.toInt()`; `internal const val TEXT_LIGHT: Int = 0xFFFFFFFF.toInt()`; `internal fun textColorFor(bg: Int): Int`.
  - `internal fun edgePoints(box: Box, out: Int, step: Int, w: Int, h: Int): List<Pair<Int, Int>>`.
  - `internal fun edgeColor(shot: Bitmap, box: Rect): Int` (in `TranslatedCard.kt`).
  - `internal class CardPainter(res: Resources)` with `var color: Int`, `var textColor: Int`, `fun setSize(w: Int, h: Int, cornerPx: Float)`, `fun draw(canvas: Canvas, glowAlpha: Float, markLinePx: Float)`.
  - `PillView` additions: `enum class Reveal { NONE, TOP_DOWN, LEFT_RIGHT }` (nested, `PillView.Reveal`); `fun showTranslation(text: String, bg: Int, reveal: Reveal, delayMs: Long)`; `fun showOriginal(reveal: Reveal, delayMs: Long)`; `val showsTranslation: Boolean`; `val translationText: String?`; `val truncated: Boolean`; `var shimmering: Boolean`; `var focused: Boolean`.

**Behavior:**
- `showTranslation` (spec §3, prism-tide header): solid card in `bg` (opaque), text colour `textColorFor(bg)` (#111111 or white, whichever has the higher WCAG contrast against black/white), outer glow #A78BFA 42 % blur 14dp + #7C3AED 16 % dy 4dp blur 14dp, bottom Halo underline 2.5dp (#A78BFA, #F472B6, #FDBA74, #67E8F9, 90 % alpha, inset max(6dp, radius) on both sides). The translation is real selectable text: wraps inside the box, size fitted from the original text size (at least 10sp) down to 10sp in 1px steps with line spacing ×1.1 (`fitTextSize` with a `StaticLayout` measure). Still too tall at 10sp → `truncated = true`, `maxLines = floor(innerHeight / lineHeightAt10sp)` (≥ 1), no system ellipsis, and the "⋯" mark (proposal-1 `.mark`): bottom-end area one line high + `PAD_Y_DP`, horizontal fade from transparent to the card colour at 45 %, then "⋯" 13sp bold in the text colour at 75 %, 6dp from the end, 18dp fade start padding.
- Reveal: `TOP_DOWN` animates a clip fraction 0→1 from the top over flip 240 ms (emphasized) after `delayMs` (tide); `LEFT_RIGHT` reveals left to right over 240 ms plus a prism sweep 320 ms (fast-out-slow-in, band 70 % of the box, colours transparent → white 70 % @35 % → #F472B6 35 % @55 % → #67E8F9 30 % @70 % → transparent) for a result arriving while translation mode is on; `NONE` swaps at once. Where the card is not yet revealed, the original pill shows.
- `showOriginal` runs the same clip back to 0 (same direction, flip 240 ms, after `delayMs`) and then restores the original text, transparent colour and Task 2 layout.
- `shimmering` (Translating, mockup note 3): a band 70 % of the box width, gradient transparent → white 70 % @45 % → #F472B6 22 % @60 % → transparent, sweeps from −110 % to +160 % of the band over 1200 ms, linear, infinite, clipped to the pill; only drawn while the original look shows; cancelled on detach.
- `focused` (bottom panel open, mockup note 9): card glow alpha pulses 0.6 ↔ 1.0 over 1200 ms (reverse, infinite); the lavender glow bitmap is built at 70 % so 0.6 = the resting 42 %.
- `edgeColor` (mockup note 4): average of `Bitmap.getPixel` every 4 px along the box grown by 2 px, clamped to the bitmap; recycled bitmap → white.
- Copy flash / shine / press feedback, selection and `onTap` keep working in both modes.

**Constraints:**
- Glows via `glowBitmap` (software); no `setShadowLayer`, no `RenderEffect`.
- `TextFit.kt` stays free of Android imports.

**Acceptance criteria:**
- [ ] Gate `RC=0`; `TextFitTest` has 8 testcases: `singleLineUsesItsHeight`, `pitchIsMeanTopDistance`, `fitPicksLargestThatFits`, `fitTruncatesAtMinimum`, `fitNeverGoesBelowMinimum`, `averageIgnoresAlpha`, `textColorPicksHigherContrast`, `edgePointsStayInsideBitmap`; 0 failures.
- [ ] `grep -nE "setShadowLayer|RenderEffect" app/src/main/java/us/jhangy/textify/*.kt` prints nothing.
- [ ] `grep -n "fun showTranslation(text: String, bg: Int, reveal: Reveal, delayMs: Long)" app/src/main/java/us/jhangy/textify/PillView.kt` prints one line.


**Steps:**

- [ ] **Step 1: Write the failing tests** — append inside `class TextFitTest` (after `pitchIsMeanTopDistance`):

```kotlin
    @Test
    fun fitPicksLargestThatFits() {
        // layout height = 10 × size; 150 px available -> 15 px fits, 16 px does not
        assertEquals(Fit(15f, false), fitTextSize(20f, 10f, 1f, 150) { (it * 10).toInt() })
    }

    @Test
    fun fitTruncatesAtMinimum() {
        assertEquals(Fit(10f, true), fitTextSize(20f, 10f, 1f, 50) { (it * 10).toInt() })
    }

    @Test
    fun fitNeverGoesBelowMinimum() {
        // original text smaller than 10sp: start at the minimum
        assertEquals(Fit(10f, false), fitTextSize(8f, 10f, 1f, 200) { (it * 10).toInt() })
    }

    @Test
    fun averageIgnoresAlpha() {
        assertEquals(0xFF7F007F.toInt(), averageColor(intArrayOf(0x00FF0000, 0xFF0000FF.toInt())))
        assertEquals(0xFFFFFFFF.toInt(), averageColor(IntArray(0)))
    }

    @Test
    fun textColorPicksHigherContrast() {
        assertEquals(TEXT_DARK, textColorFor(0xFFFFFFFF.toInt()))
        assertEquals(TEXT_DARK, textColorFor(0xFFFAF5FF.toInt()))
        assertEquals(TEXT_LIGHT, textColorFor(0xFF1E1B4B.toInt()))
        assertEquals(TEXT_LIGHT, textColorFor(0xFF000000.toInt()))
    }

    @Test
    fun edgePointsStayInsideBitmap() {
        // box 0..10 grown by 2 px, clamped to an 8×8 bitmap -> rect 0..7: top/bottom x = 0, 4; sides y = 4
        val pts = edgePoints(Box(0, 0, 10, 10), out = 2, step = 4, w = 8, h = 8)
        assertEquals(6, pts.size)
        assertEquals(true, pts.all { (x, y) -> x in 0..7 && y in 0..7 })
    }
```

- [ ] **Step 2: Run it to make sure it fails**

Run (gate-runner): `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew testDebugUnitTest --tests us.jhangy.textify.TextFitTest; echo "RC=$?"`
Expected: `RC=1`, `Unresolved reference 'Fit'` / `'fitTextSize'`.

- [ ] **Step 3: Append to `java/TextFit.kt`**

```kotlin

/** Fitted text size (px) and whether the text still overflows at the minimum (spec §3 truncation). */
internal data class Fit(val textPx: Float, val truncated: Boolean)

/**
 * Largest size from max(maxPx, minPx) down to [minPx] in [stepPx] steps whose layout height ([heightAt]) fits
 * [availPx]; truncated = even [minPx] overflows (spec §3: auto-size down to 10sp, then truncate).
 */
internal fun fitTextSize(maxPx: Float, minPx: Float, stepPx: Float, availPx: Int, heightAt: (Float) -> Int): Fit {
    var px = maxOf(maxPx, minPx)
    while (px > minPx) {
        if (heightAt(px) <= availPx) return Fit(px, false)
        px = maxOf(minPx, px - stepPx)
    }
    return Fit(minPx, heightAt(minPx) > availPx)
}

/** Mean colour of ARGB [pixels], alpha ignored, result opaque; opaque white when empty. */
internal fun averageColor(pixels: IntArray): Int {
    if (pixels.isEmpty()) return 0xFFFFFFFF.toInt()
    var r = 0L
    var g = 0L
    var b = 0L
    for (p in pixels) {
        r += (p shr 16) and 0xFF
        g += (p shr 8) and 0xFF
        b += p and 0xFF
    }
    val n = pixels.size
    return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
}

internal const val TEXT_DARK: Int = 0xFF111111.toInt()
internal const val TEXT_LIGHT: Int = 0xFFFFFFFF.toInt()

/** Spec §3: black-ish (#111111) or white text, whichever has the higher WCAG contrast against [bg] (as in the mockup). */
internal fun textColorFor(bg: Int): Int {
    val l = luminance(bg)
    val vsWhite = 1.05 / (l + 0.05)
    val vsBlack = (l + 0.05) / 0.05
    return if (vsWhite > vsBlack) TEXT_LIGHT else TEXT_DARK
}

private fun luminance(c: Int): Double {
    fun ch(v: Int): Double {
        val s = v / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
}

/** Sample points every [step] px on the edge of [box] grown by [out] px, clamped to a [w]×[h] bitmap (prism-tide note 4). */
internal fun edgePoints(box: Box, out: Int, step: Int, w: Int, h: Int): List<Pair<Int, Int>> {
    if (w <= 0 || h <= 0) return emptyList()
    val l = (box.left - out).coerceIn(0, w - 1)
    val r = (box.right - 1 + out).coerceIn(0, w - 1)
    val t = (box.top - out).coerceIn(0, h - 1)
    val b = (box.bottom - 1 + out).coerceIn(0, h - 1)
    val pts = ArrayList<Pair<Int, Int>>()
    var x = l
    while (x <= r) {
        pts += x to t
        pts += x to b
        x += step
    }
    var y = t + step
    while (y < b) {
        pts += l to y
        pts += r to y
        y += step
    }
    return pts
}
```

- [ ] **Step 4: Run the tests to make sure they pass**

Run (gate-runner): `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew testDebugUnitTest --tests us.jhangy.textify.TextFitTest; echo "RC=$?"`
Expected: `RC=0`, 8 tests, 0 failures.

- [ ] **Step 5: Create `java/TranslatedCard.kt`**

```kotlin
package us.jhangy.textify

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import kotlin.math.max
import kotlin.math.roundToInt

/** Average screenshot colour along [box] (screenshot px), 2 px outside it, every 4 px (prism-tide note 4); recycled -> white. */
internal fun edgeColor(shot: Bitmap, box: Rect): Int {
    if (shot.isRecycled) return Color.WHITE
    val points = edgePoints(box.toBox(), EDGE_OUT_PX, EDGE_STEP_PX, shot.width, shot.height)
    return averageColor(IntArray(points.size) { shot.getPixel(points[it].first, points[it].second) })
}

private const val EDGE_OUT_PX = 2
private const val EDGE_STEP_PX = 4

/**
 * Translated paragraph card (translation spec §3, prism-tide header): opaque [color] fill, outer glow #A78BFA 42 %
 * blur 14dp + #7C3AED 16 % dy 4dp blur 14dp (software bitmaps, one path for API 26+), bottom Halo underline
 * 2.5dp at 90 %, and the truncation "⋯" mark of proposal-1 (fade in the card colour + "⋯" at 75 % text colour).
 */
internal class CardPainter(res: Resources) {
    private val density = res.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val underline = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, MARK_SP, res.displayMetrics)
        textAlign = Paint.Align.RIGHT
        letterSpacing = MARK_SPACING
    }
    private val cardPath = Path()
    private val lavenderDst = RectF()
    private val violetDst = RectF()
    private var lavender: Bitmap? = null
    private var violet: Bitmap? = null
    private var w = 0f
    private var h = 0f
    private var r = 0f

    /** Card fill: the sampled screenshot colour (opaque). */
    var color: Int = Color.WHITE
        set(value) {
            field = value
            fill.color = value
        }

    /** Text colour of the card (textColorFor(color)); the "⋯" mark uses it at 75 %. */
    var textColor: Int = TEXT_DARK

    fun setSize(w: Int, h: Int, cornerPx: Float) {
        this.w = w.toFloat()
        this.h = h.toFloat()
        r = cornerPx
        cardPath.reset()
        if (w <= 0 || h <= 0) {
            lavender = null
            violet = null
            return
        }
        cardPath.addRoundRect(0f, 0f, this.w, this.h, r, r, Path.Direction.CW)
        val blur = GLOW_DP * density
        val dy = DY_DP * density
        // lavender built at 70 % so glowAlpha 0.6 = the resting 42 % and 1.0 = the panel focus peak
        lavender = glowBitmap(this.w, this.h, r, blur, withAlpha(0.70f, 0xA78BFA))
        val lp = glowPad(blur, 0f, 0f)
        lavenderDst.set(-lp, -lp, this.w + lp, this.h + lp)
        violet = glowBitmap(this.w, this.h, r, blur, withAlpha(0.16f, 0x7C3AED), dyPx = dy)
        val vp = glowPad(blur, 0f, dy)
        violetDst.set(-vp, -vp, this.w + vp, this.h + vp)
        val inset = max(UNDERLINE_INSET_DP * density, r)
        underline.shader = LinearGradient(inset, 0f, this.w - inset, 0f, HALO, null, Shader.TileMode.CLAMP)
        underline.alpha = (UNDERLINE_ALPHA * 255f).roundToInt()
    }

    /**
     * Draws into (0, 0, w, h) of the current canvas. [glowAlpha] multiplies the lavender glow (0.6 rest, 1.0 focus);
     * [markLinePx] = height of the "⋯" mark area (one text line + bottom padding), 0 = not truncated.
     */
    fun draw(canvas: Canvas, glowAlpha: Float, markLinePx: Float) {
        if (w <= 0f || h <= 0f) return
        lavender?.let {
            glowPaint.alpha = (glowAlpha.coerceIn(0f, 1f) * 255f).roundToInt()
            canvas.drawBitmap(it, null, lavenderDst, glowPaint)
        }
        violet?.let {
            glowPaint.alpha = 255
            canvas.drawBitmap(it, null, violetDst, glowPaint)
        }
        canvas.drawRoundRect(0f, 0f, w, h, r, r, fill)
        if (markLinePx > 0f) drawMark(canvas, markLinePx)
        val inset = max(UNDERLINE_INSET_DP * density, r)
        val uh = UNDERLINE_DP * density
        canvas.drawRoundRect(inset, h - uh, w - inset, h, uh / 2f, uh / 2f, underline)
    }

    /** proposal-1 .mark: padding 0 6dp 0 18dp, fade transparent -> card colour at 45 %, "⋯" 13sp bold at 75 %. */
    private fun drawMark(canvas: Canvas, areaH: Float) {
        val right = w - MARK_END_DP * density
        val left = right - mark.measureText(MARK) - MARK_START_DP * density
        val top = h - areaH
        fade.shader = LinearGradient(
            left, 0f, w, 0f, intArrayOf(color and 0x00FFFFFF, color, color), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        val saved = canvas.save()
        canvas.clipPath(cardPath)
        canvas.drawRect(left, top, w, h, fade)
        mark.color = textColor
        mark.alpha = (MARK_ALPHA * 255f).roundToInt()
        val fm = mark.fontMetrics
        canvas.drawText(MARK, right, top + areaH / 2f - (fm.ascent + fm.descent) / 2f, mark)
        canvas.restoreToCount(saved)
    }

    companion object {
        private const val GLOW_DP = 14f
        private const val DY_DP = 4f
        private const val UNDERLINE_DP = 2.5f
        private const val UNDERLINE_INSET_DP = 6f
        private const val UNDERLINE_ALPHA = 0.9f
        private const val MARK = "⋯"
        private const val MARK_SP = 13f
        private const val MARK_SPACING = 0.08f          // CSS letter-spacing 1px at 13px
        private const val MARK_ALPHA = 0.75f
        private const val MARK_END_DP = 6f
        private const val MARK_START_DP = 18f
        private val HALO = intArrayOf(0xFFA78BFA.toInt(), 0xFFF472B6.toInt(), 0xFFFDBA74.toInt(), 0xFF67E8F9.toInt())
    }
}
```

- [ ] **Step 6: Replace `java/PillView.kt`**

```kotlin
package us.jhangy.textify

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Bundle
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.animation.doOnEnd
import androidx.core.widget.TextViewCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One OCR paragraph as an a2 "Halo" box with two modes (translation spec §1, §3).
 * Original: the paragraph's text is real, selectable and transparent, laid out line by line on the screenshot's
 * OCR line boxes, so long-press gives system handles + copy menu on the glyphs.
 * Translated: a solid card in the screenshot's edge colour hides the glyphs and shows the translation as real,
 * selectable text, fitted down to 10sp and truncated with a "⋯" mark below that.
 * Tap is detected with GestureDetector: a selectable TextView is focusable in touch mode, so the first tap would
 * only take focus and never reach an OnClickListener.
 */
@SuppressLint("ViewConstructor")
class PillView(context: Context, val block: OcrBlock) : AppCompatTextView(context) {

    /** How the translated card appears / disappears. */
    enum class Reveal { NONE, TOP_DOWN, LEFT_RIGHT }

    /** Single tap. dismissOnly = a text selection was alive when the finger went down. */
    var onTap: ((pill: PillView, dismissOnly: Boolean) -> Unit)? = null

    /** Supplied by the activity: is any selection ActionMode alive right now? */
    var selectionActive: () -> Boolean = { false }

    /** Set by the activity while this pill's selection ActionMode is alive (a2 .box.selecting). */
    var selecting = false
        set(value) {
            field = value
            invalidate()
        }

    /** Translation shown (or still animating out); null once the original layout is back. */
    var translationText: String? = null
        private set

    /** True from showTranslation until showOriginal is called. */
    var showsTranslation = false
        private set

    /** The translation did not fit at 10sp: text cut to whole lines and the "⋯" mark drawn (spec §3). */
    var truncated = false
        private set

    /** Paragraph is Translating: light shimmer over the original pill (prism-tide note 3). */
    var shimmering = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startShimmer() else stopShimmer()
        }

    /** Bottom panel open for this paragraph: the card glow pulses (prism-tide note 9). */
    var focused = false
        set(value) {
            if (field == value) return
            field = value
            if (value) startFocus() else stopFocus()
        }

    private val dp = resources.displayMetrics.density
    private var painter: PillPainter? = null
    private val card = CardPainter(resources)
    private var corner = 0f
    private var originalTextPx = 0f
    private var markLinePx = 0f
    private val overlay = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val prismPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillPath = Path()
    private var overlayAlpha = 0f
    private var shineX = Float.NaN
    private var shimmerX = Float.NaN
    private var prismX = Float.NaN
    private var reveal = 0f                       // 0 = original look, 1 = card fully shown
    private var revealDir = Reveal.TOP_DOWN
    private var glowAlpha = GLOW_REST
    private var overlayAnim: ValueAnimator? = null
    private var shineAnim: ValueAnimator? = null
    private var shimmerAnim: ValueAnimator? = null
    private var prismAnim: ValueAnimator? = null
    private var revealAnim: ValueAnimator? = null
    private var focusAnim: ValueAnimator? = null
    private var dismissOnly = false

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onTap?.invoke(this@PillView, dismissOnly)
            return false
        }

        override fun onLongPress(e: MotionEvent) = press(false)

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            press(false)
            return false
        }
    })

    init {
        text = block.text
        setTextColor(Color.TRANSPARENT)
        highlightColor = withAlpha(0.26f, 0x7C3AED)
        setTextIsSelectable(true)
        includeFontPadding = false
        gravity = Gravity.START or Gravity.TOP
        val px = (PAD_X_DP * dp).roundToInt()
        val py = (PAD_Y_DP * dp).roundToInt()
        setPadding(px, py, px, py)
        applyOriginalLayout()
        alpha = 0f
        scaleX = 0.9f
        scaleY = 0.9f
    }

    /** Spec §1: keep the OCR line breaks; size and pitch from the OCR line boxes; squeeze glyphs to their widths. */
    private fun applyOriginalLayout() {
        val boxes = block.lines.map { it.toBox() }
        val m = lineMetrics(boxes)
        originalTextPx = m.textPx
        setHorizontallyScrolling(true)                    // never re-wrap: one TextView line per OCR line
        maxLines = Int.MAX_VALUE
        setLineSpacing(0f, 1f)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, m.textPx)
        TextViewCompat.setLineHeight(this, m.pitchPx.roundToInt())
        textScaleX = 1f
        val measured = block.text.split('\n').sumOf { paint.measureText(it).toDouble() }
        val target = boxes.sumOf { it.width }.toDouble()
        textScaleX = if (measured > 0.0) (target / measured).toFloat().coerceIn(0.5f, 2f) else 1f
        scrollTo(0, 0)
    }

    /** Spec §3: wrap inside the box; largest size from the original size (≥ 10sp) down to 10sp; truncate below that. */
    private fun applyTranslatedLayout(text: String) {
        val lp = layoutParams
        val innerW = max(1, lp.width - paddingLeft - paddingRight)
        val innerH = max(1, lp.height - paddingTop - paddingBottom)
        val minPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, MIN_SP, resources.displayMetrics)
        val measure = TextPaint(paint).apply { textScaleX = 1f }
        fun layout(px: Float): StaticLayout {
            measure.textSize = px
            return StaticLayout.Builder.obtain(text, 0, text.length, measure, innerW)
                .setLineSpacing(0f, TR_SPACING)
                .setIncludePad(false)
                .build()
        }
        val fit = fitTextSize(max(originalTextPx, minPx), minPx, 1f, innerH) { layout(it).height }
        setHorizontallyScrolling(false)
        textScaleX = 1f
        setLineSpacing(0f, TR_SPACING)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, fit.textPx)
        truncated = fit.truncated
        markLinePx = 0f
        maxLines = Int.MAX_VALUE
        if (truncated) {
            val at10 = layout(minPx)
            val line = (at10.getLineBottom(0) - at10.getLineTop(0)).toFloat()
            maxLines = max(1, (innerH / line).toInt())
            markLinePx = line + PAD_Y_DP * dp
        }
        ellipsize = null                                 // custom "⋯" mark instead of the system ellipsis (final pick)
        scrollTo(0, 0)
    }

    /**
     * Shows [text] on a solid card of colour [bg] (spec §3). [reveal]: NONE = at once, TOP_DOWN = tide flip,
     * LEFT_RIGHT = arrive flip + prism sweep. Starts after [delayMs].
     */
    fun showTranslation(text: String, bg: Int, reveal: Reveal, delayMs: Long) {
        translationText = text
        showsTranslation = true
        card.color = bg
        card.textColor = textColorFor(bg)
        setText(text)
        setTextColor(card.textColor)
        applyTranslatedLayout(text)
        animateReveal(1f, reveal, delayMs, null)
        if (reveal == Reveal.LEFT_RIGHT) playPrism(delayMs)
    }

    /** Back to the transparent original (spec §3 toggle): the card clips away first, then the original layout returns. */
    fun showOriginal(reveal: Reveal, delayMs: Long) {
        if (!showsTranslation) return
        showsTranslation = false
        animateReveal(0f, reveal, delayMs) {
            translationText = null
            truncated = false
            markLinePx = 0f
            setText(block.text)
            setTextColor(Color.TRANSPARENT)
            applyOriginalLayout()
        }
    }

    /** a2 boxIn: opacity 0 -> 1 and scale .9 -> 1 over 260 ms, cubic-bezier(.2,0,0,1), after [delayMs]. */
    fun playIn(delayMs: Long) {
        animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(delayMs).setDuration(BOX_IN).setInterpolator(EMPHASIZED)
    }

    /** a2 copy feedback: overlay flash 1 -> 0 (ease-out) and a white shine sweep, both over flash 380 ms. */
    fun playCopied() {
        animateOverlay(1f, 0L, EMPHASIZED)
        animateOverlay(0f, FLASH, EASE_OUT)
        val band = width * SHINE_BAND
        shineAnim?.cancel()
        shineAnim = ValueAnimator.ofFloat(-1.1f * band, width.toFloat()).apply {
            duration = FLASH
            interpolator = SHINE_CURVE
            addUpdateListener { shineX = it.animatedValue as Float; invalidate() }
            doOnEnd { shineX = Float.NaN; invalidate() }
            start()
        }
    }

    /** flip 240 (emphasized) of the clip fraction to [to]; a newer call replaces a running one without its end action. */
    private fun animateReveal(to: Float, how: Reveal, delayMs: Long, onEnd: (() -> Unit)?) {
        revealAnim?.let {
            it.removeAllListeners()
            it.cancel()
        }
        revealAnim = null
        if (how == Reveal.NONE) {
            reveal = to
            invalidate()
            onEnd?.invoke()
            return
        }
        revealDir = how
        revealAnim = ValueAnimator.ofFloat(reveal, to).apply {
            startDelay = delayMs
            duration = FLIP
            interpolator = EMPHASIZED
            addUpdateListener { reveal = it.animatedValue as Float; invalidate() }
            if (onEnd != null) doOnEnd { onEnd() }
            start()
        }
    }

    /** prism-tide arrive 320: diagonal-ish prism band from -110 % of the band to 160 % of the box. */
    private fun playPrism(delayMs: Long) {
        prismAnim?.cancel()
        prismAnim = ValueAnimator.ofFloat(-1.1f * BAND, 1.6f).apply {
            startDelay = delayMs
            duration = ARRIVE
            interpolator = SHINE_CURVE
            addUpdateListener { prismX = (it.animatedValue as Float) * width; invalidate() }
            doOnEnd { prismX = Float.NaN; invalidate() }
            start()
        }
    }

    /** prism-tide shimmer 1200 linear infinite: band from -110 % to 160 % of its own width. */
    private fun startShimmer() {
        shimmerAnim?.cancel()
        shimmerAnim = ValueAnimator.ofFloat(-1.1f, 1.6f).apply {
            duration = SHIMMER
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { shimmerX = (it.animatedValue as Float) * width * BAND; invalidate() }
            start()
        }
    }

    private fun stopShimmer() {
        shimmerAnim?.cancel()
        shimmerAnim = null
        shimmerX = Float.NaN
        invalidate()
    }

    /** prism-tide focusGlow: 1200 ms ease-in-out, alternate, infinite. */
    private fun startFocus() {
        focusAnim?.cancel()
        focusAnim = ValueAnimator.ofFloat(GLOW_REST, 1f).apply {
            duration = FOCUS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = EASE_IN_OUT
            addUpdateListener { glowAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopFocus() {
        focusAnim?.cancel()
        focusAnim = null
        glowAlpha = GLOW_REST
        invalidate()
    }

    private fun press(on: Boolean) {
        val s = if (on) 1.03f else 1f
        animate().scaleX(s).scaleY(s).setStartDelay(0L).setDuration(BOX_IN).setInterpolator(EMPHASIZED)
        animateOverlay(if (on) 0.65f else 0f, PRESS_FADE, CSS_EASE)
    }

    private fun animateOverlay(to: Float, ms: Long, curve: TimeInterpolator) {
        overlayAnim?.cancel()
        if (ms == 0L) {
            overlayAlpha = to
            invalidate()
            return
        }
        overlayAnim = ValueAnimator.ofFloat(overlayAlpha, to).apply {
            duration = ms
            interpolator = curve
            addUpdateListener { overlayAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility") // accessibility clicks are handled in performAccessibilityAction
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dismissOnly = selectionActive()
                if (!dismissOnly) press(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> press(false)
        }
        gestures.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_CLICK) {
            onTap?.invoke(this, false)
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        val fw = w.toFloat()
        val fh = h.toFloat()
        corner = min(CORNER_DP * dp, fh / 2f)                // prism-tide: radius = min(12dp, h/2)
        // a2 box: LinearGradient #A78BFA 20% -> #F472B6 16%, glow #A78BFA 28% r12
        painter = PillPainter(withAlpha(0.20f, 0xA78BFA), withAlpha(0.16f, 0xF472B6), withAlpha(0.28f, 0xA78BFA), GLOW_R_DP * dp, corner)
            .also { it.setSize(w, h) }
        card.setSize(w, h, corner)
        pillPath.reset()
        pillPath.addRoundRect(0f, 0f, fw, fh, corner, corner, Path.Direction.CW)
        overlay.shader = LinearGradient(0f, 0f, fw, 0f, withAlpha(0.28f, 0x7C3AED), withAlpha(0.26f, 0xEC4899), Shader.TileMode.CLAMP)
        shine.shader = LinearGradient(
            0f, 0f, fw * SHINE_BAND, 0f,
            intArrayOf(withAlpha(0f, 0xFFFFFF), withAlpha(0.9f, 0xFFFFFF), withAlpha(0f, 0xFFFFFF)), null, Shader.TileMode.CLAMP,
        )
        val band = fw * BAND
        shimmerPaint.shader = LinearGradient(
            0f, 0f, band, 0f,
            intArrayOf(withAlpha(0f, 0xFFFFFF), withAlpha(0.7f, 0xFFFFFF), withAlpha(0.22f, 0xF472B6), withAlpha(0f, 0xFFFFFF)),
            floatArrayOf(0f, 0.45f, 0.6f, 1f), Shader.TileMode.CLAMP,
        )
        prismPaint.shader = LinearGradient(
            0f, 0f, band, 0f,
            intArrayOf(withAlpha(0f, 0xFFFFFF), withAlpha(0.7f, 0xFFFFFF), withAlpha(0.35f, 0xF472B6), withAlpha(0.3f, 0x67E8F9), withAlpha(0f, 0xFFFFFF)),
            floatArrayOf(0f, 0.35f, 0.55f, 0.7f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val saved = canvas.save()
        canvas.translate(scrollX.toFloat(), scrollY.toFloat())   // keep the box fixed while the text scrolls
        if (reveal < 1f) {
            painter?.draw(canvas, alpha = if (selecting) SELECTING else 1f, withGlow = !selecting)   // a2 .box.selecting: fill x.45, no glow
            if (!shimmerX.isNaN()) sweep(canvas, shimmerPaint, shimmerX, w * BAND)
        }
        if (reveal > 0f) {
            val clip = canvas.save()
            clipReveal(canvas, w, h, GLOW_CLIP_DP * dp)
            card.draw(canvas, glowAlpha, markLinePx)
            canvas.restoreToCount(clip)
        }
        if (!prismX.isNaN()) sweep(canvas, prismPaint, prismX, w * BAND)
        if (overlayAlpha > 0f) {
            val layer = canvas.saveLayerAlpha(0f, 0f, w, h, (overlayAlpha.coerceIn(0f, 1f) * 255f).roundToInt())
            canvas.drawRoundRect(0f, 0f, w, h, corner, corner, overlay)
            canvas.restoreToCount(layer)
        }
        if (!shineX.isNaN()) sweep(canvas, shine, shineX, w * SHINE_BAND)
        canvas.restoreToCount(saved)
        when {
            translationText == null || reveal >= 1f -> super.onDraw(canvas)   // glyphs + selection highlight
            reveal > 0f -> {                                                  // translated text only where the card is
                val clip = canvas.save()
                canvas.translate(scrollX.toFloat(), scrollY.toFloat())
                clipReveal(canvas, w, h, 0f)
                canvas.translate(-scrollX.toFloat(), -scrollY.toFloat())
                super.onDraw(canvas)
                canvas.restoreToCount(clip)
            }
        }
    }

    /** Clips to the revealed part of the card ([pad] lets the glow outside the box through); no clip at 1. */
    private fun clipReveal(canvas: Canvas, w: Float, h: Float, pad: Float) {
        if (reveal >= 1f) return
        if (revealDir == Reveal.LEFT_RIGHT) canvas.clipRect(-pad, -pad, w * reveal, h + pad)
        else canvas.clipRect(-pad, -pad, w + pad, h * reveal)
    }

    /** A horizontal band of [paint] (shader spans 0..[band]) at [x], clipped to the box outline. */
    private fun sweep(canvas: Canvas, paint: Paint, x: Float, band: Float) {
        val clip = canvas.save()
        canvas.clipPath(pillPath)
        canvas.translate(x, 0f)
        canvas.drawRect(0f, 0f, band, height.toFloat(), paint)
        canvas.restoreToCount(clip)
    }

    override fun onDetachedFromWindow() {
        overlayAnim?.cancel()
        shineAnim?.cancel()
        shimmerAnim?.cancel()
        prismAnim?.cancel()
        revealAnim?.cancel()
        focusAnim?.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        const val PAD_X_DP = 7f
        const val PAD_Y_DP = 3f
        private const val CORNER_DP = 12f
        private const val GLOW_R_DP = 12f
        private const val GLOW_CLIP_DP = 32f       // > card glow pad (blur 14dp + dy 4dp)
        private const val MIN_SP = 10f             // spec §3 minimum translation size
        private const val TR_SPACING = 1.1f        // ≈ mockup line-height 1.28 over the default font line height
        private const val BAND = 0.7f              // shimmer / prism band = 70 % of the box
        private const val GLOW_REST = 0.6f         // × 70 % lavender = 42 %
        private const val BOX_IN = 260L
        private const val FLASH = 380L
        private const val FLIP = 240L
        private const val ARRIVE = 320L
        private const val SHIMMER = 1200L
        private const val FOCUS = 1200L
        private const val PRESS_FADE = 140L
        private const val SHINE_BAND = 0.48f
        private const val SELECTING = 0.45f
        private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)      // a2 boxIn, prism-tide flip
        private val CSS_EASE = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)  // CSS "ease" (pressing overlay)
        private val EASE_OUT = PathInterpolator(0f, 0f, 0.58f, 1f)       // CSS "ease-out" (copied flash)
        private val EASE_IN_OUT = PathInterpolator(0.42f, 0f, 0.58f, 1f) // CSS "ease-in-out" (focus glow)
        private val SHINE_CURVE = PathInterpolator(0.4f, 0f, 0.2f, 1f)   // a2 shine, prism-tide arrive
    }
}
```

- [ ] **Step 7: Run the gate and the greps**

Send `SETTLED`; gate-runner: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug testDebugUnitTest; echo "RC=$?"`
Expected: `RC=0`; `TEST-us.jhangy.textify.TextFitTest.xml` → 8 testcases, 0 failures; OcrMergeTest 7, TranslateRulesTest 12, OpenCcTest 3.
Then: `grep -nE "setShadowLayer|RenderEffect" app/src/main/java/us/jhangy/textify/*.kt` → no output; `grep -n "fun showTranslation(text: String, bg: Int, reveal: Reveal, delayMs: Long)" app/src/main/java/us/jhangy/textify/PillView.kt` → one line.

- [ ] **Step 8: Commit**

```bash
git rev-parse --abbrev-ref HEAD   # main
git add app/src/main/java/us/jhangy/textify/TextFit.kt app/src/main/java/us/jhangy/textify/TranslatedCard.kt app/src/main/java/us/jhangy/textify/PillView.kt app/src/test/java/us/jhangy/textify/TextFitTest.kt
git commit -m "feat(session): translated card mode for paragraph boxes

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>" -- app/src/main/java/us/jhangy/textify/TextFit.kt app/src/main/java/us/jhangy/textify/TranslatedCard.kt app/src/main/java/us/jhangy/textify/PillView.kt app/src/test/java/us/jhangy/textify/TextFitTest.kt
git status --porcelain -- app/src/main/java/us/jhangy/textify/TextFit.kt app/src/main/java/us/jhangy/textify/TranslatedCard.kt app/src/main/java/us/jhangy/textify/PillView.kt app/src/test/java/us/jhangy/textify/TextFitTest.kt   # empty
```

### Task 7: Session translation mode

**Files:**
- Modify: `res/layout/activity_session.xml` (add `tide` after `pills`; add `trBtn` after `settings`)
- Create: `res/drawable/ic_translate.xml`
- Modify: `res/values/strings.xml` (`session_translate`)
- Modify: `java/SessionActivity.kt`
- Modify: `java/Translate.kt` (one comment line recording the on-device Wi-Fi observation)

**Interfaces:**
- Consumes: `Translate.start`, `TranslateRun.close` (Task 4); `Prefs.target` (Task 4); `ParagraphState`, `chipFor`, `anyTranslating`, `needsRun` (Task 3); `SpinnerRingView`, `StatusChipView` (Task 5); `PillView.showTranslation/showOriginal/showsTranslation/translationText/truncated/shimmering/Reveal`, `edgeColor` (Task 6); `GlowDrawable`, `CssGradientDrawable`, `withAlpha` (`Glow.kt`).
- Produces (used by Task 9, all private members of `SessionActivity`):
  - `private var blocks: List<OcrBlock>`; `private val pills: ArrayList<PillView>`; `private val chips: ArrayList<StatusChipView>`; `private var states: Array<ParagraphState?>`; `private var translating: Boolean`.
  - `private fun onPillTap(i: Int, pill: PillView, dismissOnly: Boolean)`; `private fun putClip(text: String)`; `private fun toggleTranslation()`.
  - Layout ids `tide`, `trBtn`, `trOn`, `trIcon`, `trRing`.

**Behavior:**
- Button (spec §3): third round button left of settings (`marginEnd` 120dp, 40dp, `session_x_bg`), same entrance as X/settings (`xIn` 240, overshoot), delay 2 × btnStagger; appears only after OCR finished with at least one paragraph. Looks: off = white 86 % + plum icon; busy = off + icon alpha 0.55 + 48dp spinner ring (3dp) fading in over btnState 240; on = gradient #7C3AED → #EC4899 (135°) layer with glow #F472B6 45 % blur 18dp fading in over 240, white icon. Busy = translation mode on and `anyTranslating(states)` (FP); while models only download it shows on.
- Press when off: mode on; start a `TranslateRun` for every paragraph with `needsRun(state)` (never run, `WaitingWifi`, `Failed`) using `Prefs.target`; tide in. Press when on or busy: mode off (tide back); running translations continue and their results show next time (§3). Toggling never re-translates `Done` / `NotNeeded` / `Unsupported` paragraphs.
- Tide (mockup note 1): `tide` view 220dp, background `CssGradientDrawable` 180° stops transparent @0, #A78BFA 42 % @.30, #F472B6 48 % @.48, #FDBA74 40 % @.64, #67E8F9 34 % @.80, transparent @1; translationY −220dp → H over 400 ms fast-out-slow-in (in), H → −220dp over 320 ms (back); invisible otherwise. Each paragraph's view update is delayed by the tide formula (Global Constraints) and uses `Reveal.TOP_DOWN`.
- State → view (§3): mode on + `Done` → `showTranslation(text, edgeColor(shot, block.box), …)` (colour sampled once per paragraph); mode on + `Translating` → original look + shimmer; mode on + Downloading / WaitingWifi / Unsupported / Failed → original look + chip (`chipFor`); `NotNeeded` → original look; mode off → everything original, no chips, no shimmer. A result arriving while mode is on applies at once with `Reveal.LEFT_RIGHT` (arrive).
- Chips: one `StatusChipView` per paragraph in the `pills` container, right edge 6dp beyond the box's right edge, top 11dp above the box's top edge (`Gravity.TOP or Gravity.RIGHT`), added after all pills so chips draw above boxes.
- Taps: translated box → copy the translation (plus the existing copy flash); original box → copy the paragraph; truncated translated box → copy the translation in this task (Task 9 replaces this one branch with the bottom panel). Long-press selects in both modes.
- Session end (X, settings button, back, orientation change, destroy) closes every `TranslateRun`; downloads continue (§2, §5).

**Constraints:**
- `SessionActivity` never imports `com.google.mlkit.nl.*`.
- Animations run with the timings in Global Constraints.

**Acceptance criteria:**
- [ ] Gate `RC=0`.
- [ ] `grep -n "com.google.mlkit.nl" app/src/main/java/us/jhangy/textify/SessionActivity.kt` prints nothing; `grep -n "android:id=\"@+id/trBtn\"" app/src/main/res/layout/activity_session.xml` prints one line.
- [ ] On device, Wi-Fi on, target default 繁體中文: an English paragraph (for example https://en.wikipedia.org/wiki/Computer_memory) turns into a translated card in Traditional Chinese using Taiwan phrases (記憶體 / 軟體, not 内存 / 软件); a Simplified Chinese paragraph (https://zh.wikipedia.org/zh-cn/内存) turns into Traditional; a Traditional Chinese paragraph (https://zh.wikipedia.org/zh-tw/記憶體) keeps the original look; the button shows the busy ring only while paragraphs are Translating (while only 「下載中…」 chips are pending it shows the on look); `adb logcat -d | grep -iE "opencc|houbb|FATAL"` shows no exception.
- [ ] On device, Wi-Fi-only on: `adb shell svc wifi disable` with mobile data on, translate a Japanese page whose model is not downloaded → its paragraphs show 「等待 Wi-Fi」 and `Translate.downloading` stays empty (Settings in Task 8 is not needed: no 「下載中…」 chip appears). Then `adb shell svc wifi enable`, start a Korean download by pressing translate on a Korean page, run `adb shell svc wifi disable` within 5 s, and observe the chips for 2 minutes: report whether ML Kit keeps waiting (chips stay 「下載中…」) or fails (chips turn 「翻譯失敗」). Record the observation as one `// ponytail:` comment line above `requireWifi()` in `Translate.kt` ("observed on <device>, API <n>: requireWifi without Wi-Fi → waits | fails after <s> s").
- [ ] On device: pressing the button while it spins returns to the original look at once; pressing again later shows the finished translations without the button spinning again for them.


**Steps:**

(No new JVM test: the state rules used here — `chipFor`, `anyTranslating`, `needsRun` — are tested in Task 3. This task is verified on device.)

- [ ] **Step 1: Create `res/drawable/ic_translate.xml`** (prism-tide `#trBtn` glyph)

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- translate button glyph (prism-tide #trBtn), plum; tinted white in the on state -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="20dp" android:height="20dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path
        android:fillColor="#4C1D95"
        android:pathData="M12.87,15.07l-2.54,-2.51 0.03,-0.03c1.74,-1.94 2.98,-4.17 3.71,-6.53H17V4h-7V2H8v2H1v1.99h11.17C11.5,7.92 10.44,9.75 9,11.35 8.07,10.32 7.3,9.19 6.69,8h-2c0.73,1.63 1.73,3.17 2.98,4.56l-5.09,5.02L4,19l5,-5 3.11,3.11 0.76,-2.04zM18.5,10h-2L12,22h2l1.12,-3h4.75L21,22h2l-4.5,-12zM15.88,17l1.62,-4.33L19.12,17h-3.24z" />
</vector>
```

- [ ] **Step 2: Add the string** — in `res/values/strings.xml`, before `</resources>`:

```xml
    <string name="session_translate">翻譯</string>
```

- [ ] **Step 3: Edit `res/layout/activity_session.xml`**

Change the header comment's layer list to `screenshot, scrim, pill container, tide, edge glow, X, settings, translate, hint.` Insert directly after the `pills` `FrameLayout`:

```xml

    <!-- translation tide band (prism-tide note 1): 220dp, swept top <-> bottom by SessionActivity -->
    <View
        android:id="@+id/tide"
        android:layout_width="match_parent"
        android:layout_height="220dp"
        android:importantForAccessibility="no"
        android:visibility="invisible" />
```

Insert directly after the `settings` `FrameLayout`:

```xml

    <!-- translate button (translation spec §3, prism-tide #trBtn), left of settings. clipChildren=false: the
         48dp busy ring and the on-state glow draw outside the 40dp circle. -->
    <FrameLayout
        android:id="@+id/trBtn"
        android:layout_width="40dp"
        android:layout_height="40dp"
        android:layout_gravity="top|end"
        android:layout_marginTop="44dp"
        android:layout_marginEnd="120dp"
        android:background="@drawable/session_x_bg"
        android:clickable="true"
        android:clipChildren="false"
        android:clipToPadding="false"
        android:contentDescription="@string/session_translate"
        android:elevation="4dp"
        android:focusable="true">

        <View
            android:id="@+id/trOn"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:alpha="0"
            android:importantForAccessibility="no" />

        <ImageView
            android:id="@+id/trIcon"
            android:layout_width="20dp"
            android:layout_height="20dp"
            android:layout_gravity="center"
            android:importantForAccessibility="no"
            android:src="@drawable/ic_translate" />

        <us.jhangy.textify.SpinnerRingView
            android:id="@+id/trRing"
            android:layout_width="48dp"
            android:layout_height="48dp"
            android:layout_gravity="center"
            android:alpha="0"
            android:importantForAccessibility="no"
            android:visibility="invisible" />
    </FrameLayout>
```

- [ ] **Step 4: Replace `java/SessionActivity.kt`**

```kotlin
package us.jhangy.textify

import android.animation.ObjectAnimator
import android.animation.TimeInterpolator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.os.Bundle
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import us.jhangy.textify.databinding.ActivitySessionBinding
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Frozen-screen capture session (a2 "Halo"). Screenshot 1:1 under hidden system bars, edge glow +
 * scrim while OCR runs, then one selectable pill per OCR paragraph. Tap pill = copy paragraph, long-press =
 * system selection, X/back = closeOut fade, then CaptureService brings the bubble back.
 * Settings button = session fades out and SettingsActivity opens (bubble stays hidden).
 * Translate button (translation spec §3) = translated cards in place of the paragraphs, and back.
 */
class SessionActivity : AppCompatActivity() {

    private lateinit var b: ActivitySessionBinding
    private var shot: Bitmap? = null
    private val origin = IntArray(2)          // window position on screen; screenshot px - origin = view px
    private var startOrientation = Configuration.ORIENTATION_UNDEFINED
    private var closing = false
    private var ended = false
    private var ocrRunning = false
    private var actionMode: ActionMode? = null

    // translation (spec §3); every list is indexed by paragraph in display order (top, then left)
    private var blocks: List<OcrBlock> = emptyList()
    private val pills = ArrayList<PillView>()
    private val chips = ArrayList<StatusChipView>()
    private var states: Array<ParagraphState?> = emptyArray()
    private val cardColors = HashMap<Int, Int>()
    private var translating = false           // translation mode on (button on or busy)
    private val runs = ArrayList<TranslateRun>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()                    // decor does not fit system windows + cutout mode (library handles API levels)
        val bmp = pending
        pending = null
        if (bmp == null) { finish(); return } // nothing to show; onDestroy still ends the session
        shot = bmp
        active = WeakReference(this)
        startOrientation = resources.configuration.orientation

        b = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(b.root)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        b.shot.setImageBitmap(bmp)
        b.root.doOnLayout { root ->
            root.getLocationOnScreen(origin)
            b.shot.imageMatrix = Matrix().apply { setTranslate(-origin[0].toFloat(), -origin[1].toFloat()) }
            b.hint.translationY = -0.04f * root.height       // a2 hint centre sits at 46% of the height
        }

        b.close.setOnClickListener { close() }
        b.settings.setOnClickListener { openSettings() }
        b.trBtn.setOnClickListener { toggleTranslation() }
        b.pills.setOnClickListener { actionMode?.finish() }   // empty space never closes; it only drops a selection
        onBackPressedDispatcher.addCallback(this) {
            val mode = actionMode
            if (mode != null) mode.finish() else close()
        }

        b.hintOrb.setImageDrawable(orb(dim = true))
        b.close.apply { alpha = 0f; scaleX = 0.6f; scaleY = 0.6f; rotation = -90f; isEnabled = false }
        b.settings.apply { alpha = 0f; scaleX = 0.6f; scaleY = 0.6f; rotation = -90f; isEnabled = false }
        b.trBtn.apply { alpha = 0f; scaleX = 0.6f; scaleY = 0.6f; rotation = -90f; isEnabled = false }
        // prism-tide #trBtn on state: LinearGradient(135deg) #7C3AED -> #EC4899 + glow #F472B6 45% r18
        b.trOn.background = GlowDrawable(
            CssGradientDrawable(intArrayOf(0xFF7C3AED.toInt(), 0xFFEC4899.toInt()), null, 135f, -1f),
            withAlpha(0.45f, 0xF472B6), dpToPx(18f), 0f, 0f, -1f,
        )
        b.tide.background = CssGradientDrawable(TIDE_COLORS, TIDE_STOPS, 180f, 0f)

        // activation: glow blooms in, faint scrim after scrimDelay, OCR runs
        showClose()                           // X is available from capture start (pressing it during OCR closes)
        b.glow.activate()
        b.scrim.background = scrimDrawable()
        b.scrim.animate().alpha(1f).setStartDelay(SCRIM_DELAY).setDuration(SCRIM_IN).setInterpolator(EASE_OUT)
        ocrRunning = true
        Ocr.recognize(this, bmp, Prefs.scripts(this)) { found -> runOnUiThread { onOcr(found) } }
    }

    private fun onOcr(found: List<OcrBlock>) {
        ocrRunning = false
        if (isDestroyed) {                    // session already gone: the bitmap is ours to free now
            shot?.recycle()
            shot = null
            return
        }
        if (closing) return
        b.root.doOnLayout {
            b.scrim.animate().cancel()
            b.scrim.animate().alpha(0f).setStartDelay(0L).setDuration(SCRIM_OUT).setInterpolator(EASE_IN)
            b.glow.settle()
            if (found.isEmpty()) showHint() else showPills(found)
        }
    }

    private fun showPills(found: List<OcrBlock>) {
        val d = resources.displayMetrics.density
        val padX = (PillView.PAD_X_DP * d).roundToInt()
        val padY = (PillView.PAD_Y_DP * d).roundToInt()
        blocks = found.sortedWith(compareBy({ it.box.top }, { it.box.left }))
        states = arrayOfNulls(blocks.size)
        val n = blocks.size
        blocks.forEachIndexed { i, block ->
            val pill = PillView(this, block)
            pill.layoutParams = FrameLayout.LayoutParams(block.box.width() + 2 * padX, block.box.height() + 2 * padY).apply {
                leftMargin = block.box.left - padX - origin[0]
                topMargin = block.box.top - padY - origin[1]
            }
            pill.selectionActive = { actionMode != null }
            pill.onTap = { p, dismissOnly -> onPillTap(i, p, dismissOnly) }
            pill.customSelectionActionModeCallback = selectionCallback(pill)
            b.pills.addView(pill)
            pills += pill
            // a2: box i delay = round(i/(n-1) * boxStagger)
            pill.playIn(if (n > 1) (i * BOX_STAGGER / (n - 1)).roundToLong() else 0L)
        }
        // chips after every box so they draw above them (prism-tide .chip: right -6dp, top -11dp of the box)
        val containerW = b.pills.width
        for (pill in pills) {
            val lp = pill.layoutParams as FrameLayout.LayoutParams
            val chip = StatusChipView(this)
            chip.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (CHIP_H_DP * d).roundToInt(), Gravity.TOP or Gravity.RIGHT,
            ).apply {
                rightMargin = containerW - (lp.leftMargin + lp.width) - (CHIP_DX_DP * d).roundToInt()
                topMargin = lp.topMargin - (CHIP_DY_DP * d).roundToInt()
            }
            b.pills.addView(chip)
            chips += chip
        }
        showTranslateButton()
    }

    /** Tap on paragraph [i]: drop a selection, copy the translation (translated card) or the paragraph. */
    private fun onPillTap(i: Int, pill: PillView, dismissOnly: Boolean) {
        val shown = pill.translationText
        when {
            dismissOnly -> actionMode?.finish()
            pill.showsTranslation && shown != null -> copyText(pill, shown)
            else -> copyText(pill, blocks[i].text)
        }
    }

    private fun selectionCallback(pill: PillView) = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            actionMode = mode
            pill.selecting = true
            return true                       // keep the system menu (Copy, Select all, ...)
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId == android.R.id.copy) {
                val s = minOf(pill.selectionStart, pill.selectionEnd)
                val e = maxOf(pill.selectionStart, pill.selectionEnd)
                if (s in 0 until e) pill.post { copied(pill) } // feedback only; the TextView performs the copy
            }
            return false                      // let the TextView handle the item
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            if (actionMode === mode) actionMode = null
            pill.selecting = false
        }
    }

    private fun copyText(pill: PillView, text: String) {
        putClip(text)
        copied(pill)
    }

    private fun putClip(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("textify", text))
    }

    private fun copied(pill: PillView) {
        if (closing) return
        pill.playCopied()
    }

    // ---- translation (spec §3) ----

    /** Off -> on: (re)start translation for paragraphs that need it and sweep the tide in; on / busy -> original. */
    private fun toggleTranslation() {
        if (closing || pills.isEmpty()) return
        actionMode?.finish()
        translating = !translating
        if (translating) startRun()
        tide(translating)
        refreshButton()
    }

    /** Spec §2/§5: never-run, WaitingWifi and Failed paragraphs are (re)translated; others keep their result. */
    private fun startRun() {
        val todo = blocks.indices.filter { needsRun(states[it]) }.associateWith { blocks[it].text }
        if (todo.isNotEmpty()) runs += Translate.start(this, todo, Prefs.target(this), ::onParagraphState)
    }

    private fun onParagraphState(i: Int, s: ParagraphState) {
        if (ended || isDestroyed) return
        states[i] = s
        if (translating) applyState(i, PillView.Reveal.LEFT_RIGHT, 0L)   // prism-tide arrive
        refreshButton()
    }

    /** Maps paragraph [i]'s state to its box and chip for the current mode (spec §3). */
    private fun applyState(i: Int, reveal: PillView.Reveal, delayMs: Long) {
        val pill = pills[i]
        val s = states[i]
        pill.shimmering = translating && s == ParagraphState.Translating
        chips[i].show(if (translating) chipFor(s) else null, delayMs)
        if (translating && s is ParagraphState.Done) {
            if (!pill.showsTranslation || pill.translationText != s.text) pill.showTranslation(s.text, cardColor(i), reveal, delayMs)
        } else if (pill.showsTranslation) {
            pill.showOriginal(reveal, delayMs)
        }
    }

    /** Card colour = average screenshot colour along the box edge, sampled once per paragraph. */
    private fun cardColor(i: Int): Int = cardColors.getOrPut(i) {
        shot?.let { edgeColor(it, blocks[i].box) } ?: Color.WHITE
    }

    /** prism-tide tide 400 (in) / tideBack 320 (out); each box follows with the tide delay (Global Constraints). */
    private fun tide(on: Boolean) {
        val h = max(1, b.root.height).toFloat()
        val band = dpToPx(TIDE_BAND_DP)
        b.tide.animate().cancel()
        b.tide.visibility = View.VISIBLE
        b.tide.translationY = if (on) -band else h
        b.tide.animate().translationY(if (on) h else -band)
            .setStartDelay(0L).setDuration(if (on) TIDE else TIDE_BACK).setInterpolator(FAST_OUT_SLOW_IN)
            .withEndAction { b.tide.visibility = View.INVISIBLE }
        pills.forEachIndexed { i, pill ->
            val cy = (pill.top + pill.height / 2f) / h
            val delay = if (on) (TIDE * cy * 0.85f).roundToLong() else (TIDE_BACK * (1f - cy) * 0.8f).roundToLong()
            applyState(i, PillView.Reveal.TOP_DOWN, max(0L, delay))
        }
    }

    /** Final pick: busy (ring, dimmed icon) only while a paragraph is Translating; on = gradient; off = white. */
    private fun refreshButton() {
        val busy = translating && anyTranslating(states.asList())
        val on = translating && !busy
        b.trOn.animate().alpha(if (on) 1f else 0f).setDuration(BTN_STATE)
        b.trIcon.setColorFilter(if (on) Color.WHITE else PLUM)
        b.trIcon.animate().alpha(if (busy) 0.55f else 1f).setDuration(BTN_STATE)
        if (busy) b.trRing.visibility = View.VISIBLE
        b.trRing.animate().alpha(if (busy) 1f else 0f).setDuration(BTN_STATE).withEndAction {
            if (!(translating && anyTranslating(states.asList()))) b.trRing.visibility = View.INVISIBLE
        }
        b.trBtn.isSelected = translating
    }

    /** Same entrance as X / settings (xIn 240, overshoot), one more btnStagger later; only once OCR found text. */
    private fun showTranslateButton() {
        val delay = 2 * BTN_STAGGER
        b.trBtn.isEnabled = true
        b.trBtn.fade(1f, XIN, EMPHASIZED, delay)
        b.trBtn.animate().scaleX(1f).scaleY(1f).rotation(0f)
            .setStartDelay(delay).setDuration(XIN).setInterpolator(X_OVERSHOOT)
    }

    private fun showClose() {
        listOf(b.close, b.settings).forEachIndexed { i, btn ->
            val delay = i * BTN_STAGGER
            btn.isEnabled = true
            btn.fade(1f, XIN, EMPHASIZED, delay)
            btn.animate().scaleX(1f).scaleY(1f).rotation(0f)
                .setStartDelay(delay).setDuration(XIN).setInterpolator(X_OVERSHOOT)
        }
    }

    private fun showHint() {
        b.hint.apply { visibility = View.VISIBLE; alpha = 0f; scaleX = 0.92f; scaleY = 0.92f }
        b.hint.fade(1f, HINT_IN, EMPHASIZED)
        b.hint.animate().scaleX(1f).scaleY(1f).setDuration(HINT_IN).setInterpolator(HINT_OVERSHOOT)
    }

    /** a2 scrim: RadialGradient #1E1B4B 5% centre -> 16%, CSS radial-gradient(120% 80% at 50% 50%). */
    private fun scrimDrawable() = ShapeDrawable(RectShape()).apply {
        shaderFactory = object : ShapeDrawable.ShaderFactory() {
            override fun resize(width: Int, height: Int): Shader {
                val w = max(1, width).toFloat()
                val h = max(1, height).toFloat()
                return RadialGradient(w / 2f, h / 2f, 1.2f * w, withAlpha(0.05f, INK), withAlpha(0.16f, INK), Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply { setScale(1f, (0.8f * h) / (1.2f * w), w / 2f, h / 2f) })
                }
            }
        }
    }

    /** a2 orb: conic ring of the halo colours with a white hole (inset 7dp); dim = grayscale + opacity .6. */
    private fun orb(dim: Boolean): Drawable {
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.SWEEP_GRADIENT
            colors = ORB_COLORS
        }
        val hole = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        val inset = (7f * resources.displayMetrics.density).roundToInt()
        return LayerDrawable(arrayOf<Drawable>(ring, hole)).apply {
            setLayerInset(1, inset, inset, inset, inset)
            if (dim) {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                alpha = 153
            }
        }
    }

    private fun View.fade(to: Float, ms: Long, curve: TimeInterpolator, delay: Long = 0L) {
        ObjectAnimator.ofFloat(this, View.ALPHA, to).apply {
            startDelay = delay
            duration = ms
            interpolator = curve
            setAutoCancel(true)               // a newer fade of the same view replaces the running one
            start()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation != startOrientation) finish()   // screenshot no longer maps 1:1
    }

    private fun close() {
        if (closing) return
        closing = true
        actionMode?.finish()
        b.close.animate().scaleX(0.6f).scaleY(0.6f).rotation(90f)
            .setDuration(CLOSE_OUT).setInterpolator(X_OVERSHOOT)
        b.root.animate().alpha(0f).setDuration(CLOSE_OUT).setInterpolator(CLOSE_CURVE)
            .withEndAction { endSession(); finish() }
    }

    /** §4: session fades out, then settings opens with the bubble hidden. Allowed during and after OCR. */
    private fun openSettings() {
        if (closing) return
        closing = true
        actionMode?.finish()
        b.settings.animate().rotation(90f).scaleX(1.08f).scaleY(1.08f)
            .setStartDelay(0L).setDuration(SESSION_OUT).setInterpolator(X_OVERSHOOT)
        b.root.animate().alpha(0f).setDuration(SESSION_OUT).setInterpolator(CLOSE_CURVE)
            .withEndAction {
                CaptureService.setSettingsVisible(true)
                SettingsActivity.start(this)
                endSession()
                finish()
            }
    }

    /** Exactly one session-ended callback to the service per activity instance; late translation results are dropped. */
    private fun endSession() {
        if (ended) return
        ended = true
        runs.forEach { it.close() }           // translation spec §2/§5: model downloads keep running
        runs.clear()
        CaptureService.onSessionEnded()
    }

    override fun onDestroy() {
        if (active?.get() === this) active = null
        shot?.let { bmp ->
            b.shot.setImageDrawable(null)
            if (!ocrRunning) {                // ML Kit may still read it; onOcr frees it otherwise
                bmp.recycle()
                shot = null
            }
        }
        endSession()
        super.onDestroy()
    }

    companion object {
        // a2 header TIMINGS (ms)
        private const val SCRIM_DELAY = 150L
        private const val SCRIM_IN = 300L
        private const val SCRIM_OUT = 300L
        private const val XIN = 240L
        private const val HINT_IN = 300L
        private const val CLOSE_OUT = 300L
        private const val SESSION_OUT = 300L
        private const val BTN_STAGGER = 40L
        private const val BOX_STAGGER = 140f
        // prism-tide header TIMINGS (ms) and geometry
        private const val TIDE = 400L
        private const val TIDE_BACK = 320L
        private const val BTN_STATE = 240L
        private const val TIDE_BAND_DP = 220f
        private const val CHIP_H_DP = 20f
        private const val CHIP_DX_DP = 6f
        private const val CHIP_DY_DP = 11f

        private const val INK = 0x1E1B4B
        private val PLUM = 0xFF4C1D95.toInt()
        private val ORB_COLORS = longArrayOf(0xFFA78BFA, 0xFFF472B6, 0xFFFDBA74, 0xFF67E8F9, 0xFF818CF8, 0xFFA78BFA).map { it.toInt() }.toIntArray()
        // prism-tide tide band: vertical [0, #A78BFA 42%, #F472B6 48%, #FDBA74 40%, #67E8F9 34%, 0]
        private val TIDE_COLORS = intArrayOf(
            withAlpha(0f, 0xA78BFA), withAlpha(0.42f, 0xA78BFA), withAlpha(0.48f, 0xF472B6),
            withAlpha(0.40f, 0xFDBA74), withAlpha(0.34f, 0x67E8F9), withAlpha(0f, 0x67E8F9),
        )
        private val TIDE_STOPS = floatArrayOf(0f, 0.30f, 0.48f, 0.64f, 0.80f, 1f)

        private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)         // a2 cubic-bezier(.2,0,0,1)
        private val X_OVERSHOOT = PathInterpolator(0.2f, 0f, 0f, 1.3f)      // a2 .xbtn transform
        private val HINT_OVERSHOOT = PathInterpolator(0.2f, 0f, 0f, 1.2f)   // a2 .nohint transform
        private val EASE_OUT = PathInterpolator(0f, 0f, 0.58f, 1f)          // CSS ease-out (scrimIn)
        private val EASE_IN = PathInterpolator(0.42f, 0f, 1f, 1f)           // CSS ease-in (scrimOut)
        private val CLOSE_CURVE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)   // a2 close() opacity curve
        private val FAST_OUT_SLOW_IN = PathInterpolator(0.4f, 0f, 0.2f, 1f) // prism-tide tide

        private var pending: Bitmap? = null
        private var active: WeakReference<SessionActivity>? = null

        /** Main thread only. Takes ownership of [shot]; the session recycles it. */
        fun start(ctx: Context, shot: Bitmap) {
            pending?.recycle()
            pending = shot
            ctx.startActivity(
                Intent(ctx, SessionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        }

        /** Projection stopped: drop any unconsumed shot and close the open session without animation. */
        fun finishActive() {
            pending?.recycle()
            pending = null
            active?.get()?.finish()
        }
    }
}
```

- [ ] **Step 5: Run the gate**

Send `SETTLED`; gate-runner: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug testDebugUnitTest; echo "RC=$?"` → `RC=0`, test counts unchanged (7 / 8 / 12 / 3).
Then: `grep -n "com.google.mlkit.nl" app/src/main/java/us/jhangy/textify/SessionActivity.kt` → no output; `grep -n 'android:id="@+id/trBtn"' app/src/main/res/layout/activity_session.xml` → one line.

- [ ] **Step 6: On-device check — translation path and OpenCC on Android**

Gate-runner installs: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew installDebug`. Wi-Fi on, target default 繁體中文 (fresh install), Wi-Fi-only default on.
1. `adb logcat -c`; `adb shell am start -a android.intent.action.VIEW -d "https://en.wikipedia.org/wiki/Computer_memory"`; capture, press translate. Expected: the button shows the busy ring while paragraphs are Translating, the tide sweeps, paragraphs turn into cards with readable text in Traditional Chinese using Taiwan phrases (記憶體 / 軟體, not 内存 / 软件; the zh model may first show 「下載中…」 chips with the button in the on look, no ring); the button ends in the on look.
2. Same with `https://zh.wikipedia.org/zh-cn/内存` → Simplified paragraphs become Traditional cards.
3. Same with `https://zh.wikipedia.org/zh-tw/記憶體` → paragraphs keep the original look (OpenCC output equals input).
4. `adb logcat -d | grep -iE "opencc|houbb|FATAL EXCEPTION"` → no exception lines.
5. Press the button while it shows the ring → boxes return to the original look at once (tide back); press again after a few seconds → finished translations appear with the tide and the button does not show the ring for them.
Report what you saw per item.

- [ ] **Step 7: On-device check — Wi-Fi-only behaviour (spec §2 "verify on device")**

1. `adb shell svc wifi disable` (mobile data on). Open `https://ja.wikipedia.org/wiki/東京都` (ja model not downloaded), capture, press translate → Japanese paragraphs show 「等待 Wi-Fi」; no 「下載中…」 chip appears.
2. `adb shell svc wifi enable`; open `https://ko.wikipedia.org/wiki/서울특별시`, capture, press translate; as soon as 「下載中…」 chips appear run `adb shell svc wifi disable`; watch the chips for 2 minutes. Record whether they stay 「下載中…」 (ML Kit waits) or turn 「翻譯失敗」 (ML Kit fails) and after how many seconds. Then `adb shell svc wifi enable` and press translate again (if the chips failed) to confirm the download completes.
3. In `java/Translate.kt`, add this comment line directly above `val conditions = DownloadConditions.Builder()…` with the observed values filled in (device model, API level, and either `waits` or `fails after N s`):
```kotlin
        // ponytail: observed on <device>, API <n>: requireWifi() while Wi-Fi drops mid-download -> <waits | fails after N s>
```

- [ ] **Step 8: Commit**

```bash
git rev-parse --abbrev-ref HEAD   # main
git add app/src/main/res/drawable/ic_translate.xml app/src/main/res/values/strings.xml app/src/main/res/layout/activity_session.xml app/src/main/java/us/jhangy/textify/SessionActivity.kt app/src/main/java/us/jhangy/textify/Translate.kt
git commit -m "feat(session): translate button, tide and per-paragraph translated cards

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>" -- app/src/main/res/drawable/ic_translate.xml app/src/main/res/values/strings.xml app/src/main/res/layout/activity_session.xml app/src/main/java/us/jhangy/textify/SessionActivity.kt app/src/main/java/us/jhangy/textify/Translate.kt
git status --porcelain -- app/src/main/res/drawable/ic_translate.xml app/src/main/res/values/strings.xml app/src/main/res/layout/activity_session.xml app/src/main/java/us/jhangy/textify/SessionActivity.kt app/src/main/java/us/jhangy/textify/Translate.kt   # empty
```

### Task 8: Settings 「翻譯」 card

**Files:**
- Modify: `res/layout/activity_settings.xml` (new card after `langCard`; sheet views at the end of the root `FrameLayout`)
- Create: `res/drawable/ic_chevron.xml`, `res/drawable/ic_trash.xml`, `res/drawable/ic_download.xml`, `res/drawable/ic_info.xml`
- Modify: `res/values/strings.xml` (settings strings listed in Step 1)
- Modify: `java/SettingsActivity.kt`

**Interfaces:**
- Consumes: `Prefs.target/setTarget/wifiOnly/setWifiOnly`, `Translate.onModelsChanged/downloading/waitingWifi/languages/download/delete/downloaded` (Task 4); `TargetLang`, `ModelState`, `modelRows` (Task 3); `Languages` (Task 3); `SpinnerRingView` (Task 5); `HaloStyle.card`, `GlowDrawable`, `CssGradientDrawable`, `withAlpha` (`Glow.kt`).
- Produces (used by Task 10): layout id `trCard` (vertical `LinearLayout`; its second child is the rows block `trRows`); `private fun applyTarget(key: String)` in `SettingsActivity` (updates the target value text; Task 10 adds the preview update inside it).

**Behavior:**
- Card (spec §4, prism-tide `#trCard`): same card style (`HaloStyle.card`) below the OCR language card. Header 「翻譯」 + hint 「在擷取畫面按「譯」鍵才會翻譯」. Rows (`prow`: #7C3AED 6 % fill, radius 24dp, min height 56dp, pressed 12 %): 「目標語言」 / 「每段自動偵測來源語言」 with value (`Languages.targetTitle`, #7C3AED bold) + chevron, tap opens the target sheet; 「只用 Wi-Fi 下載」 / 「使用行動網路時不下載語言模型」 with a switch (default on; off track #1E1B4B 12 %, on track gradient #A78BFA → #F472B6, white thumb with glow, toggle 200 ms), saved at once.
- Model list (spec §4): header 「語言模型」 + 「每次開啟設定時向系統查詢」; well #FAF5FF 90 % radius 22dp; one 60dp row per `modelRows(downloaded, Translate.downloading, Translate.waitingWifi)`: 38dp gradient disc with `Languages.glyph`, title `Languages.modelTitle` 15sp bold + `modelSub` 12sp #6B5FA8, status line 12.5sp: BUILTIN 「隨 App 內建，無法刪除」 + badge 「內建」 (#7C3AED 10 %); DONE 「已下載 · 可離線翻譯」 + 44dp trash button (deletes at once, no dialog, FP); DOWNLOADING 「下載中…」 (#7C3AED bold) + 22dp spinner (3dp); WAITING_WIFI grey disc #E5E2EE, all text #8A86A0, 「等待 Wi-Fi」 + 20dp wifi icon. Row add/remove animate over 260 ms (`LayoutTransition`). A row going DOWNLOADING → DONE plays the sparkle (disc scale 1 → 1.15 → 1, 380 ms).
- The downloaded set is queried from ML Kit in every `onResume` and on every `Translate.onModelsChanged` while Settings is resumed; nothing is stored separately.
- 「預先下載語言」 button (50dp, #7C3AED 8 %, download icon) opens the download sheet; note line with info icon 「每個語言約 30MB，下載後可離線翻譯」.
- Sheet (in-window): scrim #1E1B4B 18 %, white 97 % sheet with 28dp top corners, at most 74 % of the screen, sheetIn 300 ms decelerate / sheetOut 240 ms accelerate; tap scrim or back closes it. Target mode: title 「目標語言」, sub 「翻譯結果會顯示成這個語言」, rows for `TargetLang.keys(Translate.languages())` with radio (selected = gradient dot with white centre and glow, row #7C3AED 7 %); pick → `Prefs.setTarget`, `applyTarget`, close after 220 ms. Download mode: title 「預先下載語言」, sub 「選一個語言開始下載，約 30MB」, rows for `TargetLang.modelKeys(Translate.languages())`; rows that already have a state are disabled with 「內建」 / check + 「已下載」 / spinner + 「下載中」 / 「等待 Wi-Fi」; pick → close, then after 240 ms `Translate.download(this, code)` and refresh.

**Constraints:**
- No `RecyclerView`, no dialogs, no new dependency.
- Settings never calls ML Kit directly (only `Translate`).

**Acceptance criteria:**
- [ ] Gate `RC=0`.
- [ ] `grep -n "android:id=\"@+id/trCard\"" app/src/main/res/layout/activity_settings.xml` prints one line; `grep -n "com.google.mlkit" app/src/main/java/us/jhangy/textify/SettingsActivity.kt` prints nothing; `grep -nE "AlertDialog|RecyclerView" app/src/main/java/us/jhangy/textify/SettingsActivity.kt` prints nothing.
- [ ] On device (Wi-Fi on): pre-download 法文 → a row appears with 「下載中…」 and spinner, then turns 「已下載 · 可離線翻譯」 with the sparkle; trash removes the row without a dialog; leaving and reopening Settings does not show it again. English row shows 「內建」 and no trash button.
- [ ] On device: press translate on a Korean page whose model is absent, then tap the session's settings button while chips show 「下載中…」 → Settings lists 한국어 as 「下載中…」.
- [ ] On device: `adb shell svc wifi disable` (mobile data on), Wi-Fi-only on, pre-download Deutsch → grey 「等待 Wi-Fi」 row; `adb shell svc wifi enable` afterwards.
- [ ] On device (spec §6 OpenCC check): target 简体中文, translate an English paragraph containing "software" and "memory" → card shows Simplified (软件 / 内存); target 繁體中文, same page → 軟體 / 記憶體.


**Steps:**

(No new JVM test: `modelRows`, `TargetLang.keys/modelKeys` and `Languages` feed this UI and `modelRows` / keys are tested in Task 3. This task is verified on device.)

- [ ] **Step 1: Add the strings** — in `res/values/strings.xml`, before `</resources>`:

```xml
    <string name="tr_section">翻譯</string>
    <string name="tr_section_hint">在擷取畫面按「譯」鍵才會翻譯</string>
    <string name="tr_target">目標語言</string>
    <string name="tr_target_sub">每段自動偵測來源語言</string>
    <string name="tr_wifi">只用 Wi-Fi 下載</string>
    <string name="tr_wifi_sub">使用行動網路時不下載語言模型</string>
    <string name="tr_models">語言模型</string>
    <string name="tr_models_hint">每次開啟設定時向系統查詢</string>
    <string name="tr_predownload">預先下載語言</string>
    <string name="tr_note">每個語言約 30MB，下載後可離線翻譯</string>
    <string name="model_builtin">內建</string>
    <string name="model_builtin_sub">隨 App 內建，無法刪除</string>
    <string name="model_done_sub">已下載 · 可離線翻譯</string>
    <string name="model_delete">刪除%1$s模型</string>
    <string name="sheet_target_sub">翻譯結果會顯示成這個語言</string>
    <string name="sheet_dl_sub">選一個語言開始下載，約 30MB</string>
    <string name="sheet_done">已下載</string>
    <string name="sheet_downloading">下載中</string>
```

(Downloading / waiting rows reuse `chip_downloading` 「下載中…」 and `chip_waiting_wifi` 「等待 Wi-Fi」 from Task 5.)

- [ ] **Step 2: Create the icons** (prism-tide SVGs, 24 viewport)

`res/drawable/ic_chevron.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="18dp" android:height="18dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:pathData="M9,6l6,6 -6,6" android:strokeColor="#7C3AED" android:strokeWidth="2.4"
        android:strokeLineCap="round" android:strokeLineJoin="round" />
</vector>
```

`res/drawable/ic_trash.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="20dp" android:height="20dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:pathData="M4,7h16M10,11v6M14,11v6M6,7l1,12a2,2 0,0 0,2 2h6a2,2 0,0 0,2 -2l1,-12M9,7V4h6v3"
        android:strokeColor="#4C1D95" android:strokeWidth="2" android:strokeLineCap="round" android:strokeLineJoin="round" />
</vector>
```

`res/drawable/ic_download.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="20dp" android:height="20dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:pathData="M12,4v11M7,10l5,5 5,-5M5,20h14" android:strokeColor="#7C3AED" android:strokeWidth="2.2"
        android:strokeLineCap="round" android:strokeLineJoin="round" />
</vector>
```

`res/drawable/ic_info.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="15dp" android:height="15dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:pathData="M12,3a9,9 0,1 0,0 18a9,9 0,1 0,0 -18zM12,11v5M12,7.5v0.5" android:strokeColor="#6B5FA8"
        android:strokeWidth="2.2" android:strokeLineCap="round" />
</vector>
```

- [ ] **Step 3: Add the card to `res/layout/activity_settings.xml`** — change the header comment to `scrollable cards (permission, language, translation), footer, language sheet.` and insert directly before the footer `TextView` (the one with `android:text="@string/settings_foot"`):

```xml
                <!-- 「翻譯」 card (translation spec §4, prism-tide #trCard); background set in code -->
                <LinearLayout
                    android:id="@+id/trCard"
                    android:layout_width="match_parent" android:layout_height="wrap_content"
                    android:layout_marginTop="16dp"
                    android:clipChildren="false" android:clipToPadding="false"
                    android:orientation="vertical"
                    android:paddingStart="16dp" android:paddingTop="16dp" android:paddingEnd="16dp" android:paddingBottom="18dp">

                    <LinearLayout
                        android:layout_width="wrap_content" android:layout_height="wrap_content"
                        android:layout_marginStart="4dp" android:layout_marginEnd="4dp" android:layout_marginBottom="12dp"
                        android:orientation="horizontal">
                        <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
                            android:letterSpacing="0.06" android:text="@string/tr_section"
                            android:textColor="#6B5FA8" android:textSize="13sp" android:textStyle="bold" />
                        <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
                            android:layout_marginStart="8dp" android:alpha="0.8" android:text="@string/tr_section_hint"
                            android:textColor="#6B5FA8" android:textSize="13sp" />
                    </LinearLayout>

                    <!-- .rows: target + Wi-Fi-only (prow: min 56dp, padding 16/10/10/10) -->
                    <LinearLayout
                        android:id="@+id/trRows"
                        android:layout_width="match_parent" android:layout_height="wrap_content"
                        android:orientation="vertical">
                        <LinearLayout
                            android:id="@+id/targetRow"
                            android:layout_width="match_parent" android:layout_height="wrap_content"
                            android:minHeight="56dp" android:gravity="center_vertical" android:orientation="horizontal"
                            android:paddingStart="16dp" android:paddingTop="10dp" android:paddingEnd="10dp" android:paddingBottom="10dp"
                            android:clickable="true" android:focusable="true">
                            <LinearLayout android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1"
                                android:orientation="vertical">
                                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
                                    android:text="@string/tr_target" android:textColor="#1E1B4B" android:textSize="14.5sp" android:textStyle="bold" />
                                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
                                    android:layout_marginTop="2dp" android:text="@string/tr_target_sub" android:textColor="#6B5FA8" android:textSize="12sp" />
                            </LinearLayout>
                            <TextView android:id="@+id/targetValue" android:layout_width="wrap_content" android:layout_height="wrap_content"
                                android:textColor="#7C3AED" android:textSize="14.5sp" android:textStyle="bold" />
                            <ImageView android:layout_width="18dp" android:layout_height="18dp" android:layout_marginStart="4dp"
                                android:importantForAccessibility="no" android:src="@drawable/ic_chevron" />
                        </LinearLayout>
                        <LinearLayout
                            android:id="@+id/wifiRow"
                            android:layout_width="match_parent" android:layout_height="wrap_content" android:layout_marginTop="8dp"
                            android:minHeight="56dp" android:gravity="center_vertical" android:orientation="horizontal"
                            android:paddingStart="16dp" android:paddingTop="10dp" android:paddingEnd="10dp" android:paddingBottom="10dp"
                            android:clickable="true" android:focusable="true">
                            <LinearLayout android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1"
                                android:orientation="vertical">
                                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
                                    android:text="@string/tr_wifi" android:textColor="#1E1B4B" android:textSize="14.5sp" android:textStyle="bold" />
                                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
                                    android:layout_marginTop="2dp" android:text="@string/tr_wifi_sub" android:textColor="#6B5FA8" android:textSize="12sp" />
                            </LinearLayout>
                            <!-- settings-final .switch 52×32, thumb 24dp at 4dp -->
                            <FrameLayout android:id="@+id/wifiSwitch" android:layout_width="52dp" android:layout_height="32dp"
                                android:clipChildren="false" android:importantForAccessibility="no">
                                <View android:id="@+id/wifiTrackOff" android:layout_width="match_parent" android:layout_height="match_parent" />
                                <View android:id="@+id/wifiTrackOn" android:layout_width="match_parent" android:layout_height="match_parent" android:alpha="0" />
                                <View android:id="@+id/wifiThumb" android:layout_width="24dp" android:layout_height="24dp"
                                    android:layout_gravity="start|center_vertical" android:layout_marginStart="4dp" />
                            </FrameLayout>
                        </LinearLayout>
                    </LinearLayout>

                    <!-- .mh margin 16/6/8 -->
                    <LinearLayout
                        android:layout_width="match_parent" android:layout_height="wrap_content"
                        android:layout_marginStart="6dp" android:layout_marginTop="16dp" android:layout_marginEnd="6dp" android:layout_marginBottom="8dp"
                        android:baselineAligned="true" android:orientation="horizontal">
                        <TextView android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1"
                            android:text="@string/tr_models" android:textColor="#6B5FA8" android:textSize="13sp" android:textStyle="bold" />
                        <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"
                            android:alpha="0.85" android:text="@string/tr_models_hint" android:textColor="#6B5FA8" android:textSize="12sp" />
                    </LinearLayout>

                    <!-- .wells: rows added in code -->
                    <LinearLayout
                        android:id="@+id/modelWell"
                        android:layout_width="match_parent" android:layout_height="wrap_content"
                        android:orientation="vertical" android:padding="4dp" />

                    <LinearLayout
                        android:id="@+id/dlBtn"
                        android:layout_width="match_parent" android:layout_height="50dp" android:layout_marginTop="12dp"
                        android:gravity="center" android:orientation="horizontal"
                        android:clickable="true" android:focusable="true">
                        <ImageView android:layout_width="20dp" android:layout_height="20dp"
                            android:importantForAccessibility="no" android:src="@drawable/ic_download" />
                        <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_marginStart="8dp"
                            android:text="@string/tr_predownload" android:textColor="#4C1D95" android:textSize="15sp" android:textStyle="bold" />
                    </LinearLayout>

                    <LinearLayout
                        android:layout_width="match_parent" android:layout_height="wrap_content"
                        android:layout_marginStart="6dp" android:layout_marginTop="10dp" android:layout_marginEnd="6dp"
                        android:orientation="horizontal">
                        <ImageView android:layout_width="15dp" android:layout_height="15dp" android:layout_marginTop="2dp"
                            android:importantForAccessibility="no" android:src="@drawable/ic_info" />
                        <TextView android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1"
                            android:layout_marginStart="6dp" android:lineSpacingMultiplier="1.2"
                            android:text="@string/tr_note" android:textColor="#6B5FA8" android:textSize="12.5sp" />
                    </LinearLayout>
                </LinearLayout>
```

- [ ] **Step 4: Add the language sheet to `res/layout/activity_settings.xml`** — insert as the last children of the root `FrameLayout` (after the `content` `LinearLayout`):

```xml

    <!-- language sheet (prism-tide .sheet): in-window, opened from the 「翻譯」 card; height capped in code -->
    <View
        android:id="@+id/sheetScrim"
        android:layout_width="match_parent" android:layout_height="match_parent"
        android:alpha="0" android:background="#2E1E1B4B"
        android:clickable="true" android:importantForAccessibility="no"
        android:visibility="gone" />

    <LinearLayout
        android:id="@+id/sheet"
        android:layout_width="match_parent" android:layout_height="wrap_content"
        android:layout_gravity="bottom"
        android:clickable="true" android:clipChildren="false"
        android:orientation="vertical" android:visibility="gone">
        <LinearLayout
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:orientation="vertical"
            android:paddingStart="22dp" android:paddingTop="12dp" android:paddingEnd="22dp" android:paddingBottom="10dp">
            <View android:id="@+id/sheetGrip" android:layout_width="40dp" android:layout_height="4dp"
                android:layout_gravity="center_horizontal" android:layout_marginBottom="12dp" />
            <TextView android:id="@+id/sheetTitle" android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:layout_marginTop="4dp" android:textColor="#1E1B4B" android:textSize="19sp" android:textStyle="bold" />
            <TextView android:id="@+id/sheetSub" android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:textColor="#6B5FA8" android:textSize="12.5sp" />
        </LinearLayout>
        <ScrollView
            android:id="@+id/sheetScroll"
            android:layout_width="match_parent" android:layout_height="wrap_content"
            android:scrollbars="none">
            <LinearLayout android:id="@+id/sheetList"
                android:layout_width="match_parent" android:layout_height="wrap_content"
                android:orientation="vertical"
                android:paddingStart="10dp" android:paddingEnd="10dp" android:paddingBottom="22dp" />
        </ScrollView>
    </LinearLayout>
```

- [ ] **Step 5: Edit the existing parts of `java/SettingsActivity.kt`**

Class kdoc: replace `multi-select OCR language card.` with `multi-select OCR language card, and the translation card (translation spec §4).`

Add these imports (keep the existing ones):
```kotlin
import android.animation.LayoutTransition
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt
```

In `onCreate`, replace
```kotlin
        setupLanguageCard()                         // C2
        b.back.setOnClickListener { leave() }
        onBackPressedDispatcher.addCallback(this) { leave() }
```
with
```kotlin
        setupLanguageCard()                         // C2
        setupTranslateCard()                        // translation spec §4
        b.back.setOnClickListener { leave() }
        onBackPressedDispatcher.addCallback(this) { if (sheetMode != SheetMode.NONE) closeSheet() else leave() }
```

In `onResume`, after `main.postDelayed(recheck, RECHECK)` add:
```kotlin
        Translate.onModelsChanged = { refreshModels() }   // a session download may finish while Settings is open
        refreshModels()                                    // spec §4: query ML Kit every time Settings opens
```

In `onPause`, before `super.onPause()` add:
```kotlin
        Translate.onModelsChanged = null
```

In `stylePage()`, after `b.langCard.background = HaloStyle.card(this)` add:
```kotlin
        b.trCard.background = HaloStyle.card(this)
```

In the companion object, after `private val EASE_IN = …` add:
```kotlin
        // translation card (prism-tide TIMINGS, ms)
        private const val TOGGLE = 200L
        private const val ROW_ANIM = 260L
        private const val SPARKLE = 380L
        private const val SHEET_IN = 300L
        private const val SHEET_OUT = 240L
        private const val PICK_HOLD = 220L
        private const val SHEET_MAX = 0.74f
        private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)  // toggle thumb
        private val DECELERATE = PathInterpolator(0f, 0f, 0.2f, 1f)  // sheetIn
        private val ACCELERATE = PathInterpolator(0.4f, 0f, 1f, 1f)  // sheetOut
        private val INK = 0xFF1E1B4B.toInt()
        private val SECONDARY = 0xFF6B5FA8.toInt()
        private val VIOLET = 0xFF7C3AED.toInt()
        private val GREY = 0xFF8A86A0.toInt()
        private val GREY_DISC = 0xFFE5E2EE.toInt()
```

- [ ] **Step 6: Add the card code to `java/SettingsActivity.kt`, part 1** — insert directly before `    // ---- small drawables ----`:

```kotlin
    // ---- translation card (translation spec §4, prism-tide #trCard) ----

    private enum class SheetMode { NONE, TARGET, DOWNLOAD }

    private var sheetMode = SheetMode.NONE
    private var wifiOnly = true
    private var lastRows: Map<String, ModelState> = emptyMap()

    private fun setupTranslateCard() {
        val rowBg = { pressed(round(withAlpha(0.12f, 0x7C3AED), 24 * d), round(withAlpha(0.06f, 0x7C3AED), 24 * d)) }
        b.targetRow.background = rowBg()
        b.wifiRow.background = rowBg()
        b.modelWell.background = round(withAlpha(0.90f, 0xFAF5FF), 22 * d)
        b.modelWell.layoutTransition = LayoutTransition().apply { setDuration(ROW_ANIM) }   // rowIn / rowOut 260
        b.dlBtn.background = pressed(round(withAlpha(0.14f, 0x7C3AED), -1f), round(withAlpha(0.08f, 0x7C3AED), -1f))
        b.wifiTrackOff.background = round(withAlpha(0.12f, 0x1E1B4B), -1f)
        b.wifiTrackOn.background = CssGradientDrawable(intArrayOf(0xFFA78BFA.toInt(), 0xFFF472B6.toInt()), null, 90f, -1f)
        b.wifiThumb.background = GlowDrawable(oval(Color.WHITE), withAlpha(0.25f, 0x1E1B4B), 3 * d, 1 * d, 0f, -1f)
        b.sheet.background = sheetBackground()
        b.sheetGrip.background = CssGradientDrawable(intArrayOf(0xFFA78BFA.toInt(), 0xFFF472B6.toInt(), 0xFFFDBA74.toInt()), null, 90f, 2 * d)
        ViewCompat.setAccessibilityDelegate(b.wifiRow, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Switch::class.java.name
                info.isCheckable = true
                info.isChecked = wifiOnly
            }
        })
        applyTarget(Prefs.target(this).key)
        wifiOnly = Prefs.wifiOnly(this)
        showWifi(animate = false)
        b.targetRow.setOnClickListener { openSheet(SheetMode.TARGET) }
        b.wifiRow.setOnClickListener {
            wifiOnly = !wifiOnly
            Prefs.setWifiOnly(this, wifiOnly)                 // saved at once
            showWifi(animate = true)
        }
        b.dlBtn.setOnClickListener { openSheet(SheetMode.DOWNLOAD) }
        b.sheetScrim.setOnClickListener { closeSheet() }
    }

    /** Shows the target in the target row (Task 10 also updates the preview here). */
    private fun applyTarget(key: String) {
        b.targetValue.text = Languages.targetTitle(key)
    }

    /** toggle 200 (.2,0,0,1): on track fades in, thumb slides 20dp. */
    private fun showWifi(animate: Boolean) {
        val x = if (wifiOnly) 20 * d else 0f
        val a = if (wifiOnly) 1f else 0f
        if (animate) {
            b.wifiThumb.animate().translationX(x).setDuration(TOGGLE).setInterpolator(EMPHASIZED)
            b.wifiTrackOn.animate().alpha(a).setDuration(TOGGLE)
        } else {
            b.wifiThumb.translationX = x
            b.wifiTrackOn.alpha = a
        }
    }

    /** Spec §4: the downloaded set comes from ML Kit every time; downloading / waiting come from Translate. */
    private fun refreshModels() {
        Translate.downloaded { done ->
            if (!isDestroyed) renderModels(modelRows(done, Translate.downloading, Translate.waitingWifi))
        }
    }

    /** Diffs [rows] into the well: removed / added rows animate (LayoutTransition), changed rows swap in place. */
    private fun renderModels(rows: List<Pair<String, ModelState>>) {
        val next = rows.toMap()
        val well = b.modelWell
        for (i in well.childCount - 1 downTo 0) if (well.getChildAt(i).tag !in next) well.removeViewAt(i)
        rows.forEachIndexed { index, (code, state) ->
            val existing = (0 until well.childCount).map { well.getChildAt(it) }.firstOrNull { it.tag == code }
            if (existing == null) {
                well.addView(modelRow(code, state), index)
            } else if (lastRows[code] != state) {
                val at = well.indexOfChild(existing)
                val transition = well.layoutTransition
                well.layoutTransition = null                  // state change: swap without the row animation
                well.removeViewAt(at)
                val row = modelRow(code, state)
                well.addView(row, at)
                well.layoutTransition = transition
                if (lastRows[code] == ModelState.DOWNLOADING && state == ModelState.DONE) sparkle(row)
            }
        }
        lastRows = next
    }

    /** prism-tide .mrow: 60dp, disc 38dp, title + small name, status line, right-side action / state. */
    private fun modelRow(code: String, state: ModelState): View {
        val waiting = state == ModelState.WAITING_WIFI
        val row = LinearLayout(this).apply {
            tag = code
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(px(10f), 0, px(4f), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(60f))
        }
        row.addView(disc(code, 14f, waiting), LinearLayout.LayoutParams(px(38f), px(38f)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = true }
        titleRow.addView(label(Languages.modelTitle(code), 15f, true, if (waiting) GREY else INK))
        titleRow.addView(label(Languages.modelSub(code), 12f, true, if (waiting) GREY else SECONDARY).apply { setPaddingRelative(px(6f), 0, 0, 0) })
        texts.addView(titleRow)
        val sub = when (state) {
            ModelState.BUILTIN -> R.string.model_builtin_sub
            ModelState.DONE -> R.string.model_done_sub
            ModelState.DOWNLOADING -> R.string.chip_downloading
            ModelState.WAITING_WIFI -> R.string.chip_waiting_wifi
        }
        val subColor = when (state) { ModelState.DOWNLOADING -> VIOLET; ModelState.WAITING_WIFI -> GREY; else -> SECONDARY }
        texts.addView(label(getString(sub), 12.5f, state == ModelState.DOWNLOADING, subColor))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(12f) })
        row.addView(modelAction(code, state))
        return row
    }
```

- [ ] **Step 7: Add the card code, part 2** — append directly after `modelRow` (still before `// ---- small drawables ----`):

```kotlin
    /** Right side of a model row: 內建 badge, trash (deletes at once, final pick), spinner, or wifi icon. */
    private fun modelAction(code: String, state: ModelState): View = when (state) {
        ModelState.BUILTIN -> label(getString(R.string.model_builtin), 12f, true, SECONDARY).apply {
            setPadding(px(10f), px(4f), px(10f), px(4f))
            background = round(withAlpha(0.10f, 0x7C3AED), -1f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = px(8f) }
        }
        ModelState.DONE -> FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(px(44f), px(44f))
            background = pressed(oval(withAlpha(0.16f, 0xF472B6)), null)
            contentDescription = getString(R.string.model_delete, Languages.modelTitle(code))
            isClickable = true
            isFocusable = true
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_trash) }, FrameLayout.LayoutParams(px(20f), px(20f), Gravity.CENTER))
            setOnClickListener { Translate.delete(code) { if (!isDestroyed) refreshModels() } }
        }
        ModelState.DOWNLOADING -> FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(px(44f), px(44f))
            addView(SpinnerRingView(context).apply { strokePx = 3 * d }, FrameLayout.LayoutParams(px(22f), px(22f), Gravity.CENTER))
        }
        ModelState.WAITING_WIFI -> FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(px(44f), px(44f))
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_chip_wifi); setColorFilter(GREY) },
                FrameLayout.LayoutParams(px(20f), px(20f), Gravity.CENTER))
        }
    }

    /** Language disc: 135° gradient of Languages.discColors with a white glyph; grey when waiting for Wi-Fi. */
    private fun disc(code: String, textSp: Float, grey: Boolean): TextView = TextView(this).apply {
        text = Languages.glyph(code)
        textSize = textSp
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        maxLines = 1
        setTextColor(if (grey) GREY else Color.WHITE)
        background = if (grey) oval(GREY_DISC) else CssGradientDrawable(Languages.discColors(code), null, 135f, -1f)
    }

    private fun label(s: CharSequence, sp: Float, bold: Boolean, color: Int): TextView = TextView(this).apply {
        text = s
        textSize = sp
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setTextColor(color)
        maxLines = 1
    }

    private fun px(dp: Float): Int = (dp * d).roundToInt()

    /** prism-tide sparkle 380: download done -> disc scale 1 -> 1.15 -> 1. */
    private fun sparkle(row: View) {
        val disc = (row as LinearLayout).getChildAt(0)
        disc.animate().scaleX(1.15f).scaleY(1.15f).setDuration(SPARKLE * 45 / 100).withEndAction {
            disc.animate().scaleX(1f).scaleY(1f).setDuration(SPARKLE * 55 / 100)
        }
    }

    /** prism-tide .sheet: white 97 %, 28dp top corners, box-shadow 0 -12px 40px #4C1D95 22 %. */
    private fun sheetBackground(): Drawable {
        val r = 28 * d
        val fill = GradientDrawable().apply {
            setColor(withAlpha(0.97f, 0xFFFFFF))
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        return GlowDrawable(fill, withAlpha(0.22f, 0x4C1D95), 40 * d, -12 * d, 0f, r)
    }
```

- [ ] **Step 8: Add the card code, part 3 (sheet)** — append directly after `sheetBackground` (still before `// ---- small drawables ----`):

```kotlin
    /** sheetIn 300 (decelerate) + scrim fade; at most 74 % of the screen (prism-tide .sheet max-height). */
    private fun openSheet(mode: SheetMode) {
        sheetMode = mode
        b.sheetTitle.setText(if (mode == SheetMode.TARGET) R.string.tr_target else R.string.tr_predownload)
        b.sheetSub.setText(if (mode == SheetMode.TARGET) R.string.sheet_target_sub else R.string.sheet_dl_sub)
        fillSheet()
        (b.sheet.layoutParams as FrameLayout.LayoutParams).topMargin = (b.root.height * (1f - SHEET_MAX)).roundToInt()
        b.sheet.requestLayout()
        b.sheetScroll.scrollTo(0, 0)
        b.sheetScrim.visibility = View.VISIBLE
        b.sheet.visibility = View.VISIBLE
        b.sheetScrim.animate().cancel()
        b.sheet.animate().cancel()
        b.sheetScrim.animate().alpha(1f).setDuration(SHEET_IN).setInterpolator(EASE_OUT)
        b.sheet.translationY = b.root.height.toFloat()
        b.sheet.animate().translationY(0f).setDuration(SHEET_IN).setInterpolator(DECELERATE)
    }

    /** sheetOut 240 (accelerate); tap on the scrim or back. */
    private fun closeSheet() {
        if (sheetMode == SheetMode.NONE) return
        sheetMode = SheetMode.NONE
        b.sheetScrim.animate().alpha(0f).setDuration(SHEET_OUT).setInterpolator(EASE_IN)
            .withEndAction { b.sheetScrim.visibility = View.GONE }
        b.sheet.animate().translationY(b.sheet.height.toFloat()).setDuration(SHEET_OUT).setInterpolator(ACCELERATE)
            .withEndAction { b.sheet.visibility = View.GONE }
    }

    private fun fillSheet() {
        val list = b.sheetList
        list.removeAllViews()
        val langs = Translate.languages()
        if (sheetMode == SheetMode.TARGET) {
            val current = Prefs.target(this).key
            for (key in TargetLang.keys(langs)) {
                val selected = key == current
                list.addView(sheetRow(Languages.targetTitle(key), Languages.targetSub(key), TargetLang.of(key).mlkit, radio(selected), selected, true) { pickTarget(key) })
            }
        } else {
            for (code in TargetLang.modelKeys(langs)) {
                val state = lastRows[code]
                list.addView(sheetRow(Languages.modelTitle(code), Languages.modelSub(code), code, state?.let { stateLabel(it) }, false, state == null) { pickDownload(code) })
            }
        }
    }

    /** prism-tide .lrow: 58dp, radius 18dp, disc 34dp, title 15.5sp bold + sub 12.5sp, right-side view. */
    private fun sheetRow(title: String, sub: String, discCode: String, right: View?, selected: Boolean, enabled: Boolean, onPick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(px(12f), 0, px(14f), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(58f))
            background = if (selected) round(withAlpha(0.07f, 0x7C3AED), 18 * d) else pressed(round(withAlpha(0.08f, 0x7C3AED), 18 * d), null)
            isEnabled = enabled
            if (enabled) setOnClickListener { onPick() }
        }
        row.addView(disc(discCode, 13f, false), LinearLayout.LayoutParams(px(34f), px(34f)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 15.5f, true, if (enabled) INK else GREY))
        texts.addView(label(sub, 12.5f, false, SECONDARY))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(12f) })
        right?.let { row.addView(it) }
        return row
    }

    /** prism-tide .radio: #7C3AED 12 % dot; selected = gradient #7C3AED -> #F472B6 with glow and a white centre. */
    private fun radio(selected: Boolean): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(px(24f), px(24f))
        background = if (!selected) oval(withAlpha(0.12f, 0x7C3AED)) else LayerDrawable(
            arrayOf<Drawable>(
                GlowDrawable(CssGradientDrawable(intArrayOf(0xFF7C3AED.toInt(), 0xFFF472B6.toInt()), null, 135f, -1f), withAlpha(0.45f, 0xF472B6), 12 * d, 0f, 0f, -1f),
                oval(Color.WHITE),
            ),
        ).apply { setLayerInset(1, px(7f), px(7f), px(7f), px(7f)) }
    }

    /** Disabled download-sheet row state: 內建 / ✓ 已下載 / spinner 下載中 / 等待 Wi-Fi (12.5sp bold #6B5FA8). */
    private fun stateLabel(state: ModelState): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        when (state) {
            ModelState.DONE -> addView(ImageView(context).apply { setImageResource(R.drawable.ic_check) }, LinearLayout.LayoutParams(px(16f), px(16f)))
            ModelState.DOWNLOADING -> addView(SpinnerRingView(context).apply { strokePx = 2.5f * d }, LinearLayout.LayoutParams(px(16f), px(16f)))
            else -> Unit
        }
        val text = when (state) {
            ModelState.BUILTIN -> R.string.model_builtin
            ModelState.DONE -> R.string.sheet_done
            ModelState.DOWNLOADING -> R.string.sheet_downloading
            ModelState.WAITING_WIFI -> R.string.chip_waiting_wifi
        }
        addView(label(getString(text), 12.5f, true, SECONDARY).apply { setPaddingRelative(if (childCount > 0) px(6f) else 0, 0, 0, 0) })
    }

    private fun pickTarget(key: String) {
        Prefs.setTarget(this, key)
        applyTarget(key)
        fillSheet()                                           // move the radio before the sheet leaves
        main.postDelayed({ closeSheet() }, PICK_HOLD)
    }

    private fun pickDownload(code: String) {
        closeSheet()
        main.postDelayed({
            Translate.download(this, code)
            refreshModels()
        }, SHEET_OUT)
    }
```

- [ ] **Step 9: Run the gate and the greps**

Send `SETTLED`; gate-runner: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug testDebugUnitTest; echo "RC=$?"` → `RC=0`, test counts unchanged (7 / 8 / 12 / 3).
Then: `grep -n 'android:id="@+id/trCard"' app/src/main/res/layout/activity_settings.xml` → one line; `grep -n "com.google.mlkit" app/src/main/java/us/jhangy/textify/SettingsActivity.kt` → no output; `grep -nE "AlertDialog|RecyclerView" app/src/main/java/us/jhangy/textify/SettingsActivity.kt` → no output.

- [ ] **Step 10: On-device checks (spec §6 Settings + OpenCC)**

Gate-runner installs: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew installDebug`. Open Settings from the launcher.
1. Card: 「翻譯」 card below 「辨識語言」; target row shows 繁體中文; Wi-Fi switch on; model list starts with English 「內建」 (no trash); note line visible.
2. Wi-Fi on: 預先下載語言 → pick Français → a row appears with 「下載中…」 + spinner, then turns 「已下載 · 可離線翻譯」 with the disc sparkle; trash removes the row at once (no dialog); back out of Settings and reopen → no Français row.
3. Press translate on `https://ko.wikipedia.org/wiki/서울특별시` (Korean model absent; delete it first if present), and while chips show 「下載中…」 tap the session's settings button → Settings lists 한국어 with 「下載中…」, later 「已下載 · 可離線翻譯」 while Settings stays open.
4. `adb shell svc wifi disable` (mobile data on), switch on, 預先下載語言 → Deutsch → grey 「等待 Wi-Fi」 row. `adb shell svc wifi enable`.
5. Target 简体中文 (sheet closes ~220 ms after the radio moves), translate `https://en.wikipedia.org/wiki/Computer_memory` → cards show Simplified (软件 / 内存 where the source says software / memory). Target back to 繁體中文, same page → 軟體 / 記憶體. This is the spec §6 check "ML Kit zh output is Simplified, and OpenCC turns it into Traditional with Taiwan phrases".
Report what you saw per item.

- [ ] **Step 11: Commit**

```bash
git rev-parse --abbrev-ref HEAD   # main
git add app/src/main/res/layout/activity_settings.xml app/src/main/res/values/strings.xml app/src/main/res/drawable/ic_chevron.xml app/src/main/res/drawable/ic_trash.xml app/src/main/res/drawable/ic_download.xml app/src/main/res/drawable/ic_info.xml app/src/main/java/us/jhangy/textify/SettingsActivity.kt
git commit -m "feat(settings): translation card with target, Wi-Fi-only and model list

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>" -- app/src/main/res/layout/activity_settings.xml app/src/main/res/values/strings.xml app/src/main/res/drawable/ic_chevron.xml app/src/main/res/drawable/ic_trash.xml app/src/main/res/drawable/ic_download.xml app/src/main/res/drawable/ic_info.xml app/src/main/java/us/jhangy/textify/SettingsActivity.kt
git status --porcelain -- app/src/main/res/layout/activity_settings.xml app/src/main/res/values/strings.xml app/src/main/res/drawable/ic_chevron.xml app/src/main/res/drawable/ic_trash.xml app/src/main/res/drawable/ic_download.xml app/src/main/res/drawable/ic_info.xml app/src/main/java/us/jhangy/textify/SettingsActivity.kt   # empty
```

### Task 9: Bottom panel for truncated boxes

**Files:**
- Modify: `res/layout/activity_session.xml` (append `panelScrim` and `panel` as the last children)
- Create: `res/drawable/ic_copy.xml`
- Modify: `res/values/strings.xml` (panel strings)
- Create: `java/TranslationPanel.kt`
- Modify: `java/SessionActivity.kt` (`panel` field, back callback, `onPillTap` truncated branch, new `openPanel`, `toggleTranslation`)

**Interfaces:**
- Consumes: `ActivitySessionBinding` (ViewBinding of `activity_session.xml`); `PillView.focused`, `PillView.truncated`, `PillView.showsTranslation` (Task 6); `SessionActivity.onPillTap/putClip/toggleTranslation/states/blocks/translating` (Task 7); `Prefs.target` (Task 4); `Languages.zhName/targetTitle` (Task 3); `GlowDrawable`, `CssGradientDrawable`, `withAlpha` (`Glow.kt`).
- Produces: `internal class TranslationPanel(b: ActivitySessionBinding, copy: (String) -> Unit)` with `val isOpen: Boolean`, `fun open(pill: PillView, original: String, translation: String, langLabel: String)`, `fun close()`.

**Behavior:**
- Tap on a truncated translated box opens the panel instead of copying (§3). Panel (prism-tide `.psheet`): in-window, margins 10dp, top limit 56dp, opaque white 96 % (#F5FFFFFF) radius 28dp with glow #4C1D95 28 % blur 48dp dy 18dp (software, FP: no blur behind), grip 40×4dp gradient #A78BFA → #F472B6 → #FDBA74, title 「完整譯文」 17sp bold, language pill 「<source> → <target>」 (12.5sp bold #6B5FA8 on #7C3AED 8 %), original block (#FAF5FF 90 %, radius 20dp, label 「原文」, text 16sp) on top, translation block (gradient #A78BFA 12 % → #F472B6 8 % at 135°, label 「譯文」, text 17sp bold) below, each with one copy button (white capsule 36dp, shadow #7C3AED 16 %, copy icon + 「複製原文」 / 「複製譯文」; after copying: gradient #7C3AED → #EC4899 fades in over 200 ms with white check + 「已複製」, back after 1400 ms). Long content scrolls inside the panel.
- Motion: panelIn 300 ms (translationY 40dp → 0 + alpha, decelerate; scrim #1E1B4B 18 % fades in ease-out); panelOut 240 ms (accelerate / ease-in), then `GONE`. While open, the source box is `focused` (glow pulse).
- Closing: tap on the scrim, back (before ActionMode and session close), translate button press. Session close / settings fade the whole window, so they need no extra call.

**Constraints:**
- No dialog, no `BottomSheetDialog`, no `RenderEffect`.

**Acceptance criteria:**
- [ ] Gate `RC=0`.
- [ ] `grep -nE "Dialog|RenderEffect" app/src/main/java/us/jhangy/textify/TranslationPanel.kt` prints nothing; `grep -n "panel.open(" app/src/main/java/us/jhangy/textify/SessionActivity.kt` prints one line.
- [ ] On device, spec §6 screenshot set with target 繁體中文: capture one screen each of https://ja.wikipedia.org/wiki/東京都 , https://ko.wikipedia.org/wiki/서울특별시 , https://en.wikipedia.org/wiki/Tokyo and https://zh.wikipedia.org/zh-cn/东京都 and press translate: every paragraph box is one box covering its lines; translated cards hide the original glyphs with a matching background and readable text; text shrinks inside the box down to 10sp. Report per screenshot: paragraph count, translated count, chips seen, truncated count.
- [ ] On device: set target English in Settings, translate the Japanese screenshot (long English output) until at least one box shows the "⋯" mark; tapping it opens the panel (original + translation, both copy buttons paste the right text); tap outside and back each close it; a non-truncated card's tap copies the translation.


**Steps:**

(No new JVM test: the panel only shows texts the session already holds; checked on device.)

- [ ] **Step 1: Add strings and the copy icon**

In `res/values/strings.xml`, before `</resources>`:
```xml
    <string name="panel_title">完整譯文</string>
    <string name="panel_original">原文</string>
    <string name="panel_translation">譯文</string>
    <string name="panel_copy_original">複製原文</string>
    <string name="panel_copy_translation">複製譯文</string>
    <string name="panel_copied">已複製</string>
    <string name="panel_lang">%1$s → %2$s</string>
```

`res/drawable/ic_copy.xml` (prism-tide `.cbtn` glyph):
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="16dp" android:height="16dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:pathData="M11.5,9h6.5a2.5,2.5 0,0 1,2.5 2.5v6a2.5,2.5 0,0 1,-2.5 2.5h-6.5a2.5,2.5 0,0 1,-2.5 -2.5v-6a2.5,2.5 0,0 1,2.5 -2.5zM5,15V6a2,2 0,0 1,2 -2h8"
        android:strokeColor="#4C1D95" android:strokeWidth="2.2" android:strokeLineCap="round" android:strokeLineJoin="round" />
</vector>
```

- [ ] **Step 2: Append the panel to `res/layout/activity_session.xml`** — as the last children of the root `FrameLayout` (after `hint`); add `, panel` to the header comment's layer list. Elevation 10dp/11dp keeps them above the 4dp buttons; `outlineProvider="none"` means no system shadow (the glow is drawn in software):

```xml

    <!-- bottom panel for truncated translated boxes (translation spec §3, prism-tide .psheet) -->
    <View
        android:id="@+id/panelScrim"
        android:layout_width="match_parent" android:layout_height="match_parent"
        android:alpha="0" android:background="#2E1E1B4B" android:clickable="true"
        android:elevation="10dp" android:importantForAccessibility="no" android:outlineProvider="none"
        android:visibility="gone" />

    <LinearLayout
        android:id="@+id/panel"
        android:layout_width="match_parent" android:layout_height="wrap_content"
        android:layout_gravity="bottom" android:layout_margin="10dp" android:layout_marginTop="56dp"
        android:clickable="true" android:elevation="11dp" android:outlineProvider="none"
        android:orientation="vertical"
        android:paddingStart="18dp" android:paddingTop="12dp" android:paddingEnd="18dp" android:paddingBottom="18dp"
        android:visibility="gone">
        <View android:id="@+id/panelGrip" android:layout_width="40dp" android:layout_height="4dp"
            android:layout_gravity="center_horizontal" android:layout_marginBottom="12dp" />
        <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content"
            android:layout_marginBottom="12dp" android:gravity="center_vertical" android:orientation="horizontal">
            <TextView android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1"
                android:text="@string/panel_title" android:textColor="#1E1B4B" android:textSize="17sp" android:textStyle="bold" />
            <TextView android:id="@+id/panelLang" android:layout_width="wrap_content" android:layout_height="wrap_content"
                android:paddingStart="9dp" android:paddingTop="3dp" android:paddingEnd="9dp" android:paddingBottom="3dp"
                android:textColor="#6B5FA8" android:textSize="12.5sp" android:textStyle="bold" />
        </LinearLayout>
        <ScrollView android:id="@+id/panelScroll" android:layout_width="match_parent" android:layout_height="wrap_content"
            android:scrollbars="none">
            <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content" android:orientation="vertical">
                <LinearLayout android:id="@+id/panelOrigBlock" android:layout_width="match_parent" android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:paddingStart="14dp" android:paddingTop="12dp" android:paddingEnd="14dp" android:paddingBottom="14dp">
                    <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content"
                        android:layout_marginBottom="6dp" android:gravity="center_vertical" android:orientation="horizontal">
                        <TextView android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1"
                            android:letterSpacing="0.04" android:text="@string/panel_original"
                            android:textColor="#6B5FA8" android:textSize="12.5sp" android:textStyle="bold" />
                        <FrameLayout android:id="@+id/copyOrig" android:layout_width="wrap_content" android:layout_height="36dp"
                            android:clickable="true" android:focusable="true" android:contentDescription="@string/panel_copy_original">
                            <View android:id="@+id/copyOrigOk" android:layout_width="match_parent" android:layout_height="match_parent" android:alpha="0" />
                            <LinearLayout android:layout_width="wrap_content" android:layout_height="match_parent"
                                android:gravity="center_vertical" android:orientation="horizontal"
                                android:paddingStart="11dp" android:paddingEnd="14dp">
                                <ImageView android:id="@+id/copyOrigIcon" android:layout_width="16dp" android:layout_height="16dp"
                                    android:importantForAccessibility="no" android:src="@drawable/ic_copy" />
                                <TextView android:id="@+id/copyOrigText" android:layout_width="wrap_content" android:layout_height="wrap_content"
                                    android:layout_marginStart="6dp" android:text="@string/panel_copy_original"
                                    android:textColor="#4C1D95" android:textSize="13sp" android:textStyle="bold" />
                            </LinearLayout>
                        </FrameLayout>
                    </LinearLayout>
                    <TextView android:id="@+id/panelOrig" android:layout_width="match_parent" android:layout_height="wrap_content"
                        android:lineSpacingMultiplier="1.2" android:textColor="#1E1B4B" android:textSize="16sp" />
                </LinearLayout>
                <LinearLayout android:id="@+id/panelTrBlock" android:layout_width="match_parent" android:layout_height="wrap_content"
                    android:layout_marginTop="10dp" android:orientation="vertical"
                    android:paddingStart="14dp" android:paddingTop="12dp" android:paddingEnd="14dp" android:paddingBottom="14dp">
                    <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content"
                        android:layout_marginBottom="6dp" android:gravity="center_vertical" android:orientation="horizontal">
                        <TextView android:layout_width="0dp" android:layout_height="wrap_content" android:layout_weight="1"
                            android:letterSpacing="0.04" android:text="@string/panel_translation"
                            android:textColor="#6B5FA8" android:textSize="12.5sp" android:textStyle="bold" />
                        <FrameLayout android:id="@+id/copyTr" android:layout_width="wrap_content" android:layout_height="36dp"
                            android:clickable="true" android:focusable="true" android:contentDescription="@string/panel_copy_translation">
                            <View android:id="@+id/copyTrOk" android:layout_width="match_parent" android:layout_height="match_parent" android:alpha="0" />
                            <LinearLayout android:layout_width="wrap_content" android:layout_height="match_parent"
                                android:gravity="center_vertical" android:orientation="horizontal"
                                android:paddingStart="11dp" android:paddingEnd="14dp">
                                <ImageView android:id="@+id/copyTrIcon" android:layout_width="16dp" android:layout_height="16dp"
                                    android:importantForAccessibility="no" android:src="@drawable/ic_copy" />
                                <TextView android:id="@+id/copyTrText" android:layout_width="wrap_content" android:layout_height="wrap_content"
                                    android:layout_marginStart="6dp" android:text="@string/panel_copy_translation"
                                    android:textColor="#4C1D95" android:textSize="13sp" android:textStyle="bold" />
                            </LinearLayout>
                        </FrameLayout>
                    </LinearLayout>
                    <TextView android:id="@+id/panelTr" android:layout_width="match_parent" android:layout_height="wrap_content"
                        android:lineSpacingMultiplier="1.2" android:textColor="#1E1B4B" android:textSize="17sp" android:textStyle="bold" />
                </LinearLayout>
            </LinearLayout>
        </ScrollView>
    </LinearLayout>
```

- [ ] **Step 3: Create `java/TranslationPanel.kt`**

```kotlin
package us.jhangy.textify

import android.graphics.Color
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.TextView
import us.jhangy.textify.databinding.ActivitySessionBinding

/**
 * Bottom panel for a truncated translated box (translation spec §3, prism-tide .psheet / note 9): original on top,
 * translation below, one copy button each. In-window views (no dialog window, so "tap outside closes" works over
 * the session); opaque 96 % white with a software glow, no blur (final pick).
 */
internal class TranslationPanel(private val b: ActivitySessionBinding, private val copy: (String) -> Unit) {

    var isOpen = false
        private set

    private val d = b.root.resources.displayMetrics.density
    private var source: PillView? = null

    init {
        b.panel.background = GlowDrawable(
            CssGradientDrawable.solid(0xF5FFFFFF.toInt(), 28 * d), withAlpha(0.28f, 0x4C1D95), 48 * d, 18 * d, 0f, 28 * d,
        )
        b.panelGrip.background = CssGradientDrawable(intArrayOf(0xFFA78BFA.toInt(), 0xFFF472B6.toInt(), 0xFFFDBA74.toInt()), null, 90f, 2 * d)
        b.panelLang.background = CssGradientDrawable.solid(withAlpha(0.08f, 0x7C3AED), -1f)
        b.panelOrigBlock.background = CssGradientDrawable.solid(withAlpha(0.90f, 0xFAF5FF), 20 * d)
        b.panelTrBlock.background = CssGradientDrawable(intArrayOf(withAlpha(0.12f, 0xA78BFA), withAlpha(0.08f, 0xF472B6)), null, 135f, 20 * d)
        for ((button, ok) in listOf(b.copyOrig to b.copyOrigOk, b.copyTr to b.copyTrOk)) {
            button.background = GlowDrawable(CssGradientDrawable.solid(Color.WHITE, -1f), withAlpha(0.16f, 0x7C3AED), 10 * d, 3 * d, 0f, -1f)
            ok.background = CssGradientDrawable(intArrayOf(0xFF7C3AED.toInt(), 0xFFEC4899.toInt()), null, 135f, -1f)
        }
        b.panelScrim.setOnClickListener { close() }
        b.copyOrig.setOnClickListener { copied(b.panelOrig.text.toString(), b.copyOrigOk, b.copyOrigIcon, b.copyOrigText, R.string.panel_copy_original) }
        b.copyTr.setOnClickListener { copied(b.panelTr.text.toString(), b.copyTrOk, b.copyTrIcon, b.copyTrText, R.string.panel_copy_translation) }
    }

    /** panelIn 300: translationY 40dp -> 0 + alpha (decelerate), scrim fades in; [pill] glows while open. */
    fun open(pill: PillView, original: String, translation: String, langLabel: String) {
        source?.focused = false
        source = pill
        pill.focused = true
        b.panelOrig.text = original
        b.panelTr.text = translation
        b.panelLang.text = langLabel
        b.panelScroll.scrollTo(0, 0)
        b.panelScrim.animate().cancel()
        b.panel.animate().cancel()
        if (!isOpen) {
            b.panelScrim.alpha = 0f
            b.panel.alpha = 0f
            b.panel.translationY = PANEL_DY * d
        }
        isOpen = true
        b.panelScrim.visibility = View.VISIBLE
        b.panel.visibility = View.VISIBLE
        b.panelScrim.animate().alpha(1f).setDuration(PANEL_IN).setInterpolator(EASE_OUT)
        b.panel.animate().translationY(0f).alpha(1f).setDuration(PANEL_IN).setInterpolator(DECELERATE)
    }

    /** panelOut 240 (accelerate / ease-in), then GONE. */
    fun close() {
        if (!isOpen) return
        isOpen = false
        source?.focused = false
        source = null
        b.panelScrim.animate().alpha(0f).setDuration(PANEL_OUT).setInterpolator(EASE_IN)
            .withEndAction { b.panelScrim.visibility = View.GONE }
        b.panel.animate().translationY(PANEL_DY * d).alpha(0f).setDuration(PANEL_OUT).setInterpolator(ACCELERATE)
            .withEndAction { b.panel.visibility = View.GONE }
    }

    /** copyOk 200: gradient fades in with a white check and 已複製; back after copyOkHold 1400. */
    private fun copied(text: String, ok: View, icon: ImageView, label: TextView, idleText: Int) {
        copy(text)
        ok.animate().cancel()
        ok.animate().alpha(1f).setDuration(COPY_OK)
        icon.setImageResource(R.drawable.ic_check)
        icon.setColorFilter(Color.WHITE)
        label.setText(R.string.panel_copied)
        label.setTextColor(Color.WHITE)
        (label.tag as? Runnable)?.let { label.removeCallbacks(it) }
        val revert = Runnable {
            ok.animate().alpha(0f).setDuration(COPY_OK)
            icon.setImageResource(R.drawable.ic_copy)
            icon.clearColorFilter()
            label.setText(idleText)
            label.setTextColor(PLUM)
        }
        label.tag = revert
        label.postDelayed(revert, COPY_OK + COPY_OK_HOLD)
    }

    companion object {
        private const val PANEL_IN = 300L
        private const val PANEL_OUT = 240L
        private const val COPY_OK = 200L
        private const val COPY_OK_HOLD = 1400L
        private const val PANEL_DY = 40f
        private val PLUM = 0xFF4C1D95.toInt()
        private val DECELERATE = PathInterpolator(0f, 0f, 0.2f, 1f)
        private val ACCELERATE = PathInterpolator(0.4f, 0f, 1f, 1f)
        private val EASE_OUT = PathInterpolator(0f, 0f, 0.58f, 1f)
        private val EASE_IN = PathInterpolator(0.42f, 0f, 1f, 1f)
    }
}
```

- [ ] **Step 4: Wire the panel in `java/SessionActivity.kt`**

Add a field after `private val runs = ArrayList<TranslateRun>()`:
```kotlin
    private lateinit var panel: TranslationPanel
```

In `onCreate`, replace
```kotlin
        onBackPressedDispatcher.addCallback(this) {
            val mode = actionMode
            if (mode != null) mode.finish() else close()
        }
```
with
```kotlin
        panel = TranslationPanel(b, ::putClip)
        onBackPressedDispatcher.addCallback(this) {
            val mode = actionMode
            when {
                panel.isOpen -> panel.close()          // spec §3: back closes the panel first
                mode != null -> mode.finish()
                else -> close()
            }
        }
```

In `onPillTap`, replace
```kotlin
            dismissOnly -> actionMode?.finish()
            pill.showsTranslation && shown != null -> copyText(pill, shown)
```
with
```kotlin
            dismissOnly -> actionMode?.finish()
            pill.showsTranslation && pill.truncated -> openPanel(i, pill)   // spec §3: truncated box opens the panel
            pill.showsTranslation && shown != null -> copyText(pill, shown)
```

In `toggleTranslation`, after `actionMode?.finish()` add:
```kotlin
        panel.close()
```

Add after `onPillTap`:
```kotlin
    /** Panel header "source → target" (e.g. 英文 → 繁體中文), original paragraph and full translation. */
    private fun openPanel(i: Int, pill: PillView) {
        val done = states[i] as? ParagraphState.Done ?: return
        val label = getString(R.string.panel_lang, Languages.zhName(done.source), Languages.targetTitle(Prefs.target(this).key))
        panel.open(pill, blocks[i].text, done.text, label)
    }
```

- [ ] **Step 5: Run the gate and the greps**

Send `SETTLED`; gate-runner: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug testDebugUnitTest; echo "RC=$?"` → `RC=0`, test counts unchanged (7 / 8 / 12 / 3).
Then: `grep -nE "Dialog|RenderEffect" app/src/main/java/us/jhangy/textify/TranslationPanel.kt` → no output; `grep -n "panel.open(" app/src/main/java/us/jhangy/textify/SessionActivity.kt` → one line.

- [ ] **Step 6: On-device check — spec §6 screenshot set**

Gate-runner installs: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew installDebug`. Wi-Fi on, target 繁體中文. For each URL: `adb shell am start -a android.intent.action.VIEW -d "<url>"`, capture, press translate, wait until the button stops spinning:
- `https://ja.wikipedia.org/wiki/東京都`
- `https://ko.wikipedia.org/wiki/서울특별시`
- `https://en.wikipedia.org/wiki/Tokyo`
- `https://zh.wikipedia.org/zh-cn/东京都`
Check: each paragraph is one box covering its lines; translated cards hide the original glyphs, their colour matches the page background around the box, the text is readable (dark on light, light on dark); long translations shrink inside the box. Report per screenshot: paragraph count, translated cards, chips seen (which), boxes with "⋯".

- [ ] **Step 7: On-device check — truncation and panel**

Set target English in Settings, capture the Japanese page again and translate until at least one box shows the "⋯" mark (bottom-right fade in the card colour). Tap it → the panel slides up with 「完整譯文」, 「日文 → English」, the original on top and the full translation below; the source box glow pulses. 「複製原文」 / 「複製譯文」 each turn into 「已複製」 for ~1.4 s and paste the right text. Tap the dimmed area → closes; reopen, press back → closes; tap a card without "⋯" → copies the translation (shine + flash). Set the target back to 繁體中文. Report what you saw.

- [ ] **Step 8: Commit**

```bash
git rev-parse --abbrev-ref HEAD   # main
git add app/src/main/res/values/strings.xml app/src/main/res/drawable/ic_copy.xml app/src/main/res/layout/activity_session.xml app/src/main/java/us/jhangy/textify/TranslationPanel.kt app/src/main/java/us/jhangy/textify/SessionActivity.kt
git commit -m "feat(session): bottom panel for truncated translations

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>" -- app/src/main/res/values/strings.xml app/src/main/res/drawable/ic_copy.xml app/src/main/res/layout/activity_session.xml app/src/main/java/us/jhangy/textify/TranslationPanel.kt app/src/main/java/us/jhangy/textify/SessionActivity.kt
git status --porcelain -- app/src/main/res/values/strings.xml app/src/main/res/drawable/ic_copy.xml app/src/main/res/layout/activity_session.xml app/src/main/java/us/jhangy/textify/TranslationPanel.kt app/src/main/java/us/jhangy/textify/SessionActivity.kt   # empty
```

### Task 10: Settings translation preview strip

**Files:**
- Create: `java/TranslatePreviewView.kt`
- Modify: `res/layout/activity_settings.xml` (insert the preview between the 「翻譯」 header and `trRows`)
- Modify: `java/SettingsActivity.kt` (`applyTarget`, `onResume`, `onPause`)

**Interfaces:**
- Consumes: `trCard`, `trRows`, `applyTarget(key)` (Task 8); `Languages.targetTitle` (Task 3); `TargetLang` (Task 3); `glowBitmap`, `CssGradientDrawable`, `withAlpha` (`Glow.kt`).
- Produces: `class TranslatePreviewView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View` with `fun setTarget(key: String)`, `fun resume()`, `fun pause()`; layout id `trPreview`.

**Behavior:**
- Prism-tide `.tpv` (mockup note 10): 118dp strip, radius 24dp, background gradient 160° #A78BFA 14 % → #67E8F9 12 %. Centred white bubble (radius 16dp, padding 14/9dp, shadow #4C1D95 35 % dy 8dp blur 20dp spread −10dp) with sample text 15sp #1D2330 over an a2 pill (inset 8/4dp). Below it a language row 12sp bold: source pill (white 75 %, #6B5FA8) → arrow → target pill (gradient #A78BFA → #F472B6, white text).
- Loop 3200 ms, linear, infinite, paused in `onPause`: 0–36 % original; 36–48.5 % translated layer (white radius 12dp, #111 text, Halo underline) revealed left to right with a band (105°-ish: transparent → #A78BFA 45 % @30 % → #F472B6 50 % @50 % → #67E8F9 40 % @70 % → transparent, width 45 % of the bubble) sweeping across; 48.5–86 % hold; 86–96 % hidden left to right with the band; 96–100 % original.
- Samples (mockup `PV`): source "Meet at the lobby at 9." with target texts `zh-Hant` 九點在大廳見。, `zh-Hans` 九点在大厅见。, `ja` 9時にロビーで集合。, `ko` 9시에 로비에서 만나요., `fr` Rendez-vous au hall à 9 h., `de` Treffen um 9 in der Lobby., `es` Nos vemos en el vestíbulo a las 9.; target `en` uses source 9時にロビーで集合。 → Meet at the lobby at 9. and source label 日文; any other target shows "<targetTitle> · 範例譯文". Source label 英文 otherwise; target label `Languages.targetTitle(key)`.
- `applyTarget` updates the preview; decorative (`importantForAccessibility="no"`).

**Constraints:**
- One `ValueAnimator`; software drawing only.

**Acceptance criteria:**
- [ ] Gate `RC=0`.
- [ ] `grep -n "us.jhangy.textify.TranslatePreviewView" app/src/main/res/layout/activity_settings.xml` prints one line; `grep -n "trPreview.pause()" app/src/main/java/us/jhangy/textify/SettingsActivity.kt` prints one line.
- [ ] On device: the strip loops (original → tide → translation → tide → original); changing the target to 日本語 changes the target pill and the sample to 9時にロビーで集合。 within the next loop.

**Steps:**

(No JVM test: decorative drawing; checked on device.)

- [ ] **Step 1: Create `java/TranslatePreviewView.kt`**

```kotlin
package us.jhangy.textify

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * Looping preview in the 「翻譯」 card (prism-tide .tpv, note 10): a chat bubble whose text is flipped into the
 * target language by a Halo band, then flipped back. One ValueAnimator (previewLoop 3200), software drawing.
 */
class TranslatePreviewView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val d = resources.displayMetrics.density
    private val bg = CssGradientDrawable(intArrayOf(withAlpha(0.14f, 0xA78BFA), withAlpha(0.12f, 0x67E8F9)), null, 160f, 24 * d)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(15f); color = 0xFF1D2330.toInt() }
    private val trText = Paint(text).apply { color = 0xFF111111.toInt() }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sp(12f); typeface = Typeface.DEFAULT_BOLD }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val srcPill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(0.75f, 0xFFFFFF) }
    private val dstPill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val underline = Paint(Paint.ANTI_ALIAS_FLAG)
    private val band = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.4f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = withAlpha(0.7f, 0x6B5FA8)
    }
    private val pill = PillPainter(withAlpha(0.20f, 0xA78BFA), withAlpha(0.16f, 0xF472B6), withAlpha(0.28f, 0xA78BFA), 12 * d, -1f)
    private val bubble = RectF()
    private val layer = RectF()
    private var shadow: Bitmap? = null
    private val shadowDst = RectF()
    private var source = ""
    private var translated = ""
    private var srcLabel = ""
    private var dstLabel = ""
    private var t = 0f
    private val loop = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = LOOP; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    /** Sample sentence and labels for target [key] (prism-tide PV map). */
    fun setTarget(key: String) {
        if (key == "en") { source = "9時にロビーで集合。"; translated = "Meet at the lobby at 9."; srcLabel = "日文" }
        else { source = "Meet at the lobby at 9."; translated = SAMPLES[key] ?: "${Languages.targetTitle(key)} · 範例譯文"; srcLabel = "英文" }
        dstLabel = Languages.targetTitle(key)
        layoutBubble()
        invalidate()
    }

    fun resume() { if (loop.isStarted) loop.resume() else loop.start() }

    fun pause() { loop.pause() }

    override fun onDetachedFromWindow() { loop.cancel(); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); layoutBubble() }

    /** Bubble (padding 14/9dp, max 300dp) + 12dp gap + language row, centred. */
    private fun layoutBubble() {
        if (width == 0 || height == 0) return
        bg.setBounds(0, 0, width, height)
        val bw = min(maxOf(text.measureText(source), text.measureText(translated)) + 28 * d, min(300 * d, width - 24 * d))
        val bh = text.fontSpacing + 18 * d
        val total = bh + 12 * d + small.fontSpacing + 6 * d
        val top = (height - total) / 2f
        bubble.set((width - bw) / 2f, top, (width + bw) / 2f, top + bh)
        layer.set(bubble.left + 6 * d, bubble.top + 5 * d, bubble.right - 6 * d, bubble.bottom - 5 * d)
        pill.setSize((bw - 16 * d).toInt(), (bh - 8 * d).toInt())
        val blur = 20 * d; val dy = 8 * d; val spread = -10 * d
        shadow = glowBitmap(bw, bh, 16 * d, blur, withAlpha(0.35f, 0x4C1D95), spread, dy)
        val pad = glowPad(blur, spread, dy)
        shadowDst.set(bubble.left - pad, bubble.top - pad, bubble.right + pad, bubble.bottom + pad)
        underline.shader = LinearGradient(layer.left + 8 * d, 0f, layer.right - 8 * d, 0f, HALO, null, Shader.TileMode.CLAMP)
        band.shader = LinearGradient(0f, 0f, bw * BAND, 0f, BAND_COLORS, BAND_STOPS, Shader.TileMode.CLAMP)
    }
```

(The class continues in Step 2.)
- [ ] **Step 2: Finish `java/TranslatePreviewView.kt`** — append directly after `layoutBubble()` (these members close the class):

```kotlin
    override fun onDraw(canvas: Canvas) {
        if (bubble.isEmpty) return
        bg.draw(canvas)
        shadow?.let { canvas.drawBitmap(it, null, shadowDst, null) }
        canvas.drawRoundRect(bubble, 16 * d, 16 * d, white)
        canvas.save()
        canvas.translate(bubble.left + 8 * d, bubble.top + 4 * d)          // a2 pill, inset 8/4dp
        pill.draw(canvas)
        canvas.restore()
        val baseline = bubble.centerY() - (text.ascent() + text.descent()) / 2f
        canvas.save()
        canvas.clipRect(bubble)
        canvas.drawText(source, bubble.left + 14 * d, baseline, text)
        val (from, to) = coverage(t)
        if (to > from) {                                                   // translated layer, revealed left to right
            canvas.save()
            canvas.clipRect(layer.left + from * layer.width(), bubble.top, layer.left + to * layer.width(), bubble.bottom)
            canvas.drawRoundRect(layer, 12 * d, 12 * d, white)
            canvas.drawText(translated, bubble.left + 14 * d, baseline, trText)
            canvas.drawRoundRect(layer.left + 8 * d, layer.bottom - 2.5f * d, layer.right - 8 * d, layer.bottom, 1.25f * d, 1.25f * d, underline)
            canvas.restore()
        }
        canvas.restore()
        bandPhase(t)?.let { (p, alpha) ->                                  // Halo band sweeping across the bubble
            val bw = bubble.width() * BAND
            band.alpha = (alpha * 255f).toInt()
            canvas.save()
            canvas.translate(bubble.left + (-0.9f + 3.2f * p) * bw, 0f)
            canvas.drawRect(0f, bubble.top - 16 * d, bw, bubble.bottom + 16 * d, band)
            canvas.restore()
        }
        drawLanguageRow(canvas, bubble.bottom + 12 * d)
    }

    /** Source pill (white 75 %) -> arrow -> target pill (gradient #A78BFA -> #F472B6), padding 10/3dp. */
    private fun drawLanguageRow(canvas: Canvas, top: Float) {
        val h = small.fontSpacing + 6 * d
        val sw = small.measureText(srcLabel) + 20 * d
        val dw = small.measureText(dstLabel) + 20 * d
        val gap = 8 * d
        val aw = 14 * d
        var x = (width - (sw + gap + aw + gap + dw)) / 2f
        val ty = top + h / 2f - (small.ascent() + small.descent()) / 2f
        canvas.drawRoundRect(x, top, x + sw, top + h, h / 2f, h / 2f, srcPill)
        small.color = 0xFF6B5FA8.toInt()
        canvas.drawText(srcLabel, x + 10 * d, ty, small)
        x += sw + gap
        val s = aw / 24f                                                   // arrow path M5 12h14 M13 6l6 6-6 6
        val cy = top + h / 2f
        canvas.drawPath(Path().apply {
            moveTo(x + 5 * s, cy); lineTo(x + 19 * s, cy)
            moveTo(x + 13 * s, cy - 6 * s); lineTo(x + 19 * s, cy); lineTo(x + 13 * s, cy + 6 * s)
        }, arrow)
        x += aw + gap
        dstPill.shader = LinearGradient(x, 0f, x + dw, 0f, 0xFFA78BFA.toInt(), 0xFFF472B6.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(x, top, x + dw, top + h, h / 2f, h / 2f, dstPill)
        small.color = Color.WHITE
        canvas.drawText(dstLabel, x + 10 * d, ty, small)
    }

    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    companion object {
        private const val LOOP = 3200L
        private const val BAND = 0.45f
        private val HALO = intArrayOf(0xFFA78BFA.toInt(), 0xFFF472B6.toInt(), 0xFFFDBA74.toInt(), 0xFF67E8F9.toInt())
        private val BAND_COLORS = intArrayOf(
            withAlpha(0f, 0xFFFFFF), withAlpha(0.45f, 0xA78BFA), withAlpha(0.5f, 0xF472B6), withAlpha(0.4f, 0x67E8F9), withAlpha(0f, 0xFFFFFF),
        )
        private val BAND_STOPS = floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f)
        private val SAMPLES = mapOf(
            "zh-Hant" to "九點在大廳見。", "zh-Hans" to "九点在大厅见。", "ja" to "9時にロビーで集合。",
            "ko" to "9시에 로비에서 만나요.", "fr" to "Rendez-vous au hall à 9 h.", "de" to "Treffen um 9 in der Lobby.",
            "es" to "Nos vemos en el vestíbulo a las 9.",
        )

        /** Covered fraction range [from, to] of the bubble at loop time [t] (prism-tide tflip keyframes). */
        private fun coverage(t: Float): Pair<Float, Float> = when {
            t < 0.36f -> 0f to 0f
            t < 0.485f -> 0f to (t - 0.36f) / 0.125f
            t < 0.86f -> 0f to 1f
            t < 0.96f -> (t - 0.86f) / 0.10f to 1f
            else -> 0f to 0f
        }

        /** Band progress 0..1 and alpha during the two sweeps (prism-tide tband), null otherwise. */
        private fun bandPhase(t: Float): Pair<Float, Float>? = when {
            t in 0.36f..0.485f -> ((t - 0.36f) / 0.125f).let { it to 1f - it }
            t in 0.86f..0.96f -> ((t - 0.86f) / 0.10f).let { it to 1f - it }
            else -> null
        }
    }
}
```

- [ ] **Step 3: Insert the preview in `res/layout/activity_settings.xml`** — inside `trCard`, between the 「翻譯」 header `LinearLayout` and `trRows`:

```xml
                    <!-- .tpv looping preview (prism-tide note 10); .rows margin-top 14 -->
                    <us.jhangy.textify.TranslatePreviewView
                        android:id="@+id/trPreview"
                        android:layout_width="match_parent" android:layout_height="118dp"
                        android:layout_marginBottom="14dp"
                        android:importantForAccessibility="no" />
```

- [ ] **Step 4: Drive it from `java/SettingsActivity.kt`**

In `applyTarget`, after `b.targetValue.text = Languages.targetTitle(key)` add:
```kotlin
        b.trPreview.setTarget(key)
```
Change its kdoc to `/** Shows the target in the target row and the preview strip. */`.
In `onResume`, after `refreshModels()` add:
```kotlin
        b.trPreview.resume()
```
In `onPause`, after `Translate.onModelsChanged = null` add:
```kotlin
        b.trPreview.pause()
```

- [ ] **Step 5: Run the gate and the greps**

Send `SETTLED`; gate-runner: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug testDebugUnitTest; echo "RC=$?"` → `RC=0`, test counts unchanged (7 / 8 / 12 / 3).
Then: `grep -n "us.jhangy.textify.TranslatePreviewView" app/src/main/res/layout/activity_settings.xml` → one line; `grep -n "trPreview.pause()" app/src/main/java/us/jhangy/textify/SettingsActivity.kt` → one line.

- [ ] **Step 6: On-device check**

Install (gate-runner `installDebug`), open Settings: the strip loops every ~3.2 s — original bubble with the pill, a Halo band sweeps and the bubble shows 九點在大廳見。 with a rainbow underline, holds, then sweeps back; row shows 英文 → 繁體中文. Pick target 日本語 → the row shows 英文 → 日本語 and the translation 9時にロビーで集合。; target English → 日文 → English with "Meet at the lobby at 9.". Leave Settings and come back: the loop resumes. Report what you saw.

- [ ] **Step 7: Commit**

```bash
git rev-parse --abbrev-ref HEAD   # main
git add app/src/main/java/us/jhangy/textify/TranslatePreviewView.kt app/src/main/res/layout/activity_settings.xml app/src/main/java/us/jhangy/textify/SettingsActivity.kt
git commit -m "feat(settings): looping translation preview in the translation card

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>" -- app/src/main/java/us/jhangy/textify/TranslatePreviewView.kt app/src/main/res/layout/activity_settings.xml app/src/main/java/us/jhangy/textify/SettingsActivity.kt
git status --porcelain -- app/src/main/java/us/jhangy/textify/TranslatePreviewView.kt app/src/main/res/layout/activity_settings.xml app/src/main/java/us/jhangy/textify/SettingsActivity.kt   # empty
```
