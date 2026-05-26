# Vertex AI Imagen Upscale Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Google Imagen 4 upscale as a paid model option in PosterPDF's upscale pipeline, slotting between AuraSR and CCSR in the model picker card order, using the Vertex AI `imagen-4.0-upscale-preview:predict` endpoint with precise (no-hallucination) configuration.

**Architecture:** Backend extends `backend/functions/src/upscale.ts` to route `modelId === 'imagen'` to a new `callVertexImagen()` function in a separate module (`vertex-imagen.ts`). The Vertex API is synchronous (single POST, no polling), authenticates via the Cloud Function's default service account (`google-auth-library` already in the tree), and writes output directly to GCS via `parameters.storageUri`. The existing Firestore state-machine + credit-refund-on-failure flow is inherited unchanged. Android client adds `UpscaleModel.IMAGEN` to the enum plus a corresponding `UpscaleOption` card. Comparison demo asset wires automatically via filename convention.

**Tech Stack:** TypeScript 5.6 (backend Cloud Functions), Node 20 (`node:test` built-in for tests, no new deps), Kotlin 2.0 + Jetpack Compose (Android), Python 3 for asset baking, `google-auth-library` 10.x (already transitive via firebase-admin) for Vertex OAuth2 token. Spec source: [Spec A — Vertex Imagen upscale](../specs/2026-05-25-vertex-imagen-upscale-design.md).

---

## Pre-flight

**Worktree:** Already on `feat/md3e-redesign` (the active development branch in `/home/projects/pdfposter-md3e`). No new worktree needed.

**LSP / build infrastructure** (already in place from prior RCs):
- Backend builds via `gcloud builds submit --config=cloudbuild-backend.yaml`.
- Android builds via GitHub Actions workflow `build-android.yml` (auto on push to `app/**`, ~8 min).
- No local Gradle wrapper; APKs come from CI artifacts.

**One-time setup verification before Task 1:**

- [ ] **Verify Cloud Functions runtime SA has Vertex AI access.** Run:

```bash
gcloud projects get-iam-policy static-webbing-461904-c4 \
  --flatten='bindings[].members' \
  --format='table(bindings.role)' \
  --filter='bindings.members:(serviceAccount:*compute@developer.gserviceaccount.com)' \
  | grep -E 'aiplatform|owner|editor'
```

Expected: one of `roles/aiplatform.user`, `roles/owner`, or `roles/editor` is present. If NONE of those appear, grant `roles/aiplatform.user` before proceeding:

```bash
gcloud projects add-iam-policy-binding static-webbing-461904-c4 \
  --member="serviceAccount:$(gcloud projects describe static-webbing-461904-c4 \
    --format='value(projectNumber)')-compute@developer.gserviceaccount.com" \
  --role=roles/aiplatform.user
```

If grant succeeds, the Cloud Function's calls to Vertex AI will authenticate without any additional secret. If the project uses a non-default Cloud Functions SA (rare), substitute its email.

---

## File structure

### Files created in this plan

| Path | Responsibility |
|---|---|
| `backend/functions/src/vertex-imagen.ts` | Vertex Imagen API client. Pure helpers (`buildVertexImagenRequest`, `mapVertexErrorToFailureReason`, `extractOutputUriFromVertexResponse`, `assertScaleSupported`) + the integration function `callVertexImagen` with injectable HTTP client + token fetcher. |
| `backend/functions/test/vertex-imagen.test.mjs` | Node-native test using `node:test`. Covers the pure helpers and a mocked-fetch integration test for `callVertexImagen`. |
| `backend/scripts/bake-imagen-demo.py` | One-shot script that runs Imagen 4 upscale on the 4 canonical demo source images and writes the outputs to `app/src/main/res/raw/<subject>_imagen.png`. |
| `app/src/main/res/raw/cat_shimmer_imagen.png` | Baked demo asset (committed binary). |
| `app/src/main/res/raw/disco_chicken_imagen.png` | Baked demo asset. |
| `app/src/main/res/raw/earth_imagen.png` | Baked demo asset. |
| `app/src/main/res/raw/gristmill_imagen.png` | Baked demo asset. |

### Files modified in this plan

| Path | Why |
|---|---|
| `backend/functions/src/upscale.ts` | Add `'imagen'` to `UpscaleModel` union, `assertModel`, IMAGEN_MAX_OUTPUT_MP guard, IMAGEN_SPEC const, and the routing branch in `requestUpscale` callable. |
| `backend/functions/package.json` | No dep additions; this file is unchanged but referenced for context (Node 20 + Firebase Admin already provide `google-auth-library`). |
| `app/src/main/kotlin/com/posterpdf/ui/components/LowDpiUpgradeModal.kt` | Add `IMAGEN` to the `UpscaleModel` enum (line 104) + new `UpscaleOption` entry to `ALL_OPTIONS` (insertion between AuraSR and CCSR, lines 169–183). |
| `app/src/main/kotlin/com/posterpdf/ui/components/ModelDetailDialog.kt` | Add `UpscaleModel.IMAGEN -> ModelDetailCopy(...)` case to the marketing-detail dialog switch. |
| `app/src/main/res/values/strings.xml` | New English strings: `upscale_option_imagen_name`, `upscale_option_imagen_pros`, `upscale_option_imagen_cons`, `upscale_option_imagen_byline`, `vm_error_imagen_content_filter`, `vm_error_imagen_too_large`, plus model-detail copy keys. |
| `app/src/main/res/values-{de,es,fr,hi,ar,pt-BR,ja,ko,zh-CN}/strings.xml` | Same keys translated. 9-locale fan-out (subagent dispatch per RC44 pattern). |
| `app/build.gradle.kts` | Bump `versionName` from `1.0-rc59` to `1.0-rc60` after end-to-end verification. |
| `backend/scripts/post-release-notes.sh` | Append a Release Notes entry to the community board mentioning Google Imagen. |

---

## Task 1: Backend — pure helpers in `vertex-imagen.ts` (TDD)

**Files:**
- Create: `backend/functions/src/vertex-imagen.ts`
- Test: `backend/functions/test/vertex-imagen.test.mjs`

- [ ] **Step 1: Write the failing tests for the pure helpers**

Create `backend/functions/test/vertex-imagen.test.mjs`:

