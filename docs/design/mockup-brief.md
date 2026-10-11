# Textify capture-session mockup brief

## Product context
Textify is an Android app (minSdk 26). A floating bubble (56dp circle, overlay window) sits over any app.
Tapping it grabs one screenshot via MediaProjection and runs on-device OCR (ML Kit: blocks -> lines -> elements, each with a bounding box).
Today the result is a plain bottom sheet with text. We are replacing it with an in-place "frozen screen" session.

## Confirmed requirements (user-approved, do not change)
1. Tap bubble -> screen freezes: full-screen overlay showing the captured screenshot, bubble hidden.
2. One selectable text box per OCR **line**, positioned exactly over that line in the screenshot.
3. Box look: the screenshot stays untouched; text areas get a faint tinted highlight. Selection shows a selection colour. **No solid outline strokes** anywhere (user: "do not leave solid-line frames") — use faint fills, soft glows, gradients.
4. Interaction: single tap a box = copy the whole line; long-press inside a box = partial selection with drag handles + system copy menu.
5. Close: X button at top-right ends the session (bubble reappears). System back also closes. Tapping empty space does NOT close.
6. No "copy all" button. Only the X in the chrome.
7. Copy feedback: brief "Copied" toast near bottom + the copied box flashes a confirm tint.
8. No text found: frozen screen with a "No text detected" hint; user closes with X.
9. Bubble drag-to-dismiss like Facebook Messenger chat heads: while dragging, a dismiss target (circle with X) fades in at bottom-centre; bubble snaps/magnetises to it when close; release on it = bubble disappears and the whole app (service) stops.

## Motion (user-specified)
- Most transitions are fades within **0.4 s** (in and out).
- Recognition start: trendy "AI activation" effect — a gradient glow pulses/shimmers along the screen edges (think Apple Intelligence / Gemini edge glow), then a very faint dim scrim covers the frozen screen until OCR finishes (typical 0.5–1 s, design for up to ~3 s).
- Recognition done: scrim fades out, edge glow settles into a **very faint gradient edge** that stays for the session (no solid border), text boxes fade in (may stagger subtly, total ≤0.4 s).
- Close: everything fades out ≤0.4 s, bubble fades back in.
- Every action (tap-copy, long-press select, toast, X, bubble drag/snap/dismiss) needs a suitable micro-animation.

## Must be implementable natively
Target is Android Views/Canvas (overlay window). Effects must be reproducible with: gradient shaders (Linear/Sweep/Radial), alpha/scale/translate animators, RenderEffect blur (API 31+ only — must have a graceful non-blur look for API 26–30). Avoid effects that only CSS can do (backdrop-filter as the sole carrier, mix-blend tricks with no Canvas equivalent).

## Deliverable format
- Each mockup: a single self-contained `.html` file (inline CSS/JS, no network, no external fonts/images — draw a fake app screenshot in HTML/SVG, e.g. a chat or article screen with CJK + English text).
- Phone frame ~412x915 CSS px (Pixel-class), centred on the page.
- Must be **interactive and animated**: a "Replay" control to re-run: idle bubble -> tap -> edge glow -> scrim -> boxes appear; clicking a box simulates copy (flash + toast); long-press simulates partial selection with handles; X closes; bubble draggable with Messenger-style bottom dismiss target. Also a toggle to preview the "no text detected" state.
- Each of the 3 mockups per designer must be a genuinely different visual direction (colour palette, glow character, box highlight treatment, toast/X style), not minor variants.
- Top of each file: an HTML comment with direction name, palette hex values, and the exact timing values used (ms) so implementation can copy them.
