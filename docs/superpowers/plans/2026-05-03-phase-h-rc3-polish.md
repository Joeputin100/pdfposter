# Phase H — RC3 polish (post real-device testing feedback)

**Branch:** `feat/md3e-redesign` (becomes `feat/md3e-rc3` if/when re-tagged)
**Status:** Plan-of-record. P0 batch shipped 2026-05-03 alongside this doc; P1 + P2 are subagent-driven follow-ups.
**Triggered by:** real-device testing on Galaxy A26 5G after the first RC build (`07f614f`) was sideloaded.

---

## Why this plan exists

User installed the RC build, completed Google sign-in, walked through the upscale modal end-to-end, and surfaced 22 distinct UX issues. None are architectural — Phase G's plumbing works. Phase H is the polish pass before tagging RC3 / merging to master.

The feedback is reproduced verbatim where it shapes the spec. UX choices the user made explicit (e.g., "no tech jargon", "one warning is sufficient", "use AI brand image") are non-negotiable design constraints for this phase.

## Already shipped tonight (P0 — `[H-P0]` commit)

- **H-P0.1** — Google sign-in: force account picker (added `client.signOut()` before returning the intent so the chooser appears even when an account is cached).
- **H-P0.2** — Capability check: drop the RED tier on RAM grounds. TFLite already runs tile-by-tile; working set is bounded by tile size, not output image size. Worst case is YELLOW with a "will take a few minutes" caption. RED is reserved for 32-bit-only ABIs.
- **H-P0.3** — Removed duplicate low-DPI warning under poster-size card (`MainActivity.kt`); kept the actionable card under construction preview.
- **H-P0.4** — Aspect-ratio fix in `LowDpiUpgradeModal` thumbnails: scale to longest-side 384, then `ContentScale.Crop` center-crops into 136dp without distortion.
- **H-P0.5** — `Icons.AutoMirrored.Filled.Login` (door / arrow) replaced with the Google G drawable (`ic_google_g.xml`) at both sign-in sites (Settings hamburger + AI upscale card).
- **H-P0.6** — `"Load file"` → `"Show me how to do it…"` on the "Bring your own" card (rename only; behavior remains "open file picker" until H-P2.10 lands the help walkthrough).

## Tasks

Each task has a self-contained spec for subagent execution. P1 and P2 batches are independent — can dispatch in parallel where files don't overlap.

### H-P1.1 — Paper size cards: text → to-scale infographics

**Sites:** Setup wizard (MainActivity Onboarding step), Settings hamburger, Main screen poster size selector.
**Current:** Each paper size is a vertical text label (`Letter`, `A4`, `Legal`, `Tabloid`, etc.) on a chip.
**Target:** Replace with to-scale rectangle infographics. All sizes shown at the same pixel scale so visual comparison is honest. **Letter** carries a sparkly star tooltip: "If you print at home in North America, this is probably the size you want."
**Asset path:** Render via Vertex (Imagen) at job-build time, or hand-author SVGs. Subagent picks based on token budget. Either way, end up at `app/src/main/res/drawable/paper_letter.xml` etc.
**Implementation:** New composable `PaperSizeCard(option, isSelected, ...)` swaps text label for `Image` + small text caption. Cards laid out in a `Row` (or `LazyRow` if too narrow). Sparkle tooltip only on Letter.

### H-P1.2 — Units toggle: inches/metric → inches/centimeters with ruler infographics

**Sites:** Setup wizard + Settings.
**Current:** Plain text toggle "Inches" vs "Metric".
**Target:** Two cards each carrying a small ruler infographic (inch ticks vs cm ticks). Labels are explicit: "Inches" / "Centimeters" — drop the abstract "Metric".
**Asset:** ruler vector drawables.
**Implementation:** `UnitsToggleCard(unit, isSelected, ...)`. Replaces the existing `RadioButton` row.

### H-P1.3 — Bring back Clarus the Dogcow (page orientation infographic)

**Site:** Main screen orientation selector.
**Current:** Reportedly "missing" — possibly was removed during MD3E redesign.
**Target:** Restore Clarus (the Apple Mac OS Classic dogcow used to indicate page orientation in the Page Setup dialog) as the orientation indicator.
**Asset:** if archives don't have a previous version, render via Vertex with prompt "Clarus the dogcow vintage Apple monochrome line drawing".
**Implementation:** `OrientationSelector` swaps current arrow icons for Clarus rotated 0° (portrait) vs 90° (landscape).