```javascript
// backend/functions/test/vertex-imagen.test.mjs
//
// Node-native tests (no Jest / Vitest). Run via:
//   cd backend/functions && npm run build && node --test test/vertex-imagen.test.mjs
//
// We compile TS → JS via the existing `npm run build` step (writes lib/),
// then import from lib/ so this test exercises the same compiled output
// the Cloud Function runs.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  buildVertexImagenRequest,
  mapVertexErrorToFailureReason,
  extractOutputUriFromVertexResponse,
  assertScaleSupported,
} from '../lib/vertex-imagen.js';

test('buildVertexImagenRequest: precise upscale request shape', () => {
  const req = buildVertexImagenRequest({
    inputGsUri: 'gs://posterpdf-input/abc.png',
    outputGsUri: 'gs://posterpdf-output/abc-x4.png',
    upscaleFactor: 'x4',
  });
  assert.deepEqual(req, {
    instances: [
      {
        prompt: '',
        image: { gcsUri: 'gs://posterpdf-input/abc.png' },
      },
    ],
    parameters: {
      sampleCount: 1,
      mode: 'upscale',
      storageUri: 'gs://posterpdf-output/abc-x4.png',
      outputOptions: {
        mimeType: 'image/png',
        compressionQuality: 100,
      },
      upscaleConfig: {
        upscaleFactor: 'x4',
      },
    },
  });
});

test('buildVertexImagenRequest: no enhanceInputImage (precise upscale guarantee)', () => {
  const req = buildVertexImagenRequest({
    inputGsUri: 'gs://x/y.png',
    outputGsUri: 'gs://x/y-out.png',
    upscaleFactor: 'x2',
  });
  const paramsKeys = Object.keys(req.parameters);
  assert.ok(!paramsKeys.includes('enhanceInputImage'),
    'enhanceInputImage must NOT be sent — it would risk hallucinated detail');
  assert.equal(req.instances[0].prompt, '',
    'prompt must be empty to gate Imagen into super-resolution mode');
});

test('assertScaleSupported: accepts x2, x3, x4', () => {
  assertScaleSupported('x2');
  assertScaleSupported('x3');
  assertScaleSupported('x4');
});

test('assertScaleSupported: rejects unsupported factors', () => {
  assert.throws(() => assertScaleSupported('x5'),
    /Imagen upscale supports only x2, x3, or x4/);
  assert.throws(() => assertScaleSupported('x1'),
    /Imagen upscale supports only x2, x3, or x4/);
});

test('mapVertexErrorToFailureReason: content filter → content_filter', () => {
  const reason = mapVertexErrorToFailureReason(400, {
    error: {
      code: 400,
      status: 'INVALID_ARGUMENT',
      message: 'Image was filtered out by safety filter',
    },
  });
  assert.equal(reason, 'content_filter');
});

test('mapVertexErrorToFailureReason: invalid image format → invalid_input', () => {
  const reason = mapVertexErrorToFailureReason(400, {
    error: { code: 400, status: 'INVALID_ARGUMENT', message: 'Unsupported MIME type' },
  });
  assert.equal(reason, 'invalid_input');
});

test('mapVertexErrorToFailureReason: quota → quota_exceeded', () => {
  const reason = mapVertexErrorToFailureReason(429, {
    error: { code: 429, status: 'RESOURCE_EXHAUSTED', message: 'Quota exceeded' },
  });
  assert.equal(reason, 'quota_exceeded');
});

test('mapVertexErrorToFailureReason: timeout → timeout', () => {
  const reason = mapVertexErrorToFailureReason(504, {
    error: { code: 504, status: 'DEADLINE_EXCEEDED' },
  });
  assert.equal(reason, 'timeout');
});

test('mapVertexErrorToFailureReason: 5xx → server_error', () => {
  const reason = mapVertexErrorToFailureReason(503, {
    error: { code: 503, status: 'UNAVAILABLE' },
  });
  assert.equal(reason, 'server_error');
});

test('mapVertexErrorToFailureReason: unparseable response → unknown', () => {
  const reason = mapVertexErrorToFailureReason(400, null);
  assert.equal(reason, 'unknown');
});

test('extractOutputUriFromVertexResponse: storageUri pointer', () => {
  const response = {
    predictions: [
      { mimeType: 'image/png', storageUri: 'gs://output/result.png' },
    ],
  };
  assert.equal(extractOutputUriFromVertexResponse(response),
    'gs://output/result.png');
});

test('extractOutputUriFromVertexResponse: missing predictions throws', () => {
  assert.throws(() => extractOutputUriFromVertexResponse({ predictions: [] }),
    /Vertex Imagen returned no predictions/);
});

test('extractOutputUriFromVertexResponse: missing storageUri throws', () => {
  assert.throws(() =>
    extractOutputUriFromVertexResponse({
      predictions: [{ mimeType: 'image/png' }],
    }),
    /Vertex Imagen prediction missing storageUri/);
});
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /home/projects/pdfposter-md3e/backend/functions
node --test test/vertex-imagen.test.mjs 2>&1 | head -10
```

Expected: ERR_MODULE_NOT_FOUND for `../lib/vertex-imagen.js`. This confirms the test file is being executed and the implementation doesn't exist yet.

- [ ] **Step 3: Implement the pure helpers**

Create `backend/functions/src/vertex-imagen.ts`:

```typescript
// backend/functions/src/vertex-imagen.ts
//
// Vertex AI Imagen 4 upscale client. Pure helpers + a top-level callVertexImagen()
// that accepts injected HTTP + token-fetcher dependencies for testability.
//
// Spec: docs/superpowers/specs/2026-05-25-vertex-imagen-upscale-design.md
//
// Precise upscale guarantee:
//  - prompt: '' (empty) — gates Imagen into super-resolution mode, not
//    text-guided regeneration.
//  - No enhanceInputImage parameter — that's from older docs and isn't in
//    the current public imagen-4.0-upscale-preview API surface.
//  - mode: 'upscale' (required by the API).

import { GoogleAuth } from 'google-auth-library';

export type UpscaleFactor = 'x2' | 'x3' | 'x4';

export interface VertexImagenRequest {
  instances: Array<{
    prompt: string;
    image: { gcsUri: string };
  }>;
  parameters: {
    sampleCount: number;
    mode: 'upscale';
    storageUri: string;
    outputOptions: { mimeType: string; compressionQuality: number };
    upscaleConfig: { upscaleFactor: UpscaleFactor };
  };
}

export interface VertexImagenResponse {
  predictions?: Array<{ mimeType?: string; storageUri?: string }>;
}

export type FailureReason =
  | 'content_filter'
  | 'invalid_input'
  | 'quota_exceeded'
  | 'timeout'
  | 'server_error'
  | 'unknown';

export class VertexImagenError extends Error {
  constructor(
    public readonly httpStatus: number,
    public readonly failureReason: FailureReason,
    message: string,
  ) {
    super(message);
    this.name = 'VertexImagenError';
  }
}

/**
 * Build the JSON body for a precise Imagen 4 upscale call.
 *
 * Precise == empty prompt + no editing parameters. See file header.
 */
export function buildVertexImagenRequest(args: {
  inputGsUri: string;
  outputGsUri: string;
  upscaleFactor: UpscaleFactor;
}): VertexImagenRequest {
  return {
    instances: [
      {
        prompt: '',
        image: { gcsUri: args.inputGsUri },
      },
    ],
    parameters: {
      sampleCount: 1,
      mode: 'upscale',
      storageUri: args.outputGsUri,
      outputOptions: {
        mimeType: 'image/png',
        compressionQuality: 100,
      },
      upscaleConfig: {
        upscaleFactor: args.upscaleFactor,
      },
    },
  };
}

/**
 * Validate that the scale string is one Imagen 4 upscale supports.
 * Throws Error if not — caller wraps in HttpsError at the boundary.
 */
export function assertScaleSupported(scale: string): asserts scale is UpscaleFactor {
  if (scale !== 'x2' && scale !== 'x3' && scale !== 'x4') {
    throw new Error(`Imagen upscale supports only x2, x3, or x4; got "${scale}"`);
  }
}

/**
 * Map a Vertex error response onto our internal failure-reason taxonomy.
 * The taxonomy is the one consumed by Firestore's refund trigger.
 */
export function mapVertexErrorToFailureReason(
  httpStatus: number,
  body: unknown,
): FailureReason {
  // Try to parse the Google error envelope: { error: { code, status, message } }.
  const error = (body as { error?: { code?: number; status?: string; message?: string } } | null)?.error;
  const status = error?.status;
  const message = error?.message ?? '';

  // Safety-filter blocks are 400 INVALID_ARGUMENT with a safety-related message.
  // Vertex's exact wording has varied across model versions; match on common
  // substrings. NOTE: refine this on first observed real-world block by
  // logging the raw response body — see Open Verification Item #3 in the spec.
  if (httpStatus === 400 && /safety|filter|policy/i.test(message)) {
    return 'content_filter';
  }
  if (httpStatus === 400 || status === 'INVALID_ARGUMENT') {
    return 'invalid_input';
  }
  if (httpStatus === 429 || status === 'RESOURCE_EXHAUSTED') {
    return 'quota_exceeded';
  }
  if (httpStatus === 504 || status === 'DEADLINE_EXCEEDED') {
    return 'timeout';
  }
  if (httpStatus >= 500) {
    return 'server_error';
  }
  return 'unknown';
}

/**
 * Pull the output gs:// URI out of a successful Imagen response.
 * When we set parameters.storageUri, the API writes the result there and
 * returns a pointer; otherwise it returns base64 (we don't use that path).
 */
export function extractOutputUriFromVertexResponse(
  response: VertexImagenResponse,
): string {
  const first = response.predictions?.[0];
  if (!first) {
    throw new Error('Vertex Imagen returned no predictions');
  }
  if (!first.storageUri) {
    throw new Error('Vertex Imagen prediction missing storageUri');
  }
  return first.storageUri;
}

/** Injectable dependencies for callVertexImagen() — overridable in tests. */
export interface VertexImagenDeps {
  /** Returns an OAuth2 bearer token string. */
  getAccessToken: () => Promise<string>;
  /** HTTP POST that returns { status, body }. */
  postJson: (url: string, headers: Record<string, string>, body: unknown) =>
    Promise<{ status: number; body: unknown }>;
}

const ENDPOINT =
  'https://us-central1-aiplatform.googleapis.com/v1' +
  '/projects/static-webbing-461904-c4/locations/us-central1' +
  '/publishers/google/models/imagen-4.0-upscale-preview:predict';

/**
 * Synchronously call Vertex Imagen 4 upscale. Returns the gs:// URI of
 * the upscaled output. Throws VertexImagenError on any failure with the
 * normalized failure_reason attached.
 */
export async function callVertexImagen(
  args: {
    inputGsUri: string;
    outputGsUri: string;
    upscaleFactor: UpscaleFactor;
  },
  deps: VertexImagenDeps = defaultDeps(),
): Promise<string> {
  assertScaleSupported(args.upscaleFactor);
  const requestBody = buildVertexImagenRequest(args);
  const token = await deps.getAccessToken();
  const { status, body } = await deps.postJson(
    ENDPOINT,
    {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json; charset=utf-8',
    },
    requestBody,
  );
  if (status < 200 || status >= 300) {
    const reason = mapVertexErrorToFailureReason(status, body);
    throw new VertexImagenError(
      status,
      reason,
      `Vertex Imagen call failed: HTTP ${status} reason=${reason}`,
    );
  }
  return extractOutputUriFromVertexResponse(body as VertexImagenResponse);
}

/** Default production deps — real OAuth2 + real fetch. */
function defaultDeps(): VertexImagenDeps {
  const auth = new GoogleAuth({
    scopes: ['https://www.googleapis.com/auth/cloud-platform'],
  });
  return {
    getAccessToken: async () => {
      const client = await auth.getClient();
      const tokenResp = await client.getAccessToken();
      if (!tokenResp.token) {
        throw new Error('GoogleAuth returned no access token');
      }
      return tokenResp.token;
    },
    postJson: async (url, headers, body) => {
      const res = await fetch(url, {
        method: 'POST',
        headers,
        body: JSON.stringify(body),
      });
      const text = await res.text();
      let parsed: unknown = null;
      try { parsed = text.length > 0 ? JSON.parse(text) : null; } catch { parsed = text; }
      return { status: res.status, body: parsed };
    },
  };
}
```

