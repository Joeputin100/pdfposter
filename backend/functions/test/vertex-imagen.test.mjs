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

test('mapVertexErrorToFailureReason: uncategorized HTTP status → unknown', () => {
  // 418 has no entry in our mapping table (not 400/429/504, not 5xx).
  // Verify the fall-through "unknown" branch fires for unexpected codes.
  // Note: 400 with null body still routes to invalid_input by design — the
  // 400 itself communicates "bad input" even without a parseable error body.
  const reason = mapVertexErrorToFailureReason(418, null);
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

// Integration tests with mocked HTTP client

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
