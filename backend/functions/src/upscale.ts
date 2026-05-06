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
//
// RC3+ collapsed topaz_4x/topaz_8x → topaz; backend now picks the smallest
// scale factor that meets the target DPI (saves 5-10× cost on typical posters).
type UpscaleModel = 'topaz' | 'recraft' | 'aurasr' | 'esrgan' | 'ccsr';

interface RequestUpscaleInput {
  modelId: UpscaleModel;
  inputUrl: string;
  /** Megapixels of the source image (client-computed). */
  inputMp: number;
  /** RC3+ — DPI-aware scale picker inputs. */
  posterWidthInches: number;
  posterHeightInches: number;
  targetDpi: number;
  /** RC28 — Topaz "headroom" override. If set, pickScale will not pick
   *  a scale below this floor (clamped to the model's supported scales).
   *  Used by the Topaz detail card's "Above target" / "Maximum" buttons. */
  minScale?: number;
}

interface GetStatusInput {
  txId: string;
}

// 1 credit = 1¢ retail. Cost-per-credit budget at 50% margin: $0.00425.
// Mirror of pricing.ts CREDIT_COST_BUDGET_USD; keep in sync.
// See docs/superpowers/plans/2026-05-03-phase-h-rc3-polish.md (H-P1.10c).
const CREDIT_COST_BUDGET_USD = 0.00425;

interface ModelSpec {
  endpoint: string;
  /** Supported scale factors in ascending order — backend picks the smallest
   *  that produces output_mp ≥ target_mp. RC17 dropped a previous 1.2× crop
   *  headroom because Topaz's [2,4,6,8] supportedScales were forcing scale
   *  6 whenever scale 4 met the target exactly, billing the user for ~125%
   *  more output MP than the target DPI required. Real bleed/crop is <5%
   *  of dimensions; targeting target_mp exactly is the honest behavior. */
  supportedScales: number[];
  costFn: (outputMp: number) => number;   // returns COGS in USD
  body: (imageUrl: string, scale: number) => Record<string, unknown>;
}

// RC3+: every model body pins `output_format: 'png'` so FAL returns lossless
// pixels. AuraSR's JPEG default was producing visibly blurry/blocky output at
// 200+ MP poster sizes; pinning to PNG eliminates that loss. Models that don't
// expose the param (recraft, esrgan) silently ignore it on FAL's side.
const MODELS: Record<UpscaleModel, ModelSpec> = {
  topaz: {
    endpoint: 'fal-ai/topaz/upscale/image',
    supportedScales: [2, 4, 6, 8],
    costFn: (mp) => mp * 0.01,                        // $0.01/MP output
    body: (url, scale) => ({
      image_url: url,
      upscale_factor: scale,
      output_format: 'png',
    }),
  },
  recraft: {
    endpoint: 'fal-ai/recraft/upscale/crisp',
    supportedScales: [4],                             // flat-rate per image
    costFn: () => 0.004,
    body: (url) => ({ image_url: url, output_format: 'png' }),
  },
  aurasr: {
    endpoint: 'fal-ai/aura-sr',
    supportedScales: [4],
    // ~1 second per output MP empirically; $0.00125/sec
    costFn: (mp) => mp * 0.00125,
    body: (url) => ({
      image_url: url,
      upscaling_factor: 4,
      output_format: 'png',
    }),
  },
  esrgan: {
    endpoint: 'fal-ai/esrgan',
    supportedScales: [4],
    // ~1 second per output MP empirically; $0.00111/sec
    costFn: (mp) => mp * 0.00111,
    body: (url) => ({
      image_url: url,
      scale: 4,
      model: 'RealESRGAN_x4plus',
      output_format: 'png',
    }),
  },
  // RC29: CCSR — Cascaded Conditional Super-Resolution. Photo-faithful
  // (deterministic-ish, not a creative regenerator) but with a configurable
  // scale param like Topaz. Costs are GPU-second; rough budget ≈ AuraSR.
  // Output format param appears undocumented but FAL's body parser
  // ignores unknown fields, so pinning png is harmless.
  ccsr: {
    endpoint: 'fal-ai/ccsr',
    supportedScales: [2, 3, 4],
    costFn: (mp) => mp * 0.00125,
    body: (url, scale) => ({
      image_url: url,
      scale,
      output_format: 'png',
    }),
  },
};

/**
 * RC3+ / RC17 — pick smallest scale factor that produces enough pixels for
 * the target DPI on the user's poster size. Saves 5-10× FAL cost vs. always
 * 4× for typical phone-shot 12 MP photos printed at 24×19" / 150 DPI.
 *
 * targetMp = (posterW × DPI) × (posterH × DPI) / 1e6
 * outputMp(scale) = inputMp × scale²
 * Pick smallest scale where outputMp ≥ targetMp. RC17 dropped a previous
 * 1.2× headroom because it pushed Topaz's [2,4,6,8] from scale 4 → 6 even
 * when scale 4 met the target exactly, ~doubling the credit charge for no
 * functional benefit. If no scale meets the target, return the largest
 * available (best-effort).
 */
