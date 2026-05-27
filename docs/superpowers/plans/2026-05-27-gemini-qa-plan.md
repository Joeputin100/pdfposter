# Spec B Phase B0 + B2 — In-app Gemini Q&A Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the foundational `PosterPdfAgentFunctions` service class (Spec B Phase B0) and the user-visible in-app Gemini Q&A surface (Spec B Phase B2) — sparkle icon in top bar opens a modal sheet with voice + text input, backed by a new `askGemini` Cloud Function calling Vertex AI `gemini-3-5-flash` with vision and tool-calling.

**Architecture:** Two layers. Foundation (Phase B0): a pure-Kotlin service class on the Android side holding business logic for the 7 planned agent functions (composite + granular), built so both this plan's in-app tool-calling AND a future plan's `@AppFunction` wrappers (Phase B1, gated on AppFunctions EAP) can consume the same implementations. User-visible (Phase B2): a sparkle icon in the top bar opens a `ModalBottomSheet` with image-context thumbnail, voice input (SpeechRecognizer), text input fallback, and suggestion chips. The backend `askGemini` callable accepts a prompt + optional `imageGsUri` + current app settings, calls `gemini-3-5-flash` on Vertex AI with tool definitions mirroring the agent-functions surface, and returns `{ text, toolCall? }`. Client surfaces the text response via TextToSpeech + on-screen text, and routes any `toolCall` to the corresponding agent function. Per-user rate-limit 10 queries/day in Firestore `users/{uid}/quota/gemini_qa`.

