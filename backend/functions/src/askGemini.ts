// backend/functions/src/askGemini.ts
//
// Spec B Phase B2: in-app Gemini Q&A backend.
//
// This file currently holds only the PURE HELPERS — tool definitions,
// system context builder, response parsers, and the rate-limit
// transaction. The onCall callable + the real @google/genai Gemini
// client land in Task 4 and Task 5 respectively. Splitting the work
// this way lets us TDD the helpers against pure-function mocks before
// any real Vertex AI surface is wired in.
//
// Tool-calling registers the same function signatures the
// PosterPdfAgentFunctions class (Phase B0) implements client-side —
// askGemini returns the function name + args, the Android client
// invokes the local implementation (or deep-links into the UI for
// paid actions).
//
// Spec: docs/superpowers/specs/2026-05-25-appfunctions-gemini-design.md

export const DAILY_QUERY_LIMIT = 10;

/** Tool definitions Gemini receives as the callable's `tools` parameter.
 *  Mirrors PosterPdfAgentFunctions's surface. Only the Phase B2-relevant
 *  function (quoteUpscaleCost) is exposed in this plan; the deferred
 *  Phase B1 plan adds the remaining 6 functions when EAP clears. */
export function buildToolDefinitions(): unknown[] {
  return [
    {
      functionDeclarations: [
        {
          name: 'quoteUpscaleCost',
          description:
            'Quote the credit cost for upscaling a poster with a specific AI model. ' +
            'Always call this BEFORE recommending a paid upscale model to the user. ' +
            'Returns the credit cost and the user\'s current balance so you can quote ' +
            'real numbers instead of guessing.',
          parameters: {
            type: 'object',
            properties: {
              upscaleModel: {
                type: 'string',
                enum: ['none', 'free_local', 'topaz', 'recraft', 'aurasr', 'esrgan', 'ccsr', 'seedvr'],
                description: 'Which AI upscaler to quote. Use \'free_local\' for the no-cost on-device option.',
              },
              targetWidthInches: { type: 'number', description: 'Desired poster width in inches.' },
              targetHeightInches: { type: 'number', description: 'Desired poster height in inches.' },
              targetDpi: {
                type: 'number',
                description: 'Print resolution. 150 for typical posters, 300 for high-quality prints. Default 150 if not provided.',
              },
            },
            required: ['upscaleModel', 'targetWidthInches', 'targetHeightInches'],
          },
        },
      ],
    },
  ];
}

/** Build the system instruction for Gemini. Tells the model what app it's
 *  helping with + what state the user is currently in. */
export function buildSystemContext(state: {
  selectedImageMp?: number;
  targetWidthInches?: number;
  targetHeightInches?: number;
  paperSize?: string;
  currentUpscaleModel?: string;
}): string {
  const hasImage = typeof state.selectedImageMp === 'number';
  const imageLine = hasImage
    ? `The user has selected an image (${state.selectedImageMp?.toFixed(1)} MP).`
    : 'The user has not yet selected an image (no image attached).';
  const sizeLine = (state.targetWidthInches && state.targetHeightInches)
    ? `Target poster size: ${state.targetWidthInches}×${state.targetHeightInches} inches.`
    : 'Target poster size: not yet set.';
  const paperLine = state.paperSize ? `Paper size: ${state.paperSize}.` : '';
  const modelLine = state.currentUpscaleModel
    ? `Currently selected upscale model: ${state.currentUpscaleModel}.`
    : '';

  return [
    'You are an in-app assistant for PosterPDF, an Android app that turns ' +
    'a source image into a multi-page tiled PDF poster the user prints + tapes.',
    imageLine,
    sizeLine,
    paperLine,
    modelLine,
    'You help with paper sizes, image sharpness/DPI, upscaler choices, and ' +
    'general how-to questions about making posters. Answer most questions ' +
    'directly in one or two concise sentences. ONLY call the quoteUpscaleCost ' +
    'tool when the user explicitly asks about the price, cost, or credit count ' +
    'of a paid upscale — never for general questions. After a tool result is ' +
    'provided, weave the real numbers into a natural sentence; do not dump the ' +
    'raw figures. If the user asks something unrelated to PosterPDF, politely ' +
    'redirect.',
  ].filter(s => s.length > 0).join('\n\n');
}