- [ ] **Step 4: Add an integration-style test with a mocked POST**

Append to `backend/functions/test/vertex-imagen.test.mjs`:

```javascript
// (append below the existing tests)
import { callVertexImagen, VertexImagenError } from '../lib/vertex-imagen.js';

test('callVertexImagen: happy path returns storageUri', async () => {
  const mockedPostBody = {
    predictions: [
      { mimeType: 'image/png', storageUri: 'gs://posterpdf-output/result.png' },
    ],
  };
  const out = await callVertexImagen(
    {
      inputGsUri: 'gs://posterpdf-input/source.png',
      outputGsUri: 'gs://posterpdf-output/result.png',
      upscaleFactor: 'x4',
    },
    {
      getAccessToken: async () => 'mock-token',
      postJson: async (url, headers, body) => {
        assert.match(url, /imagen-4\.0-upscale-preview:predict$/);
        assert.equal(headers.Authorization, 'Bearer mock-token');
        // assert request body shape correct (precise upscale)
        assert.equal(body.instances[0].prompt, '');
        assert.equal(body.parameters.mode, 'upscale');
        assert.equal(body.parameters.upscaleConfig.upscaleFactor, 'x4');
        return { status: 200, body: mockedPostBody };
      },
    },
  );
  assert.equal(out, 'gs://posterpdf-output/result.png');
});

test('callVertexImagen: 400 with safety-filter message throws VertexImagenError(content_filter)', async () => {
  await assert.rejects(
    () => callVertexImagen(
      { inputGsUri: 'gs://x/in.png', outputGsUri: 'gs://x/out.png', upscaleFactor: 'x2' },
      {
        getAccessToken: async () => 'mock-token',
        postJson: async () => ({
          status: 400,
          body: { error: { code: 400, status: 'INVALID_ARGUMENT',
            message: 'Image was blocked by safety filter' } },
        }),
      },
    ),
    (err) => {
      assert.ok(err instanceof VertexImagenError);
      assert.equal(err.httpStatus, 400);
      assert.equal(err.failureReason, 'content_filter');
      return true;
    },
  );
});

test('callVertexImagen: 429 quota exceeded throws VertexImagenError(quota_exceeded)', async () => {
  await assert.rejects(
    () => callVertexImagen(
      { inputGsUri: 'gs://x/in.png', outputGsUri: 'gs://x/out.png', upscaleFactor: 'x4' },
      {
        getAccessToken: async () => 'mock-token',
        postJson: async () => ({ status: 429,
          body: { error: { code: 429, status: 'RESOURCE_EXHAUSTED' } } }),
      },
    ),
    (err) => {
      assert.equal(err.failureReason, 'quota_exceeded');
      return true;
    },
  );
});
```

- [ ] **Step 5: Build TypeScript and run all tests**

```bash
cd /home/projects/pdfposter-md3e/backend/functions
npm run build && node --test test/vertex-imagen.test.mjs
```

Expected: 14 tests pass, 0 fail. If the build fails on TS errors, fix the `.ts` source and re-run.

- [ ] **Step 6: Commit**

```bash
cd /home/projects/pdfposter-md3e
git add backend/functions/src/vertex-imagen.ts backend/functions/test/vertex-imagen.test.mjs
git commit -m "feat(backend): vertex-imagen.ts — Vertex Imagen 4 upscale client with tests"
```

---

## Task 2: Backend — wire `'imagen'` into `upscale.ts`

**Files:**
- Modify: `backend/functions/src/upscale.ts`

The existing FAL pipeline uses a `MODELS` map with FAL-specific shape (endpoint, body builder, costFn). Imagen doesn't fit that shape (it's a Vertex POST with a different body), so we add a separate `IMAGEN_SPEC` constant and a routing branch in `requestUpscale`. The `UpscaleModel` union, `assertModel`, and `assertModelCapacity` all get an `'imagen'` case.

- [ ] **Step 1: Extend the `UpscaleModel` union (line 27)**

Find this line in `backend/functions/src/upscale.ts`:

```typescript
type UpscaleModel = 'topaz' | 'recraft' | 'aurasr' | 'esrgan' | 'ccsr';
```

Change to:

