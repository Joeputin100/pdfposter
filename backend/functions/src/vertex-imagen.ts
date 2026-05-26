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
    // RC60: PNG output is lossless; Vertex rejects compressionQuality on
    // PNG with HTTP 400 "PNG does not accept compressionQuality" (observed
    // 2026-05-25 during the demo asset bake). compressionQuality is
    // JPEG-only and was a leftover from the spec's initial request shape.
    // We always output PNG so the field never appears in our requests.
    outputOptions: { mimeType: string };
    upscaleConfig: { upscaleFactor: UpscaleFactor };
  };
}

// RC60: response shape from Vertex Imagen 4 upscale `:predict`.
// Verified against the live API during the demo asset bake — without
// `parameters.storageUri` set, the API returns inline `bytesBase64Encoded`.
// We intentionally do NOT set `storageUri` in the request — handling the
// output write ourselves lets us use the project's default Firebase Storage
// bucket (matching FAL's `downloadAndStoreOutput` pattern in upscale.ts)
// and return a v4 signed HTTPS URL the Android client can fetch via
// `URL().openStream()` (which doesn't recognize `gs://`).
export interface VertexImagenResponse {
  predictions?: Array<{ mimeType?: string; bytesBase64Encoded?: string }>;
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
      outputOptions: {
        mimeType: 'image/png',
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
 * Pull the output PNG bytes out of a successful Imagen response.
 *
 * Without `parameters.storageUri` in the request, the API returns the
 * upscaled image inline as base64-encoded bytes under
 * `predictions[0].bytesBase64Encoded`. This matches the shape verified
 * during the demo asset bake — see bake-imagen-demo.py line ~88.
 *
 * Caller is responsible for writing the bytes to storage (typically the
 * default Firebase Storage bucket, then generating a v4 signed URL — same
 * pattern as FAL's downloadAndStoreOutput in upscale.ts).
 */
export function extractOutputBytesFromVertexResponse(
  response: VertexImagenResponse,
): Buffer {
  const first = response.predictions?.[0];
  if (!first) {
    throw new Error('Vertex Imagen returned no predictions');
  }
  if (!first.bytesBase64Encoded) {
    throw new Error('Vertex Imagen prediction missing bytesBase64Encoded');
  }
  return Buffer.from(first.bytesBase64Encoded, 'base64');
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
 * Synchronously call Vertex Imagen 4 upscale. Returns the upscaled PNG
 * bytes inline (the API responds with bytesBase64Encoded when we don't
 * ask it to write to GCS for us). Throws VertexImagenError on any
 * failure with the normalized failure_reason attached.
 *
 * Caller is responsible for storing the bytes (typically in the default
 * Firebase Storage bucket via getStorage().bucket().file(path).save(...))
 * and generating a v4 signed HTTPS URL the Android client can fetch.
 */
export async function callVertexImagen(
  args: {
    inputGsUri: string;
    upscaleFactor: UpscaleFactor;
  },
  deps: VertexImagenDeps = defaultDeps(),
): Promise<Buffer> {
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
  return extractOutputBytesFromVertexResponse(body as VertexImagenResponse);
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
