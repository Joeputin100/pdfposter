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
// SKU ladder rescaled upward 2026-05-02 once live FAL pricing showed
// per-megapixel cost — the original $1.99 entry tier couldn't profitably
// issue even one credit at realistic Topaz output sizes. New floor: $4.99.
const SKU_PRICES_USD: Record<string, number> = {
  credits_small: 4.99, credits_medium: 9.99, credits_large: 19.99, credits_jumbo: 39.99,
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
    // FAIL CLOSED. Confirmed against the live API (2026-05-02): FAL returns
    // unit="megapixels" at $0.01/MP for fal-ai/topaz/upscale/image. Treating
    // that as $/image would massively under-cost credits (84 credits per
    // $1.99 SKU vs. the intended 16) and ship a guaranteed-loss product.
    // Until pricing.ts grows a per-MP cost model that multiplies by an
    // expected output area, throwing here forces refreshPricing to reuse
    // the last known-good cached value rather than corrupt pricing/current.
    throw new Error(
      `FAL Topaz pricing unit is "${item.unit}" (expected "image"). ` +
        `Credit-cost math needs a megapixel multiplier — see TODO 10 / ` +
        `Phase G economics revision before deploying this fetch.`,
    );
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
