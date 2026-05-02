# Phase G Economics Revision — per-megapixel credit pricing + capability-aware UX

**Branch:** `feat/md3e-redesign`
**Status:** Plan-of-record. Backend tasks (G-R1, G-R2) implemented 2026-05-02. Client tasks (G-R3 through G-R7) deferred to next session.
**Supersedes:** the static `1 credit = 1 upscale` model in `docs/superpowers/pricing-policy.md` v1 and the `CREDIT_COST: Record<Tier, number>` map in `backend/functions/src/upscale.ts`.

---

## Why this plan exists

Live FAL pricing API (`GET /v1/models/pricing?endpoint_id=fal-ai/topaz/upscale/image`) returned `unit="megapixels"`, `unit_price=$0.01/MP` on 2026-05-02 — not `unit="image"` as the original Phase G plan assumed. A 12 MP source upscaled 4× produces 192 MP of output and costs ~$1.92 in FAL fees per single upscale. The original `$1.99 → 15 credits` SKU ladder couldn't profitably issue even one credit.

Two structural changes follow:

1. **Credit cost varies with output size.** No more "1 credit per upscale". Charge a credit count derived from `input_mp × scale² × $0.01/MP`, scaled to maintain 50% gross margin across the SKU ladder.
2. **The user must see the cost before they commit.** With variable credits-per-upscale, hiding the cost means surprise debits. The upgrade modal becomes a live calculator.

Since FAL costs scale with output area (16× for 4×, 64× for 8×), we also need the client to:
- Tell the user whether their device can handle on-device upscaling at all (the always-free path)
- Show a realistic ETA so they know what they're committing to
- Offer an exit ramp: bring an already-upscaled image from any other tool (free with PosterPDF either way)

---

## Design decisions

### D1 — Credit math

```
output_mp     = input_mp × scale²       # 4× → 16× area, 8× → 64× area
fal_cost_usd  = output_mp × $0.01
credits       = ceil(output_mp / MP_PER_CREDIT)   # MP_PER_CREDIT = 5
```

`MP_PER_CREDIT = 5` derives from a $0.05/credit cost basis at FAL's $0.01/MP, which keeps the existing SKU ladder's 50% gross margin intact:

| SKU | Price | Cost budget (price×0.85×0.50) | Credits granted (÷$0.05) | Effective $/credit |
|---|---|---|---|---|
| `credits_small` | $4.99 | $2.121 | 42 | $0.119 |
| `credits_medium` | $9.99 | $4.246 | 84 | $0.119 |
| `credits_large` | $19.99 | $8.496 | 169 | $0.118 |
| `credits_jumbo` | $39.99 | $16.996 | 339 | $0.118 |

Per-upscale cost in credits at typical input sizes:

| Input | 4× → output | 4× credits | 8× → output | 8× credits |
|---|---|---|---|---|
| 4 MP | 64 MP | 13 | 256 MP | 52 |
| 6 MP | 96 MP | 20 | 384 MP | 77 |
| 12 MP (typical phone) | 192 MP | 39 | 768 MP | 154 |
| 24 MP | 384 MP | 77 | 1536 MP | 308 |
| 50 MP (S25/16 Pro ProRAW) | 800 MP | 160 | 3200 MP | 640 |

50% gross margin holds at every size because both revenue (SKU $/credit) and cost (FAL $/credit) are proportional to credits.

### D2 — No input cap

Per user direction: don't downscale before submit. Server passes the image to FAL at its native resolution. Implication: a single 50 MP @ 8× upscale is 640 credits = ~$75 retail in one job. The dynamic pricing UI (D4) makes this visible *before* the user spends.

**Anti-abuse follow-up (TODO 11):** server-side dimension verification after FAL completes. If `actual_output_mp / scale² > claimed_input_mp × 1.05`, log a fraud signal and debit the difference. For v1 with a single user this is paper; gets real once the app has scale.

### D3 — Updated `pricing.ts` cost basis

`fetchFalCostPerCredit` returns `unit_price × MP_PER_CREDIT = $0.05` instead of throwing on `unit !== "image"`. The unit check stays as an audit-log warning so a future FAL change (e.g., to `"image"` flat-rate) is surfaced loudly in Cloud Logging. `refreshPricing` is unblocked; the cron resumes daily writes to `/pricing/current`.

### D4 — Live credit calculator in `LowDpiUpgradeModal`

Modal surfaces three buttons, each computed live from the user's selected image:

```
┌────────────────────────────────────────────────────┐
│ [On-device] FREE — works offline       capable: ✓  │
│ [AI 4×]    39 credits (~$4.64)         ETA 1–3 min │
│ [AI 8×]    154 credits (~$18.36)       ETA 4–8 min │
│ [I'll bring my own]   FREE — load upscaled file    │
└────────────────────────────────────────────────────┘
```

Math runs as soon as the image is picked (`inputMp` known), before the modal opens. State updates if the user changes the scale toggle.

### D5 — Device capability check (on-device upscale)

```kotlin
data class DeviceCapability(
    val tier: CapabilityTier,             // GREEN | YELLOW | RED
    val reason: String?,                  // shown when not GREEN
    val recommendedMaxOutputMp: Int,
)
enum class CapabilityTier { GREEN, YELLOW, RED }

fun assessLocalUpscale(inputMp: Int, scale: Int, ctx: Context): DeviceCapability {
    val outputMp     = inputMp * scale * scale
    val outputBytes  = outputMp.toLong() * 1_000_000L * 4L     // RGBA8888
    val needBytes    = outputBytes * 3                          // input + output + intermediate

    val am           = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val largeMemMb   = am.largeMemoryClass.toLong()
    val largeMemBytes = largeMemMb * 1024L * 1024L

    return when {
        Build.SUPPORTED_64_BIT_ABIS.isEmpty() ->
            DeviceCapability(RED, "32-bit device — TFLite needs 64-bit", recommendedMaxOutputMp = 0)
        needBytes > largeMemBytes * 0.30 ->
            DeviceCapability(RED, "Not enough RAM for this size on-device", recommendedMaxOutputMp = ((largeMemBytes * 0.30) / 12_000_000).toInt())
        needBytes > largeMemBytes * 0.15 ->
            DeviceCapability(YELLOW, "Tight on memory — uses tile mode (slower)", recommendedMaxOutputMp = outputMp)
        else ->
            DeviceCapability(GREEN, null, recommendedMaxOutputMp = outputMp)
    }
}
```

### D6 — ETA estimation

**On-device** — first-launch benchmark stored in DataStore as `msPerMegapixel`:
1. Decode a 1024×1024 sample from app assets
2. Run ESRGAN x4 over it once (warm-up discarded), then 3× more (median wall-clock)
3. Compute `msPerMegapixel = median_ms / (1024 * 1024 / 1_000_000)`
4. ETA: `outputMp * msPerMegapixel`

Re-benchmark on Android version updates and on first launch after a 30-day staleness.