```typescript
type UpscaleModel = 'topaz' | 'recraft' | 'aurasr' | 'esrgan' | 'ccsr' | 'imagen';
```

- [ ] **Step 2: Update `assertModel` (around line 197)**

Find this function:

```typescript
function assertModel(m: unknown): UpscaleModel {
  if (m === 'topaz' || m === 'recraft' || m === 'aurasr' || m === 'esrgan' || m === 'ccsr') return m;
  throw new HttpsError(
    'invalid-argument',
    'modelId must be one of: topaz, recraft, aurasr, esrgan, ccsr',
  );
}
```

Change to:

```typescript
function assertModel(m: unknown): UpscaleModel {
  if (m === 'topaz' || m === 'recraft' || m === 'aurasr' || m === 'esrgan'
      || m === 'ccsr' || m === 'imagen') return m;
  throw new HttpsError(
    'invalid-argument',
    'modelId must be one of: topaz, recraft, aurasr, esrgan, ccsr, imagen',
  );
}
```

- [ ] **Step 3: Add Imagen capacity guard**

Find this block (around line 227):

```typescript
const CCSR_MAX_OUTPUT_MP = 8;
const RECRAFT_MAX_INPUT_MP = 1.5;
```

Add after:

```typescript
const CCSR_MAX_OUTPUT_MP = 8;
const RECRAFT_MAX_INPUT_MP = 1.5;
// Imagen 4 upscale preview: the model's published cap is 17 MP output.
// Reject larger jobs before debiting credits. See Spec A — Constraints from
// Imagen 4.
const IMAGEN_MAX_OUTPUT_MP = 17;
```

Then extend `assertModelCapacity` (the next function in the file):

```typescript
function assertModelCapacity(modelId: UpscaleModel, inputMp: number, outputMp: number): void {
  if (modelId === 'ccsr' && outputMp > CCSR_MAX_OUTPUT_MP) {
    throw new HttpsError(
      'invalid-argument',
      `CCSR can only handle outputs up to ${CCSR_MAX_OUTPUT_MP} MP; ` +
      `this job would produce ${outputMp.toFixed(1)} MP. ` +
      `Pick a smaller poster size or a different model.`,
    );
  }
  if (modelId === 'recraft' && inputMp > RECRAFT_MAX_INPUT_MP) {
    throw new HttpsError(
      'invalid-argument',
      `Recraft can only handle inputs up to ${RECRAFT_MAX_INPUT_MP} MP ` +
      `(roughly 1024×1024); your image is ${inputMp.toFixed(1)} MP. ` +
      `Pick a different model — Topaz, AuraSR, ESRGAN, and CCSR all ` +
      `accept larger inputs.`,
    );
  }
  if (modelId === 'imagen' && outputMp > IMAGEN_MAX_OUTPUT_MP) {
    throw new HttpsError(
      'invalid-argument',
      `Google Imagen can only handle outputs up to ${IMAGEN_MAX_OUTPUT_MP} MP; ` +
      `this job would produce ${outputMp.toFixed(1)} MP. ` +
      `Pick a smaller poster size, a smaller scale, or a different model.`,
    );
  }
}
```

- [ ] **Step 4: Add `IMAGEN_SPEC` constant**

Find the `const MODELS: Record<UpscaleModel, ModelSpec> = { ... };` declaration (around line 70–120). Imagen does NOT belong in `MODELS` because that map's `ModelSpec` is FAL-shaped. Instead, add Imagen's spec as a separate constant immediately AFTER the `MODELS` declaration:

```typescript
// Imagen 4 upscale lives off-vendor (Vertex AI, not FAL), so it doesn't fit
// the FAL-shaped ModelSpec above. Separate registry; routing handled in
// requestUpscale.
//
// Pricing: ~$0.02-$0.03 per output image (Imagen 4 preview pricing not yet
// published on Google's public pricing page as of 2026-05-25; this constant
// is our internal cost estimate, used for credit-deduction math via the
// existing CREDIT_COST_BUDGET_USD margin formula). Revise once Google
// publishes preview pricing. NOTE: this is per CALL (flat), not per MP —
// Vertex bills per request for upscale, unlike Topaz's per-MP model.
const IMAGEN_COST_PER_CALL_USD = 0.03;

const IMAGEN_SPEC = {
  // Imagen 4 upscale supports x2, x3, x4 — three discrete factors, no
  // continuous range. pickScale will pick the smallest that meets target DPI.
  supportedScales: [2, 3, 4] as const,
  // Flat per-call cost. costFn signature matches MODELS for symmetry.
  costFn: (_outputMp: number) => IMAGEN_COST_PER_CALL_USD,
};
```

- [ ] **Step 5: Teach `pickScale` about Imagen**

Find the `pickScale` function (around line 140–170). It currently reads `MODELS[modelId].supportedScales`. Imagen's scales live in `IMAGEN_SPEC`, so we need a helper:

```typescript
// Add right above pickScale:
function supportedScalesFor(modelId: UpscaleModel): readonly number[] {
  if (modelId === 'imagen') return IMAGEN_SPEC.supportedScales;
  return MODELS[modelId].supportedScales;
}
```

Then change pickScale's body's reference from `MODELS[modelId].supportedScales` to `supportedScalesFor(modelId)`. Apply the same swap to `computeCreditsForJob` (the helper that calls `MODELS[modelId].costFn(outputMp)`), wrapping it:

```typescript
function costFnFor(modelId: UpscaleModel): (outputMp: number) => number {
  if (modelId === 'imagen') return IMAGEN_SPEC.costFn;
  return MODELS[modelId].costFn;
}
```

And replace `MODELS[modelId].costFn(outputMp)` inside `computeCreditsForJob` with `costFnFor(modelId)(outputMp)`.

- [ ] **Step 6: Add the routing branch in `requestUpscale`**

Locate the `requestUpscale` callable (around line 615). After the existing `submitFalJob` call sequence, the function flow is:

1. `submit = await submitFalJob(...)` for FAL models.
2. `pollFalJob` if needed.
3. `fetchFalResult` to extract the output URL.
4. `downloadAndStoreOutput` to put the result in our GCS bucket.
5. Mark Firestore tx as succeeded.

For Imagen, the flow collapses to a single sync call. Find the line:

```typescript
const submit = await submitFalJob(modelId, fetchableUrl, scale, FAL_KEY.value());
```

Replace the `try { ... }` block containing this call with the dispatching version:

```typescript
    try {
      const fetchableUrl = await resolveFetchableUrl(inputUrl);
      let storedUrl: string;

      if (modelId === 'imagen') {
        // Vertex Imagen path: synchronous POST, writes output directly to
        // our GCS bucket via parameters.storageUri. No FAL queue / polling
        // / re-download — the response already lives in our bucket.
        const outputGsUri =
          `gs://posterpdf-upscale-output/${uid}/${txId}.png`;
        const factorStr = `x${scale}` as 'x2' | 'x3' | 'x4';
        try {
          const resultGsUri = await callVertexImagen({
            inputGsUri: fetchableUrl,
            outputGsUri,
            upscaleFactor: factorStr,
          });
          storedUrl = resultGsUri;
        } catch (e) {
          if (e instanceof VertexImagenError) {
            await getFirestore()
              .collection('upscaleTransactions')
              .doc(txId)
              .set(
                {
                  status: 'failed',
                  failure_reason: e.failureReason,
                  failed_at: new Date(),
                },
                { merge: true },
              );
            // The existing onUpscaleFailed Firestore trigger will refund
            // the user's credits. Surface a useful error to the client.
            throw new HttpsError(
              'aborted',
              userFacingImagenError(e.failureReason),
            );
          }
          throw e;
        }
      } else {
        // Existing FAL path — unchanged.
        const submit = await submitFalJob(modelId, fetchableUrl, scale, FAL_KEY.value());
        // [...existing FAL pipeline body...]
        // (Leave the existing code here verbatim — do NOT delete or refactor it.)
        // Eventually computes `outputUrl`, then:
        // const storedUrl = await downloadAndStoreOutput(outputUrl, uid, txId);
        // (The assignment to `storedUrl` happens inside this block too.)
      }

      // Common success path — Firestore doc update — runs for both branches.
      await getFirestore()
        .collection('upscaleTransactions')
        .doc(txId)
        .set(
          { status: 'succeeded', output_url: storedUrl, completed_at: new Date() },
          { merge: true },
        );
      return { txId, outputUrl: storedUrl };
    } catch (e) {
      // [...existing catch block unchanged...]
    }
