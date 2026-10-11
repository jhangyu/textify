# On-device translation + paragraph OCR — design

Status: design approved; mockup picked (`docs/design/mockups/translation/proposal-2-prism-tide.html`); implementation plan next.

## Decisions (user)
1. Engine: ML Kit on-device translation (`com.google.mlkit:translate`). Language models (~30 MB each) are
   downloaded from Google on first use, then run offline. Adds the INTERNET permission. README's "fully offline"
   wording changes to "OCR fully offline; translation offline after a one-time model download".
2. Display: translated text replaces the original in place; when it does not fit, a bottom panel shows it.
3. OCR always works in paragraphs (not lines), with or without translation.
4. One target language chosen in Settings (default 繁體中文); the source language is detected per paragraph.
5. Model download: automatic on demand, with a "只用 Wi-Fi 下載" switch.
6. Translation is triggered only by a button on the session screen. No auto-translate setting.
7. Traditional Chinese output uses OpenCC (Java port, e.g. opencc4j), mode "Simplified -> Taiwan standard with
   Taiwan phrases" (s2twp). Verify the current version and APK size cost on Maven Central before adding it.
8. Settings shows per-language model state (downloaded / downloading / waiting for Wi-Fi) with delete.

## 1. OCR in paragraphs
- `Ocr.kt`: `candidates()` works on `Text.TextBlock` instead of `Text.Line`. Paragraph text = its lines joined
  with `\n`; box = block bounding box; per-line boxes are kept for layout.
- `OcrLine` is replaced by `OcrBlock(text: String, box: Rect, lines: List<Rect>)`. `OcrLine` is deleted.
- `OcrMerge.kt`: same IoU > 0.5 merge, applied to paragraphs. Paragraph confidence = mean of its lines'
  `Text.Line.getConfidence()` (ML Kit blocks have no confidence). `OcrMergeTest` updated.
- `PillView`: multi-line (no `setSingleLine`). Original mode is unchanged in spirit: transparent selectable text
  over the screenshot; tap copies the whole paragraph, long-press selects part of it. Line height and text size
  are derived from the per-line boxes so the selection handles sit on the screenshot glyphs. This is the
  highest technical risk of this section.
- Known risk: ML Kit sometimes merges two adjacent columns into one block or splits a paragraph. A custom
  line-clustering heuristic is out of scope until blocks prove insufficient.
- README "How it works" step 3: "every recognised line becomes a pill" -> "every recognised paragraph".

## 2. Translation engine — `Translate.kt`
Single entry point: given the session's `OcrBlock` list and the target language, report a state per paragraph
(callbacks on the main thread). The session screen never calls ML Kit directly.

Per paragraph:
1. ML Kit language identification (`com.google.mlkit:language-id`, bundled model, no download).
2. Route (pure function, unit-tested):
   - undetermined, or source == target language -> `NotNeeded` (stays original);
   - target 繁體中文 and source Chinese -> OpenCC only (no ML Kit, no download; also converts on-screen
     Simplified Chinese to Traditional);
   - source not supported by ML Kit translation -> `Unsupported`;
   - otherwise -> ML Kit translate, then OpenCC when the target is 繁體中文.
3. Paragraphs are grouped by source language: one translator and one model check/download per language.

Paragraph states: `Translating`, `Downloading`, `WaitingWifi`, `Unsupported`, `Failed`, `NotNeeded`,
`Done(text)`.

Target language list: every ML Kit translation language, with Chinese split into 繁體中文 (ML Kit `zh` + OpenCC)
and 简体中文 (ML Kit `zh` as is).

Downloads:
- Done through ML Kit's `RemoteModelManager`, not through the translator, so a download survives the session
  screen closing.
- `Translate.kt` holds one process-wide set of languages currently downloading; the session screen and Settings
  both read it.
- "只用 Wi-Fi" on and not on Wi-Fi now: that language's paragraphs become `WaitingWifi`; no download is queued.
  Pressing translate again after connecting retries. Needs ACCESS_NETWORK_STATE to read the current network.

Lifecycle: results live in the session's memory only; toggling original/translation does not re-translate.
Translators are closed when the session ends.

Known limitation: Japanese text heavy in kanji may be detected as Chinese; it then only gets the OpenCC
conversion, not a translation.

## 3. Session screen
Translate button:
- Sits with X and settings, same entrance animation; appears only after OCR finished with at least one paragraph.
- Looks: off (original), on (translation shown), busy (spinner ring around the button).
- Pressing it while busy returns to the original; translation keeps running and its results show the next time.

