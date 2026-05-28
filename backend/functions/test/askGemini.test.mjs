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
  handleAskGemini,
  setGeminiClient,
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

// RC69: pin the cost-quote gating wording so a future edit can't silently
// regress it back to "ALWAYS call quoteUpscaleCost".
test('buildSystemContext gates quoteUpscaleCost on explicit price intent', () => {
  const ctx = buildSystemContext({});
  assert.ok(
    ctx.includes('ONLY call the quoteUpscaleCost tool when the user explicitly asks'),
    'system prompt must gate the cost tool on explicit price intent',
  );
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

// ─── Shared test harness for the RC68 rate-limit-with-auto-buy flow ────────────

/** Build a fake-Firestore harness for applyRateLimit tests.
 *
 *  Provides in-memory doc storage keyed by path, plus closures that match
 *  the RateLimitDeps interface. The returned `creditLogWrites` array
 *  captures every doc written to the creditLog subcollection so tests can
 *  assert on the audit-trail payload shape. */
function buildRateLimitHarness({
  quotaDoc,
  userDoc,
  uid = 'u1',
} = {}) {
  const docs = new Map();
  const quotaPath = `users/${uid}/quota/askGemini`;
  const userPath = `users/${uid}`;
  if (quotaDoc !== undefined) docs.set(quotaPath, quotaDoc);
  if (userDoc !== undefined) docs.set(userPath, userDoc);

  const creditLogWrites = [];
  let creditLogCounter = 0;

  const fakeFirestore = {
    runTransaction: async (fn) => fn({
      get: async (ref) => ({
        exists: docs.has(ref.path),
        data: () => docs.get(ref.path),
      }),
      set: (ref, val) => {
        if (ref.path.startsWith(`users/${uid}/creditLog/`)) {
          creditLogWrites.push({ path: ref.path, val });
        }
        docs.set(ref.path, val);
      },
      update: (ref, val) => {
        // Simulate FieldValue.increment by applying the {__inc: n} sentinel.
        const cur = docs.get(ref.path) ?? {};
        const next = { ...cur };
        for (const [k, v] of Object.entries(val)) {
          if (v && typeof v === 'object' && '__inc' in v) {
            next[k] = (Number(next[k] ?? 0)) + Number(v.__inc);
          } else {
            next[k] = v;
          }
        }
        docs.set(ref.path, next);
      },
    }),
  };

  return {
    docs,
    creditLogWrites,
    quotaPath,
    userPath,
    deps: {
      firestore: fakeFirestore,
      quotaDocRef: { path: quotaPath },
      userDocRef: { path: userPath },
      creditLogColl: {
        doc: () => ({ path: `users/${uid}/creditLog/log${++creditLogCounter}` }),
      },
      fieldIncrement: (n) => ({ __inc: n }),
      serverTimestamp: () => 'SERVER_TIMESTAMP',
    },
  };
}

test('applyRateLimit increments count and returns remaining', async () => {
  const h = buildRateLimitHarness({ userDoc: { credits: 0 } });
  const result1 = await applyRateLimit({
    ...h.deps,
    now: new Date('2026-05-27T10:00:00Z'),
  });
  assert.equal(result1.allowed, true);
  assert.equal(result1.remaining, 9);
  assert.equal(result1.packPurchased, false);
});

test('applyRateLimit blocks after DAILY_QUERY_LIMIT when no credits and no paid bucket', async () => {
  const h = buildRateLimitHarness({
    quotaDoc: { freeUsedToday: 10, dayKey: '2026-05-27', paidRemaining: 0 },
    userDoc: { credits: 0 },
  });
  const result = await applyRateLimit({
    ...h.deps,
    now: new Date('2026-05-27T10:00:00Z'),
  });
  assert.equal(result.allowed, false);
  assert.equal(result.remaining, 0);
  assert.equal(result.packPurchased, false);
});

test('applyRateLimit resets on new day', async () => {
  const h = buildRateLimitHarness({
    quotaDoc: { freeUsedToday: 10, dayKey: '2026-05-26', paidRemaining: 0 },
    userDoc: { credits: 0 },
  });
  const result = await applyRateLimit({
    ...h.deps,
    now: new Date('2026-05-27T10:00:00Z'),
  });
  assert.equal(result.allowed, true);
  assert.equal(result.remaining, 9);
  assert.equal(result.packPurchased, false);
});

// ─── RC68 auto-buy + rollover tests ───────────────────────────────────────────

test('RC68: first-ever query for new user — empty quota, 5 credits → free 1/10, paid=0', async () => {
  const h = buildRateLimitHarness({ userDoc: { credits: 5 } });
  const result = await applyRateLimit({
    ...h.deps,
    now: new Date('2026-05-27T10:00:00Z'),
  });
  assert.equal(result.allowed, true);
  assert.equal(result.remaining, 9);
  assert.equal(result.packPurchased, false);
  // user credits unchanged
  assert.equal(h.docs.get(h.userPath).credits, 5);
  // quota updated
  const q = h.docs.get(h.quotaPath);
  assert.equal(q.freeUsedToday, 1);
  assert.equal(q.dayKey, '2026-05-27');
});

test('RC68: mid-day 5th query — freeUsedToday=4 → remaining=5', async () => {
  const h = buildRateLimitHarness({
    quotaDoc: { freeUsedToday: 4, dayKey: '2026-05-27', paidRemaining: 0 },
    userDoc: { credits: 0 },
  });
  const result = await applyRateLimit({
    ...h.deps,
    now: new Date('2026-05-27T10:00:00Z'),
  });
  assert.equal(result.allowed, true);
  assert.equal(result.remaining, 5);
  assert.equal(result.packPurchased, false);
});

test('RC68: day-rollover resets free but preserves paid bucket', async () => {
  // Yesterday: free exhausted, 3 paid remaining
  const h = buildRateLimitHarness({
    quotaDoc: { freeUsedToday: 10, dayKey: '2026-05-26', paidRemaining: 3 },
    userDoc: { credits: 0 },
  });
  const result = await applyRateLimit({
    ...h.deps,
    now: new Date('2026-05-27T10:00:00Z'),
  });
  assert.equal(result.allowed, true);
  // free reset to 0, then this call → freeUsedToday=1; remaining = (10-1) + 3 = 12
  assert.equal(result.remaining, 12);
  assert.equal(result.packPurchased, false);
  const q = h.docs.get(h.quotaPath);
  assert.equal(q.freeUsedToday, 1);
  assert.equal(q.dayKey, '2026-05-27');
  assert.equal(q.paidRemaining, 3);
});

test('RC68: free exhausted, paid bucket has 5 → uses paid, no credit debit', async () => {
  const h = buildRateLimitHarness({
    quotaDoc: { freeUsedToday: 10, dayKey: '2026-05-27', paidRemaining: 5 },
    userDoc: { credits: 3 },
  });
  const result = await applyRateLimit({
    ...h.deps,
    now: new Date('2026-05-27T10:00:00Z'),
  });
  assert.equal(result.allowed, true);
  assert.equal(result.remaining, 4);
  assert.equal(result.packPurchased, false);
  // user credits unchanged
  assert.equal(h.docs.get(h.userPath).credits, 3);
  // no creditLog
  assert.equal(h.creditLogWrites.length, 0);
  // paidRemaining decremented
  assert.equal(h.docs.get(h.quotaPath).paidRemaining, 4);
});

test('RC68: free exhausted, paid empty, 3 credits → auto-buy pack', async () => {
  const h = buildRateLimitHarness({
    quotaDoc: { freeUsedToday: 10, dayKey: '2026-05-27', paidRemaining: 0 },
    userDoc: { credits: 3 },
  });
  const result = await applyRateLimit({
    ...h.deps,
    now: new Date('2026-05-27T10:00:00Z'),
  });
  assert.equal(result.allowed, true);
  assert.equal(result.remaining, 9);
  assert.equal(result.packPurchased, true);
  // user credits debited by 1
  assert.equal(h.docs.get(h.userPath).credits, 2);
  // creditLog entry written
  assert.equal(h.creditLogWrites.length, 1);
  // paidRemaining now 9 (10-pack minus this send)
  assert.equal(h.docs.get(h.quotaPath).paidRemaining, 9);
});

test('RC68: free exhausted, paid empty, 0 credits → blocked', async () => {
  const h = buildRateLimitHarness({
    quotaDoc: { freeUsedToday: 10, dayKey: '2026-05-27', paidRemaining: 0 },
    userDoc: { credits: 0 },
  });
  const result = await applyRateLimit({
    ...h.deps,
    now: new Date('2026-05-27T10:00:00Z'),
  });
  assert.equal(result.allowed, false);
  assert.equal(result.remaining, 0);
  assert.equal(result.packPurchased, false);
  // user credits unchanged
  assert.equal(h.docs.get(h.userPath).credits, 0);
  assert.equal(h.creditLogWrites.length, 0);
});

test('RC68: paid bucket rolls over across days', async () => {
  // Yesterday: free maxed, paid=7
  const h = buildRateLimitHarness({
    quotaDoc: { freeUsedToday: 10, dayKey: '2026-05-26', paidRemaining: 7 },
    userDoc: { credits: 0 },
  });
  const result = await applyRateLimit({
    ...h.deps,
    now: new Date('2026-05-27T10:00:00Z'),
  });
  assert.equal(result.allowed, true);
  // remaining = (10-1) + 7 = 16
  assert.equal(result.remaining, 16);
  assert.equal(result.packPurchased, false);
  const q = h.docs.get(h.quotaPath);
  assert.equal(q.freeUsedToday, 1);
  assert.equal(q.paidRemaining, 7);
});

test('RC68: auto-buy creditLog payload includes kind, delta, balances, createdAt', async () => {
  const h = buildRateLimitHarness({
    quotaDoc: { freeUsedToday: 10, dayKey: '2026-05-27', paidRemaining: 0 },
    userDoc: { credits: 4 },
  });
  const result = await applyRateLimit({
    ...h.deps,
    now: new Date('2026-05-27T10:00:00Z'),
  });
  assert.equal(result.packPurchased, true);
  assert.equal(h.creditLogWrites.length, 1);
  const entry = h.creditLogWrites[0].val;
  assert.equal(entry.kind, 'gemini_pack');
  assert.equal(entry.delta, -1);
  assert.equal(entry.balanceBefore, 4);
  assert.equal(entry.balanceAfter, 3);
  assert.ok(entry.createdAt, 'createdAt must be present');
});

// ─── RC69 handler: tool round-trip + continuation rate-limit skip ──────────────

/** Build a fake GeminiClient that records the generate() args and returns a
 *  caller-supplied canned response. */
function fakeGemini(response) {
  const calls = [];
  setGeminiClient({
    generate: async (args) => {
      calls.push(args);
      return response;
    },
  });
  return calls;
}

test('RC69: continuation turn skips rate limit, returns text + remainingQueries=-1', async () => {
  const calls = fakeGemini({
    candidates: [{ content: { parts: [{ text: 'Upscaling to topaz costs 3 credits.' }] } }],
  });
  // Rate-limit dep that THROWS if invoked — proves the continuation never calls it.
  const rateLimit = async () => { throw new Error('applyRateLimit must NOT run on continuation'); };

  const result = await handleAskGemini('u1', {
    prompt: 'ignored on continuation',
    toolResult: {
      name: 'quoteUpscaleCost',
      args: { upscaleModel: 'topaz', targetWidthInches: 24, targetHeightInches: 18 },
      response: { creditCost: 3, currentBalance: 5 },
      originalPrompt: 'How much does topaz cost?',
    },
  }, rateLimit);

  assert.equal(result.text, 'Upscaling to topaz costs 3 credits.');
  assert.equal(result.toolCall, null);
  assert.equal(result.remainingQueries, -1);
  assert.equal(calls.length, 1);
});

test('RC69: continuation builds 3-turn contents with functionResponse part', async () => {
  const calls = fakeGemini({
    candidates: [{ content: { parts: [{ text: 'ok' }] } }],
  });
  const rateLimit = async () => { throw new Error('should not be called'); };

  await handleAskGemini('u1', {
    prompt: 'p',
    toolResult: {
      name: 'quoteUpscaleCost',
      args: { upscaleModel: 'recraft', targetWidthInches: 36, targetHeightInches: 24 },
      response: { creditCost: 7, currentBalance: 12 },
      originalPrompt: 'What does recraft cost?',
    },
  }, rateLimit);

  const contents = calls[0].contents;
  assert.ok(Array.isArray(contents));
  assert.equal(contents.length, 3);
  assert.equal(contents[0].role, 'user');
  assert.equal(contents[0].parts[0].text, 'What does recraft cost?');
  assert.equal(contents[1].role, 'model');
  assert.equal(contents[1].parts[0].functionCall.name, 'quoteUpscaleCost');
  assert.equal(contents[2].role, 'user');
  const fnResp = contents[2].parts[0].functionResponse;
  assert.equal(fnResp.name, 'quoteUpscaleCost');
  assert.deepEqual(fnResp.response, { creditCost: 7, currentBalance: 12 });
  // No single-turn prompt passed on the continuation path.
  assert.equal(calls[0].prompt, undefined);
});

test('RC69: normal first turn applies rate limit + single-turn, returns toolCall', async () => {
  const calls = fakeGemini({
    candidates: [{
      content: {
        parts: [{
          functionCall: {
            name: 'quoteUpscaleCost',
            args: { upscaleModel: 'topaz', targetWidthInches: 24, targetHeightInches: 18 },
          },
        }],
      },
    }],
  });
  let rateLimitCalls = 0;
  const rateLimit = async () => {
    rateLimitCalls++;
    return { allowed: true, remaining: 8, packPurchased: false };
  };

  const result = await handleAskGemini('u1', {
    prompt: 'How much does topaz cost?',
  }, rateLimit);

  assert.equal(rateLimitCalls, 1, 'rate limit must be applied on first turn');
  assert.equal(result.toolCall?.name, 'quoteUpscaleCost');
  assert.equal(result.remainingQueries, 8);
  // Single-turn path: prompt passed, no pre-built contents.
  assert.equal(calls[0].prompt, 'How much does topaz cost?');
  assert.equal(calls[0].contents, undefined);
});