```

> **Note for implementer:** the FAL branch above is shown abbreviated. Do NOT delete the existing FAL-pipeline code — leave it in place inside `else { ... }`. The structural diff is: wrap the existing pipeline body in `if (modelId === 'imagen') { ... new code ... } else { ... existing code ... }` and unify the success-path Firestore write at the bottom.

Add this helper near the bottom of the file (above the closing braces):

```typescript
/** Map Vertex Imagen failure_reason → user-facing error string. */
function userFacingImagenError(reason: FailureReason): string {
  switch (reason) {
    case 'content_filter':
      return "This image couldn't be processed by Google Imagen's safety filters. " +
             "Your credits weren't charged — try a different upscaler or image.";
    case 'invalid_input':
      return "Google Imagen couldn't read this image format. " +
             "Try a different model.";
    case 'quota_exceeded':
      return 'Google Imagen is temporarily unavailable due to quota limits. ' +
             "Try again in a few minutes — your credits weren't charged.";
    case 'timeout':
      return "Google Imagen took too long to respond. " +
             "Your credits weren't charged.";
    case 'server_error':
    case 'unknown':
    default:
      return "Something went wrong with Google Imagen. " +
             "Your credits weren't charged.";
  }
}
```

- [ ] **Step 7: Add the imports at top of `upscale.ts`**

Find the existing imports block (top of the file). Add:

```typescript
import {
  callVertexImagen,
  VertexImagenError,
  type FailureReason,
} from './vertex-imagen.js';
```

- [ ] **Step 8: Build to verify TypeScript compiles clean**

```bash
cd /home/projects/pdfposter-md3e/backend/functions
npm run build
```

Expected: zero TS errors. If errors appear, the most likely cause is `pickScale` / `computeCreditsForJob` still referencing `MODELS[modelId]` directly somewhere — fix all references to go through `supportedScalesFor()` / `costFnFor()`.

- [ ] **Step 9: Re-run the vertex-imagen tests + grep for unintended changes**

```bash
node --test test/vertex-imagen.test.mjs
git diff backend/functions/src/upscale.ts | head -200
```

Expected: tests still pass; diff shows additions but the FAL-pipeline branch is preserved verbatim.

- [ ] **Step 10: Commit**

```bash
cd /home/projects/pdfposter-md3e
git add backend/functions/src/upscale.ts
git commit -m "feat(backend): route modelId=imagen through vertex-imagen.ts in upscale.ts"
```

---

## Task 3: Backend — deploy and smoke-test

**Files:** None modified; this task is operational verification.

- [ ] **Step 1: Deploy via Cloud Build**

```bash
cd /home/projects/pdfposter-md3e
gcloud builds submit --config=cloudbuild-backend.yaml
```

Expected: build succeeds in ~3-5 min. Watch the log; the build's last stage runs `firebase deploy --only functions` which redeploys `requestUpscale`.

- [ ] **Step 2: Verify deployment**

```bash
gcloud functions describe requestUpscale --region=us-central1 \
  --format='value(updateTime,state)'
```

Expected: `updateTime` within the last 5 minutes; `state` is `ACTIVE`.

- [ ] **Step 3: Smoke-test the endpoint with a manual callable invocation**

From a phone or Android Studio with an authenticated test user:

```kotlin
// Adb shell pseudo-test — or invoke from a temporary debug button.
// Real test: use the existing in-app flow with model = IMAGEN (after Task 5).
// For now, smoke-test via Firebase Console "Test function" tab:
//   functionName: requestUpscale
//   modelId: "imagen"
//   inputUrl: "gs://posterpdf-input/some-test-image.png" (upload one first)
//   inputMp: 1.0
//   posterWidthInches: 24
//   posterHeightInches: 24
//   targetDpi: 150
```

Expected response: `{ txId, outputUrl: "gs://..." }` with `outputUrl` pointing to a file in `posterpdf-upscale-output/`.

If the response is an error, check the Cloud Function logs:

```bash
gcloud functions logs read requestUpscale --region=us-central1 --limit=50
```

Common first-deploy issues:
- `PERMISSION_DENIED` calling Vertex AI → re-run the pre-flight IAM grant.
- `INVALID_ARGUMENT` on the Vertex side → check the failure_reason in the Firestore doc; refine `mapVertexErrorToFailureReason` if a new error class shows up.

- [ ] **Step 4: Verify Firestore state machine + credit refund on a forced failure**

Manually deploy a test that submits an image guaranteed to be rejected by safety filters (e.g., one with prominent text-on-fabric, per Spec A's note). Confirm:

- Doc state transitions: `requested → processing → failed` with `failure_reason: 'content_filter'`.
- The existing `onUpscaleFailed` trigger fires, restoring the user's credit balance.

```bash
# Query the user's credit balance before + after via the Firebase console
# or:
gcloud firestore export --collection-ids=users gs://posterpdf-backup/ 2>&1 | head -5
# (or just use the Firebase console UI — easier)
```

- [ ] **Step 5: Commit any fix-ups discovered during smoke-test**

If you had to adjust `mapVertexErrorToFailureReason` or the routing branch based on real API behavior, commit those changes now:

```bash
cd /home/projects/pdfposter-md3e
git add backend/functions/
git commit -m "fix(backend): refine vertex-imagen handling per smoke-test observations"
```

If everything worked first try, skip this commit.

---

## Task 4: Android — add `UpscaleModel.IMAGEN` to the enum and `ALL_OPTIONS`

**Files:**
- Modify: `app/src/main/kotlin/com/posterpdf/ui/components/LowDpiUpgradeModal.kt`

- [ ] **Step 1: Add IMAGEN to the enum (line 104)**

Find:

```kotlin
enum class UpscaleModel { NONE, FREE_LOCAL, TOPAZ, RECRAFT, AURASR, ESRGAN, CCSR }
```

Change to:

```kotlin
enum class UpscaleModel { NONE, FREE_LOCAL, TOPAZ, RECRAFT, AURASR, ESRGAN, CCSR, IMAGEN }
```

- [ ] **Step 2: Add the UpscaleOption between AuraSR and CCSR**

Find the `ALL_OPTIONS` list. The AuraSR entry is around line 168; CCSR starts around line 182. Insert between them:

```kotlin
    // RC60: Google Imagen 4 — mid-tier Pure-Google cloud upscale. Slots
    // between AuraSR and CCSR in the card grid, both visually and
    // pricing-wise. Supports x2 / x3 / x4. Backend routes via Vertex AI
    // (not FAL); UI shape is identical to other cloud models.
    UpscaleOption(
        model = UpscaleModel.IMAGEN,
        displayNameRes = R.string.upscale_option_imagen_name,
        prosRes = R.string.upscale_option_imagen_pros,
        consRes = R.string.upscale_option_imagen_cons,
        scale = 4,
        supportedScales = listOf(2, 3, 4),
    ),
