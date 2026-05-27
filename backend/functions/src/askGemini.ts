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
                enum: ['none', 'free_local', 'topaz', 'recraft', 'aurasr', 'esrgan', 'ccsr', 'imagen'],
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
    'When the user asks about credit cost for an AI upscale, ALWAYS call the ' +
    'quoteUpscaleCost tool first and use the returned numbers in your reply ' +
    '(not your own estimate). Be concise; one or two sentences per reply unless ' +
    'the user asks for more detail. If the user asks something unrelated to ' +
    'PosterPDF, politely redirect.',
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
 *  fake Firestore. Production callers pass the real getFirestore() client. */
export interface RateLimitDeps {
  firestore: {
    runTransaction: <T>(
      fn: (txn: {
        get: (ref: { path: string }) => Promise<{
          exists: boolean;
          data: () => { count?: number; dayKey?: string } | undefined;
        }>;
        set: (
          ref: { path: string },
          val: { count: number; dayKey: string },
        ) => void;
      }) => Promise<T>,
    ) => Promise<T>;
  };
  docRef: { path: string };
  now: Date;
}

/** Increment the user's daily quota counter atomically. Returns whether
 *  the call is allowed AND how many queries remain after this one.
 *  Day key is the UTC ISO date (YYYY-MM-DD) — intentionally not timezone-
 *  aware (per-user TZ tracking would be real complexity for no real benefit
 *  on a "free queries/day" feature). */
export async function applyRateLimit(
  deps: RateLimitDeps,
): Promise<{ allowed: boolean; remaining: number }> {
  const todayKey = deps.now.toISOString().slice(0, 10); // YYYY-MM-DD UTC
  return deps.firestore.runTransaction(async (txn) => {
    const snap = await txn.get(deps.docRef);
    const data = snap.exists ? (snap.data() ?? {}) : {};
    const sameDay = data.dayKey === todayKey;
    const currentCount = sameDay ? (data.count ?? 0) : 0;
    if (currentCount >= DAILY_QUERY_LIMIT) {
      return { allowed: false, remaining: 0 };
    }
    const nextCount = currentCount + 1;
    txn.set(deps.docRef, { count: nextCount, dayKey: todayKey });
    return { allowed: true, remaining: DAILY_QUERY_LIMIT - nextCount };
  });
}