**FAL** — empirical curve (no benchmark available; FAL doesn't expose granular timing):
```
upload_ms    = inputBytes / bytesPerSecond                  # NetworkInfo bandwidth estimate
queue_ms     = 30_000                                        # constant; tighten with telemetry
inference_ms = outputMp * 500                                # 0.5 s/MP empirical for Topaz Gigapixel
download_ms  = outputBytes / bytesPerSecond
total_ms     = upload_ms + queue_ms + inference_ms + download_ms
```

Show ETA as a ±25% range (`"~total_ms × 0.75 to total_ms × 1.25"`) so users aren't disappointed by a flat number.

### D7 — Progress indicator (Material 3 1.5.0-alpha18)

Use `LinearWavyProgressIndicator` for the active job. Phases:

| Phase | Indicator | Label |
|---|---|---|
| Uploading | Determinate by bytes | "Uploading… 38%" |
| Queued at FAL | Indeterminate wavy | "Waiting in queue…" |
| Inference (FAL) | Indeterminate wavy + estimated countdown | "Upscaling… ~85s left" |
| Downloading | Determinate by bytes | "Downloading… 64%" |
| Done | Hides; replaced by image | — |

For on-device: determinate by tile count throughout (we know the tile loop progress).

### D8 — 3rd-party upscale messaging

The third button in the modal: **"I'll bring my own"** opens the file picker directly. The selected file goes into the same poster pipeline that on-device and AI upscale outputs use. Zero credit charge. Below the modal, a single line:

> Already upscaled with Canva, OpenArt, Topaz Photo AI, or Magnific? Load the upscaled file directly. **PosterPDF is free with any image source** — AI upscale credits only cover the FAL inference cost.

This is both UX honesty (don't gatekeep) and load-shedding (every user who upscales elsewhere is a user who doesn't burn FAL credits). It's also a hedge: if FAL becomes too expensive or unreliable, the app still works without the AI tier.

---

## Tasks

### G-R1 — Backend: per-MP credit formula in `upscale.ts` ✅ DONE

- Replace `CREDIT_COST: Record<Tier, number>` with `creditsForUpscale(inputMp, scale): number = ceil(inputMp * scale² / MP_PER_CREDIT)`
- Add `inputMp: number` to `RequestUpscaleInput`; validate `> 0` and `< 1000` (sanity, not user cap — 1000 MP is impossible for any consumer device)
- Trust the client value for the debit; log it alongside the actual output dimensions in the tx doc for post-hoc reconciliation

### G-R2 — Backend: pricing.ts cost basis ✅ DONE

- `fetchFalCostPerCredit` returns `unit_price × MP_PER_CREDIT` (= $0.05/credit at $0.01/MP × 5)
- Convert the `unit !== 'image'` throw back to a warn — we now expect "megapixels"
- New `unit !== 'megapixels'` triggers an alert-level log so a future FAL pricing-model change is loud
- Cron resumes daily writes; cached fallback path still kicks in on transient API outages

### G-R3 — Client: dynamic credit calculator in `LowDpiUpgradeModal` ⏳ NEXT SESSION

- New `MainViewModel` field `selectedImageMp: Int?` populated when the user picks an image (decode bounds-only via `BitmapFactory.Options.inJustDecodeBounds`)
- New helper `creditsFor(inputMp, scale): Int = ceil(inputMp * scale * scale / 5)`
- Modal renders all three CTAs with live cost + ETA; updates if user toggles 4× ↔ 8×
- USD display reads `pricing/current.products[*].priceUsd / products[*].credits` for `effective $/credit`, multiplies by computed credit count

### G-R4 — Client: device capability check ⏳ NEXT SESSION

- `assessLocalUpscale()` per D5 above; lives in `app/src/main/kotlin/com/pdfposter/ml/Capability.kt`
- Modal disables on-device button + shows `reason` text when tier is RED
- YELLOW shows the button enabled with a "tile mode (slower)" caption

### G-R5 — Client: ETA benchmark + estimator ⏳ NEXT SESSION

- One-time benchmark in `UpscalerOnDevice.benchmarkAndCache(ctx)` invoked on first app launch (or on 30-day staleness); writes `msPerMegapixel` to DataStore
- `etaForLocal(outputMp, msPerMp): IntRange` returns ±25% range
- `etaForFal(inputBytes, outputMp): IntRange` per the empirical curve in D6
- Modal renders the range in human terms ("~1–3 min")

### G-R6 — Client: MD3E progress indicator wiring ⏳ NEXT SESSION

- New composable `UpscaleProgressBar(state: UpscaleProgress)` using `LinearWavyProgressIndicator`
- `UpscaleProgress` is a sealed class with `Uploading(pct)`, `Queued`, `Inferring(estimatedMsLeft)`, `Downloading(pct)`, `Done`
- Hooked to `MainViewModel.upscaleState` flow

### G-R7 — Client: 3rd-party "I'll bring my own" CTA ⏳ NEXT SESSION

- Third button in `LowDpiUpgradeModal` — opens the existing `pickImage` flow directly, skipping the upgrade gate
- Below the modal: a single text line per the D8 copy
- No new permissions, no new storage paths — uses the same `Uri` flow the main image picker uses

### G-R8 — `pricing-policy.md` v2 ⏳ DONE inline alongside G-R1/G-R2

- Replace static `1 credit = 1 upscale` table with the per-MP formula
- Add the worked-example matrix from D1
- Update tier table; both 4× and 8× now have *variable* credit cost

### G-R9 — TODO 11: server-side dimension verification ⏳ FUTURE

- After FAL submit returns output URL, compute `actual_output_mp` from headers/HEAD response
- Compare to `claimed_input_mp × scale²`
- If `actual / claimed > 1.05`: log fraud event, debit additional credits idempotently
- Add to `docs/superpowers/TODO.md` as TODO 11

---

## Open questions for next session

1. **Where does `inputMp` come from on the client?** EXIF for JPEG, parser for PNG/WEBP, `ImageDecoder` for everything else. Pick one path or branch on MIME.
2. **Benchmark sample size.** 1024×1024 may take 30+ seconds on a low-end phone. Consider 512×512 with a `× 4` extrapolation.
3. **ETA accuracy budget.** The empirical FAL constants (30 s queue, 0.5 s/MP) are guesses. Plan to instrument the `requestUpscale` Cloud Function to log actual durations, then refit the constants from the first 50 real jobs.

---

## Out of scope (kept for future)

- Subscription model (still the v2 conversation, post-launch)
- Promo codes / first-time-user free credits — explicitly deferred per pricing-policy.md v1
- Per-region credit grant variation (Play handles SKU price localization; credit grants stay USD-denominated)
- Real-time fraud detection (TODO 11 is a daily reconciliation; real-time is a v2 problem)