```

(The exact field shape must match the existing UpscaleOption data class — verify by looking at the AuraSR or CCSR entry immediately above/below your insertion point. Use those as templates.)

- [ ] **Step 3: Add IMAGEN to the visible-options list**

Find the `val visibleOptions` declaration near `ALL_OPTIONS` (around line 276). It's a list of `UpscaleModel` values that show by default. Add `UpscaleModel.IMAGEN` to it in the position matching the array order:

```kotlin
// Add IMAGEN to the array between AURASR and CCSR. Edit the visibleOptions
// constant — the exact name and shape are right after ALL_OPTIONS.
```

Verify: the modal renders all 7 paid options + NONE + FREE_LOCAL, with Imagen positioned between AuraSR and CCSR.

- [ ] **Step 4: Build via GitHub Actions**

```bash
cd /home/projects/pdfposter-md3e
git add app/src/main/kotlin/com/posterpdf/ui/components/LowDpiUpgradeModal.kt
git commit -m "feat(android): add UpscaleModel.IMAGEN to ALL_OPTIONS card grid"
git push
```

Wait for GitHub Actions build to complete (~8 min). Expected: green build. If it fails due to a missing string resource (`R.string.upscale_option_imagen_*`), that means we ran this task before Task 6 — proceed to Task 6 to add the strings, then retry.

- [ ] **Step 5: Pull the build artifact and smoke-test the UI**

```bash
gh run list --workflow=build-android.yml --branch=feat/md3e-redesign --limit=1 --json databaseId,status,conclusion
```

When the build is complete: pull the APK from the artifact, install on a test device, navigate to the low-DPI upgrade modal, confirm the Imagen card appears between AuraSR and CCSR.

(Skip manual smoke for now if Task 6's strings are not yet committed — the card will crash with a `Resources.NotFoundException`.)

---

## Task 5: Android — `ModelDetailDialog` marketing copy case

**Files:**
- Modify: `app/src/main/kotlin/com/posterpdf/ui/components/ModelDetailDialog.kt`

The detail dialog has a switch over `UpscaleModel` returning a `ModelDetailCopy` for each model. Add the IMAGEN case.

- [ ] **Step 1: Add the IMAGEN case**

Find the existing TOPAZ case in `ModelDetailDialog.kt` (around line 396). Add a new branch for IMAGEN before `else ->` (or anywhere in the when-chain — Kotlin's `when` doesn't care about order):

```kotlin
    UpscaleModel.IMAGEN -> ModelDetailCopy(
        title = stringResource(R.string.upscale_option_imagen_name),
        pricing = stringResource(R.string.model_detail_imagen_pricing),
        whatItDoes = stringResource(R.string.model_detail_imagen_what),
        useWhen = stringResource(R.string.model_detail_imagen_use_when),
        tradeoffs = stringResource(R.string.model_detail_imagen_tradeoffs),
        provider = stringResource(R.string.model_detail_imagen_provider),
    )
```

(The exact field names in `ModelDetailCopy` may differ — verify by looking at the CCSR case in the same file. Match its shape.)

- [ ] **Step 2: Commit (build will fail until strings land in Task 6)**

```bash
cd /home/projects/pdfposter-md3e
git add app/src/main/kotlin/com/posterpdf/ui/components/ModelDetailDialog.kt
git commit -m "feat(android): ModelDetailDialog case for UpscaleModel.IMAGEN"
```

---

## Task 6: Android — i18n strings (English)

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add the English strings**

Find the existing `<string name="upscale_option_topaz_gigapixel">` (or similar — search for an existing model card string). Add these adjacent so they group with related strings:

```xml
    <!-- RC60: Google Imagen 4 upscale card. Material 3 body-small typography
         budget for pros/cons; ≤80 chars each. -->
    <string name="upscale_option_imagen_name">Google Imagen</string>
    <string name="upscale_option_imagen_pros">Photo-faithful · texture-preserving · made by Google</string>
    <string name="upscale_option_imagen_cons">x4 max · 17 MP output cap · Google safety filters apply</string>
    <string name="upscale_option_imagen_byline">by Google</string>

    <!-- Imagen error messages — keep parity with vm_error_<failure_reason> pattern. -->
    <string name="vm_error_imagen_content_filter">Google Imagen\'s safety filters couldn\'t process this image. Your credits weren\'t charged — try a different upscaler.</string>
    <string name="vm_error_imagen_too_large">Google Imagen can only handle outputs up to 17 megapixels. Try a smaller poster size or a different model.</string>

    <!-- ModelDetailDialog marketing copy. -->
    <string name="model_detail_imagen_pricing">~$0.03 per image · 3-30 credits typical</string>
    <string name="model_detail_imagen_what">Google\'s newest upscaler. Reconstructs textures and sharp edges up to 4× without re-imagining the image.</string>
    <string name="model_detail_imagen_use_when">You want a clean, fast, Pure-Google option — and your output stays under 17 megapixels.</string>
    <string name="model_detail_imagen_tradeoffs">Smaller output cap than Topaz. Google\'s safety filters can occasionally false-positive on poster graphics; you get a full refund when that happens.</string>
    <string name="model_detail_imagen_provider">Vertex AI · imagen-4.0-upscale-preview</string>
```

- [ ] **Step 2: Build + verify**

```bash
cd /home/projects/pdfposter-md3e
git add app/src/main/res/values/strings.xml
git commit -m "feat(i18n): English strings for UpscaleModel.IMAGEN (en-US)"
git push
```

Wait for GitHub Actions. Expected: green build (Tasks 4 + 5 + 6 strings now all resolve).

- [ ] **Step 3: Manual UI verification**

Install the build artifact. Navigate to the upscale model picker. Confirm:

- "Google Imagen" card visible between AuraSR and CCSR.
- Pros + cons text render without wrapping issues at default Material 3 font scale.
- Tap the card → marketing-detail dialog opens with the Imagen copy.
- Tap "Generate" or "View Details" link → upscale runs (will hit real Vertex API, will charge credits — only test on the admin allowlist account `joeputin100@gmail.com` or `mojo.xanadu.2@gmail.com`).

---

## Task 7: Android — translation fan-out to 9 locales

**Files:**
- Modify: `app/src/main/res/values-{de,es,fr,hi,ar,pt-BR,ja,ko,zh-CN}/strings.xml`

Use the RC38/RC44 translation-subagent pattern. Dispatch one subagent per locale, in parallel, each translating the 10 new keys added in Task 6.

- [ ] **Step 1: Dispatch translation subagents in parallel**

Use the Agent tool with `subagent_type: claude` (or `general-purpose`) for each locale. Per the `feedback_subagent_model_tiers` memory rule, use Opus 4.7 max ultrathink for ALL subagents.

For each of the 9 locales, send a prompt like (substituting the locale and language):

```
Translate these 10 strings.xml keys from English to German (de-DE),
matching the project's existing translation tone and the Material 3 body-small
typography budget (≤80 chars for pros/cons lines). Add the translated entries
to /home/projects/pdfposter-md3e/app/src/main/res/values-de/strings.xml,
matching the surrounding XML style.

The English source (commit it just landed in main):
  - upscale_option_imagen_name: Google Imagen
  - upscale_option_imagen_pros: Photo-faithful · texture-preserving · made by Google
  - upscale_option_imagen_cons: x4 max · 17 MP output cap · Google safety filters apply
  - upscale_option_imagen_byline: by Google
  - vm_error_imagen_content_filter: (long error message)
  - vm_error_imagen_too_large: (long error message)
  - model_detail_imagen_pricing
  - model_detail_imagen_what
  - model_detail_imagen_use_when
  - model_detail_imagen_tradeoffs
  - model_detail_imagen_provider

