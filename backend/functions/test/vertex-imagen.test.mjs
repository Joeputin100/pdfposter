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
  extractOutputBytesFromVertexResponse,
  assertScaleSupported,
} from '../lib/vertex-imagen.js';

test('buildVertexImagenRequest: precise upscale request shape', () => {
  const req = buildVertexImagenRequest({
    inputGsUri: 'gs://posterpdf-input/abc.png',
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
      outputOptions: {
        mimeType: 'image/png',
      },
      upscaleConfig: {
        upscaleFactor: 'x4',
      },
    },
  });
});

test('buildVertexImagenRequest: no enhanceInputImage, no storageUri (precise upscale + base64 response)', () => {
  const req = buildVertexImagenRequest({
    inputGsUri: 'gs://x/y.png',
    upscaleFactor: 'x2',
  });
  const paramsKeys = Object.keys(req.parameters);
  assert.ok(!paramsKeys.includes('enhanceInputImage'),
    'enhanceInputImage must NOT be sent — it would risk hallucinated detail');
  assert.ok(!paramsKeys.includes('storageUri'),
    'storageUri must NOT be sent — we want the base64 inline response so ' +
    'we can write to our default Firebase bucket ourselves');
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

test('extractOutputBytesFromVertexResponse: base64 → Buffer', () => {
  // 'hello' base64 → "aGVsbG8="
  const response = {
    predictions: [
      { mimeType: 'image/png', bytesBase64Encoded: 'aGVsbG8=' },
    ],
  };
  const buf = extractOutputBytesFromVertexResponse(response);
  assert.ok(Buffer.isBuffer(buf));
  assert.equal(buf.toString('utf8'), 'hello');
});

test('extractOutputBytesFromVertexResponse: missing predictions throws', () => {
  assert.throws(() => extractOutputBytesFromVertexResponse({ predictions: [] }),
    /Vertex Imagen returned no predictions/);
});

test('extractOutputBytesFromVertexResponse: missing bytesBase64Encoded throws', () => {
  assert.throws(() =>
    extractOutputBytesFromVertexResponse({
      predictions: [{ mimeType: 'image/png' }],
    }),
    /Vertex Imagen prediction missing bytesBase64Encoded/);
});

// Integration tests with mocked HTTP client

import { callVertexImagen, VertexImagenError } from '../lib/vertex-imagen.js';

test('callVertexImagen: happy path returns Buffer of PNG bytes', async () => {
  // 'PNGDATA' base64 → "UE5HREFUQQ=="
  const mockedPostBody = {
    predictions: [
      { mimeType: 'image/png', bytesBase64Encoded: 'UE5HREFUQQ==' },
    ],
  };
  const out = await callVertexImagen(
    {
      inputGsUri: 'gs://posterpdf-input/source.png',
      upscaleFactor: 'x4',
    },
    {
      getAccessToken: async () => 'mock-token',
      postJson: async (url, headers, body) => {
        assert.match(url, /imagen-4\.0-upscale-preview:predict$/);
        assert.equal(headers.Authorization, 'Bearer mock-token');
        // assert request body shape correct (precise upscale)
        assert.equal(body.instances[0].prompt, '');
        assert.equal(body.instances[0].image.gcsUri, 'gs://posterpdf-input/source.png');
        assert.equal(body.parameters.mode, 'upscale');
        assert.equal(body.parameters.upscaleConfig.upscaleFactor, 'x4');
        // storageUri must NOT be present — we want base64 inline.
        assert.equal(body.parameters.storageUri, undefined);
        return { status: 200, body: mockedPostBody };
      },
    },
  );
  assert.ok(Buffer.isBuffer(out));
  assert.equal(out.toString('utf8'), 'PNGDATA');
});

test('callVertexImagen: 400 with safety-filter message throws VertexImagenError(content_filter)', async () => {
  await assert.rejects(
    () => callVertexImagen(
      { inputGsUri: 'gs://x/in.png', upscaleFactor: 'x2' },
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
      { inputGsUri: 'gs://x/in.png', upscaleFactor: 'x4' },
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
