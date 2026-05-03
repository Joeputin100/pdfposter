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

### H-P1.10 — Upscale modal: 2 visible AI options + expandable + comparison demo

**Site:** `LowDpiUpgradeModal.kt` — major rewrite.
**Current:** 4 cards in a horizontal `LazyRow`: Now / Free / AI / Bring own. AI has a tier toggle (4× / 8×).

**Target — default visible cards (tiled, not horizontal scroll):**
1. **None** — "use the source as-is, scale up with built-in Android tools". Pros: fastest, completely free, works on any device. Cons: visible pixelation at large sizes.
2. **Free upscale (on-device AI)** — ESRGAN x4. Pros: free, offline, works anywhere. Cons: 4× max output, slower on older phones.
3. **AI upscale: Topaz Gigapixel** — premium precision. Pros: cleanest edges, polished output. Cons: highest cost.
4. **AI upscale: Recraft Crisp** — cheaper precision. Pros: photo-faithful, ~40× cheaper than Topaz. Cons: less crisp on text/UI than Topaz.
5. **Bring your own** — file picker; free with any source.

**Below the visible cards, two text links:**

- **"See other AI options"** — expands inline to reveal AuraSR + Real-ESRGAN cards. Selected by power users; default-collapsed because user A/B-testing showed AuraSR/ESRGAN clean-up was below acceptable bar.
- **"Help me decide…"** — opens the comparison demo screen (H-P1.10b).

The 4× / 8× radio toggle is removed; both Topaz tiers become separate cards if/when user reopens "See other AI options" (8× is power-user territory).

**Per-card data:** live credit cost, USD equivalent, ETA, 1-line pros + 1-line cons.

**Implementation:** New `UpscaleOptionCard(option: UpscaleOption, ...)` data class. Layout in `LazyVerticalGrid(columns = 2)`. Expandable section uses `AnimatedVisibility`.

### H-P1.10b — "Help me decide…" comparison demo

**Site:** New `app/src/main/kotlin/com/posterpdf/ui/screens/UpscaleComparisonScreen.kt`.