### H-P1.4 — Settings hamburger: file types comma-separated

**Site:** Settings hamburger "Supported file types" section.
**Current:** Bulleted list.
**Target:** Comma-separated inline list. ("PDF, PNG, JPG, WebP, …")
**Implementation:** Trivial `Text` swap.

### H-P1.5 — Share button: "Share…" with text

**Site:** Main screen actions row.
**Current:** Bare share icon.
**Target:** Icon + "Share…" text label.
**Implementation:** Trivial `Button` swap.

### H-P1.6 — Construction preview: remove page curl

**Site:** `PosterPreview.kt` — the construction-preview canvas.
**Current:** Decorative page-curl effect on the bottom-right of pages.
**Target:** Remove entirely. User said "looks terrible".
**Implementation:** Delete the page-curl draw block and any associated state.

### H-P1.7 — Construction preview: tape & tacks gating

**Site:** `PosterPreview.kt` — Assembly Cycle.
**Current:** Tape strips + thumb tacks float above pages while pages are still arranging.
**Target:** Tape + tacks only appear AFTER pages reach final aligned position.
**Implementation:** Gate the tape/tack alpha + position on Assembly Cycle phase ≥ "Aligned".

### H-P1.8 — Construction preview: new animation arc

**Site:** `PosterPreview.kt`.
**Current:** Pages fly in from somewhere.
**Target:** New narrative — dot-matrix printer outputs pages → stack of papers lands on desk → scissors ✂️ appear and cut panes out → tape strips + thumb tacks come in and set the final arrangement.
**Implementation:** Substantial. New phases in `AssemblyPhase` enum: `Printing`, `Stacking`, `Cutting`, `Aligning`, `Securing`. Per-phase composables. Sound design out of scope. Consider this the highlight of Phase H.

### H-P1.9 — Modal warning on View / Save / Share at <150 DPI

**Site:** Main screen action buttons.
**Current:** No interception; user can View/Save/Share even at 41 DPI.
**Target:** If current DPI < 150 and user taps View, Save, or Share, show a confirm modal: "This poster will print at low resolution. Continue anyway?" with options "Continue" and "Upgrade source first" (deeplinks to upgrade modal).
**Implementation:** Wrap each action's onClick in a DPI-check that shows the modal if < 150. Reuse existing `LowDpiUpgradeModal`'s entrypoint for "Upgrade source first".

### H-P1.10 — Upscale modal: tile grid with 5 cards + per-card pros/cons

**Site:** `LowDpiUpgradeModal.kt` — major rewrite.
**Current:** 4 cards in a horizontal `LazyRow`: Now / Free / AI / Bring own. AI has a tier toggle (4× / 8×).
**Target:** Tiled grid (2×3 or 3×2 depending on orientation) with 5 cards:
1. **None** — "use the source as-is, scale up with built-in Android tools". Pros: fastest, completely free, works on any device. Cons: visible pixelation at large sizes.
2. **Free upscale (on-device AI)** — ESRGAN x4. Pros: free, offline, works anywhere. Cons: 4× max, slower on older phones.
3. **AI upscale 4× (Topaz Gigapixel)** — premium. Pros: highest quality. Cons: premium price (~$X for this image).
4. **AI upscale 8× (Topaz Gigapixel)** — same model, larger output. Pros + cons same plus 4× cost.
5. **AI upscale (Recraft Crisp)** — cheaper precision option. Pros: $0.004/image flat (vs Topaz $0.01/MP), photo-faithful. Cons: 4× max output.
6. **Bring your own** — file picker; free with any source.

Every card shows: live credit cost (where applicable), USD equivalent, ETA, and a 1-line pros + 1-line cons. The 4× / 8× radio toggle is removed (each tier becomes its own card).
**Implementation:** New `UpscaleOptionCard(option: UpscaleOption, ...)` taking a unified data class. Layout in `LazyVerticalGrid` with 2 columns. Substantial.

### H-P1.11 — AI upscale card visuals: model brand image + magic wand + sparkles

