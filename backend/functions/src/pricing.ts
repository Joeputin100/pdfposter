import * as functions from 'firebase-functions/v2';
import { onSchedule } from 'firebase-functions/v2/scheduler';
import { logger } from 'firebase-functions/v2';
import { getFirestore } from 'firebase-admin/firestore';

// Phase H credit denomination: 1 credit = 1¢ retail (always).
// COGS-per-credit budget at 50% margin = $0.01 × 0.85 (Play fee) × 0.50 = $0.00425.
// Per-call upscale credit charges happen in upscale.ts using getCogsUsd();
// pricing.ts is only responsible for writing the SKU table that grants credits
// at purchase time. Mirror this constant in upscale.ts and LowDpiUpgradeModal.kt.
//
// See docs/superpowers/plans/2026-05-03-phase-h-rc3-polish.md (H-P1.10c).
export const CREDIT_COST_BUDGET_USD = 0.00425;

// Tiered SKU ladder with bonus credits — increases cash flow at the high
// end at the cost of slightly lower per-pack margin.
interface Sku {
  id: string;
  priceUsd: number;
  baseCredits: number;
  bonusCredits: number;
}
const SKUS: Sku[] = [
  { id: 'credits_starter', priceUsd: 1.99, baseCredits: 199, bonusCredits: 0 },
  { id: 'credits_small',   priceUsd: 4.99, baseCredits: 499, bonusCredits: 25 },
  { id: 'credits_medium',  priceUsd: 9.99, baseCredits: 999, bonusCredits: 75 },
  { id: 'credits_large',   priceUsd: 19.99, baseCredits: 1999, bonusCredits: 200 },
];

interface PricingDoc {
  costPerCreditUsd: number;
  products: Array<{
    id: string;
    credits: number;       // total = baseCredits + bonusCredits
    baseCredits: number;
    bonusCredits: number;
    priceUsd: number;
    bonusPct: number;      // for UI display ("+10% bonus")
  }>;
  updatedAt: FirebaseFirestore.Timestamp;
}

export const getPricing = functions.https.onRequest(async (_req, res) => {
  const db = getFirestore();
  const doc = await db.doc('pricing/current').get();
  if (!doc.exists) {
    res.status(503).json({ error: 'pricing not yet computed' });
    return;
  }
  res.json(doc.data());
});

// refreshPricing now writes the static SKU table. It runs daily so the
// client always has a fresh copy in Firestore even if app code changes
// the SKU table; if/when bonus structure changes, this propagates within
// 24h without a redeploy.
export const refreshPricing = onSchedule(
  { schedule: '0 6 * * *', timeZone: 'UTC' },
  async () => {
    const products = SKUS.map((s) => ({
      id: s.id,
      credits: s.baseCredits + s.bonusCredits,
      baseCredits: s.baseCredits,
      bonusCredits: s.bonusCredits,
      priceUsd: s.priceUsd,
      bonusPct: s.baseCredits === 0 ? 0 :
        Math.round((s.bonusCredits / s.baseCredits) * 1000) / 10,
    }));
    await getFirestore().doc('pricing/current').set({
      costPerCreditUsd: CREDIT_COST_BUDGET_USD,
      products,
      updatedAt: new Date(),
    } as unknown as Partial<PricingDoc>);
    logger.info('refreshPricing wrote SKU table', { count: products.length });
  }
);
