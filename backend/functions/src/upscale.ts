// EXPORT NEEDED in index.ts:
//   export { requestUpscale, getUpscaleStatus } from './upscale';
//
// Phase G Task G9 — AI upscale (FAL.ai → Topaz Gigapixel) Cloud Functions.
// Two callable endpoints:
//   - requestUpscale({ tier, inputUrl })   → { txId }
//   - getUpscaleStatus({ txId })           → upscaleTransactions doc
//
// Schema: writes to /upscaleTransactions/{txId} (per G5 schema reconciliation),
// NOT /upscaleJobs/. Credits live on users/{uid}.credits with a /creditLog
// subcollection. Anonymous Firebase Auth users are blocked.

import { onCall, HttpsError, CallableRequest } from 'firebase-functions/v2/https';
import { defineSecret } from 'firebase-functions/params';
import { logger } from 'firebase-functions/v2';
import { getFirestore, FieldValue, Timestamp } from 'firebase-admin/firestore';
import { getStorage } from 'firebase-admin/storage';
import axios from 'axios';

const FAL_KEY = defineSecret('FAL_KEY');

// Phase H upscale models — each has its own FAL endpoint + pricing shape.
// Mirror of the client-side enum in LowDpiUpgradeModal.kt.
type UpscaleModel = 'topaz_4x' | 'topaz_8x' | 'recraft' | 'aurasr' | 'esrgan';

interface RequestUpscaleInput {
  modelId: UpscaleModel;
  inputUrl: string;
  /** Megapixels of the source image (client-computed). */
  inputMp: number;
}

interface GetStatusInput {
  txId: string;
}

// 1 credit = 1¢ retail. Cost-per-credit budget at 50% margin: $0.00425.
// Mirror of pricing.ts CREDIT_COST_BUDGET_USD; keep in sync.
// See docs/superpowers/plans/2026-05-03-phase-h-rc3-polish.md (H-P1.10c).
const CREDIT_COST_BUDGET_USD = 0.00425;

interface ModelSpec {
  endpoint: string;          // FAL endpoint slug
  scale: number;             // linear upscale factor
  costFn: (outputMp: number) => number;   // returns COGS in USD
  body: (imageUrl: string) => Record<string, unknown>;
}

const MODELS: Record<UpscaleModel, ModelSpec> = {
  topaz_4x: {
    endpoint: 'fal-ai/topaz/upscale/image',
    scale: 4,
    costFn: (mp) => mp * 0.01,                        // $0.01/MP output
    body: (url) => ({ image_url: url, upscale_factor: 4 }),
  },
  topaz_8x: {
    endpoint: 'fal-ai/topaz/upscale/image',
    scale: 8,
    costFn: (mp) => mp * 0.01,
    body: (url) => ({ image_url: url, upscale_factor: 8 }),
  },
  recraft: {
    endpoint: 'fal-ai/recraft/upscale/crisp',
    scale: 4,                                         // approx; flat-rate per image
    costFn: () => 0.004,                              // flat $0.004/image
    body: (url) => ({ image_url: url }),
  },
  aurasr: {
    endpoint: 'fal-ai/aura-sr',
    scale: 4,
    // ~1 second per output MP empirically; $0.00125/sec
    costFn: (mp) => mp * 0.00125,
    body: (url) => ({ image_url: url, upscaling_factor: 4 }),
  },
  esrgan: {
    endpoint: 'fal-ai/esrgan',
    scale: 4,
    // ~1 second per output MP empirically; $0.00111/sec
    costFn: (mp) => mp * 0.00111,
    body: (url) => ({ image_url: url, scale: 4, model: 'RealESRGAN_x4plus' }),
  },
};

/**
 * Charge for a job using the model-specific COGS curve, converted to credits
 * at the universal $0.00425 cost budget. ceil() so we never under-charge.
 */
function computeCreditsForJob(modelId: UpscaleModel, inputMp: number): number {
  const spec = MODELS[modelId];
  const outputMp = inputMp * spec.scale * spec.scale;
  const cogs = spec.costFn(outputMp);
  return Math.ceil(cogs / CREDIT_COST_BUDGET_USD);
}

const POLL_TIMEOUT_MS = 60_000; // 60s inline poll, then return for client polling
const POLL_INTERVAL_MS = 2_000;