**Site:** `LowDpiUpgradeModal.kt` — AI cards' image.
**Current:** Uses `ai_upscale_demo` (our own logo / placeholder).
**Target:** Use the FAL/Topaz model brand image. Overlay a magic wand 🪄 emoji + AGSL sparkle shader (Android 13+) at the top-left corner of each AI card.
**Implementation:** Add brand drawables for Topaz + Recraft. AGSL sparkle shader is reusable (ShaderBrush in Compose). On <Android 13, fall back to a static sparkle drawable.

### H-P1.12 — Per-card output DPI math accuracy

**Site:** `LowDpiUpgradeModal.kt`.
**Current:** Free + AI 4× both show "currentDpi × 4" — confusingly identical.
**Target:** Each card shows the actual output DPI for that option. None: 1×. Free: 4×. Topaz 4×: 4×. Topaz 8×: 8×. Recraft Crisp: 4×. Pros for the 8× option emphasize "twice the print size at the same quality" so the user gets why it costs more.
**Implementation:** Plumbed via the new `UpscaleOption` data class in H-P1.10.

### H-P2.1 — Getting Started doc

**Site:** New `app/src/main/kotlin/com/posterpdf/ui/screens/GettingStartedScreen.kt`.
**Content:** What PosterPDF does, the free vs paid breakdown ("What You Get for Free" headline), a 3-step guided tour (pick image → set size → generate). Add a hamburger-menu link.

### H-P2.2 — Help docs

**Site:** `HelpScreen.kt`.
**Content:** How-tos: paper sizes, low-DPI fix, upscale options, sign-in, history, sharing.

### H-P2.3 — FAQ

**Site:** `FaqScreen.kt`.
**Content:** "Why does it ask for credits?", "Why are my credits worth less than the price I paid?" (margin transparency), "Can I use it offline?", "What's the SHA-1 fingerprint for?", etc.

### H-P2.4 — Privacy Policy

**Site:** `PrivacyPolicyScreen.kt`. Static markdown render.
**Content:** What data we collect (anonymous + linked Google), what's stored where (Firestore + GCS + FAL), data-deletion request path.

### H-P2.5 — Support link

**Site:** Hamburger menu.
**Target:** External link to GitHub Issues at `https://github.com/Joeputin100/pdfposter/issues/new/choose`.
**Implementation:** Trivial.

### H-P2.6 — "Show me how to do it…" walkthrough

**Site:** `BringYourOwnHelpDialog.kt` (new) shown when "Show me how to do it…" is tapped. Then transitions to file picker.
**Content:** Step-by-step guide for Canva, OpenArt, FAL.ai, Topaz Photo AI: how to upload your image, run upscale, download the output, return to PosterPDF and select the upscaled file. Each step has a screenshot or mockup.

### H-P2.7 — QR code in PDF branding

**Site:** PDF generation pipeline (`PosterLogic.kt` or wherever the brand footer is drawn).
**Current:** Play Store URL printed as text.
**Target:** Add a QR code next to the URL. Code resolves to the Play Store listing.
**Implementation:** Use `com.google.zxing:core:3.5.3` to generate a QR bitmap, draw it via `Canvas.drawBitmap` in the PDF.

---

## Out of scope (for later phases)

- TFLite GPU delegate — capability tier reshuffling could let GPU work on some devices but not blocking.
- Voice/screen-reader accessibility audit — treat as a separate Phase I.
- Multi-language support (TODO 6) — separate effort.
- Telemetry instrumentation — defer until we have real users.

## Suggested execution order

1. **H-P0** ✅ shipped tonight in commit `[hash]`
2. **H-P1.4 / H-P1.5 / H-P1.6** (trivial tweaks) — single subagent
3. **H-P1.10 + H-P1.12** (modal rewrite + per-card DPI) — paired subagent
4. **H-P1.1 / H-P1.2 / H-P1.3** (asset-heavy: paper, ruler, dogcow) — Vertex pipeline + dispatched subagent
5. **H-P1.11** (AI brand + AGSL sparkles) — depends on H-P1.10 landing
6. **H-P1.7 / H-P1.8** (construction preview) — substantial; subagent with focused spec
7. **H-P1.9** (DPI gate modal on View/Save/Share)
8. **H-P2** batch — content writing + new screens