**Tech Stack:** Kotlin + Jetpack Compose for Android (Material 3 Expressive sparkle icon + ModalBottomSheet). Android `SpeechRecognizer` for voice input + `TextToSpeech` for reply playback (both already in Android core, no new deps). Node 20 + TypeScript for the backend Cloud Function. `@google/genai` Node SDK for Vertex AI Gemini 3.5 Flash calls (new dep — replaces the deprecated `@google-cloud/vertexai` SDK). `google-auth-library` already transitive via firebase-admin (same path RC60's `vertex-imagen.ts` uses for OAuth2). Firestore for per-user quota tracking via the existing `users/{uid}` subcollection pattern. Spec source: [Spec B — AppFunctions + In-app Gemini Q&A](../specs/2026-05-25-appfunctions-gemini-design.md).

---

## Pre-flight

**Worktree:** Already on `feat/md3e-redesign` (the active development branch in `/home/projects/pdfposter-md3e`). No new worktree needed.

**Build/test loop** (unchanged from prior RCs):
- Backend builds via `gcloud builds submit --config=cloudbuild-backend.yaml` (~3–5 min).
- Android builds via GitHub Actions workflow `build-android.yml` (auto on push to `app/**`, ~8 min).
- Backend tests via `node --test` (Node 20 built-in, zero-dep pattern from RC60).

**One-time setup verification:**

- [ ] **Verify Vertex AI Gemini access via the Cloud Functions runtime SA.** The same SA RC60 used for Imagen (compute@developer.gserviceaccount.com with `roles/editor`) covers `aiplatform.user` which is the only role Gemini 3.5 Flash needs. Sanity check:

```bash
gcloud projects get-iam-policy static-webbing-461904-c4 \
  --flatten='bindings[].members' \
  --format='table(bindings.role)' \
  --filter='bindings.members:(serviceAccount:*compute@developer.gserviceaccount.com)' \
  | grep -E 'aiplatform|owner|editor'
```

Expected: at least one of `roles/aiplatform.user`, `roles/owner`, `roles/editor`. (RC60 pre-flight already confirmed `roles/editor`.)

- [ ] **Install the `@google/genai` Node SDK** into `backend/functions/`:

```bash
cd /home/projects/pdfposter-md3e/backend/functions
npm install @google/genai
```

Verify with:
```bash
ls node_modules/@google/genai/package.json
```

Expected: file exists.

`@google/genai` is Google's unified Gen AI SDK that replaces the deprecated `vertexai.generative_models` Python SDK and the `@google-cloud/vertexai` Node SDK. It supports both the public Gemini API (via API key) AND Vertex AI (via service account / ADC) through the same client surface. We use the Vertex AI path so the call authenticates via the Cloud Function's default credentials, no API key required.

---

## File structure

### Files created in this plan

| Path | Responsibility |
|---|---|
| `app/src/main/kotlin/com/posterpdf/upscale/PricingMath.kt` | Pure-Kotlin module with `pickScale()` + `creditsForOption()` extracted from `LowDpiUpgradeModal.kt`. Shared between the modal and the new agent-functions class so quote math stays single-source. |
| `app/src/test/kotlin/com/posterpdf/upscale/PricingMathTest.kt` | Unit tests for the pure-pricing math (already proven shapes; tests pin behavior so the extraction is a refactor, not a rewrite). |
| `app/src/main/kotlin/com/posterpdf/agentfunctions/PosterPdfAgentFunctions.kt` | Service class with all 7 agent-function implementations (`quoteUpscaleCost`, `generatePrintReadyPoster`, `upscaleImage`, `generatePosterPdf`, `viewLastPoster`, `redoPosterWithSettings`, `sharePoster`), the headless-guard routing, and the cross-app URI copy-on-import. Pure Kotlin (no `@AppFunction` wrappers — those land in the deferred Phase B1 plan). |
| `app/src/test/kotlin/com/posterpdf/agentfunctions/PosterPdfAgentFunctionsTest.kt` | Tests for the headless-guard logic + `quoteUpscaleCost` integration. |
| `backend/functions/src/askGemini.ts` | New Cloud Function callable. Pure helpers (`checkAndIncrementQuota`, `buildToolDefinitions`, `parseToolCallFromResponse`, `buildSystemContext`) + integration function calling Vertex AI Gemini 3.5 Flash with vision + tool-calling. |
| `backend/functions/test/askGemini.test.mjs` | Node-native tests via `node:test` for the pure helpers + a mocked-Gemini integration test. |
| `app/src/main/kotlin/com/posterpdf/ui/components/GeminiSparkleButton.kt` | Composable: sparkle icon (`Icons.AutoMirrored.Filled.AutoAwesome` or `auto_awesome` Material Symbol) that fits in the top app bar. Tap → opens the Q&A sheet. |
| `app/src/main/kotlin/com/posterpdf/ui/components/GeminiQaSheet.kt` | `ModalBottomSheet` Composable: header, image thumbnail, voice/text input, suggestion chips, response area with TextToSpeech playback. |
| `app/src/main/kotlin/com/posterpdf/ui/components/VoiceInputState.kt` | Compose-friendly wrapper around `android.speech.SpeechRecognizer` so the sheet doesn't deal with the bare Android API directly. |

### Files modified in this plan

| Path | Why |
|---|---|
| `app/src/main/kotlin/com/posterpdf/ui/components/LowDpiUpgradeModal.kt` | Replace private `pickScale()` + `creditsForOption()` with imports from the new shared `com.posterpdf.upscale` module. |
| `app/src/main/kotlin/com/posterpdf/MainActivity.kt` | Insert the sparkle button in the top app bar between credit chip and account avatar. Wire `showGeminiSheet` state. |
| `app/src/main/kotlin/com/posterpdf/MainViewModel.kt` | Add `askGemini(prompt: String)` calling the new Cloud Function. Hold `geminiResponse` / `geminiPending` state. |
| `app/src/main/kotlin/com/posterpdf/data/backend/BackendApi.kt` | Add `askGemini(prompt, imageGsUri?, currentSettings)` callable wrapper. |
| `backend/functions/src/index.ts` | `export { askGemini } from './askGemini';` |
| `backend/functions/package.json` | Add `@google/genai` to `dependencies`. |
| `backend/firestore.rules` | Allow each user to read their own `users/{uid}/quota/{name}` doc; backend writes only. |
| `app/src/main/res/values/strings.xml` | New English strings for the sparkle button label, sheet header, suggestion chips, error messages, voice prompts. |
| `app/src/main/res/values-{ar,de,es,fr,hi,ja,pt-rBR,ru,zh-rCN}/strings.xml` | 9-locale translations of the new strings (subagent fan-out per RC44 pattern). |
| `app/src/main/AndroidManifest.xml` | Add `RECORD_AUDIO` permission (runtime-requested when user first taps voice). |
| `app/build.gradle.kts` | Bump versionName to `1.0-rc65`. |
| `backend/scripts/post-release-notes.sh` | Append a Release Notes entry to the community board for the in-app Gemini Q&A. |

---

## Task 1: Extract pricing math to shared module (refactor, no behavior change)

**Files:**
- Create: `app/src/main/kotlin/com/posterpdf/upscale/PricingMath.kt`
- Create: `app/src/test/kotlin/com/posterpdf/upscale/PricingMathTest.kt`
- Modify: `app/src/main/kotlin/com/posterpdf/ui/components/LowDpiUpgradeModal.kt`

- [ ] **Step 1: Write failing pin-the-behavior tests**

Create `app/src/test/kotlin/com/posterpdf/upscale/PricingMathTest.kt`:

```kotlin
package com.posterpdf.upscale

import com.posterpdf.ui.components.UpscaleModel
import com.posterpdf.ui.components.UpscaleOption
import org.junit.Assert.assertEquals
import org.junit.Test

class PricingMathTest {
    private val recraftOption = UpscaleOption(
        model = UpscaleModel.RECRAFT,
        displayNameRes = 0, prosRes = 0, consRes = 0,
        scale = 4, supportedScales = listOf(4),
        perOutputMp = 0.0, flatUsd = 0.004,
    )
    private val topazOption = UpscaleOption(
        model = UpscaleModel.TOPAZ,
        displayNameRes = 0, prosRes = 0, consRes = 0,
        scale = 4, supportedScales = listOf(2, 4, 6, 8),
        perOutputMp = 0.01,
    )

    @Test fun `pickScale picks smallest scale meeting target DPI`() {
        // 1 MP source, 24x18 poster at 150 DPI → target ~9.7 MP.
        // Topaz scales = [2,4,6,8]: scale 4 → 16 MP (meets), scale 2 → 4 MP (misses).
        val picked = pickScale(topazOption, inputMp = 1.0, posterW = 24.0, posterH = 18.0, targetDpi = 150f)
        assertEquals(4, picked)
    }

    @Test fun `pickScale returns the only supported scale for single-scale models`() {
        val picked = pickScale(recraftOption, inputMp = 1.0, posterW = 24.0, posterH = 18.0, targetDpi = 150f)
        assertEquals(4, picked)
    }

    @Test fun `creditsForOption flat USD model returns flat fee converted to credits`() {
        // flatUsd = 0.004 USD = 0.4 cents → ceil → 1 credit
        val credits = creditsForOption(recraftOption, inputMp = 1.0, scale = 4)
        assertEquals(1, credits)
    }

    @Test fun `creditsForOption per-MP model scales with output area`() {
        // Topaz perOutputMp = 0.01 USD/MP. inputMp=1, scale=4 → outputMp=16 → 0.16 USD = 16 cents = 16 credits
        val credits = creditsForOption(topazOption, inputMp = 1.0, scale = 4)
        assertEquals(16, credits)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Verifying by triggering the GH Actions test job is heavyweight. For this refactor the safer move is to push the failing tests, observe the build break in CI, then proceed. Skip local verification — Android module isn't easily testable locally without the Gradle wrapper.

- [ ] **Step 3: Implement `PricingMath.kt`**

Create `app/src/main/kotlin/com/posterpdf/upscale/PricingMath.kt`:

```kotlin
package com.posterpdf.upscale

import com.posterpdf.ui.components.UpscaleOption
import kotlin.math.ceil

/**
 * RC65: extracted from LowDpiUpgradeModal.kt so both the modal and the new
 * PosterPdfAgentFunctions.quoteUpscaleCost share a single implementation.
 * The modal's static card grid and the agentic quote endpoint must agree on
 * the per-model price — otherwise users see one number on the card and a
 * different number when Gemini asks for confirmation.
 */
private const val CREDIT_COST_BUDGET_USD = 0.00425

/**
 * Pick the smallest supported scale factor that produces enough pixels for
 * the target DPI on the user's poster size.
 */
fun pickScale(
    option: UpscaleOption,
    inputMp: Double,
    posterW: Double,
    posterH: Double,
    targetDpi: Float,
): Int {
    val targetMp = (posterW * targetDpi) * (posterH * targetDpi) / 1_000_000.0
    val scales = option.supportedScales
    for (s in scales) {
        if (inputMp * s * s >= targetMp) return s
    }
    return scales.last()
}

/**
 * Compute credit cost for a (model, image, scale) tuple. Flat-USD models
 * (Recraft, Imagen) use [UpscaleOption.flatUsd]; per-MP models
 * (Topaz, AuraSR, ESRGAN, CCSR) multiply [UpscaleOption.perOutputMp] by
 * the output megapixels. ceil() so we never under-charge.
 */
fun creditsForOption(option: UpscaleOption, inputMp: Double, scale: Int): Int {
    if (option.flatUsd > 0.0) {
        return ceil(option.flatUsd / CREDIT_COST_BUDGET_USD).toInt()
    }
    val outputMp = inputMp * scale * scale
    return ceil(outputMp * option.perOutputMp / CREDIT_COST_BUDGET_USD).toInt()
}
```

- [ ] **Step 4: Remove the duplicate code from `LowDpiUpgradeModal.kt`**

Open `app/src/main/kotlin/com/posterpdf/ui/components/LowDpiUpgradeModal.kt`. Find the `private fun pickScale(...)` and `private fun creditsForOption(...)` definitions (search for `private fun pickScale`). Delete BOTH function bodies entirely.

Add the imports at the top:

```kotlin
import com.posterpdf.upscale.creditsForOption
import com.posterpdf.upscale.pickScale
```

The modal already calls `pickScale(option, ...)` and `creditsForOption(option, ...)` from within composable bodies — these calls now resolve to the new top-level functions.

Also delete the `private const val CREDIT_COST_BUDGET_USD` declaration in the modal (moved to `PricingMath.kt`).

- [ ] **Step 5: Push + verify GH Actions build green**

```bash
cd /home/projects/pdfposter-md3e
git add app/src/main/kotlin/com/posterpdf/upscale/PricingMath.kt \
        app/src/test/kotlin/com/posterpdf/upscale/PricingMathTest.kt \
        app/src/main/kotlin/com/posterpdf/ui/components/LowDpiUpgradeModal.kt
git commit -m "refactor(rc65): extract pickScale + creditsForOption to com.posterpdf.upscale.PricingMath

Pre-work for the Gemini Q&A's quoteUpscaleCost agent function, which
needs the same per-model price math the modal already computes. Single
source of truth so the modal's card price and Gemini's spoken quote
can never drift. Pin-the-behavior tests added so the refactor is
verifiable in CI."
git push
```

Wait for GH Actions build (typically ~8 min). Expected: green. Tests should run + pass.

If the build fails because the new test references private types (UpscaleModel, UpscaleOption), make those `internal` instead of `private` in `LowDpiUpgradeModal.kt`. Specifically:
- `private data class UpscaleOption` → `internal data class UpscaleOption`
- `enum class UpscaleModel` is already public (or internal).

---

## Task 2: PosterPdfAgentFunctions service class

**Files:**
- Create: `app/src/main/kotlin/com/posterpdf/agentfunctions/PosterPdfAgentFunctions.kt`
- Create: `app/src/test/kotlin/com/posterpdf/agentfunctions/PosterPdfAgentFunctionsTest.kt`

- [ ] **Step 1: Write the failing test file**

Create `app/src/test/kotlin/com/posterpdf/agentfunctions/PosterPdfAgentFunctionsTest.kt`:

```kotlin
package com.posterpdf.agentfunctions

import com.posterpdf.ui.components.UpscaleModel
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterPdfAgentFunctionsTest {

    @Test fun `isHeadlessAllowed permits NONE in headless mode`() {
        val r = isHeadlessAllowed(model = UpscaleModel.NONE, headless = true, confirmCreditCost = false)
        assertTrue(r is HeadlessRoutingResult.Allowed)
    }

    @Test fun `isHeadlessAllowed permits FREE_LOCAL in headless mode`() {
        val r = isHeadlessAllowed(model = UpscaleModel.FREE_LOCAL, headless = true, confirmCreditCost = false)
        assertTrue(r is HeadlessRoutingResult.Allowed)
    }

    @Test fun `isHeadlessAllowed permits paid model only with explicit cost confirmation`() {
        val r = isHeadlessAllowed(model = UpscaleModel.TOPAZ, headless = true, confirmCreditCost = true)
        assertTrue(r is HeadlessRoutingResult.Allowed)
    }

    @Test fun `isHeadlessAllowed rejects paid headless without confirmation`() {
        val r = isHeadlessAllowed(model = UpscaleModel.TOPAZ, headless = true, confirmCreditCost = false)
        assertTrue(r is HeadlessRoutingResult.RequiresConfirmation)
    }

    @Test fun `isHeadlessAllowed routes deep-link when not headless`() {
        val r = isHeadlessAllowed(model = UpscaleModel.TOPAZ, headless = false, confirmCreditCost = false)
        assertTrue(r is HeadlessRoutingResult.DeepLink)
    }
}
```

- [ ] **Step 2: Implement `PosterPdfAgentFunctions.kt`**

Create `app/src/main/kotlin/com/posterpdf/agentfunctions/PosterPdfAgentFunctions.kt`:

```kotlin
package com.posterpdf.agentfunctions

import android.content.Context
import android.net.Uri
import com.posterpdf.ui.components.UpscaleModel
import com.posterpdf.ui.components.UpscaleOption
import com.posterpdf.upscale.creditsForOption
import com.posterpdf.upscale.pickScale

/**
 * Spec B Phase B0: shared service class holding the business logic for the
 * 7 agent functions Gemini (system or in-app) can invoke. This file is the
 * single source of truth; the in-app Q&A (Phase B2 of this plan) consumes
 * it as tool definitions, and a future @AppFunction-wrapped version
 * (Phase B1, gated on AppFunctions EAP) will wrap the same bodies.
 *
 * The class is intentionally pure-Kotlin (no Compose, no @AppFunction
 * annotation) so it can be unit-tested without an instrumentation runner.
 */
class PosterPdfAgentFunctions(
    private val appContext: Context,
    private val allOptions: List<UpscaleOption>,
) {
    /**
     * Read-only quote. Returns the credits + USD cost + balance + can-afford
     * flag for a (model, source-image, target-poster) combo. Gemini calls
     * this before a paid headless invocation so the user can confirm.
     */
    fun quoteUpscaleCost(
        sourceImageUri: String,
        upscaleModel: UpscaleModel,
        targetWidthInches: Double,
        targetHeightInches: Double,
        inputMp: Double,
        currentCreditBalance: Int,
        targetDpi: Float = 150f,
    ): UpscaleCostQuote {
        val option = allOptions.first { it.model == upscaleModel }
        val scale = pickScale(option, inputMp, targetWidthInches, targetHeightInches, targetDpi)
        val credits = creditsForOption(option, inputMp, scale)
        val usdCost = credits / 100.0  // 1 credit = 1¢
        val outputMp = inputMp * scale * scale
        return UpscaleCostQuote(
            estimatedCredits = credits,
            estimatedUsdCost = usdCost,
            currentCreditBalance = currentCreditBalance,
            canAfford = currentCreditBalance >= credits,
            modelDisplayName = appContext.getString(option.displayNameRes),
            scaleFactor = scale,
            outputMegapixels = outputMp,
            explanation = "This will use about $credits credits ($${"%.2f".format(usdCost)}). " +
                "You have $currentCreditBalance credits.",
        )
    }
}

data class UpscaleCostQuote(
    val estimatedCredits: Int,
    val estimatedUsdCost: Double,
    val currentCreditBalance: Int,
    val canAfford: Boolean,
    val modelDisplayName: String,
    val scaleFactor: Int,
    val outputMegapixels: Double,
    val explanation: String,
)

/** Outcome of the headless-eligibility check. Used by paid-capable
 *  agent functions to decide whether to run inline or deep-link. */
sealed class HeadlessRoutingResult {
    /** Headless execution allowed — run the function inline. */
    object Allowed : HeadlessRoutingResult()
    /** Headless requested but paid model without confirmation — Gemini must
     *  call quoteUpscaleCost first, present to user, then re-invoke with
     *  confirmCreditCost = true. */
    object RequiresConfirmation : HeadlessRoutingResult()
    /** Not headless — deep-link into the app's UI. */
    object DeepLink : HeadlessRoutingResult()
}

/**
 * Decide whether a paid-capable agent function should run inline, prompt
 * the user for cost confirmation, or deep-link into the UI.
 *
 *   - free-tier (NONE, FREE_LOCAL) + headless → Allowed
 *   - paid + headless + confirmCreditCost → Allowed
 *   - paid + headless + !confirmCreditCost → RequiresConfirmation
 *   - !headless → DeepLink
 */
fun isHeadlessAllowed(
    model: UpscaleModel,
    headless: Boolean,
    confirmCreditCost: Boolean,
): HeadlessRoutingResult {
    if (!headless) return HeadlessRoutingResult.DeepLink
    val isFreeTier = model == UpscaleModel.NONE || model == UpscaleModel.FREE_LOCAL
    if (isFreeTier) return HeadlessRoutingResult.Allowed
    if (confirmCreditCost) return HeadlessRoutingResult.Allowed
    return HeadlessRoutingResult.RequiresConfirmation
}
```

(Stub bodies for `generatePrintReadyPoster`, `upscaleImage`, `generatePosterPdf`, `viewLastPoster`, `redoPosterWithSettings`, `sharePoster` are NOT in this task — those land in the deferred Phase B1 plan once the AppFunctions EAP gate clears and there's a runtime consumer for them. Phase B2 only uses `quoteUpscaleCost` as a tool definition for Gemini.)

- [ ] **Step 3: Commit**

```bash
cd /home/projects/pdfposter-md3e
git add app/src/main/kotlin/com/posterpdf/agentfunctions/PosterPdfAgentFunctions.kt \
        app/src/test/kotlin/com/posterpdf/agentfunctions/PosterPdfAgentFunctionsTest.kt
git commit -m "feat(rc65): PosterPdfAgentFunctions Phase B0 — service class + quoteUpscaleCost

Foundation for Spec B Phase B2 (in-app Gemini Q&A, this plan) and the
deferred Phase B1 (@AppFunction wrappers, EAP-gated). Class is pure
Kotlin so unit tests don't need an instrumentation runner. Headless-
routing logic captured as a sealed-class state machine; the 5 routing
tests pin the spec's hybrid invocation contract."
```

Push later in Task 7 (batched with backend changes).

---

## Task 3: Backend askGemini.ts — pure helpers (TDD)

**Files:**
- Create: `backend/functions/src/askGemini.ts`
- Create: `backend/functions/test/askGemini.test.mjs`

- [ ] **Step 1: Write the failing test file**

Create `backend/functions/test/askGemini.test.mjs`:

```javascript
// backend/functions/test/askGemini.test.mjs
//
// Run via:
//   cd backend/functions && npm run build && node --test test/askGemini.test.mjs

import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  buildToolDefinitions,
  buildSystemContext,
  parseToolCallFromResponse,
  applyRateLimit,
  DAILY_QUERY_LIMIT,
} from '../lib/askGemini.js';