function assertSignedIn(request: CallableRequest<unknown>): string {
  const auth = request.auth;
  if (!auth) {
    throw new HttpsError('unauthenticated', 'sign-in required');
  }
  const provider = (auth.token?.firebase as { sign_in_provider?: string } | undefined)
    ?.sign_in_provider;
  if (provider === 'anonymous') {
    throw new HttpsError('permission-denied', 'anonymous users cannot AI-upscale');
  }
  return auth.uid;
}

function assertModel(m: unknown): UpscaleModel {
  if (m === 'topaz_4x' || m === 'topaz_8x' || m === 'recraft' ||
      m === 'aurasr' || m === 'esrgan') return m;
  throw new HttpsError(
    'invalid-argument',
    'modelId must be one of: topaz_4x, topaz_8x, recraft, aurasr, esrgan',
  );
}

function assertInputUrl(u: unknown): string {
  if (typeof u !== 'string' || u.length === 0) {
    throw new HttpsError('invalid-argument', 'inputUrl is required');
  }
  return u;
}

function assertInputMp(m: unknown): number {
  // Sanity bound (1000 MP) is anti-abuse, NOT a user cap — no consumer
  // camera shoots more than ~150 MP. The user spec is "no cap"; this just
  // catches obvious injection attempts. Server-side dimension verification
  // (TODO 11 / G-R9) closes the loop after FAL responds.
  if (typeof m !== 'number' || !Number.isFinite(m) || m <= 0 || m > 1000) {
    throw new HttpsError('invalid-argument', 'inputMp must be a positive number ≤ 1000');
  }
  return m;
}

/**
 * Atomically debit `required` credits from users/{uid} and create a pending
 * upscaleTransactions doc. Returns the new txId.
 *
 * Throws HttpsError('failed-precondition', 'insufficient credits') if the
 * user does not have enough credits.
 */