**Demo subjects (4 baked-in, pulled from GCS at app build time):**
- `disco_chicken` — face + text + graphics (the user's test image; 1024×1024)
- `cat_shimmer` — soft fur + black background (Flickr photo `15558064844`, CC BY 2.0)
- `gristmill` — high-detail building + foliage (Wikimedia `Wayside_Inn_Gristmill.jpg`, CC BY-SA 4.0)
- `yardsale` — flyer with hand-drawn text + graphics (Flickr photo `4557813089`, CC BY-SA 2.0)

For each subject we ship 5 image assets in `app/src/main/res/raw/`:
- `<subject>_source.jpg` (the input)
- `<subject>_topaz.jpg`
- `<subject>_recraft.jpg`
- `<subject>_aurasr.jpg`
- `<subject>_esrgan.jpg`

**Subject picker:** chip row at top of screen, persisted selection.

**Comparison viewport:**
- Pinch-to-zoom (Compose `transformGesture` with state-saved scale + offset; clamp 1× to 8×)
- **Sliding handle for before/after**: vertical line draggable horizontally; left of line = source (input), right = upscaled. Dragging is `pointerInput` + `dragGesture`. Reuse the source-vs-upscaled split for any chosen model.
- Model toggle below the viewport: chip row of `Topaz` / `Recraft` / `AuraSR` / `Real-ESRGAN`. Persists last selection.

**Attribution footer:** mandatory CC license credit per source image (BY, BY-SA, etc.).

**Bake step:** Phase H execution should run all 4 demo subjects through all 4 FAL models (16 jobs), download outputs, downsample to ≤2 MP each (small enough to fit in app raw dir without bloating APK), and commit. ~$0.50 in FAL fees one-time.

### H-P1.10c — Credit denomination: 1 credit = 1 cent + tiered SKU bonuses

**Why:** Multi-model lineup needs price differentiation that doesn't collapse to "1 credit" for the cheap models. **And** the user wants the credit-to-cash mental model to be trivial: 1 credit = 1¢.

**Pricing model:**
- 1 credit = $0.01 retail (always)
- Cost-per-credit budget at 50% margin: $0.01 × 0.85 (Play fee) × 0.50 = **$0.00425**
- All in-app COGS conversions use this constant

**SKU ladder with tiered bonuses (cash-flow boost via volume discount):**

| SKU | Price | Base credits | Bonus credits | Total | Bonus % |
|---|---|---|---|---|---|
| `credits_starter` | $1.99 | 199 | 0 | **199** | 0% |
| `credits_small` | $4.99 | 499 | 25 | **524** | 5% |
| `credits_medium` | $9.99 | 999 | 75 | **1,074** | 7.5% |
| `credits_large` | $19.99 | 1,999 | 200 | **2,199** | 10% |

*(NB: this redenominates and renames the SKUs from Phase G — the existing `credits_small` / `medium` / `large` / `jumbo` ladder is replaced with a 4-tier `starter` / `small` / `medium` / `large`. Old purchases keep their grants; only future grants use the new ladder.)*

**Per-image cost on disco_chicken (16.78 MP output) at new denomination:**

| Model | COGS | Credits charged (ceil COGS/$0.00425) |
|---|---|---|
| Topaz Gigapixel | $0.168 | **40 credits** |
| AuraSR v2 | $0.013 | **4 credits** |
| Real-ESRGAN | $0.011 | **3 credits** |
| Recraft Crisp | $0.004 | **1 credit** |
| Free (on-device) | $0 | 0 credits |

**Implementation:**
- Replace `MP_PER_CREDIT = 5` with `CREDIT_COST_BUDGET_USD = 0.00425` (same name in `pricing.ts` / `upscale.ts` / `LowDpiUpgradeModal.kt` for cross-file consistency).
- New `getCogsUsd(model, outputMp, computeSeconds?): Double` helper knowing each model's pricing shape (per-MP for Topaz; per-image flat for Recraft; per-compute-second for AuraSR + ESRGAN).
- Backend: `computeCreditsForJob(...) = ceil(getCogsUsd(...) / CREDIT_COST_BUDGET_USD)`.
- For estimating before submit (when compute_seconds not yet known): assume ~1 sec per output MP for AuraSR/ESRGAN. Charge the estimate; refund if actual under-runs by >2 credits.
- SKU IDs in Play Console need to be created fresh; existing SKUs from Phase G plan are obsolete. Coordinate with Play Console paid setup (G1).

---

## H-P3 — Storage policy + History view + cleanup automation

User-mandated 2026-05-03 to bound cloud storage growth.

### H-P3.1 — Retention policy

**Source-of-truth in `pricing-policy.md`:**

| Tier | Where | Duration | Cost to user |
|---|---|---|---|
| History list (metadata) | Firestore `users/{uid}/history/{historyId}` | Indefinite | Free |
| Local PDF cache | Device storage | Indefinite, until app uninstalled | Free |
| **Cloud PDF storage (default)** | GCS `gs://.../user-pdfs/{uid}/{historyId}.pdf` | First 30 days free; after that, **1 credit/month** per file | Free → 1 credit/file/month after day 30 |
| Cloud PDF (grace period) | Same | 30 more days after credit balance hits 0 | Free + email + in-app notification |
| Cloud PDF (auto-delete opt-in) | Same | 30 days exactly | Free, then deleted with no charge |

The 1-credit-per-file-per-month rate gives the project a 90% margin on storage (typical 20MB PDF costs us ~$0.0004/month at GCS standard rates; 1 credit = $0.00425 cost budget). The user pays roughly **1¢ per file per month** to keep posters stored beyond 30 days.

### H-P3.2 — Account setup question (new flow)

On first sign-in (or in Settings later), present the choice:

> **What should happen to your posters after 30 days?**
>
> 🅐 **Auto-delete after 30 days** — keeps your storage bill at zero. Local copies and history list stay forever.
> 🅑 **Keep storing them** — about **1¢ per poster per month** after the first 30 days. Charged from your credit balance.

Persists to `users/{uid}.storageRetentionMode = 'auto-delete' | 'paid'`. Default if user closes the dialog: `paid` (matches user expectation of "saved == saved").

### H-P3.3 — Backend: retention cron + credit deduction

New `backend/functions/src/storageBilling.ts`:

- **Cron `storageBilling.dailySweep`** at 03:00 UTC daily:
  1. For each `users/{uid}/history/{historyId}` doc with `cloudStorageUri` set:
     - If `createdAt > now - 30d`: skip (free tier)
     - If user.storageRetentionMode = 'auto-delete' AND `createdAt > now - 30d`: delete blob + clear `cloudStorageUri` field, log to `users/{uid}/storageLog`
     - If user.storageRetentionMode = 'paid': charge 1 credit (atomic Firestore txn). On insufficient credits, set `gracePeriodStartedAt = now`. After `gracePeriodStartedAt + 30d`: delete blob.
  2. Coalesce notifications: if any deletes happened for a user, send a single email + push notification listing the affected posters.

- **Callable `deleteCloudCopy(historyId)`** — user-initiated cloud deletion from History view.

### H-P3.4 — History view: per-row buttons

In `HistoryScreen.kt`, each row gets four icon buttons:

| Icon | Action | Behaviour |
|---|---|---|
| `Visibility` | View | Open the poster preview (existing) |
| `Share` | Share | System share-sheet with the cloud URL or local URI |
| `Download` | Download | Pull cloud copy to local Downloads folder |
| `CloudOff` | Delete from cloud | Confirmation dialog → calls `deleteCloudCopy` callable |

Local-only entries (no `cloudStorageUri`) hide the cloud-delete button.

### H-P3.5 — Notifications

- **Push (Firebase Cloud Messaging)**: 7 days before a free-tier expiry, 24h before a grace-period expiry.
- **Email**: same triggers, sent via Firebase Auth's default templates or a Cloud Run integration with SendGrid/Mailgun (defer choice; for v1 use Firebase Auth's "custom email handler" path).