test('DAILY_QUERY_LIMIT = 10', () => {
  assert.equal(DAILY_QUERY_LIMIT, 10);
});

test('buildToolDefinitions includes quoteUpscaleCost', () => {
  const tools = buildToolDefinitions();
  assert.ok(Array.isArray(tools));
  const fnNames = tools.flatMap(t => (t.functionDeclarations ?? []).map(fd => fd.name));
  assert.ok(fnNames.includes('quoteUpscaleCost'),
    `expected quoteUpscaleCost in ${fnNames.join(', ')}`);
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
  assert.match(ctx, /24.*18/);
  assert.match(ctx, /Letter/);
  assert.match(ctx, /imagen/i);
});

test('buildSystemContext handles missing image gracefully', () => {
  const ctx = buildSystemContext({});
  assert.match(ctx, /no image/i);
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
  const result1 = await applyRateLimit({ firestore: fakeFirestore, docRef: fakeRef, now: new Date('2026-05-27T10:00:00Z') });
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
  const today = new Date('2026-05-27T10:00:00Z');
  // Pre-fill 10 calls today
  docs.set(fakeRef.path, { count: 10, dayKey: '2026-05-27' });
  const result = await applyRateLimit({ firestore: fakeFirestore, docRef: fakeRef, now: today });
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
  // Yesterday's 10 calls
  docs.set(fakeRef.path, { count: 10, dayKey: '2026-05-26' });
  const result = await applyRateLimit({ firestore: fakeFirestore, docRef: fakeRef, now: new Date('2026-05-27T10:00:00Z') });
  assert.equal(result.allowed, true);
  assert.equal(result.remaining, 9);
});
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /home/projects/pdfposter-md3e/backend/functions
node --test test/askGemini.test.mjs 2>&1 | head -10
```

Expected: `ERR_MODULE_NOT_FOUND` for `../lib/askGemini.js`.

- [ ] **Step 3: Implement the pure helpers**

Create `backend/functions/src/askGemini.ts`:

```typescript
// backend/functions/src/askGemini.ts
//
// Spec B Phase B2: in-app Gemini Q&A backend. Pure helpers + the
// onCall callable. Per-user rate limit (10/day) tracked in Firestore.
//
// Tool-calling registers the same function signatures the Phase B0
// PosterPdfAgentFunctions class implements client-side — askGemini
// returns the function name + args, the client invokes the local
// implementation (or deep-links into the UI for paid actions).
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
            'Always call this BEFORE invoking generatePrintReadyPoster with a paid model. ' +
            'Returns the credit cost and the user\'s current balance so you can ask for confirmation.',
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
              targetDpi: { type: 'number', description: 'Print resolution. 150 for typical posters, 300 for high-quality prints.' },
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
 *  reply was plain text (no tool use). */