/** Pull a function-call out of a Gemini response. Returns null if the
 *  reply was plain text (no tool use). Handles the multi-part `content.parts`
 *  shape Gemini 3.5 Flash returns. */
export function parseToolCallFromResponse(
  response: unknown,
): { name: string; args: Record<string, unknown> } | null {
  const r = response as {
    candidates?: Array<{
      content?: {
        parts?: Array<{ functionCall?: { name: string; args: Record<string, unknown> } }>;
      };
    }>;
  };
  const parts = r?.candidates?.[0]?.content?.parts ?? [];
  for (const part of parts) {
    if (part.functionCall) {
      return { name: part.functionCall.name, args: part.functionCall.args ?? {} };
    }
  }
  return null;
}

/** Extract the plain text reply from a Gemini response. Returns empty
 *  string if the response is only a function call (no text). Concatenates
 *  multiple text parts (Gemini sometimes streams text in chunks even in
 *  the non-streaming API). */
export function parseTextFromResponse(response: unknown): string {
  const r = response as {
    candidates?: Array<{
      content?: {
        parts?: Array<{ text?: string }>;
      };
    }>;
  };
  const parts = r?.candidates?.[0]?.content?.parts ?? [];
  return parts.map(p => p.text ?? '').join('');
}

/** Injectable deps for [applyRateLimit] so it can be unit-tested with a
 *  fake Firestore. Production callers pass the real getFirestore() client
 *  plus FieldValue.increment / serverTimestamp sentinels. */
export interface RateLimitDeps {
  firestore: {
    runTransaction: <T>(
      fn: (txn: {
        get: (ref: { path: string }) => Promise<{
          exists: boolean;
          data: () => Record<string, unknown> | undefined;
        }>;
        set: (ref: { path: string }, val: Record<string, unknown>) => void;
        update: (ref: { path: string }, val: Record<string, unknown>) => void;
      }) => Promise<T>,
    ) => Promise<T>;
  };
  quotaDocRef: { path: string };   // /users/{uid}/quota/askGemini
  userDocRef: { path: string };    // /users/{uid}
  creditLogColl: {                 // /users/{uid}/creditLog
    doc: () => { path: string };
  };
  fieldIncrement: (n: number) => unknown;   // FieldValue.increment shim
  serverTimestamp: () => unknown;            // FieldValue.serverTimestamp shim
  now: Date;
}

/** RC68: Atomically apply the 3-tier rate-limit policy.
 *
 *  Tier 1 — free daily allowance (DAILY_QUERY_LIMIT/day, resets at UTC midnight).
 *  Tier 2 — paid pack remainder (rolls over forever).
 *  Tier 3 — auto-buy a fresh 10-pack for 1 credit if the user has credits.
 *
 *  Returns:
 *    allowed        — whether this call may proceed.
 *    remaining      — combined remaining quota AFTER this call.
 *    packPurchased  — true iff this call triggered an auto-buy (for telemetry).
 *
 *  Day key is the UTC ISO date (YYYY-MM-DD) — intentionally not timezone-
 *  aware (per-user TZ tracking would be real complexity for no real benefit
 *  on a "free queries/day" feature). */