### H-P3.6 — Pricing policy doc update

Update `docs/superpowers/pricing-policy.md` to reflect:
- 1 credit = 1¢ retail at $0.00425 COGS budget (replaces the per-MP model)
- New 4-tier SKU ladder with bonus credits
- Cloud storage tier with 30-day free + 1-credit/file/month + grace period
- Auto-delete vs paid choice in Settings

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

### H-P1.13 — SVG support: rasterize at target poster resolution

**Site:** `PosterPreview.kt` decode path + `PosterLogic.kt` generation path.
**Current:** SVG is listed in "Supported File Types" but `BitmapFactory.decodeStream` returns null for SVG. The Coil-based preview shows the SVG correctly (`coil-svg` is on the classpath), but the bitmap state stays null — `Generate Poster` produces a broken/empty PDF, and the upscale modal can't open (gated on `previewBitmap != null`).
**Target:** When the picked URI's MIME or extension is SVG:
1. Use `androidsvg-aar` (already in deps) to rasterize at the target poster pixel resolution (`posterWidthInches × 300 DPI` default). This guarantees crisp output regardless of source.
2. Skip the upscale modal entirely — vector sources don't need upscaling.
3. The DPI gate I just added (H-P1.9) already handles this correctly because `computeCurrentDpi()` returns `0f` when `sourcePixelDimensions` is null, which is outside the gate range. Once SVG → bitmap rasterization lands, source dims become known and gate behaves normally.

**Estimate:** 1-2 hours (mostly the SVG → Bitmap conversion + plumbing into the existing path). Include a small "Vector source — prints sharp at any size" tooltip near the upscale UI to explain to SVG users why no upscale option appears.

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

1. **H-P0** ✅ shipped 2026-05-03 in commit `5fb3afd`
2. **H-P1.10c** (credit denomination → 1 credit = 1¢ + tiered bonus SKUs) — *foundation; everything pricing-related depends on it*
3. **H-P1.4 / H-P1.5 / H-P1.6** (trivial tweaks: file types comma list, Share button text, page curl removal) — single subagent
4. **H-P1.10 + H-P1.10b + H-P1.12** (modal rewrite + comparison demo + per-card DPI) — paired subagent. Depends on H-P1.10c.
5. **H-P3** (storage policy + retention cron + History buttons + account setup question) — substantial; can run in parallel with H-P1.10 since file overlap is minimal. Depends on H-P1.10c (credit math).
6. **H-P1.1 / H-P1.2 / H-P1.3** (asset-heavy: paper, ruler, dogcow) — Vertex pipeline + dispatched subagent
7. **H-P1.11** (AI brand + AGSL sparkles) — depends on H-P1.10 landing
8. **H-P1.7 / H-P1.8** (construction preview rework) — subagent with focused spec
9. **H-P1.9** (DPI gate modal on View/Save/Share)
10. **H-P2** batch — content writing + new screens