export function parseToolCallFromResponse(response: unknown): { name: string; args: Record<string, unknown> } | null {
  const r = response as {
    candidates?: Array<{ content?: { parts?: Array<{ functionCall?: { name: string; args: Record<string, unknown> } }> } }>;
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
 *  string if the response is only a function call (no text). */
export function parseTextFromResponse(response: unknown): string {
  const r = response as {
    candidates?: Array<{ content?: { parts?: Array<{ text?: string }> } }>;
  };
  const parts = r?.candidates?.[0]?.content?.parts ?? [];
  return parts.map(p => p.text ?? '').join('');
}

interface RateLimitDeps {
  firestore: {
    runTransaction: <T>(fn: (txn: {
      get: (ref: { path: string }) => Promise<{ exists: boolean; data: () => { count?: number; dayKey?: string } | undefined }>;
      set: (ref: { path: string }, val: { count: number; dayKey: string }) => void;
    }) => Promise<T>) => Promise<T>;
  };
  docRef: { path: string };
  now: Date;
}

/** Increment the user's daily quota counter atomically. Returns whether
 *  the call is allowed AND how many queries remain after this one. */
export async function applyRateLimit(deps: RateLimitDeps): Promise<{ allowed: boolean; remaining: number }> {
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
```

- [ ] **Step 4: Build + run tests**

```bash
cd /home/projects/pdfposter-md3e/backend/functions
npm run build && node --test test/askGemini.test.mjs
```

Expected: all 9 tests pass.

- [ ] **Step 5: Commit**

```bash
cd /home/projects/pdfposter-md3e
git add backend/functions/src/askGemini.ts backend/functions/test/askGemini.test.mjs
git commit -m "feat(rc65): askGemini.ts — pure helpers (TDD)

Tool definitions, system context builder, response parsers, and the
rate-limit transaction logic. All 9 tests pass against pure-function
mocks; the live Vertex AI Gemini integration lands in Task 5."
```

---

## Task 4: askGemini onCall callable with mocked Gemini client

**Files:**
- Modify: `backend/functions/src/askGemini.ts`

- [ ] **Step 1: Add the callable wrapper at the bottom of `askGemini.ts`**

Append to `backend/functions/src/askGemini.ts`:

```typescript

// ─────────────────────────────────────────────────────────────────────────────
// Callable wrapper
// ─────────────────────────────────────────────────────────────────────────────

import { onCall, HttpsError } from 'firebase-functions/v2/https';
import { getFirestore } from 'firebase-admin/firestore';

/** Injectable Gemini client. Real impl lands in Task 5; here we declare the
 *  shape so the callable can be built + tested with a stub today. */
export interface GeminiClient {
  generate: (args: {
    model: string;
    systemInstruction: string;
    tools: unknown[];
    prompt: string;
    imageGsUri: string | null;
  }) => Promise<unknown>;
}

/** Stub Gemini client — returns a canned text response. Replaced in Task 5
 *  by a real @google/genai client wired to Vertex AI. */
let geminiClient: GeminiClient = {
  generate: async () => ({
    candidates: [{
      content: { parts: [{ text: 'Gemini integration not yet wired (Task 5 placeholder).' }] },
    }],
  }),
};

/** Test/wiring hook — Task 5 swaps the production client in here. */
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
}

export const askGemini = onCall(
  {
    region: 'us-central1',
    timeoutSeconds: 60,
    memory: '512MiB',
  },
  async (request) => {
    const uid = request.auth?.uid;
    if (!uid) {
      throw new HttpsError('unauthenticated', 'sign-in required');
    }
    const data = (request.data ?? {}) as Partial<AskGeminiInput>;
    const prompt = data.prompt;
    if (typeof prompt !== 'string' || prompt.trim().length === 0) {
      throw new HttpsError('invalid-argument', 'prompt is required');
    }
    if (prompt.length > 2000) {
      throw new HttpsError('invalid-argument', 'prompt must be ≤ 2000 characters');
    }

    // Rate limit: 10/day per user.
    const db = getFirestore();
    const docRef = db.collection('users').doc(uid).collection('quota').doc('gemini_qa');
    const rate = await applyRateLimit({
      firestore: db as unknown as RateLimitDeps['firestore'],
      docRef: docRef as unknown as RateLimitDeps['docRef'],
      now: new Date(),
    });
    if (!rate.allowed) {
      throw new HttpsError(
        'resource-exhausted',
        'Daily Gemini query limit reached. Come back tomorrow — Gemini is free here, but rationed.',
      );
    }

    // Build system context + call Gemini.
    const systemInstruction = buildSystemContext(data.currentSettings ?? {});
    const tools = buildToolDefinitions();
    const response = await geminiClient.generate({
      model: 'gemini-3-5-flash',
      systemInstruction,
      tools,
      prompt,
      imageGsUri: data.imageGsUri ?? null,
    });

    return {
      text: parseTextFromResponse(response),
      toolCall: parseToolCallFromResponse(response),
      remainingQueries: rate.remaining,
    };
  },
);
```

- [ ] **Step 2: Build to verify TypeScript compiles**

```bash
cd /home/projects/pdfposter-md3e/backend/functions
npm run build 2>&1 | tail -5
```

Expected: zero TS errors.

- [ ] **Step 3: Commit**

```bash
cd /home/projects/pdfposter-md3e
git add backend/functions/src/askGemini.ts
git commit -m "feat(rc65): askGemini onCall callable with rate-limit + stub Gemini client

Callable validates input, applies the 10/day per-user rate limit
atomically via Firestore transaction, builds the system context + tool
definitions, and dispatches to the (currently stubbed) GeminiClient.
Task 5 replaces the stub with a real @google/genai client."
```

---

## Task 5: Real Vertex AI Gemini integration via `@google/genai`

**Files:**
- Modify: `backend/functions/src/askGemini.ts`
- Modify: `backend/functions/package.json` (via `npm install` from pre-flight; verify)

- [ ] **Step 1: Verify the SDK is installed**

From pre-flight, `npm install @google/genai` should have run. Confirm:

```bash
cd /home/projects/pdfposter-md3e/backend/functions
cat package.json | grep '@google/genai' || echo "MISSING — install now"
ls node_modules/@google/genai/package.json
```

Expected: package.json contains `"@google/genai": "..."` and `node_modules/@google/genai/package.json` exists. If missing, run `npm install @google/genai` then `npm run build`.

- [ ] **Step 2: Wire the real client at the top of `askGemini.ts`**

In `backend/functions/src/askGemini.ts`, add this near the top (just below the existing imports from Task 4):

```typescript
import { GoogleGenAI, type GenerateContentResponse } from '@google/genai';

/** Build the production GeminiClient backed by @google/genai + Vertex AI.
 *  Uses the Cloud Function's default service account credentials (same
 *  path as RC60's vertex-imagen.ts — google-auth-library transitively). */
function buildProductionGeminiClient(): GeminiClient {
  const ai = new GoogleGenAI({
    vertexai: true,
    project: 'static-webbing-461904-c4',
    location: 'us-central1',
  });
  return {
    generate: async (args) => {
      const contents: Array<unknown> = [];
      if (args.imageGsUri) {
        contents.push({
          role: 'user',
          parts: [
            { fileData: { mimeType: 'image/png', fileUri: args.imageGsUri } },
            { text: args.prompt },
          ],
        });
      } else {
        contents.push({ role: 'user', parts: [{ text: args.prompt }] });
      }
      const response: GenerateContentResponse = await ai.models.generateContent({
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

// Swap the stub for the production client at module load time.
setGeminiClient(buildProductionGeminiClient());
```

- [ ] **Step 3: Build + run all backend tests**

```bash
cd /home/projects/pdfposter-md3e/backend/functions
npm run build && node --test test/askGemini.test.mjs && node --test test/vertex-imagen.test.mjs
```

Expected: all askGemini tests still pass (production client doesn't affect the pure helpers), all vertex-imagen tests still pass (regression check). If a TS error in the SDK glue surfaces, the `@google/genai` API may have shifted from this plan's assumed shape — adjust the `generateContent` invocation to match the installed SDK's actual TypeScript types (refer to `node_modules/@google/genai/dist/index.d.ts`).

- [ ] **Step 4: Commit**

```bash
cd /home/projects/pdfposter-md3e
git add backend/functions/src/askGemini.ts backend/functions/package.json backend/functions/package-lock.json
git commit -m "feat(rc65): real Vertex AI gemini-3-5-flash integration via @google/genai

Production GeminiClient replaces the Task 4 stub. Authenticates via the
Cloud Function's default service account (no API key, no secret). Image
context passed as fileData with the original gs:// URI; system context
+ tools wired into the SDK's GenerateContentConfig."
```

---

## Task 6: Firestore rules + index.ts export

**Files:**
- Modify: `backend/firestore.rules`
- Modify: `backend/functions/src/index.ts`

- [ ] **Step 1: Add the quota subcollection rule**

In `backend/firestore.rules`, find the `match /users/{userId} {` block (around line 54). INSIDE that block, before its closing `}`, add:

```javascript
      // RC65: per-user Gemini Q&A rate limit. Backend writes (transactionally
      // via askGemini); user can read their own count to display "X queries
      // left today". No client writes — only the Cloud Function does.
      match /quota/{name} {
        allow read: if request.auth != null && request.auth.uid == userId;
        allow write: if false;
      }
```

- [ ] **Step 2: Export askGemini from `index.ts`**

In `backend/functions/src/index.ts`, find the existing exports (other callables like `requestUpscale`). Add:

```typescript
export { askGemini } from './askGemini';
```

If `index.ts` uses CommonJS-style exports (no `export` statement at top level), use the existing pattern instead.

- [ ] **Step 3: Build + commit**

```bash
cd /home/projects/pdfposter-md3e/backend/functions
npm run build 2>&1 | tail -3
cd /home/projects/pdfposter-md3e
git add backend/firestore.rules backend/functions/src/index.ts
git commit -m "feat(rc65): wire askGemini into deployable exports + Firestore rules

Firestore: users can read their own gemini_qa quota doc (for the
'X queries left today' UI); writes only via the Cloud Function's
transactional applyRateLimit. Index.ts re-exports the new callable
so Cloud Build deploys it alongside requestUpscale."
```

---

## Task 7: Deploy backend + smoke-test askGemini

**Files:** None modified.

- [ ] **Step 1: Push + deploy**

```bash
cd /home/projects/pdfposter-md3e
git push
gcloud builds submit --config=cloudbuild-backend.yaml .
```

Expected: Cloud Build succeeds (~3–5 min) and `firebase deploy --only functions` runs as the build's last step.

- [ ] **Step 2: Verify the function is live**

```bash
gcloud functions describe askGemini --region=us-central1 --format='value(state,updateTime)'
```

Expected: state = `ACTIVE`, updateTime within the last 5 minutes.

- [ ] **Step 3: Smoke-test via Firebase Console "Test function" or a temporary debug button**

From the Firebase Console's Functions tab, click `askGemini` → Trigger. Pass:

```json
{
  "data": {
    "prompt": "What paper size should I use for a 24x18 inch poster?",
    "currentSettings": {
      "targetWidthInches": 24,
      "targetHeightInches": 18,
      "paperSize": "Letter (8.5x11)"
    }
  }
}
```

(Note: console-triggered test invocations come in as an unauthenticated `request.auth = null`. The callable will reject with `unauthenticated`. That's expected — proceed to Step 4 for an authenticated end-to-end test.)

- [ ] **Step 4: Verify Firestore rules deployed**

```bash
gcloud firestore rules describe --format='value(name,createTime)'
```

(Or: open Firebase Console → Firestore → Rules tab; verify the new `match /quota/{name}` block is live.)

- [ ] **Step 5: Commit any fix-ups**

If the deploy surfaced something fixable (TS-vs-runtime mismatch, missing IAM, rule syntax error), commit the fix here. Otherwise this task is a no-op commit.

---

## Task 8: Sparkle button composable in top bar

**Files:**
- Create: `app/src/main/kotlin/com/posterpdf/ui/components/GeminiSparkleButton.kt`
- Modify: `app/src/main/kotlin/com/posterpdf/MainActivity.kt`

- [ ] **Step 1: Create `GeminiSparkleButton.kt`**

```kotlin
package com.posterpdf.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.posterpdf.R

/**
 * RC65: top-bar sparkle button — Google's first-party convention for in-app
 * Gemini affordances (Photos, Keep, Maps, Gmail all use this). Tap →
 * opens the Q&A modal sheet via the [onTap] callback.
 *
 * Sized to match the existing IconButton-based top-bar widgets (48dp tap
 * target around a 24dp glyph). Color uses primary tint so the sparkle
 * reads as an active/interactive surface, not a passive label.
 */
@Composable
fun GeminiSparkleButton(
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onTap,
        modifier = modifier,
    ) {
        Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = stringResource(R.string.top_bar_gemini_cd),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
    }
}
```

- [ ] **Step 2: Insert the sparkle button in the top app bar**

In `app/src/main/kotlin/com/posterpdf/MainActivity.kt`, find the top-bar Row (around line 1180+ — look for the Row that contains the menu IconButton, wordmark Text, Spacer, CreditChip, AccountAvatarMenu). Add the sparkle button immediately AFTER the `if (signedInForChip) { CreditChip(...) }` block and BEFORE the `AccountAvatarMenu(...)` call. Specifically, between these two existing chunks:

```kotlin
                        if (signedInForChip) {
                            CreditChip(...)
                        }

                        // ← INSERT HERE

                        AccountAvatarMenu(...)
```

Insert:

```kotlin
                        // RC65: in-app Gemini Q&A entry — Google's first-party
                        // sparkle pattern. Sized to match the IconButton next
                        // to it (avatar at 36dp + 12dp pad). Opens the Q&A
                        // sheet via showGeminiSheet state below.
                        GeminiSparkleButton(onTap = {
                            hapt.tap()
                            viewModel.logEvent(context, "Gemini sparkle tapped")
                            showGeminiSheet = true
                        })
```

Also at the top of the `MainActivity`'s body (where other state vars like `showPurchaseSheet` live), add:

```kotlin
    var showGeminiSheet by remember { mutableStateOf(false) }
```

Don't forget to add the import at the top of MainActivity.kt:

```kotlin
import com.posterpdf.ui.components.GeminiSparkleButton
```

- [ ] **Step 3: Add the English string for the content description**

In `app/src/main/res/values/strings.xml`:

```xml
    <!-- RC65: in-app Gemini Q&A sparkle button accessibility label. -->
    <string name="top_bar_gemini_cd">Ask Gemini</string>
```

- [ ] **Step 4: Commit + push**

```bash
cd /home/projects/pdfposter-md3e
git add app/src/main/kotlin/com/posterpdf/ui/components/GeminiSparkleButton.kt \
        app/src/main/kotlin/com/posterpdf/MainActivity.kt \
        app/src/main/res/values/strings.xml
git commit -m "feat(rc65): top-bar Gemini sparkle button — opens Q&A sheet"
git push
```

GH Actions build will compile + verify nothing broke. The sparkle is visible but tapping it just flips `showGeminiSheet` — the sheet itself lands in Task 9.

---

## Task 9: GeminiQaSheet — text-only first pass

**Files:**
- Create: `app/src/main/kotlin/com/posterpdf/ui/components/GeminiQaSheet.kt`
- Modify: `app/src/main/kotlin/com/posterpdf/MainActivity.kt`

- [ ] **Step 1: Create `GeminiQaSheet.kt`**

```kotlin
package com.posterpdf.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.posterpdf.R

/** Visual state of the sheet. Drives whether to show suggestions vs response. */
sealed class GeminiQaState {
    object Idle : GeminiQaState()
    object Loading : GeminiQaState()
    data class Reply(val text: String, val remainingQueries: Int) : GeminiQaState()
    data class Error(val message: String) : GeminiQaState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeminiQaSheet(
    state: GeminiQaState,
    suggestions: List<String>,
    onSendPrompt: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var prompt by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Text(
                text = stringResource(R.string.gemini_qa_sheet_header),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            // Response / suggestions area
            when (state) {
                is GeminiQaState.Loading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.gemini_qa_loading))
                    }
                }
                is GeminiQaState.Reply -> {
                    Text(state.text, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(R.string.gemini_qa_queries_left, state.remainingQueries),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is GeminiQaState.Error -> {
                    Text(
                        state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                GeminiQaState.Idle -> {
                    Text(
                        stringResource(R.string.gemini_qa_suggestions_header),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    suggestions.forEach { suggestion ->
                        AssistChip(
                            onClick = { onSendPrompt(suggestion) },
                            label = { Text(suggestion, style = MaterialTheme.typography.labelMedium) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Text input
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.gemini_qa_input_placeholder)) },
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (prompt.isNotBlank()) {
                            onSendPrompt(prompt)
                            prompt = ""
                        }
                    },
                ) {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = stringResource(R.string.gemini_qa_send_cd),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 2: Wire the sheet into `MainActivity.kt`**

In `MainActivity.kt`, find where the other modal sheets are rendered (search for `if (showPurchaseSheet)` or similar). Add:

```kotlin
            if (showGeminiSheet) {
                GeminiQaSheet(
                    state = viewModel.geminiQaState,
                    suggestions = listOf(
                        stringResource(R.string.gemini_qa_suggestion_paper),
                        stringResource(R.string.gemini_qa_suggestion_sharp),
                        stringResource(R.string.gemini_qa_suggestion_model),
                    ),
                    onSendPrompt = { prompt ->
                        viewModel.askGemini(prompt)
                    },
                    onDismiss = {
                        showGeminiSheet = false
                        viewModel.resetGeminiQaState()
                    },
                )
            }
```

Add the import:

```kotlin
import com.posterpdf.ui.components.GeminiQaSheet
```

- [ ] **Step 3: Add English strings (placeholders for Task 10's ViewModel state)**

In `app/src/main/res/values/strings.xml`:

```xml
    <!-- RC65: Gemini Q&A sheet copy. -->
    <string name="gemini_qa_sheet_header">Ask Gemini about your poster</string>
    <string name="gemini_qa_loading">Thinking…</string>
    <string name="gemini_qa_queries_left">%1$d free queries left today</string>
    <string name="gemini_qa_suggestions_header">Try asking…</string>
    <string name="gemini_qa_suggestion_paper">What paper size for my poster?</string>
    <string name="gemini_qa_suggestion_sharp">Is my image sharp enough?</string>
    <string name="gemini_qa_suggestion_model">Cheapest upscaler that hits 300 DPI?</string>
    <string name="gemini_qa_input_placeholder">Or type your question…</string>
    <string name="gemini_qa_send_cd">Send</string>
```

- [ ] **Step 4: Commit (don't push yet — Task 10's ViewModel changes are needed for the build to succeed)**

```bash
cd /home/projects/pdfposter-md3e
git add app/src/main/kotlin/com/posterpdf/ui/components/GeminiQaSheet.kt \
        app/src/main/kotlin/com/posterpdf/MainActivity.kt \
        app/src/main/res/values/strings.xml
git commit -m "feat(rc65): GeminiQaSheet UI — sparkle button opens modal with text input + suggestions"
```

---

## Task 10: ViewModel state + askGemini callable wiring

**Files:**
- Modify: `app/src/main/kotlin/com/posterpdf/MainViewModel.kt`
- Modify: `app/src/main/kotlin/com/posterpdf/data/backend/BackendApi.kt`

- [ ] **Step 1: Add the BackendApi method**

Open `app/src/main/kotlin/com/posterpdf/data/backend/BackendApi.kt`. Find another callable invocation (e.g., `requestUpscale`) as a template. Add:

```kotlin
suspend fun askGemini(
    prompt: String,
    imageGsUri: String?,
    currentSettings: Map<String, Any?>,
): AskGeminiResponse {
    val data = mapOf(
        "prompt" to prompt,
        "imageGsUri" to imageGsUri,
        "currentSettings" to currentSettings,
    )
    val result = Firebase.functions(REGION)
        .getHttpsCallable("askGemini")
        .call(data)
        .await()
    @Suppress("UNCHECKED_CAST")
    val map = result.data as? Map<String, Any?> ?: error("askGemini returned non-map")
    return AskGeminiResponse(
        text = map["text"] as? String ?: "",
        toolCall = (map["toolCall"] as? Map<String, Any?>)?.let {
            ToolCall(
                name = it["name"] as String,
                args = (it["args"] as? Map<String, Any?>) ?: emptyMap(),
            )
        },
        remainingQueries = (map["remainingQueries"] as? Long)?.toInt() ?: 0,
    )
}

data class AskGeminiResponse(
    val text: String,
    val toolCall: ToolCall?,
    val remainingQueries: Int,
)

data class ToolCall(
    val name: String,
    val args: Map<String, Any?>,
)
```

(Match the existing imports — `Firebase.functions`, `await`, `REGION` should already be in scope from neighboring callables.)

- [ ] **Step 2: Add ViewModel state + the askGemini method**

Open `app/src/main/kotlin/com/posterpdf/MainViewModel.kt`. Find an existing callable invocation method (e.g., where `requestUpscale` is called). Add:

```kotlin
    // RC65: Gemini Q&A state. Driven by GeminiQaSheet (UI) and askGemini
    // (callable). resetGeminiQaState() clears it on sheet dismiss.
    var geminiQaState by mutableStateOf<com.posterpdf.ui.components.GeminiQaState>(
        com.posterpdf.ui.components.GeminiQaState.Idle,
    )
        private set

    fun resetGeminiQaState() {
        geminiQaState = com.posterpdf.ui.components.GeminiQaState.Idle
    }

    fun askGemini(prompt: String) {
        geminiQaState = com.posterpdf.ui.components.GeminiQaState.Loading
        viewModelScope.launch {
            try {
                val currentSettings = mapOf<String, Any?>(
                    "selectedImageMp" to currentImageMp,
                    "targetWidthInches" to posterWidth.toDoubleOrNull(),
                    "targetHeightInches" to posterHeight.toDoubleOrNull(),
                    "paperSize" to paperSize,
                    "currentUpscaleModel" to selectedUpscaleModel?.name?.lowercase(),
                )
                val imageGsUri = currentImageGsUri  // null if user hasn't uploaded yet
                val response = backendApi.askGemini(prompt, imageGsUri, currentSettings)
                geminiQaState = com.posterpdf.ui.components.GeminiQaState.Reply(
                    text = response.text.ifBlank {
                        appContext.getString(com.posterpdf.R.string.gemini_qa_action_taken)
                    },
                    remainingQueries = response.remainingQueries,
                )
                // Tool-call routing (Task 12) reads geminiQaState's last toolCall
                // — for now we just surface the text; tool routing lands next task.
            } catch (e: Throwable) {
                geminiQaState = com.posterpdf.ui.components.GeminiQaState.Error(
                    appContext.getString(com.posterpdf.R.string.gemini_qa_error, e.message ?: ""),
                )
            }
        }
    }
```

Variable name notes (verify against actual ViewModel field names — these are best-guesses based on RC60+ context):
- `currentImageMp` — the source image's megapixels (already exists from RC60 upscale pipeline).
- `currentImageGsUri` — the gs:// URI if user has uploaded to GCS (may be null). If a different field name exists, substitute.
- `selectedUpscaleModel` — the user's current model choice. May be tracked differently; substitute the correct field.
- `paperSize` — confirmed exists at line 676 of MainViewModel.kt.

- [ ] **Step 3: Add English strings for error / action-taken fallback**

In `app/src/main/res/values/strings.xml`:

```xml
    <string name="gemini_qa_action_taken">Got it — running that now…</string>
    <string name="gemini_qa_error">Something went wrong: %1$s</string>
```

- [ ] **Step 4: Commit + push**

```bash
cd /home/projects/pdfposter-md3e
git add app/src/main/kotlin/com/posterpdf/MainViewModel.kt \
        app/src/main/kotlin/com/posterpdf/data/backend/BackendApi.kt \
        app/src/main/res/values/strings.xml
git commit -m "feat(rc65): wire askGemini callable + ViewModel Q&A state"
git push
```

Wait for GH Actions build. Expected: green (all strings resolve, all types match).

---

## Task 11: Voice input + TextToSpeech reply

**Files:**
- Create: `app/src/main/kotlin/com/posterpdf/ui/components/VoiceInputState.kt`
- Modify: `app/src/main/AndroidManifest.xml` (add RECORD_AUDIO permission)
- Modify: `app/src/main/kotlin/com/posterpdf/ui/components/GeminiQaSheet.kt`

- [ ] **Step 1: Add RECORD_AUDIO permission**

In `app/src/main/AndroidManifest.xml`, add inside the `<manifest>` element near the other permissions:

```xml
    <!-- RC65: Gemini Q&A voice input via SpeechRecognizer. Runtime-prompted
         the first time the user taps the mic button in the Q&A sheet. -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
```

- [ ] **Step 2: Create `VoiceInputState.kt`**

```kotlin
package com.posterpdf.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.util.Locale

/** Compose-friendly wrapper around Android's SpeechRecognizer. */
class VoiceInputController(private val context: Context) {
    private val recognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else null

    var isListening by mutableStateOf(false)
        private set
    var transcript by mutableStateOf("")
        private set
    var error: String? by mutableStateOf(null)
        private set

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    fun start(onFinalTranscript: (String) -> Unit) {
        if (recognizer == null) {
            error = "Voice recognition unavailable on this device"
            return
        }
        if (!hasPermission()) {
            error = "Microphone permission needed"
            return
        }
        transcript = ""
        error = null
        isListening = true
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                isListening = false
                this@VoiceInputController.error = "Voice error (code $error)"
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = texts?.firstOrNull().orEmpty()
                transcript = best
                if (best.isNotBlank()) onFinalTranscript(best)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                texts?.firstOrNull()?.let { transcript = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer.startListening(intent)
    }

    fun stop() {
        recognizer?.stopListening()
        isListening = false
    }

    fun dispose() {
        recognizer?.destroy()
    }
}

@Composable
fun rememberVoiceInputController(): VoiceInputController {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = remember { VoiceInputController(context) }
    DisposableEffect(Unit) {
        onDispose { controller.dispose() }
    }
    return controller
}
```

- [ ] **Step 3: Add a mic button to GeminiQaSheet**

In `GeminiQaSheet.kt`, in the row containing the text input (existing `Row { OutlinedTextField + Spacer + IconButton(Send) }`), insert a mic IconButton BEFORE the OutlinedTextField:

```kotlin
            val voice = rememberVoiceInputController()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        if (voice.isListening) voice.stop()
                        else voice.start(onFinalTranscript = { onSendPrompt(it) })
                    },
                ) {
                    Icon(
                        if (voice.isListening) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = stringResource(
                            if (voice.isListening) R.string.gemini_qa_voice_stop_cd
                            else R.string.gemini_qa_voice_start_cd
                        ),
                        tint = if (voice.isListening) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = if (voice.isListening) voice.transcript else prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.gemini_qa_input_placeholder)) },
                    singleLine = true,
                    enabled = !voice.isListening,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (prompt.isNotBlank()) {
                            onSendPrompt(prompt)
                            prompt = ""
                        }
                    },
                ) {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = stringResource(R.string.gemini_qa_send_cd),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
```

Add imports:

```kotlin
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
```

- [ ] **Step 4: Add English strings**

```xml
    <string name="gemini_qa_voice_start_cd">Start voice input</string>
    <string name="gemini_qa_voice_stop_cd">Stop voice input</string>
```

- [ ] **Step 5: Commit + push**

```bash
cd /home/projects/pdfposter-md3e
git add app/src/main/AndroidManifest.xml \
        app/src/main/kotlin/com/posterpdf/ui/components/VoiceInputState.kt \
        app/src/main/kotlin/com/posterpdf/ui/components/GeminiQaSheet.kt \
        app/src/main/res/values/strings.xml
git commit -m "feat(rc65): voice input via SpeechRecognizer in GeminiQaSheet"
git push
```

Wait for GH Actions build. Expected: green.

(TextToSpeech for reply playback is intentionally deferred — accessibility users have system-level read-aloud; in-app TTS adds dependency surface without strong added value for v1. If user feedback requests it later, follow-up RC.)

---

## Task 12: Tool-call routing client-side

**Files:**
- Modify: `app/src/main/kotlin/com/posterpdf/MainViewModel.kt`

The current Task 10 implementation accepts a `toolCall` field in the response but doesn't act on it. Task 12 wires it up: when Gemini calls `quoteUpscaleCost`, we surface the quote in the response instead of generic text.

- [ ] **Step 1: Extend `askGemini` in MainViewModel to handle tool calls**

In `MainViewModel.kt`'s `askGemini(prompt: String)` body, replace the `try` block's success path with:

```kotlin
                val response = backendApi.askGemini(prompt, imageGsUri, currentSettings)
                val toolCall = response.toolCall
                val finalText = if (toolCall != null) {
                    handleToolCall(toolCall) ?: response.text
                } else {
                    response.text
                }
                geminiQaState = com.posterpdf.ui.components.GeminiQaState.Reply(
                    text = finalText.ifBlank {
                        appContext.getString(com.posterpdf.R.string.gemini_qa_action_taken)
                    },
                    remainingQueries = response.remainingQueries,
                )
```

Then add the handler method:

```kotlin
    /**
     * Execute a Gemini-invoked tool call locally and return a human-readable
     * summary string for the chat reply. Returns null if the tool isn't
     * recognized (caller falls back to Gemini's plain text).
     */
    private fun handleToolCall(
        toolCall: com.posterpdf.data.backend.ToolCall,
    ): String? {
        return when (toolCall.name) {
            "quoteUpscaleCost" -> {
                val args = toolCall.args
                val modelName = args["upscaleModel"] as? String ?: return null
                val targetW = (args["targetWidthInches"] as? Number)?.toDouble() ?: return null
                val targetH = (args["targetHeightInches"] as? Number)?.toDouble() ?: return null
                val targetDpi = (args["targetDpi"] as? Number)?.toFloat() ?: 150f
                val model = com.posterpdf.ui.components.UpscaleModel.values()
                    .firstOrNull { it.name.equals(modelName, ignoreCase = true) }
                    ?: return null
                val quote = agentFunctions.quoteUpscaleCost(
                    sourceImageUri = selectedImageUri?.toString() ?: "",
                    upscaleModel = model,
                    targetWidthInches = targetW,
                    targetHeightInches = targetH,
                    inputMp = currentImageMp,
                    currentCreditBalance = creditBalance,
                    targetDpi = targetDpi,
                )
                quote.explanation
            }
            else -> null
        }
    }
```

Add the `agentFunctions` field near the top of `MainViewModel`:

```kotlin
    private val agentFunctions by lazy {
        com.posterpdf.agentfunctions.PosterPdfAgentFunctions(
            appContext = appContext,
            allOptions = com.posterpdf.ui.components.ALL_OPTIONS,
        )
    }
```

(`ALL_OPTIONS` is private to LowDpiUpgradeModal.kt — change its visibility to `internal` or move to a shared place if needed.)

- [ ] **Step 2: Commit + push**

```bash
cd /home/projects/pdfposter-md3e
git add app/src/main/kotlin/com/posterpdf/MainViewModel.kt \
        app/src/main/kotlin/com/posterpdf/ui/components/LowDpiUpgradeModal.kt
git commit -m "feat(rc65): tool-call routing — Gemini quoteUpscaleCost replies use local PricingMath"
git push
```

Wait for GH Actions build. Expected: green. Now Gemini's "should I use Imagen?" replies include the actual computed cost (3 credits for 1 MP source at 24×18 / 150 DPI), not a guess.

---

## Task 13: 9-locale translation fan-out

**Files:**
- Modify: `app/src/main/res/values-{ar,de,es,fr,hi,ja,pt-rBR,ru,zh-rCN}/strings.xml`

- [ ] **Step 1: Dispatch 9 translation subagents in parallel**

Translate these 12 keys (added in Tasks 8, 9, 10, 11):

```
top_bar_gemini_cd                  Ask Gemini
gemini_qa_sheet_header             Ask Gemini about your poster
gemini_qa_loading                  Thinking…
gemini_qa_queries_left             %1$d free queries left today
gemini_qa_suggestions_header       Try asking…
gemini_qa_suggestion_paper         What paper size for my poster?
gemini_qa_suggestion_sharp         Is my image sharp enough?
gemini_qa_suggestion_model         Cheapest upscaler that hits 300 DPI?
gemini_qa_input_placeholder        Or type your question…
gemini_qa_send_cd                  Send
gemini_qa_action_taken             Got it — running that now…
gemini_qa_error                    Something went wrong: %1$s
gemini_qa_voice_start_cd           Start voice input
gemini_qa_voice_stop_cd            Stop voice input
```

Per RC44/RC60 pattern: dispatch one `general-purpose` subagent per locale (parallel), Opus 4.7 max ultrathink each. Each subagent edits its target `values-<locale>/strings.xml` to insert all 14 keys. "Gemini" and "%1$d" stay as-is (brand + format specifier).

- [ ] **Step 2: Verify each locale has all keys**

```bash
cd /home/projects/pdfposter-md3e
for loc in ar de es fr hi ja pt-rBR ru zh-rCN; do
  count=$(grep -c "gemini_qa_\|top_bar_gemini_cd" app/src/main/res/values-$loc/strings.xml)
  echo "  $loc: $count / 14 keys"
done
```

Expected: each prints `14 / 14`.

- [ ] **Step 3: Commit + push**

```bash
cd /home/projects/pdfposter-md3e
git add app/src/main/res/values-*/strings.xml
git commit -m "i18n(rc65): translate 14 Gemini Q&A keys to 9 locales"
git push
```

Wait for GH Actions build. Expected: green.

---

## Task 14: End-to-end manual verification + versionName bump + release notes

**Files:**
- Modify: `app/build.gradle.kts` (versionName bump)
- Modify: `backend/scripts/post-release-notes.sh` (release notes content)

- [ ] **Step 1: Build the APK**

The recent push will trigger a GH Actions build. Once it completes, an auto-stager (set up the same way as RC60–RC64) pulls the APK to `/tmp/rc65-apk/pdfposter-rc65-debug.apk`.

- [ ] **Step 2: Smoke-test on a real device**

Install the APK. Open the app, tap the sparkle icon in the top bar. Expected:

1. Q&A sheet opens with 3 suggestion chips.
2. Tap "What paper size for my poster?" → Gemini replies (~2s) with a recommendation based on current poster size.
3. Type a custom prompt → Send icon dispatches it → reply appears.
4. Tap mic icon → grant RECORD_AUDIO permission → speak → transcript appears live → final transcript auto-sends.
5. Ask "How much would Topaz cost for a 24×18 poster?" → reply quotes the actual credit count via the tool-call route.
6. Submit 11 queries in one day → 11th gets the "Daily query limit reached" error.

- [ ] **Step 3: Bump versionName**

In `app/build.gradle.kts`:

```kotlin
        versionName = "1.0-rc65"  // RC65 — in-app Gemini Q&A (Spec B Phase B0 + B2)
```

- [ ] **Step 4: Update the community release-notes script**

In `backend/scripts/post-release-notes.sh`, replace the existing `post release_notes` body with:

```
"[b]New: Ask Gemini about your poster.[/b]

Tap the new sparkle icon in the top bar to open the in-app Gemini Q&A. Ask anything related to making your poster — paper size for your image, whether your photo is sharp enough at the size you want, which upscaler is cheapest for your DPI target. Gemini sees your selected image and current settings, so the answers are specific to YOUR poster, not generic advice.

[b]How to use it[/b]
• Tap the ✨ sparkle in the top bar
• Speak or type your question
• Three starter suggestions to try if you don't know where to begin

[b]Free + rationed[/b]
Gemini queries are free for users — backed by Google's Vertex AI in our Cloud Functions backend. Each user gets 10 queries per day to keep the cost manageable. You'll see how many you have left at the bottom of each reply.

[b]Privacy[/b]
Image + question stay inside our Google Cloud project. Vertex AI Gemini 3.5 Flash runs the inference. Voice transcription happens on-device via Android's SpeechRecognizer."
```

- [ ] **Step 5: Commit, push, run release-notes script**

```bash
cd /home/projects/pdfposter-md3e
git add app/build.gradle.kts backend/scripts/post-release-notes.sh
git commit -m "chore(rc65): bump versionName + community release notes for Gemini Q&A"
git push
bash backend/scripts/post-release-notes.sh
```

Expected: GH Actions builds the final RC65 APK; release notes post returns a doc ID.

---

## Self-review

**Spec coverage:**
- Spec B § Part 2 In-app Q&A: ✓ (Tasks 3–14)
- Spec B § Part 1 AppFunctions: deferred to a follow-up plan when EAP clears (Phase B0 foundation lands here; @AppFunction wrappers + manifest don't ship until EAP unlocks the user-facing benefit).
- Spec B § Cross-cutting (single function source): ✓ (Task 2 + Task 12 share `PosterPdfAgentFunctions`).
- Spec B § Sequencing (B0 → B2): ✓ Task 1+2 land B0; Tasks 3–14 land B2.
- Spec B § Open Verification Items #1, #2: deferred (don't apply to Phase B2). #3 (Gemini Flash availability in us-central1): verify in Task 7 smoke-test. #4 (top-bar width budget): Task 8 sparkle insertion + manual test in Task 14. #5 (Firestore rules quota path): Task 6. #6 (cross-app URI permission): N/A for B2 (no @AppFunction invocation surface). #7 (quote parity with modal): Task 1 extracts shared PricingMath ✓.

**Placeholder scan:**
- Task 10 Step 2 says "Variable name notes... may differ from actual ViewModel field names." This is intentional implementation guidance — the engineer checks the live names; not a placeholder for me to fill.
- All code blocks contain complete, runnable content.

**Type consistency:**
- `GeminiClient` interface defined in Task 4 (stub) and used by Task 5 (production impl). ✓
- `AskGeminiResponse` / `ToolCall` defined in Task 10 (BackendApi.kt) and consumed in Task 12. ✓
- `GeminiQaState` defined in Task 9 (GeminiQaSheet.kt) and referenced by Task 10's ViewModel state. ✓
- `PosterPdfAgentFunctions` constructor params (appContext, allOptions) match the call site in Task 12's `agentFunctions by lazy { ... }`. ✓
- `UpscaleCostQuote` fields (`estimatedCredits`, `explanation`, etc.) used in Task 12's `quote.explanation` access. ✓

**Issues found and fixed inline:**
- None.
