import * as functions from 'firebase-functions/v2';
import { onSchedule } from 'firebase-functions/v2/scheduler';
import { logger } from 'firebase-functions/v2';
import { getFirestore } from 'firebase-admin/firestore';
import { defineSecret } from 'firebase-functions/params';
import axios from 'axios';

const FAL_KEY = defineSecret('FAL_KEY');

// Topaz Gigapixel endpoint — same ID hit by upscale.ts (FAL_QUEUE_URL there
// posts to https://queue.fal.run/<TOPAZ_ENDPOINT_ID>). Keep the two in sync.
const TOPAZ_ENDPOINT_ID = 'fal-ai/topaz/upscale/image';
const FAL_PRICING_URL = 'https://api.fal.ai/v1/models/pricing';

const SKUS = ['credits_small', 'credits_medium', 'credits_large', 'credits_jumbo'] as const;
const SKU_PRICES_USD: Record<string, number> = {
  credits_small: 1.99, credits_medium: 4.99, credits_large: 9.99, credits_jumbo: 19.99,
};
const PLAY_FEE_RATE = 0.15;
const TARGET_GROSS_MARGIN = 0.50;

interface PricingDoc {
  costPerCreditUsd: number;
  products: Array<{ id: string; credits: number; priceUsd: number }>;
  updatedAt: FirebaseFirestore.Timestamp;
  falModelCostUsd: number;
}

interface FalPriceItem {
  endpoint_id: string;
  unit_price: number;
  unit: string;     // typically "image" for Topaz; could be "megapixel" etc.
  currency: string; // ISO 4217
}

interface FalPricingResponse {
  prices: FalPriceItem[];
  next_cursor: string | null;
  has_more: boolean;
}

async function fetchFalCostPerCredit(falKey: string): Promise<number> {
  const resp = await axios.get<FalPricingResponse>(FAL_PRICING_URL, {
    params: { endpoint_id: TOPAZ_ENDPOINT_ID },
    headers: { Authorization: `Key ${falKey}` },
    timeout: 15_000,
    validateStatus: () => true,
  });
  if (resp.status >= 400) {
    throw new Error(`FAL pricing API ${resp.status}: ${JSON.stringify(resp.data)}`);
  }
  // Audit log — FAL docs say "output-based pricing with proportional
  // adjustments for resolution/length", so if `unit` ever shifts off "image"
  // the credit math will need a multiplier. We want this raw payload in
  // Cloud Logging to catch that.
  logger.info('FAL pricing response', { response: resp.data });

  const item = resp.data.prices?.find((p) => p.endpoint_id === TOPAZ_ENDPOINT_ID);
  if (!item) {
    throw new Error(`FAL pricing API returned no entry for ${TOPAZ_ENDPOINT_ID}`);
  }
  if (item.currency !== 'USD') {
    throw new Error(`FAL pricing returned currency=${item.currency}, expected USD`);
  }
  if (!Number.isFinite(item.unit_price) || item.unit_price <= 0) {
    throw new Error(`FAL pricing returned invalid unit_price=${item.unit_price}`);
  }
  if (item.unit !== 'image') {
    // Don't fail — credit math still works as a per-unit cost — but flag
    // for human review so we can decide whether to add a megapixel multiplier.
    logger.warn('FAL Topaz pricing unit is not "image"; credit math may need a multiplier', {
      unit: item.unit,
      unit_price: item.unit_price,
    });
  }
  return item.unit_price;
}

async function fetchLastKnownCost(): Promise<number | null> {
  const snap = await getFirestore().doc('pricing/current').get();
  if (!snap.exists) return null;
  const v = snap.data()?.falModelCostUsd;
  return typeof v === 'number' && v > 0 ? v : null;
}

function computeCreditsForPrice(priceUsd: number, costPerCredit: number): number {
  const netRevenue = priceUsd * (1 - PLAY_FEE_RATE);
  const costBudget = netRevenue * (1 - TARGET_GROSS_MARGIN);
  return Math.floor(costBudget / costPerCredit);
}

export const getPricing = functions.https.onRequest(async (req, res) => {
  const db = getFirestore();
  const doc = await db.doc('pricing/current').get();
  if (!doc.exists) {
    res.status(503).json({ error: 'pricing not yet computed' });
    return;
  }
  res.json(doc.data());
});

export const refreshPricing = onSchedule(
  { schedule: '0 6 * * *', timeZone: 'UTC', secrets: [FAL_KEY] },
  async () => {
    let cost: number;
    try {
      cost = await fetchFalCostPerCredit(FAL_KEY.value());
    } catch (err) {
      // Don't let a transient FAL outage wipe pricing/current. Reuse the
      // last value we successfully wrote so the app keeps serving credits
      // at the established rate; surface a warning for ops to investigate.
      const cached = await fetchLastKnownCost();
      if (cached === null) {
        logger.error('FAL pricing fetch failed and no cached value to fall back on', {
          err: String(err),
        });
        throw err;
      }
      logger.warn('FAL pricing fetch failed; reusing cached falModelCostUsd', {
        err: String(err),
        cached,
      });
      cost = cached;
    }
    const products = SKUS.map((id) => ({
      id,
      credits: computeCreditsForPrice(SKU_PRICES_USD[id], cost),
      priceUsd: SKU_PRICES_USD[id],
    }));
    await getFirestore().doc('pricing/current').set({
      costPerCreditUsd: cost,
      falModelCostUsd: cost,
      products,
      updatedAt: new Date(),
    } as unknown as Partial<PricingDoc>);
  }
);