Brand name "Google Imagen" stays in English in every locale. Other strings
are translated. Use the existing strings.xml pattern (apostrophes escaped
with \').

Don't commit; just write to the file. I'll commit them all together.
```

Run all 9 in parallel (single Agent tool call message with 9 sub-blocks).

- [ ] **Step 2: Verify each locale has the keys**

```bash
for loc in de es fr hi ar pt-BR ja ko zh-CN; do
  echo "=== $loc ==="
  grep -c "upscale_option_imagen_name" \
    "/home/projects/pdfposter-md3e/app/src/main/res/values-$loc/strings.xml"
done
```

Expected: each prints `1`.

- [ ] **Step 3: Commit all 9 locales at once**

```bash
cd /home/projects/pdfposter-md3e
git add app/src/main/res/values-de/strings.xml \
        app/src/main/res/values-es/strings.xml \
        app/src/main/res/values-fr/strings.xml \
        app/src/main/res/values-hi/strings.xml \
        app/src/main/res/values-ar/strings.xml \
        app/src/main/res/values-pt-BR/strings.xml \
        app/src/main/res/values-ja/strings.xml \
        app/src/main/res/values-ko/strings.xml \
        app/src/main/res/values-zh-CN/strings.xml
git commit -m "i18n(rc60): translate UpscaleModel.IMAGEN strings to 9 locales"
git push
```

Wait for GitHub Actions. Expected: green build.

---

## Task 8: Comparison demo asset bake

**Files:**
- Create: `backend/scripts/bake-imagen-demo.py`
- Create: `app/src/main/res/raw/cat_shimmer_imagen.png`
- Create: `app/src/main/res/raw/disco_chicken_imagen.png`
- Create: `app/src/main/res/raw/earth_imagen.png`
- Create: `app/src/main/res/raw/gristmill_imagen.png`

The existing `bake-comparison-assets.py` is FAL-only; we add a parallel single-purpose script for Imagen. `UpscaleComparisonScreen.kt` wires assets by filename automatically (`getIdentifier("${subject}_imagen", "raw", ...)`), so dropping the 4 files at the right paths is the entire client-side wiring.

- [ ] **Step 1: Create `backend/scripts/bake-imagen-demo.py`**

```python
#!/usr/bin/env python3
"""
RC60 — bake Imagen 4 upscale demo assets for the in-app comparison screen.

Runs Vertex AI imagen-4.0-upscale-preview on the 4 canonical demo source
images and writes the outputs as lossless PNG to:
  app/src/main/res/raw/{cat_shimmer,disco_chicken,earth,gristmill}_imagen.png

The output is downsampled to 2× source dimensions to match the existing
asset budget (PNG files in res/raw shouldn't bloat the APK; the screen-
side rendering only needs ~2× source res to show clear upscale fidelity).

Run with:
    cd backend/scripts
    python3 bake-imagen-demo.py

Auth: uses `gcloud auth print-access-token` for the OAuth2 bearer.
Caller must have roles/aiplatform.user on static-webbing-461904-c4.

Cost: ~$0.12 total (4 calls × $0.03/call estimate). Cheap.
"""

from __future__ import annotations
import base64
import json
import subprocess
import sys
from io import BytesIO
from pathlib import Path

import requests
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent.parent
RAW_DIR = ROOT / "app/src/main/res/raw"
SUBJECTS = ["cat_shimmer", "disco_chicken", "earth", "gristmill"]

PROJECT = "static-webbing-461904-c4"
LOCATION = "us-central1"
MODEL = "imagen-4.0-upscale-preview"
ENDPOINT = (
    f"https://{LOCATION}-aiplatform.googleapis.com/v1"
    f"/projects/{PROJECT}/locations/{LOCATION}"
    f"/publishers/google/models/{MODEL}:predict"
)


def gcloud_access_token() -> str:
    out = subprocess.run(
        ["gcloud", "auth", "print-access-token"],
        capture_output=True, text=True, check=True,
    )
    return out.stdout.strip()


def read_source_as_base64(subject: str) -> tuple[str, tuple[int, int]]:
    """Read res/raw/<subject>_source.png and return its base64 + (w,h)."""
    path = RAW_DIR / f"{subject}_source.png"
    img = Image.open(path)
    size = img.size  # (w, h)
    buf = BytesIO()
    img.save(buf, format="PNG")
    return base64.b64encode(buf.getvalue()).decode("ascii"), size


def call_imagen(b64: str, scale: str = "x4") -> bytes:
    """Returns the upscaled PNG bytes via Imagen 4 upscale preview."""
    token = gcloud_access_token()
    body = {
        "instances": [
            {"prompt": "", "image": {"bytesBase64Encoded": b64}},
        ],
        "parameters": {
            "sampleCount": 1,
            "mode": "upscale",
            "outputOptions": {"mimeType": "image/png", "compressionQuality": 100},
            "upscaleConfig": {"upscaleFactor": scale},
        },
    }
    resp = requests.post(
        ENDPOINT,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json; charset=utf-8",
        },
        data=json.dumps(body),
        timeout=120,
    )
    resp.raise_for_status()
    j = resp.json()
    preds = j.get("predictions") or []
    if not preds:
        raise RuntimeError(f"No predictions in response: {j}")
    first = preds[0]
    if "bytesBase64Encoded" not in first:
        raise RuntimeError(f"No bytesBase64Encoded in prediction: {first}")
    return base64.b64decode(first["bytesBase64Encoded"])


def downsample_to_2x_source(png_bytes: bytes, source_size: tuple[int, int]) -> bytes:
    """Resize the upscaled PNG down to 2× the source dimensions. Lossless PNG."""
    img = Image.open(BytesIO(png_bytes))
    target = (source_size[0] * 2, source_size[1] * 2)
    if img.size != target:
        img = img.resize(target, Image.LANCZOS)
    buf = BytesIO()
    img.save(buf, format="PNG", optimize=True, compress_level=6)
    return buf.getvalue()