async function debitAndCreateTx(
  uid: string,
  modelId: UpscaleModel,
  inputUrl: string,
  inputMp: number,
  required: number,
): Promise<string> {
  const db = getFirestore();
  const userRef = db.collection('users').doc(uid);
  const txRef = db.collection('upscaleTransactions').doc();
  const burnLogRef = userRef.collection('creditLog').doc();

  await db.runTransaction(async (t) => {
    const userSnap = await t.get(userRef);
    const credits = Number(userSnap.exists ? (userSnap.data()?.credits ?? 0) : 0);
    if (credits < required) {
      throw new HttpsError('failed-precondition', 'insufficient credits');
    }
    t.set(
      userRef,
      {
        credits: FieldValue.increment(-required),
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
    t.set(burnLogRef, {
      type: 'burn',
      amount: -required,
      modelId,
      txId: txRef.id,
      timestamp: FieldValue.serverTimestamp(),
    });
    t.set(txRef, {
      uid,
      modelId,
      inputUrl,
      claimedInputMp: inputMp,
      status: 'pending',
      creditsCost: required,
      createdAt: FieldValue.serverTimestamp(),
    });
  });

  return txRef.id;
}

/**
 * Refund `required` credits to users/{uid}, log the refund, and mark the
 * upscaleTransactions doc as failed. Idempotent: a second invocation will
 * see status='failed' and skip the refund.
 */
async function refundAndFail(
  uid: string,
  txId: string,
  required: number,
  errorMessage: string,
): Promise<void> {
  const db = getFirestore();
  const userRef = db.collection('users').doc(uid);
  const txRef = db.collection('upscaleTransactions').doc(txId);
  const refundLogRef = userRef.collection('creditLog').doc();

  await db.runTransaction(async (t) => {
    const txSnap = await t.get(txRef);
    if (!txSnap.exists) {
      logger.warn('refundAndFail: tx missing', { txId });
      return;
    }
    const txData = txSnap.data()!;
    if (txData.status === 'failed' || txData.status === 'refunded') {
      // Idempotent: already refunded.
      return;
    }
    t.set(
      userRef,
      {
        credits: FieldValue.increment(required),
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
    t.set(refundLogRef, {
      type: 'refund',
      amount: required,
      modelId: txData.modelId,
      txId,
      timestamp: FieldValue.serverTimestamp(),
      reason: errorMessage.substring(0, 500),
    });
    t.set(
      txRef,
      {
        status: 'failed',
        error: errorMessage.substring(0, 1000),
        completedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
  });
}

/**
 * Translate a Firebase Storage gs:// URL into a signed HTTPS URL that FAL can
 * fetch. If the URL is already https://, returned unchanged.
 */
async function resolveFetchableUrl(inputUrl: string): Promise<string> {
  if (inputUrl.startsWith('https://') || inputUrl.startsWith('http://')) {
    return inputUrl;
  }
  if (!inputUrl.startsWith('gs://')) {
    throw new HttpsError('invalid-argument', 'inputUrl must be gs:// or https://');
  }
  const without = inputUrl.substring('gs://'.length);
  const slash = without.indexOf('/');
  if (slash <= 0) {
    throw new HttpsError('invalid-argument', 'malformed gs:// URL');
  }
  const bucketName = without.substring(0, slash);
  const objectPath = without.substring(slash + 1);
  const [signed] = await getStorage()
    .bucket(bucketName)
    .file(objectPath)
    .getSignedUrl({
      action: 'read',
      expires: Date.now() + 30 * 60 * 1000, // 30 min
    });
  return signed;
}

interface FalSubmitResponse {
  request_id?: string;
  status_url?: string;
  response_url?: string;
  // Sometimes FAL returns the result inline if quick:
  image?: { url: string };
  images?: Array<{ url: string }>;
}

interface FalStatusResponse {
  status?: 'IN_QUEUE' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED' | string;
  // Result payload shape varies; try a few common keys.
  image?: { url: string };
  images?: Array<{ url: string }>;
  output?: { url?: string; image?: { url: string } };
  error?: string | { message?: string };
}

// Submit a FAL job using the per-model spec. Each model has its own endpoint
// + body shape; see MODELS map above. Verified shapes against live API
// 2026-05-03 in the disco_chicken bake.
async function submitFalJob(
  modelId: UpscaleModel,
  imageUrl: string,
  apiKey: string,
): Promise<FalSubmitResponse> {
  const spec = MODELS[modelId];
  const body = spec.body(imageUrl);
  const resp = await axios.post<FalSubmitResponse>(
    `https://queue.fal.run/${spec.endpoint}`,
    body,
    {
      headers: {
        Authorization: `Key ${apiKey}`,
        'Content-Type': 'application/json',
      },
      timeout: 30_000,
      validateStatus: () => true,
    },
  );
  if (resp.status >= 400) {
    throw new Error(`FAL submit failed: ${resp.status} ${JSON.stringify(resp.data)}`);
  }
  return resp.data;
}

async function pollFalJob(
  statusUrl: string,
  apiKey: string,
  deadlineMs: number,
): Promise<FalStatusResponse | null> {
  while (Date.now() < deadlineMs) {
    const resp = await axios.get<FalStatusResponse>(statusUrl, {
      headers: { Authorization: `Key ${apiKey}` },
      timeout: 15_000,
      validateStatus: () => true,
    });
    if (resp.status >= 400) {
      throw new Error(`FAL poll failed: ${resp.status} ${JSON.stringify(resp.data)}`);
    }
    const data = resp.data;
    const status = data.status;
    if (status === 'COMPLETED') return data;
    if (status === 'FAILED') {
      const errMsg =
        typeof data.error === 'string'
          ? data.error
          : data.error?.message ?? 'FAL job failed';
      throw new Error(errMsg);
    }
    await new Promise((r) => setTimeout(r, POLL_INTERVAL_MS));
  }
  return null; // timed out — caller decides what to do
}

function extractOutputUrl(payload: FalStatusResponse | FalSubmitResponse): string | null {
  // FAL_TODO: verify exact result shape. Try a few known keys.
  const anyPayload = payload as FalStatusResponse;
  if (anyPayload.image?.url) return anyPayload.image.url;
  if (anyPayload.images && anyPayload.images.length > 0 && anyPayload.images[0].url) {
    return anyPayload.images[0].url;
  }
  if (anyPayload.output?.url) return anyPayload.output.url;
  if (anyPayload.output?.image?.url) return anyPayload.output.image.url;
  return null;
}

async function downloadAndStoreOutput(
  outputUrl: string,
  uid: string,
  txId: string,
): Promise<string> {
  const resp = await axios.get<ArrayBuffer>(outputUrl, {
    responseType: 'arraybuffer',
    timeout: 60_000,
    validateStatus: () => true,
  });
  if (resp.status >= 400) {
    throw new Error(`output download failed: ${resp.status}`);
  }
  const buf = Buffer.from(resp.data);
  const bucket = getStorage().bucket();
  const objectPath = `upscaled/${uid}/${txId}.png`;
  await bucket.file(objectPath).save(buf, {
    contentType: 'image/png',
    resumable: false,
    metadata: { cacheControl: 'private, max-age=86400' },
  });
  return `gs://${bucket.name}/${objectPath}`;
}

export const requestUpscale = onCall(
  {
    region: 'us-central1',
    timeoutSeconds: 120,
    memory: '512MiB',
    secrets: [FAL_KEY],
  },
  async (request) => {
    const uid = assertSignedIn(request);
    const data = (request.data ?? {}) as Partial<RequestUpscaleInput>;
    const modelId = assertModel(data.modelId);
    const inputUrl = assertInputUrl(data.inputUrl);
    const inputMp = assertInputMp(data.inputMp);
    const required = computeCreditsForJob(modelId, inputMp);

    // 1. Debit credits + create tx atomically.
    const txId = await debitAndCreateTx(uid, modelId, inputUrl, inputMp, required);

    // 2. Outside the transaction, kick off FAL.
    try {
      const fetchableUrl = await resolveFetchableUrl(inputUrl);
      const submit = await submitFalJob(modelId, fetchableUrl, FAL_KEY.value());

      // Some quick jobs return inline.
      let resultPayload: FalStatusResponse | FalSubmitResponse | null =
        extractOutputUrl(submit) ? submit : null;

      if (!resultPayload) {
        const statusUrl = submit.status_url ?? submit.response_url;
        if (!statusUrl) {
          throw new Error('FAL submit returned no status_url and no inline result');
        }
        // Persist the FAL request ID for debugging / future webhook reconciliation.
        await getFirestore()
          .collection('upscaleTransactions')
          .doc(txId)
          .set(
            { falRequestId: submit.request_id ?? null, falStatusUrl: statusUrl },
            { merge: true },
          );

        const deadline = Date.now() + POLL_TIMEOUT_MS;
        const finalStatus = await pollFalJob(statusUrl, FAL_KEY.value(), deadline);
        if (!finalStatus) {
          // Inline timeout. Mark as in-progress; client will poll getUpscaleStatus.
          await getFirestore()
            .collection('upscaleTransactions')
            .doc(txId)
            .set({ status: 'in_progress' }, { merge: true });
          return { txId };
        }
        resultPayload = finalStatus;
      }

      const outputUrl = extractOutputUrl(resultPayload);
      if (!outputUrl) {
        throw new Error('FAL completed but returned no output URL');
      }

      // 3. Download + restore in our own bucket.
      const storedUrl = await downloadAndStoreOutput(outputUrl, uid, txId);
      await getFirestore()
        .collection('upscaleTransactions')
        .doc(txId)
        .set(
          {
            status: 'succeeded',
            outputUrl: storedUrl,
            completedAt: FieldValue.serverTimestamp(),
          },
          { merge: true },
        );
      return { txId };
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      logger.error('upscale failed; refunding', { uid, txId, msg });
      await refundAndFail(uid, txId, required, msg);
      // Surface a generic failure to client; they can poll for details.
      throw new HttpsError('internal', `upscale failed: ${msg}`);
    }
  },
);

export const getUpscaleStatus = onCall(
  {
    region: 'us-central1',
    timeoutSeconds: 30,
    memory: '256MiB',
  },
  async (request) => {
    const uid = assertSignedIn(request);
    const data = (request.data ?? {}) as Partial<GetStatusInput>;
    const txId = data.txId;
    if (typeof txId !== 'string' || txId.length === 0) {
      throw new HttpsError('invalid-argument', 'txId is required');
    }
    const snap = await getFirestore().collection('upscaleTransactions').doc(txId).get();
    if (!snap.exists) {
      throw new HttpsError('not-found', 'upscale tx not found');
    }
    const docData = snap.data()!;
    if (docData.uid !== uid) {
      // Treat foreign reads as not-found to avoid leaking existence.
      throw new HttpsError('not-found', 'upscale tx not found');
    }
    // Convert Timestamps to ISO for the wire.
    const out: Record<string, unknown> = { id: snap.id };
    for (const [k, v] of Object.entries(docData)) {
      out[k] = v instanceof Timestamp ? v.toDate().toISOString() : v;
    }
    return out;
  },
);