export async function applyRateLimit(
  deps: RateLimitDeps,
): Promise<{ allowed: boolean; remaining: number; packPurchased: boolean }> {
  const todayKey = deps.now.toISOString().slice(0, 10); // YYYY-MM-DD UTC
  return deps.firestore.runTransaction(async (txn) => {
    const quotaSnap = await txn.get(deps.quotaDocRef);
    const userSnap = await txn.get(deps.userDocRef);
    const quotaData = (quotaSnap.exists ? quotaSnap.data() : undefined) ?? {};
    const userData = (userSnap.exists ? userSnap.data() : undefined) ?? {};

    const sameDay = quotaData.dayKey === todayKey;
    const freeUsedToday = sameDay ? Number(quotaData.freeUsedToday ?? 0) : 0;
    const paidRemaining = Number(quotaData.paidRemaining ?? 0);
    const credits = Number(userData.credits ?? 0);

    // Tier 1: free daily allowance.
    if (freeUsedToday < DAILY_QUERY_LIMIT) {
      const nextFree = freeUsedToday + 1;
      txn.set(deps.quotaDocRef, {
        freeUsedToday: nextFree,
        dayKey: todayKey,
        paidRemaining,
      });
      return {
        allowed: true,
        remaining: (DAILY_QUERY_LIMIT - nextFree) + paidRemaining,
        packPurchased: false,
      };
    }

    // Tier 2: paid pack remainder rolls over across days.
    if (paidRemaining > 0) {
      const nextPaid = paidRemaining - 1;
      txn.set(deps.quotaDocRef, {
        freeUsedToday,
        dayKey: todayKey,
        paidRemaining: nextPaid,
      });
      return { allowed: true, remaining: nextPaid, packPurchased: false };
    }

    // Tier 3: auto-buy a 10-pack for 1 credit.
    if (credits < 1) {
      return { allowed: false, remaining: 0, packPurchased: false };
    }
    // Mirror upscale.ts's debit pattern: FieldValue.increment(-1) on
    // /users/{uid}.credits + a creditLog entry for the audit trail.
    txn.update(deps.userDocRef, {
      credits: deps.fieldIncrement(-1),
      updatedAt: deps.serverTimestamp(),
    });
    const logRef = deps.creditLogColl.doc();
    txn.set(logRef, {
      kind: 'gemini_pack',
      delta: -1,
      balanceBefore: credits,
      balanceAfter: credits - 1,
      createdAt: deps.serverTimestamp(),
    });
    // This send consumes 1 of the 10-pack we just bought.
    txn.set(deps.quotaDocRef, {
      freeUsedToday,
      dayKey: todayKey,
      paidRemaining: 9,
    });
    return { allowed: true, remaining: 9, packPurchased: true };
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// Callable wrapper
// ─────────────────────────────────────────────────────────────────────────────

import { onCall, HttpsError } from 'firebase-functions/v2/https';
import { getFirestore, FieldValue } from 'firebase-admin/firestore';

/** Injectable Gemini client. Real impl lands in Task 5; here we declare the
 *  shape so the callable can be built + tested with a stub today. */
export interface GeminiClient {
  generate: (args: {
    model: string;
    systemInstruction: string;
    tools: unknown[];
    prompt?: string;            // single-turn convenience
    imageGsUri?: string | null;
    contents?: unknown[];       // multi-turn: when provided, use verbatim
  }) => Promise<unknown>;
}

/** Stub Gemini client — returns a canned text response. Replaced in Task 5
 *  by a real @google/genai client wired to Vertex AI. We default to the
 *  stub so the callable compiles + deploys today without burning Gemini
 *  tokens; production deploy in Task 5 calls setGeminiClient() before any
 *  user request can land. */
let geminiClient: GeminiClient = {
  generate: async () => ({
    candidates: [{
      content: { parts: [{ text: 'Gemini integration not yet wired (Task 5 placeholder).' }] },
    }],
  }),
};

/** Module-init hook — Task 5 swaps the production @google/genai client in
 *  here. Exported so future tests can inject a controlled fake. */
export function setGeminiClient(client: GeminiClient): void {
  geminiClient = client;
}

interface AskGeminiInput {
  prompt: string;
  imageGsUri?: string;
  currentSettings?: {
    selectedImageMp?: number;
    targetWidthInches?: number;
    targetHeightInches?: number;
    paperSize?: string;
    currentUpscaleModel?: string;
  };
  /** Continuation payload (RC69): when present, this call is the second
   *  turn of a tool round-trip. The client executed the tool locally and
   *  passes the result back so Gemini composes a natural-language reply.
   *  This path skips applyRateLimit (no double-charge). */
  toolResult?: {
    name: string;                      // tool that was called
    args: Record<string, unknown>;     // the args Gemini sent
    response: Record<string, unknown>; // what the client computed
    originalPrompt: string;            // the user's original question
  };
}

/** Result shape returned to the client. */
export interface AskGeminiResult {
  text: string;
  toolCall: { name: string; args: Record<string, unknown> } | null;
  remainingQueries: number;
  packPurchased?: boolean;
}

/** Core handler, factored out of the onCall wrapper so it can be unit-tested
 *  with a fake GeminiClient + a fake rate-limit function. The production
 *  callable passes the real applyRateLimit (wired to Firestore); tests inject
 *  a fake that records / throws as needed. */
export async function handleAskGemini(
  uid: string | undefined,
  data: Partial<AskGeminiInput>,
  rateLimit: () => Promise<{ allowed: boolean; remaining: number; packPurchased: boolean }>,
): Promise<AskGeminiResult> {
  if (!uid) {
    throw new HttpsError('unauthenticated', 'sign-in required');
  }
  const prompt = data.prompt;
  if (typeof prompt !== 'string' || prompt.trim().length === 0) {
    throw new HttpsError('invalid-argument', 'prompt is required');
  }
  if (prompt.length > 2000) {
    throw new HttpsError('invalid-argument', 'prompt must be ≤ 2000 characters');
  }

  const systemInstruction = buildSystemContext(data.currentSettings ?? {});
  const tools = buildToolDefinitions();

  // ── Continuation turn (RC69): client already executed the tool locally
  // and passes the result back. Replay [user prompt → model functionCall →
  // functionResponse] so Gemini composes a natural reply with the real
  // numbers.
  //
  // RC72 (security review): this is a REAL Vertex AI generateContent call,
  // so it must be metered. The continuation was previously unmetered
  // (returned remainingQueries=-1, skipped rate-limit) on the theory that
  // it's "the same logical query as turn 1" — but nothing ties turn 2 to a
  // billed turn 1, so a client could forge a toolResult and loop this
  // branch for unlimited unmetered generations. We now (a) reject tool
  // names not in our declared set and (b) apply the same rate limit. A
  // tool-using question therefore costs 2 query-units instead of 1, which
  // is negligible (10 free/day + 1¢/10-pack) and closes the abuse vector.
  if (data.toolResult) {
    const tr = data.toolResult;
    const validToolNames: string[] = (buildToolDefinitions() as Array<{
      functionDeclarations?: Array<{ name: string }>;
    }>).flatMap(t => (t.functionDeclarations ?? []).map(fd => fd.name));
    if (!validToolNames.includes(tr.name)) {
      throw new HttpsError('invalid-argument', `Unknown tool: ${tr.name}`);
    }
    const contRate = await rateLimit();
    if (!contRate.allowed) {
      throw new HttpsError(
        'resource-exhausted',
        'Insufficient credits to continue Gemini Q&A. Tap the credit chip to buy more.',
      );
    }
    const contents = [
      { role: 'user', parts: [{ text: tr.originalPrompt }] },
      { role: 'model', parts: [{ functionCall: { name: tr.name, args: tr.args } }] },
      { role: 'user', parts: [{ functionResponse: { name: tr.name, response: tr.response } }] },
    ];
    const response = await geminiClient.generate({
      model: 'gemini-3.5-flash',
      systemInstruction,
      tools,
      contents,
    });
    return {
      text: parseTextFromResponse(response),
      toolCall: null,
      remainingQueries: contRate.remaining,
    };
  }

  // ── First turn: rate limit + single-turn generate.
  const rate = await rateLimit();
  if (!rate.allowed) {
    throw new HttpsError(
      'resource-exhausted',
      'Insufficient credits to continue Gemini Q&A. Tap the credit chip to buy more.',
    );
  }

  // Build context + dispatch to (stub or real) Gemini client.
  const response = await geminiClient.generate({
    model: 'gemini-3.5-flash',
    systemInstruction,
    tools,
    prompt,
    imageGsUri: data.imageGsUri ?? null,
  });

  return {
    text: parseTextFromResponse(response),
    toolCall: parseToolCallFromResponse(response),
    remainingQueries: rate.remaining,
    packPurchased: rate.packPurchased,
  };
}

export const askGemini = onCall(
  {
    region: 'us-central1',
    timeoutSeconds: 60,
    memory: '512MiB',
  },
  async (request) => {
    const uid = request.auth?.uid;
    const data = (request.data ?? {}) as Partial<AskGeminiInput>;

    // Rate limit: free 10/day + auto-buy 10-pack for 1 credit, transactional.
    // Bound here (not inside handleAskGemini) so the continuation path can
    // simply never call it.
    const rateLimit = () => {
      const db = getFirestore();
      const userDocRef = db.collection('users').doc(uid!);
      const quotaDocRef = userDocRef.collection('quota').doc('askGemini');
      const creditLogColl = userDocRef.collection('creditLog');
      return applyRateLimit({
        firestore: db as unknown as RateLimitDeps['firestore'],
        quotaDocRef: quotaDocRef as unknown as RateLimitDeps['quotaDocRef'],
        userDocRef: userDocRef as unknown as RateLimitDeps['userDocRef'],
        creditLogColl: creditLogColl as unknown as RateLimitDeps['creditLogColl'],
        fieldIncrement: (n: number) => FieldValue.increment(n),
        serverTimestamp: () => FieldValue.serverTimestamp(),
        now: new Date(),
      });
    };

    return handleAskGemini(uid, data, rateLimit);
  },
);

// ─────────────────────────────────────────────────────────────────────────────
// Production GeminiClient — real Vertex AI integration
// ─────────────────────────────────────────────────────────────────────────────

import { GoogleGenAI } from '@google/genai';

/** Build the production GeminiClient backed by @google/genai + Vertex AI.
 *  Authenticates via the Cloud Function's default service account
 *  credentials (same path as RC60's vertex-imagen.ts —
 *  google-auth-library transitively, no API key, no secret).
 *
 *  Model: gemini-3.5-flash on the `global` endpoint. The 3.5 Flash
 *  publisher model is only served from `publishers/google/models/
 *  gemini-3.5-flash` at the GLOBAL location — us-central1 returns
 *  404. RC65 originally shipped with `gemini-3-5-flash` (hyphen, not
 *  dot) AND us-central1; both wrong. RC67 corrects to the dot form
 *  + global. Probe before swapping when newer Flash versions land. */
function buildProductionGeminiClient(): GeminiClient {
  const ai = new GoogleGenAI({
    vertexai: true,
    project: 'static-webbing-461904-c4',
    location: 'global',
  });
  return {
    generate: async (args) => {
      // Multi-turn continuation (RC69): when the caller supplies a pre-built
      // contents array, pass it through verbatim (it already encodes the
      // [user → model functionCall → functionResponse] round-trip).
      // Otherwise build the single-turn contents from prompt + optional image:
      // image (if any) goes first, text second — matches the SDK's documented
      // multipart input shape.
      let contents: unknown;
      if (args.contents) {
        contents = args.contents;
      } else {
        const parts: Array<unknown> = [];
        if (args.imageGsUri) {
          parts.push({ fileData: { mimeType: 'image/png', fileUri: args.imageGsUri } });
        }
        parts.push({ text: args.prompt });
        contents = [{ role: 'user', parts }];
      }

      const response = await ai.models.generateContent({
        model: args.model,
        contents: contents as never,
        config: {
          systemInstruction: args.systemInstruction,
          tools: args.tools as never,
        },
      });
      return response as unknown;
    },
  };
}

// Swap the Task 4 stub for the production client at module load. This runs
// during Cloud Function cold-start, BEFORE any user request can land, so
// there's no race window where the stub could answer a real call.
setGeminiClient(buildProductionGeminiClient());
