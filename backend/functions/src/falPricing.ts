// rc83: live FAL pricing service. fal.ai repriced 4/5 of our models between
// May and July 2026 (Topaz stayed $0.01/MP per the billing API despite the
// marketing page's tier table; AuraSR/ESRGAN/CCSR moved to per-compute-second)
// — so hardcoded COGS tables silently drift. This module makes fal's own
// billing metadata (GET api.fal.ai/v1/models/pricing) the source of truth:
//
//   - cached in Firestore `pricing/falLive` so the hot path stays fast
//   - the CHARGE path refreshes any cache older than 60s (accurate-to-the-
//     minute requirement), the UX path tolerates 10 minutes
//   - a 404 from the pricing API marks the model unavailable → the client
//     hides its card and requestUpscale rejects before debiting
//   - compute-second models are quoted as unitPrice × (secBase + secPerMp ×
//     outputMp); the curve is seeded from warm-run calibration (2026-07-03)
//     plus ~3s cold-start amortization, then self-corrects via an EMA over
//     the inference_time metric of real completed jobs.
//
// Verified 2026-07-03: the REGULAR FAL_KEY can read the pricing API (no
// admin key needed).

import { logger } from 'firebase-functions/v2';
import { getFirestore, FieldValue, type Firestore } from 'firebase-admin/firestore';
import axios from 'axios';

export type PriceUnit = 'megapixels' | 'compute seconds' | 'images';

export interface LiveModelPrice {
  endpointId: string;
  available: boolean;
  unit: PriceUnit | null;
  unitPrice: number | null;
  /** compute-second models only: predicted seconds = secBase + secPerMp*outputMp */
  secBase?: number;
  secPerMp?: number;
  obsCount?: number;
}

export interface LivePricingDoc {
  fetchedAtMs: number;
  models: Record<string, LiveModelPrice>;
}

// Warm-run calibration 2026-07-03 (512²→ and 1024²→ jobs, 2nd-run timings)
// + 3s cold-start amortization baked into secBase. Only meaningful for
// models whose live unit is 'compute seconds'.
const SEC_SEED: Record<string, { secBase: number; secPerMp: number }> = {
  aurasr: { secBase: 3.9, secPerMp: 0.21 },
  esrgan: { secBase: 4.1, secPerMp: 0.47 },
  ccsr: { secBase: 4.1, secPerMp: 1.63 },
};

const PRICING_DOC = 'pricing/falLive';
const PRICING_URL = 'https://api.fal.ai/v1/models/pricing';

async function fetchOne(endpointId: string, falKey: string): Promise<Partial<LiveModelPrice>> {
  try {
    const resp = await axios.get(PRICING_URL, {
      params: { endpoint_id: endpointId },
      headers: { Authorization: `Key ${falKey}` },
      timeout: 5_000,
      validateStatus: () => true,
    });
    if (resp.status === 404) return { available: false };
    if (resp.status >= 400) {
      logger.warn('fal pricing fetch error (keeping last known)', { endpointId, status: resp.status });
      return {};
    }
    const p = (resp.data?.prices ?? [])[0];
    if (!p?.unit || typeof p.unit_price !== 'number') return {};
    return { available: true, unit: p.unit as PriceUnit, unitPrice: p.unit_price };
  } catch (err) {
    logger.warn('fal pricing fetch threw (keeping last known)', { endpointId, err: String(err) });
    return {};
  }
}

/**
 * Return live pricing for the given model→endpoint map, refreshing the
 * Firestore cache when older than maxAgeMs. Partial fal outages degrade
 * gracefully: fetch failures keep the last known values, and callers fall
 * back to their static costFn when a model has no live data at all.
 */
