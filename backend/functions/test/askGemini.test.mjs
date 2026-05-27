// backend/functions/test/askGemini.test.mjs
//
// Run via:
//   cd backend/functions && npm run build && node --test test/askGemini.test.mjs
//
// node:test is Node 20's built-in test runner — same pattern RC60 introduced
// for vertex-imagen.test.mjs. Zero new deps.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  buildToolDefinitions,
  buildSystemContext,
  parseToolCallFromResponse,
  parseTextFromResponse,
  applyRateLimit,
  DAILY_QUERY_LIMIT,
} from '../lib/askGemini.js';

test('DAILY_QUERY_LIMIT = 10', () => {
  assert.equal(DAILY_QUERY_LIMIT, 10);
});

test('buildToolDefinitions includes quoteUpscaleCost with required parameters', () => {
  const tools = buildToolDefinitions();
  assert.ok(Array.isArray(tools));
  const fnNames = tools.flatMap(t => (t.functionDeclarations ?? []).map(fd => fd.name));
  assert.ok(fnNames.includes('quoteUpscaleCost'),
    `expected quoteUpscaleCost in ${fnNames.join(', ')}`);
  // Find the function declaration and check required params.
  const quoteDecl = tools.flatMap(t => t.functionDeclarations ?? []).find(fd => fd.name === 'quoteUpscaleCost');
  assert.ok(quoteDecl);
  const required = quoteDecl.parameters?.required ?? [];
  assert.ok(required.includes('upscaleModel'), 'upscaleModel must be required');
  assert.ok(required.includes('targetWidthInches'), 'targetWidthInches must be required');
  assert.ok(required.includes('targetHeightInches'), 'targetHeightInches must be required');
});

test('buildSystemContext includes current app state', () => {
  const ctx = buildSystemContext({
    selectedImageMp: 12.3,
    targetWidthInches: 24,
    targetHeightInches: 18,
    paperSize: 'Letter (8.5x11)',
    currentUpscaleModel: 'imagen',
  });
  assert.match(ctx, /12\.3.*MP/i);
  assert.match(ctx, /24/);
  assert.match(ctx, /18/);
  assert.match(ctx, /Letter/);
  assert.match(ctx, /imagen/i);
});

test('buildSystemContext handles missing image gracefully', () => {
  const ctx = buildSystemContext({});
  assert.match(ctx, /no image|not yet selected/i);
});

test('parseToolCallFromResponse returns null when no function call', () => {
  const response = {
    candidates: [{ content: { parts: [{ text: 'A 24x18 poster needs at least 7 MP for 150 DPI.' }] } }],
  };
  const tc = parseToolCallFromResponse(response);
  assert.equal(tc, null);
});

test('parseToolCallFromResponse extracts function name + args', () => {
  const response = {
    candidates: [
      {
        content: {
          parts: [
            {
              functionCall: {
                name: 'quoteUpscaleCost',
                args: { upscaleModel: 'topaz', targetWidthInches: 24, targetHeightInches: 18 },
              },
            },
          ],
        },
      },
    ],
  };
  const tc = parseToolCallFromResponse(response);
  assert.equal(tc?.name, 'quoteUpscaleCost');
  assert.equal(tc?.args.upscaleModel, 'topaz');
  assert.equal(tc?.args.targetWidthInches, 24);
});

test('parseTextFromResponse concatenates text parts', () => {
  const response = {
    candidates: [{ content: { parts: [{ text: 'Hello ' }, { text: 'world' }] } }],
  };
  assert.equal(parseTextFromResponse(response), 'Hello world');
});

test('parseTextFromResponse returns empty string when only function call', () => {
  const response = {
    candidates: [{ content: { parts: [{ functionCall: { name: 'x', args: {} } }] } }],
  };
  assert.equal(parseTextFromResponse(response), '');
});

test('applyRateLimit increments count and returns remaining', async () => {
  const docs = new Map();
  const fakeFirestore = {
    runTransaction: async (fn) => fn({
      get: async (ref) => ({
        exists: docs.has(ref.path),
        data: () => docs.get(ref.path),
      }),
      set: (ref, val) => { docs.set(ref.path, val); },
    }),
  };
  const fakeRef = { path: 'users/u1/quota/gemini_qa' };
  const result1 = await applyRateLimit({
    firestore: fakeFirestore,
    docRef: fakeRef,
    now: new Date('2026-05-27T10:00:00Z'),
  });
  assert.equal(result1.allowed, true);
  assert.equal(result1.remaining, 9);
});

test('applyRateLimit blocks after DAILY_QUERY_LIMIT calls in same day', async () => {
  const docs = new Map();
  const fakeFirestore = {
    runTransaction: async (fn) => fn({
      get: async (ref) => ({ exists: docs.has(ref.path), data: () => docs.get(ref.path) }),
      set: (ref, val) => { docs.set(ref.path, val); },
    }),
  };
  const fakeRef = { path: 'users/u1/quota/gemini_qa' };
  docs.set(fakeRef.path, { count: 10, dayKey: '2026-05-27' });
  const result = await applyRateLimit({
    firestore: fakeFirestore,
    docRef: fakeRef,
    now: new Date('2026-05-27T10:00:00Z'),
  });
  assert.equal(result.allowed, false);
  assert.equal(result.remaining, 0);
});

test('applyRateLimit resets on new day', async () => {
  const docs = new Map();
  const fakeFirestore = {
    runTransaction: async (fn) => fn({
      get: async (ref) => ({ exists: docs.has(ref.path), data: () => docs.get(ref.path) }),
      set: (ref, val) => { docs.set(ref.path, val); },
    }),
  };
  const fakeRef = { path: 'users/u1/quota/gemini_qa' };
  docs.set(fakeRef.path, { count: 10, dayKey: '2026-05-26' });
  const result = await applyRateLimit({
    firestore: fakeFirestore,
    docRef: fakeRef,
    now: new Date('2026-05-27T10:00:00Z'),
  });
  assert.equal(result.allowed, true);
  assert.equal(result.remaining, 9);
});