function pickScale(
  modelId: UpscaleModel,
  inputMp: number,
  posterW: number,
  posterH: number,
  targetDpi: number,
  minScale?: number,  // RC28: client-side override floor (Topaz "headroom" picker)
): number {
  const targetMp = (posterW * targetDpi) * (posterH * targetDpi) / 1_000_000;
  const scales = MODELS[modelId].supportedScales;
  let picked = scales[scales.length - 1];  // fallback if no scale meets target
  for (const s of scales) {
    if (inputMp * s * s >= targetMp) { picked = s; break; }
  }
  // RC28: client may request a minimum scale (e.g. "exceed target by one
  // step" or "always use max"). Floor to the smallest supported scale that
  // is >= the requested minimum. Doesn't permit going *below* what
  // pickScale would have chosen on its own — only above.
  if (minScale && minScale > picked) {
    const eligible = scales.filter((s) => s >= minScale);
    if (eligible.length > 0) picked = eligible[0];
    else picked = scales[scales.length - 1];
  }
  return picked;
}

/**
 * Charge for a job using the model-specific COGS curve at the picked scale.
 * ceil() so we never under-charge.
 */
function computeCreditsForJob(
  modelId: UpscaleModel,
  inputMp: number,
  scale: number,
): number {
  const spec = MODELS[modelId];
  const outputMp = inputMp * scale * scale;
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
  if (m === 'topaz' || m === 'recraft' || m === 'aurasr' || m === 'esrgan') return m;
  throw new HttpsError(
    'invalid-argument',
    'modelId must be one of: topaz, recraft, aurasr, esrgan',
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
// RC23: admin emails (and users with `admin=true` custom claim) bypass the
// credit check. Mirrors MainViewModel.kt's client-side `isAdmin` so the
// app feels consistent end-to-end. The list lives here in code rather
// than Firestore so changing it requires a redeploy (intentional —
// keeps the bypass observable in version history).
const ADMIN_EMAILS: ReadonlySet<string> = new Set([
  'joeputin100@gmail.com',
]);

function isAdminCaller(request: { auth?: { token?: Record<string, unknown> } }): boolean {
  const token = request.auth?.token ?? {};
  if ((token as { admin?: boolean }).admin === true) return true;
  const email = (token as { email?: string }).email;
  return typeof email === 'string' && ADMIN_EMAILS.has(email.toLowerCase());
}

async function debitAndCreateTx(
  uid: string,
  modelId: UpscaleModel,
  inputUrl: string,
  inputMp: number,
  required: number,
  isAdmin: boolean = false,
): Promise<string> {
  const db = getFirestore();
  const userRef = db.collection('users').doc(uid);
  const txRef = db.collection('upscaleTransactions').doc();
  const burnLogRef = userRef.collection('creditLog').doc();

  await db.runTransaction(async (t) => {
    const userSnap = await t.get(userRef);
    const credits = Number(userSnap.exists ? (userSnap.data()?.credits ?? 0) : 0);
    // RC23: admins bypass the insufficient-credits check + the actual debit.
    // The tx and creditLog still get written so admin upscales are
    // visible in the same audit trail as paid ones (just with
    // amount=0 for the burn entry instead of -required).
    if (!isAdmin && credits < required) {
      throw new HttpsError('failed-precondition', 'insufficient credits');
    }
    if (!isAdmin) {
      t.set(
        userRef,
        {
          credits: FieldValue.increment(-required),
          updatedAt: FieldValue.serverTimestamp(),
        },
        { merge: true },
      );
    }
    t.set(burnLogRef, {
      // RC23: admin runs are tagged 'admin_burn' so the audit trail
      // distinguishes them from regular burns (which would appear as
      // free-money in the user's history). Non-admin entries keep the
      // 'burn' type for backward compatibility with prior log readers.
      type: isAdmin ? 'admin_burn' : 'burn',
      amount: isAdmin ? 0 : -required,
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
      // RC32: switch from default v2 to v4 signed URLs. v2 is deprecated;
      // some downstream HTTP clients (FAL's Recraft worker hit a 422
      // image_load_error against v2 URLs from the .firebasestorage.app
      // bucket on 2026-05-06) handle v4 cleanly. v4 also produces a
      // shorter URL with a date-based signature scope.
      version: 'v4',
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
  // RC21: queue_position (only set while IN_QUEUE) + metrics.inference_time
  // (only set after COMPLETED). Surface both via the upscaleTransactions
  // doc so the client's pollForCompletion can render an ETA / queue depth
  // detail line in the progress modal.
  queue_position?: number;
  metrics?: { inference_time?: number };
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
  scale: number,
  apiKey: string,
): Promise<FalSubmitResponse> {
  const spec = MODELS[modelId];
  const body = spec.body(imageUrl, scale);
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

// RC23: FAL queue's `status_url` returns status info (queue position,
// in_progress / completed flag) but NOT the result payload. The actual
// result lives at `response_url` (same path, no `/status` suffix). When
// the status hits COMPLETED we need ONE more GET against response_url to
// retrieve the image url. Pre-RC23 this code only fetched status, so
// extractOutputUrl always returned null and the caller threw "FAL
// completed but returned no output URL."
async function fetchFalResult(
  responseUrl: string,
  apiKey: string,
): Promise<FalStatusResponse> {
  const resp = await axios.get<FalStatusResponse>(responseUrl, {
    headers: { Authorization: `Key ${apiKey}` },
    timeout: 15_000,
    validateStatus: () => true,
  });
  if (resp.status >= 400) {
    throw new Error(`FAL result fetch failed: ${resp.status} ${JSON.stringify(resp.data)}`);
  }
  return resp.data;
}

async function pollFalJob(
  statusUrl: string,
  apiKey: string,
  deadlineMs: number,
  txId?: string,
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
    // RC21: persist live progress fields so getUpscaleStatus can return them
    // to the client. We only do the write when txId is known and the values
    // are interesting (queue_position changes, status flips IN_QUEUE→IN_PROGRESS,
    // etc.) — no point churning Firestore writes on no-op iterations.
    if (txId && (status === 'IN_QUEUE' || status === 'IN_PROGRESS')) {
      const updates: Record<string, unknown> = { falStatus: status };
      if (typeof data.queue_position === 'number') {
        updates.queuePosition = data.queue_position;
      }
      try {
        await getFirestore().collection('upscaleTransactions').doc(txId).set(updates, { merge: true });
      } catch (e) {
        // Non-fatal — Firestore write failure shouldn't block the FAL poll.
        // Production probably hits this rarely; log and continue.
        console.warn('failed to persist FAL progress', e);
      }
    }
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
  const file = bucket.file(objectPath);
  await file.save(buf, {
    contentType: 'image/png',
    resumable: false,
    metadata: { cacheControl: 'private, max-age=86400' },
  });
  // RC25-fix: client uses java.net.URL().openStream() which doesn't
  // recognize gs:// — return a 7-day signed HTTPS URL instead. Storage
  // retention is tracked separately on userHistory.cloudStorageUri,
  // so changing this field doesn't affect cleanup logic.
  // RC32: v4 signing (matches resolveFetchableUrl).
  const [signedUrl] = await file.getSignedUrl({
    version: 'v4',
    action: 'read',
    expires: Date.now() + 7 * 24 * 60 * 60 * 1000,
  });
  return signedUrl;
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
    // RC3+: DPI-aware scale picker. Defaults applied if client omits any of
    // these — keeps us backwards compatible with older clients that only
    // sent (modelId, inputUrl, inputMp).
    const posterW = typeof data.posterWidthInches === 'number' && data.posterWidthInches > 0
      ? data.posterWidthInches : 24;
    const posterH = typeof data.posterHeightInches === 'number' && data.posterHeightInches > 0
      ? data.posterHeightInches : 24;
    const targetDpi = typeof data.targetDpi === 'number' && data.targetDpi >= 75 && data.targetDpi <= 1200
      ? data.targetDpi : 150;
    const minScale = typeof data.minScale === 'number' && data.minScale >= 1 && data.minScale <= 16
      ? data.minScale : undefined;
    const scale = pickScale(modelId, inputMp, posterW, posterH, targetDpi, minScale);
    const required = computeCreditsForJob(modelId, inputMp, scale);

    // RC23: admin emails (joeputin100@gmail.com etc.) and users with the
    // `admin=true` custom claim bypass the credit debit so the project
    // owner can test paid AI flows without buying SKUs from themselves.
    const isAdmin = isAdminCaller(request);

    // 1. Debit credits + create tx atomically.
    const txId = await debitAndCreateTx(uid, modelId, inputUrl, inputMp, required, isAdmin);

    // 2. Outside the transaction, kick off FAL.
    try {
      const fetchableUrl = await resolveFetchableUrl(inputUrl);
      const submit = await submitFalJob(modelId, fetchableUrl, scale, FAL_KEY.value());

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
        const finalStatus = await pollFalJob(statusUrl, FAL_KEY.value(), deadline, txId);
        if (!finalStatus) {
          // Inline timeout. Mark as in-progress; client will poll getUpscaleStatus.
          await getFirestore()
            .collection('upscaleTransactions')
            .doc(txId)
            .set({ status: 'in_progress' }, { merge: true });
          return { txId };
        }
        // RC23: status endpoint returns status info but NOT the result
        // payload. Once FAL says COMPLETED we GET the response_url
        // (which submit gave us) to fetch the actual { image: { url } }
        // payload. Without this, extractOutputUrl always saw a status-
        // only object and threw "FAL completed but returned no output
        // URL." Fall back to status-as-payload only if response_url
        // isn't available (legacy / inline-result models).
        const responseUrl = submit.response_url;
        resultPayload = responseUrl
          ? await fetchFalResult(responseUrl, FAL_KEY.value())
          : finalStatus;
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
