# Pricing Policy — pdfposter IAP + AI Upscaling

**Status:** v1, drafted alongside Phase G of the MD3E redesign (2026-05-02).

## Architectural principle

**Play Store SKU prices are fixed forever once published. Credit amounts per SKU are dynamic, computed server-side at purchase time.**

This decouples customer-facing price anchors (which Google Play won't let us change without new SKUs) from cost-of-goods (which fluctuates with FAL.ai / Topaz Gigapixel pricing).

## Tier model

Two upscale tiers, both gated by credits. **Credit cost varies with output area** (revised 2026-05-02 — see `plans/2026-05-02-phase-g-economics-revision.md`):

```
output_mp = input_mp × scale²        # 4× → 16× area, 8× → 64× area
credits   = ceil(output_mp / 5)      # 1 credit = 5 MP of FAL output capacity
```

| Input | 4× output (MP) | 4× cost | 8× output (MP) | 8× cost |
|---|---|---|---|---|
| 4 MP | 64 MP | 13 credits | 256 MP | 52 credits |
| 6 MP | 96 MP | 20 credits | 384 MP | 77 credits |
| 12 MP (typical phone) | 192 MP | 39 credits | 768 MP | 154 credits |
| 24 MP | 384 MP | 77 credits | 1536 MP | 308 credits |
| 50 MP (S25 / 16 Pro ProRAW) | 800 MP | 160 credits | 3200 MP | 640 credits |

The client surfaces the credit count + USD-equivalent before the user commits, so variable cost stays predictable from their side. **No input cap.** Server-side dimension verification (TODO 11) catches misreporting after FAL responds.

On-device upscaling (TFLite ESRGAN x4) is **always free**, no limits, no watermark, works anonymously, works offline — gated only by device RAM (32-bit + low-memory devices may not be capable; the client checks before offering).

**3rd-party upscale is free** — users can bring an image already upscaled by Canva, OpenArt, Topaz Photo AI, Magnific, or anything else. The PosterPDF poster pipeline is free with any input. AI upscale credits only cover the FAL inference cost.

## SKU ladder

Four consumable SKUs published once in Play Console (Phase H redenomination 2026-05-03):

| SKU ID | Price (USD) | Base credits | Bonus credits | Total | Bonus % |
|---|---|---|---|---|---|
| `credits_starter` | $1.99 | 199 | 0 | **199** | 0% |
| `credits_small` | $4.99 | 499 | 25 | **524** | 5% |
| `credits_medium` | $9.99 | 999 | 75 | **1,074** | 7.5% |
| `credits_large` | $19.99 | 1,999 | 200 | **2,199** | 10% |

These prices **never change**. Bonus credits are explicitly designed to nudge cash flow (incentivise larger packs at lower per-credit cost) at the deliberate trade-off of slightly lower margin on the top tier.

> **History — denomination revisions:**
> - **2026-05-02 (Phase G economics revision):** scrapped the original `$0.05/credit` placeholder once live FAL pricing showed Topaz at `$0.01/MP`. Briefly used a 4-tier `small/medium/large/jumbo` ladder at $4.99/$9.99/$19.99/$39.99 with credits derived from per-MP cost.
> - **2026-05-03 (Phase H credit denomination):** redenominated to `1 credit = 1¢` retail with tiered bonus structure. Replaced the per-MP credit math with per-model COGS lookup (Topaz, Recraft Crisp, AuraSR, ESRGAN) since the multi-model lineup needed differentiation that the per-MP model couldn't provide. Re-introduced the $1.99 entry tier as `credits_starter`.

## Credit grant formula

```
1 credit                = $0.01 retail (always)
revenue_net (Play 15%)  = $0.0085 per credit
COGS budget (50% margin)= $0.00425 per credit  ← server-side constant CREDIT_COST_BUDGET_USD
credits granted         = base + bonus (table above; not derived from FAL pricing)
```

Per-call upscale credits are computed at submit time using the per-model COGS curve:

```
credits_charged = ceil(model.cogs(input_mp) / 0.00425)
```

where `model.cogs(input_mp)` reads from the `MODELS` map in `backend/functions/src/upscale.ts`:
- **Topaz 4×**: `output_mp × $0.01` (= input_mp × 16 × $0.01)
- **Topaz 8×**: `output_mp × $0.01` (= input_mp × 64 × $0.01)
- **Recraft Crisp**: `$0.004` flat per image
- **AuraSR**: `output_mp × $0.00125` (≈ 1 sec/MP × $0.00125/sec)
- **ESRGAN**: `output_mp × $0.00111` (≈ 1 sec/MP × $0.00111/sec)

**Worked example — disco_chicken (1024² → 4× = 4096²):**

| Model | COGS | Credits charged | Effective retail |
|---|---|---|---|
| Topaz 4× | $0.168 | **40 credits** | $0.40 |
| AuraSR | $0.013 | 4 credits | $0.04 |
| ESRGAN | $0.011 | 3 credits | $0.03 |
| Recraft Crisp | $0.004 | 1 credit | $0.01 |
| Free (on-device) | $0 | 0 credits | $0 |

50% gross margin holds at every output size because credits are charged proportionally to FAL cost.

## Cloud storage retention

Phase H-P3 — bounds storage growth at the cost of a tiny per-file fee.

| Tier | Where | Duration | Cost to user |
|---|---|---|---|
| History list (metadata) | Firestore `users/{uid}/history/{historyId}` | Indefinite | Free |
| Local PDF cache | Device storage | Until app uninstalled | Free |
| Cloud PDF (default — paid mode) | GCS `gs://.../user-pdfs/{uid}/{historyId}.pdf` | First 30 days free | Free, then 1 credit/file/month (~1¢) |
| Cloud PDF (grace period) | Same | 30 more days after credit balance hits 0 | Free + email + push warning |
| Cloud PDF (auto-delete opt-in) | Same | 30 days exactly | Free, then deleted with no charge |

Auto-delete vs paid is set per-user via the **Cloud storage…** entry in the Settings hamburger (`storageRetentionMode` field on `users/{uid}`; default `paid`).

The 1-credit-per-file-per-month rate is an honest 50% margin: a typical 20 MB PDF costs us ~$0.0004/month at GCS standard rates; the 1-credit charge nets $0.00425 cost budget. Egress + index overhead easily fit inside the buffer.

The fallbacks in `CreditPricing.kt` / `PurchaseSheet.kt` carry slightly rounder numbers (40 / 85 / 180 / 380) for offline display; production grants come from the `refreshPricing` cron writing `/pricing/current` daily.

## Dynamic pricing flow

1. **Daily cron** (`backend/functions/src/pricing.ts:refreshPricing`) fetches current FAL / Topaz API rates, computes credits-per-SKU at the 50% margin, writes to Firestore `/pricing/current`.
2. **Client** (`CreditPricing.kt`) reads `/pricing/current` when the upscale modal opens; displays "$X → N credits" for each SKU.
3. **At purchase time**, server-side `redeemPurchase` reads `/pricing/current` again and grants the credit count *as of that moment*. Race-safe via Firestore transaction.
4. **Existing balance** is never re-priced. Only new purchases see new ratios.

## Account model

| User type | Anonymous Firebase Auth | Linked to Google sign-in |
|---|---|---|
| On-device upscaling | ✅ unlimited free | ✅ unlimited free |
| Server-side history | ❌ device-local only | ✅ cross-device, durable |
| Buy credits | ❌ blocked at modal | ✅ |
| AI upscale | ❌ blocked at modal | ✅ |
| Refund handling | n/a | ✅ via RTDN webhook |

When an anonymous user buys credits, they're prompted to link a Google account first. The link transfers any local history into the server-side store; subsequent credit grants attach to the Google UID.

**Future**: email/password as a second sign-in option (after Google works).

**No free trial AI upscale at launch.** Reconsider only after revenue justifies the loss leader cost.

## Anti-abuse

- **Anonymous credit-grant cycling** (uninstall → reinstall to reset free-tier counters): not applicable since on-device upscaling is unconditionally free.
- **Anonymous → Google merge as credit theft**: server refuses to merge if the target Google UID already has a `/users/{uid}` document. Forces merges to be a clean transition from anon to Google, never a swap.
- **Receipt forgery**: every purchase is server-verified via Google Play Developer API before credit grant. Client-only purchase claims are rejected.
- **Refund handling**: Real-Time Developer Notifications (RTDN) webhook decrements the user's balance when Play Console processes a refund. If the user has spent the credit before the refund: balance goes negative until next purchase (industry-standard policy).

## Pricing change policy

If FAL pricing changes:
- **Drop**: keep credits-per-SKU constant initially (margin lift). Optionally pass savings to users via in-app announcement and a credits-per-SKU bump.
- **Rise**: reduce credits-per-SKU on the next daily cron tick. Existing balance unaffected. New purchases see the new ratio.
- **Major shift (>30%)**: review the entire 50%-margin formula; the constant in the math may need adjustment.

## What this policy does NOT cover

- Subscription model (not in v1; consider for v2 once consumable behavior is observed)
- Regional pricing (Play handles this automatically per local market)
- Promo codes / Vouchers (Play Console supports; not implemented in v1 client)
- Refund disputes outside Play Store (not applicable; all transactions go through Play)