Paragraph box in translation mode:
- Solid background so the original glyphs are hidden. Background = average colour of the screenshot along the
  box edge; text colour = black or white, whichever contrasts more.
- Translation auto-sizes to fit the original box, minimum 10sp.
- Still does not fit at 10sp: text is truncated and a "⋯" mark is drawn in the box corner.
- `NotNeeded` paragraphs look as in original mode.
- `Downloading` / `WaitingWifi` / `Unsupported` / `Failed`: original look plus a small status chip in the box
  corner (下載中… / 等待 Wi-Fi / 不支援此語言 / 翻譯失敗).

Taps:
- Normal translated box: tap copies the translation, long-press selects part of it.
- Truncated box: tap opens the bottom panel instead of copying.
- Bottom panel: original on top, translation below, one copy button each. Tap outside or back closes it.

No extra toast or dialog when offline or when everything failed; the per-paragraph chips are the report.

## 4. Settings
New card 「翻譯」 below the OCR language card, same card style:
- 目標語言: one row showing the current choice (default 繁體中文); tap opens a single-choice language list.
- 只用 Wi-Fi 下載: switch, default on.
- Model list, one row per language:
  - downloaded: delete button;
  - downloading: indeterminate spinner (ML Kit gives no percentage);
  - waiting for Wi-Fi: grey text.
  - English is built into ML Kit and cannot be deleted: label 「內建」, no delete button.
- 「預先下載語言」 button under the list: opens the language list; picking one starts a download.
- Note line: 「每個語言約 30MB，下載後可離線翻譯」 (ML Kit exposes no real file size).
- Downloaded set is queried from ML Kit each time Settings opens; nothing is stored separately.

`Prefs.kt` gains: target language (default 繁體中文) and Wi-Fi-only (default true).

## 5. Error handling
- Language identification fails -> undetermined -> original stays, no error shown.
- Model download fails (offline, storage full) -> that language's paragraphs `Failed`, removed from the
  downloading set; the next translate press retries.
- One paragraph fails to translate -> only that paragraph is `Failed`.
- OpenCC fails -> show the ML Kit (Simplified) output instead of failing the paragraph.
- Session closed mid-translation -> late results are ignored (existing `ended` flag); downloads continue.

## 6. Testing
JVM unit tests (same kind as `OcrMergeTest`):
- paragraph merge with IoU after the switch to blocks;
- translation routing (detected language x target -> NotNeeded / OpenCC only / ML Kit + OpenCC / ML Kit only /
  Unsupported), kept as a pure function so it runs without ML Kit.

## Out of scope
Auto-translate setting; custom paragraph clustering; translation history; translation quality comparison;
bundled (APK-packaged) translation models.

## Mockup phase
Two opus team members each produce one HTML mockup proposal covering the session screen (translate button
states, translated boxes, status chips, truncation mark, bottom panel) and the Settings 「翻譯」 card (target
language, Wi-Fi switch, model list states, pre-download). Each follows the skills
`frontend-design:frontend-design`, `ui-ux-pro-max:ui-ux-pro-max` and `ui-ux-pro-max:ui-styling`, and extends the
approved mockups `docs/design/mockups/a2-halo.html` (session) and
`docs/design/mockups/settings/settings-final.html` (settings). Output: `docs/design/mockups/translation/`.
The user picks (or mixes) before the implementation plan is written.

## Final picks (user, after mockups)
- Visual direction: `docs/design/mockups/translation/proposal-2-prism-tide.html` ("Prism Tide"). Its header comment
  (palette, timings, Android notes) is the visual source of truth for sections 3 and 4.
- Chinese paragraphs when the target is 繁體中文: if the OpenCC output equals the input, the paragraph is
  `NotNeeded` (keeps the original look). Only changed text becomes a translated box.
- Truncation: custom "⋯" mark over a short fade in the box colour (as in `proposal-1-quiet-reprint.html`), not the
  system ellipsis.
- Deleting a model in Settings: no confirmation dialog.
- Translate button spins only while paragraphs are `Translating`. While models download it shows "on"; the
  per-paragraph 下載中… chips report the download.
- One implementation for API 26+, no per-API branches (project policy): box glow must use a technique that works
  on every supported API level (e.g. drawing it in software), and the bottom panel uses the opaque 96% white
  look with no RenderEffect blur.