export async function getLivePricing(
  endpoints: Record<string, string>,
  falKey: string,
  maxAgeMs: number,
  db: Firestore = getFirestore(),
): Promise<LivePricingDoc> {
  const ref = db.doc(PRICING_DOC);
  const snap = await ref.get();
  const existing = (snap.exists ? snap.data() : null) as LivePricingDoc | null;
  const age = existing ? Date.now() - existing.fetchedAtMs : Infinity;
  if (existing && age < maxAgeMs) return existing;

  const fetched = await Promise.all(
    Object.entries(endpoints).map(async ([modelId, endpointId]) => {
      const p = await fetchOne(endpointId, falKey);
      return [modelId, p] as const;
    }),
  );

  const models: Record<string, LiveModelPrice> = { ...(existing?.models ?? {}) };
  for (const [modelId, patch] of fetched) {
    const prev = models[modelId];
    const merged: LiveModelPrice = {
      endpointId: endpoints[modelId],
      available: prev?.available ?? true,
      unit: prev?.unit ?? null,
      unitPrice: prev?.unitPrice ?? null,
      obsCount: prev?.obsCount ?? 0,
      ...patch,
    };
    // Firestore rejects `undefined` values (and admin-SDK set() throws
    // SYNCHRONOUSLY on them, bypassing promise .catch) — only attach the
    // compute-second curve fields when they actually exist.
    const secBase = prev?.secBase ?? SEC_SEED[modelId]?.secBase;
    const secPerMp = prev?.secPerMp ?? SEC_SEED[modelId]?.secPerMp;
    if (secBase != null) merged.secBase = secBase;
    if (secPerMp != null) merged.secPerMp = secPerMp;
    models[modelId] = merged;
  }
  const doc: LivePricingDoc = { fetchedAtMs: Date.now(), models };
  try {
    await ref.set(doc);
  } catch (e) {
    logger.warn('falLive cache write failed', { e: String(e) });
  }
  return doc;
}

/** COGS in USD for a job, from live pricing. Returns null when the model has
 *  no usable live data (caller falls back to its static costFn). */
export function liveCostUsd(modelId: string, outputMp: number, p?: LiveModelPrice): number | null {
  if (!p?.unit || p.unitPrice == null) return null;
  switch (p.unit) {
    case 'megapixels': return p.unitPrice * outputMp;
    case 'images': return p.unitPrice;
    case 'compute seconds': {
      const base = p.secBase ?? 4;
      const perMp = p.secPerMp ?? 1;
      return p.unitPrice * (base + perMp * outputMp);
    }
    default: return null;
  }
}

/** EMA update of a compute-second model's throughput from a real job's
 *  billed inference_time. Fire-and-forget from the completion path. */
export async function recordObservedSeconds(
  modelId: string,
  outputMp: number,
  seconds: number,
  db: Firestore = getFirestore(),
): Promise<void> {
  if (!(outputMp > 0.5) || !(seconds > 0)) return;
  try {
    const ref = db.doc(PRICING_DOC);
    await db.runTransaction(async (t) => {
      const snap = await t.get(ref);
      const doc = snap.data() as LivePricingDoc | undefined;
      const m = doc?.models?.[modelId];
      if (!m || m.unit !== 'compute seconds') return;
      const base = m.secBase ?? 4;
      const observedPerMp = Math.max(0.01, (seconds - base) / outputMp);
      const alpha = 0.2;
      const blended = m.secPerMp != null
        ? m.secPerMp * (1 - alpha) + observedPerMp * alpha
        : observedPerMp;
      t.update(ref, {
        [`models.${modelId}.secPerMp`]: blended,
        [`models.${modelId}.obsCount`]: FieldValue.increment(1),
      });
    });
  } catch (err) {
    logger.warn('recordObservedSeconds failed (non-fatal)', { modelId, err: String(err) });
  }
}

/** Flag a model unavailable immediately (e.g. the submit endpoint 404'd). */
export async function markUnavailable(modelId: string, db: Firestore = getFirestore()): Promise<void> {
  await db.doc(PRICING_DOC)
    .set({ models: { [modelId]: { available: false } } }, { merge: true })
    .catch((e) => logger.warn('markUnavailable write failed', { e: String(e) }));
}
