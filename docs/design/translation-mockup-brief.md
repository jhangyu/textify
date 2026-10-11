# Textify translation mockup brief

## What to design
Textify (Android, minSdk 26) freezes the screen and shows OCR text as selectable boxes over the screenshot.
We are adding on-device translation. Behaviour is fixed by the approved spec — read it fully first:
`docs/superpowers/specs/2026-10-11-translation-design.md` (sections 3 "Session screen" and 4 "Settings").
Do not change behaviour; design how it looks and moves.

## One HTML file, two parts
### Part 1 — Session screen (phone frame, fake screenshot behind)
Use a believable fake app screenshot with mixed content: a Japanese paragraph, an English paragraph, a
Simplified Chinese paragraph, a Korean line, and one paragraph whose translation is too long for its box.
Show, switchable with on-page controls (buttons outside the phone frame):
1. Original mode: paragraph boxes (multi-line now, not single lines) over the screenshot, Halo style.
2. Translate button next to X and settings: off / busy (spinner ring) / on.
3. Translated mode: solid box background sampled from the screenshot edge, auto-sized text, black/white text
   by contrast; a truncated box with the "⋯" mark.
4. Status chips in the box corner: 下載中… / 等待 Wi-Fi / 不支援此語言 / 翻譯失敗.
5. Bottom panel for a truncated box: original on top, translation below, one copy button each.
6. Copy feedback on a translated box (reuse the session's existing pill shine/flash idea).

### Part 2 — Settings 「翻譯」 card (inside the existing settings page)
1. 目標語言 row + single-choice language list (繁體中文 default, 简体中文, English, 日本語, 한국어, … ).
2. 只用 Wi-Fi 下載 switch (default on).
3. Model list rows: downloaded (delete), downloading (indeterminate spinner), waiting for Wi-Fi (grey),
   English 「內建」 (no delete).
4. 「預先下載語言」 button; note line 「每個語言約 30MB，下載後可離線翻譯」.

## Style source of truth (mandatory)
- Session: `docs/design/mockups/a2-halo.html` (approved; read its header comment, copy palette and timings).
- Settings: `docs/design/mockups/settings/settings-final.html` (approved; the new card must sit in it naturally).
- Original rules still apply — see `docs/design/settings-mockup-brief.md` "Style source of truth" and
  "Must be implementable natively": light theme only, no solid outline frames, transitions <= 400 ms,
  everything expressible with Android Views/Canvas (gradient shaders, alpha/scale/translate animators,
  RenderEffect blur API 31+ with a non-blur fallback for API 26–30). No CSS-only effects as the sole carrier.
- All UI copy in Traditional Chinese (zh-TW).

## Output
- One self-contained HTML file (inline CSS/JS, no network fetches) in `docs/design/mockups/translation/`.
- A header comment in the file: direction name, one-paragraph rationale, palette + timing values, and an
  "Android implementation notes" list mapping each effect to the native API.