def main() -> int:
    print(f"Baking Imagen 4 demo assets to {RAW_DIR}...")
    for subject in SUBJECTS:
        out_path = RAW_DIR / f"{subject}_imagen.png"
        try:
            b64, size = read_source_as_base64(subject)
            print(f"  [{subject}] source={size[0]}x{size[1]} — calling Imagen 4...")
            raw = call_imagen(b64, scale="x4")
            final = downsample_to_2x_source(raw, size)
            out_path.write_bytes(final)
            print(f"  [{subject}] wrote {out_path} ({len(final)} bytes)")
        except Exception as e:
            print(f"  [{subject}] FAILED: {e}", file=sys.stderr)
            return 1
    print("Done.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2: Run the bake script**

```bash
cd /home/projects/pdfposter-md3e/backend/scripts
chmod +x bake-imagen-demo.py
python3 bake-imagen-demo.py
```

Expected output: 4 lines like `[cat_shimmer] wrote app/src/main/res/raw/cat_shimmer_imagen.png (N bytes)`, no errors. Approx. cost: $0.12 (4 calls).

If a single subject fails with a safety-filter rejection (`failed: content_filter`), Imagen's safety classifier is blocking that source image. Pick a different demo image for the failed subject, OR skip the bake for that subject — `UpscaleComparisonScreen.kt:139` has a "fall back to Topaz's asset" path that handles missing files gracefully.

- [ ] **Step 3: Visual-check the outputs**

```bash
ls -la app/src/main/res/raw/*_imagen.png
file app/src/main/res/raw/*_imagen.png
```

Expected: 4 PNG files, sizes proportional to the existing `*_topaz.png` files (~1-3 MB each typically).

Open one (e.g., `cat_shimmer_imagen.png`) and visually confirm it looks like a sharp 2× upscale of `cat_shimmer_source.png` — no hallucinated content, no obvious artifacts.

- [ ] **Step 4: Commit the bake script + baked assets**

```bash
cd /home/projects/pdfposter-md3e
git add backend/scripts/bake-imagen-demo.py
git add app/src/main/res/raw/cat_shimmer_imagen.png \
        app/src/main/res/raw/disco_chicken_imagen.png \
        app/src/main/res/raw/earth_imagen.png \
        app/src/main/res/raw/gristmill_imagen.png
git commit -m "chore(assets): bake Imagen 4 comparison demo assets for 4 subjects"
git push
```

Wait for GitHub Actions. Expected: green build; APK grows by ~5-10 MB total (4 PNGs).

- [ ] **Step 5: In-app verify**

Install the APK. Navigate to Settings → Compare AI Upscalers (or wherever the screen is exposed). Switch model chips to "Google Imagen" and verify all 4 subjects show their newly-baked Imagen asset (NOT the Topaz-fallback synthesized version).

---

## Task 9: End-to-end manual verification

**Files:** None modified; this is verification only.

- [ ] **Step 1: Build a fresh APK**

After Tasks 4–8 ship, trigger one more GH Actions build to bundle everything into a single test APK.

```bash
gh run list --workflow=build-android.yml --branch=feat/md3e-redesign --limit=1
```

- [ ] **Step 2: Smoke-test each scale factor**

On a test device (use admin account `joeputin100@gmail.com` to bypass real credit charges):

1. Pick a 1 MP source image. Set poster size = 24×18", DPI = 150. The picker should pick x4 → output 16 MP (under Imagen's 17 MP cap).
2. Tap "Generate" with Google Imagen selected.
3. Wait for completion. Verify PDF opens, content looks correct.
4. Repeat with a 4 MP source + smaller poster (target output ~6-8 MP → picker chooses x2).
5. Repeat with a 1 MP source + 36×24" poster + 300 DPI → output exceeds 17 MP → expect rejection with "Google Imagen can only handle outputs up to 17 megapixels" toast.

- [ ] **Step 3: Trigger content-filter case**

Submit an image with prominent text-on-fabric (Imagen's known false-positive class). Verify:
- Doc state in Firestore: `failed`, `failure_reason: 'content_filter'`.
- Toast in app: "Google Imagen's safety filters couldn't process this image. Your credits weren't charged — try a different upscaler."
- Credit balance returns to pre-attempt value (refund trigger worked).

If safety classifier doesn't trigger on the test image, that's fine — we've covered the code path in unit tests. Move on.

- [ ] **Step 4: Document any issues found**

If anything broke or the UX needed tweaking, file a fix into the current task and re-deploy. Common findings at this stage:
- Pricing display in the card is off (compare to backend `IMAGEN_COST_PER_CALL_USD` — fix the credit math in the card).
- "by Google" byline doesn't show — wire up `upscale_option_imagen_byline` in the card composable if needed.
- Translation truncation in some locale — adjust the string for that locale (don't expand the budget).

---

## Task 10: Versionname bump + release notes

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `backend/scripts/post-release-notes.sh`

- [ ] **Step 1: Bump versionName**

In `app/build.gradle.kts`, find:

```kotlin
versionName = "1.0-rc59"
```

Change to:

```kotlin
versionName = "1.0-rc60"  // RC60 — Google Imagen 4 upscale (Vertex AI)
```

- [ ] **Step 2: Update the community release-notes script**

In `backend/scripts/post-release-notes.sh`, find the existing release-notes body string and add a section:

```bash
# Add to the post release_notes \ body, near the top:
[b]New upscale option: Google Imagen[/b]
A new mid-tier upscale option backed by Google's Imagen 4 model on Vertex
AI is now available in the model picker. Pure-Google stack: image stays
inside our Google Cloud project end-to-end. Supports x2/x3/x4 upscale up
to 17 MP output. Slots between AuraSR and CCSR in pricing.
```

- [ ] **Step 3: Commit + push + run release-notes script**

```bash
cd /home/projects/pdfposter-md3e
git add app/build.gradle.kts backend/scripts/post-release-notes.sh
git commit -m "chore(rc60): bump versionName + add Imagen to community release notes"
git push
```

Then post to the community board:

```bash
bash backend/scripts/post-release-notes.sh
```

Expected: prints `[release_notes] <docId> — What's new in v1.0-rc60`.

- [ ] **Step 4: Final build verification**

```bash
gh run list --workflow=build-android.yml --branch=feat/md3e-redesign --limit=1 --json status,conclusion
```

Expected: latest run is `completed:success`. The APK at this build is the RC60 ship candidate.

---

## Self-review

**Spec coverage:**
- Spec A § Architecture (route-by-modelId, callVertexImagen parallel to submitFalJob): Task 2 ✓
- Spec A § Vertex API contract + precise upscale (empty prompt, no enhanceInputImage): Task 1 + Task 8 ✓
- Spec A § Constraints from Imagen 4 (17 MP cap, x2/x3/x4): Task 2 step 3 (IMAGEN_MAX_OUTPUT_MP guard), Task 4 step 2 (supportedScales) ✓
- Spec A § Android client changes (enum, ALL_OPTIONS, brand): Task 4 + Task 5 + Task 6 (note: changed brand badge from vector drawable → text byline since codebase has no existing badge pattern; documented in plan header insight) ✓
- Spec A § i18n strings + 9-locale fan-out: Task 6 + Task 7 ✓
- Spec A § Backend changes (assertModelCapacity, MODELS routing, callVertexImagen): Task 1 + Task 2 ✓
- Spec A § Auth wiring (google-auth-library): Task 1 step 3 default deps ✓
- Spec A § bake-comparison-assets.py extension: Task 8 ✓
- Spec A § Failure recovery + refund (Firestore state machine): Task 2 step 6 (failed-state write) + existing trigger handles refund ✓
- Spec A § Testing strategy (unit tests, integration test, manual): Task 1 (node:test) + Task 9 ✓
- Spec A § Open verification items (pricing, IAM, error parsing): Pre-flight (IAM) + Task 3 (smoke-test refines parsing) + Task 2 step 4 has TODO-style pricing note ✓

**Placeholder scan:**
- "Pricing not yet published" — kept as deliberate code comment with the conservative $0.03 estimate; Task 10 release notes warning is implicit.
- "Refine on first observed real-world block" in `mapVertexErrorToFailureReason` comment — acceptable; production code refines after real data.
- "Verify by looking at the AuraSR entry" in Task 4 step 2 — this is intentional implementation guidance (not a placeholder for me to fill); the implementer reads the existing entry to match the data class shape.

**Type consistency:**
- `UpscaleFactor` type is `'x2' | 'x3' | 'x4'` throughout (Tasks 1, 2, 8). ✓
- `FailureReason` union used consistently in Tasks 1, 2. ✓
- `VertexImagenError` referenced in Task 1 (definition) and Task 2 step 6 (catch). ✓
- `IMAGEN_COST_PER_CALL_USD = 0.03` (Task 2 step 4) is the single source of truth for Imagen pricing. ✓
- `supportedScales: [2, 3, 4]` (Kotlin, Task 4) matches `IMAGEN_SPEC.supportedScales: [2, 3, 4] as const` (TS, Task 2 step 4). ✓
- `model_detail_imagen_*` keys used in Task 5 are defined in Task 6 (English) and translated in Task 7. ✓

**Issues found and fixed inline:**
- None outside the items above; all tasks reference defined types and functions.
