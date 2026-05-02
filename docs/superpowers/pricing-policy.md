# Pricing Policy — pdfposter IAP + AI Upscaling

**Status:** v1, drafted alongside Phase G of the MD3E redesign (2026-05-02).

## Architectural principle

**Play Store SKU prices are fixed forever once published. Credit amounts per SKU are dynamic, computed server-side at purchase time.**

This decouples customer-facing price anchors (which Google Play won't let us change without new SKUs) from cost-of-goods (which fluctuates with FAL.ai / Topaz Gigapixel pricing).

## Tier model

Two upscale tiers, both gated by credits:

| Tier | Linear scale | Typical output | Cost in credits |
|---|---|---|---|
| Standard AI | 4× | ~3840×2160 (~8 MP) | **1 credit** |
| Premium AI | 8× (or 6× capped by Topaz) | ~7680×4320 (~33 MP) | **2 credits** |

On-device upscaling (TFLite Real-ESRGAN x4) is **always free**, no limits, no watermark, works anonymously, works offline. Gates: none.

## SKU ladder

Four consumable SKUs published once in Play Console:

| SKU ID | Price (USD) |
|---|---|
| `credits_small` | $4.99 |
| `credits_medium` | $9.99 |
| `credits_large` | $19.99 |
| `credits_jumbo` | $39.99 |

These prices **never change**. Local-currency conversions handled by Play Store automatically.

> **Rescale note (2026-05-02):** Original ladder was $1.99 / $4.99 / $9.99 / $19.99. Live check of FAL's `/v1/models/pricing` revealed Topaz Gigapixel is `$0.01/megapixel`, not `$0.05/image` as the original plan assumed. A 12 MP source upscaled 4× produces ~192 MP of output → ~$1.92 in FAL cost per single upscale. The $1.99 entry tier couldn't profitably issue even one credit; rescaled upward to $4.99 floor. The per-megapixel credit-cost model itself is still tracked as a Phase G economics revision (TODO 10) before the `refreshPricing` cron is unblocked.

## Credit grant formula

For revenue `R` (post-tax, pre-Play-fee), Google takes 15% (or 30% above $1M annual). Server-side cost-per-credit `C` (sum of FAL/Topaz fee + Firebase Storage retention).

```
revenue_net    = R × 0.85
target_profit  = revenue_net × 0.50    # 50% gross margin
cost_budget    = revenue_net × 0.50
credits        = floor(cost_budget / C)
```

**Worked example** at the rescaled ladder, evaluated against the *old* `C = $0.05/credit` placeholder for shape comparison only — the real `C` is per-megapixel and still being settled (see TODO 10):

| SKU | Price | Math | Credits granted | Effective $/credit |
|---|---|---|---|---|
| `credits_small` | $4.99 | floor(4.99 × 0.85 × 0.50 / 0.05) = floor(42.4) | **40** | $0.125 |
| `credits_medium` | $9.99 | floor(84.9) | **85** | $0.118 |
| `credits_large` | $19.99 | floor(169.9) | **180** | $0.111 |
| `credits_jumbo` | $39.99 | floor(339.9) | **380** | $0.105 |

Larger packs receive minor volume discounts via the floor() rounding asymmetry; tweakable per launch.

These credit grants are **fallback placeholders** (mirrored in `CreditPricing.kt` and `PurchaseSheet.kt`); production grants will come from the daily `refreshPricing` cron once a per-megapixel cost model lands. Until then the cron fail-closes (throws on `unit !== "image"`) and `pricing/current` is never written.

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
