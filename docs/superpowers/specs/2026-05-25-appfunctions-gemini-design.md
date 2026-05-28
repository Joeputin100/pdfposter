# Spec B — AppFunctions + In-app Gemini Q&A

**Author:** brainstormed 2026-05-25 with Claude (session "posterpdf")
**Status:** Approved design — awaiting implementation plan via `superpowers:writing-plans`
**Related:** [Spec A — Vertex Imagen upscale](2026-05-25-vertex-imagen-upscale-design.md), [Spec C — Launch-readiness checklist](2026-05-25-launch-readiness-design.md)

---

## Goal

Make PosterPDF discoverable AND callable by Google's intelligent OS layer (system Gemini, "Ask Play"), and add an in-app one-shot Gemini Q&A surface so users can ask Gemini for advice without leaving the app.

Two parts that share function definitions but ship independently:

- **Part 1 — AppFunctions surface:** Expose typed Kotlin functions via `androidx.appfunctions` so the system Gemini assistant can discover + invoke PosterPDF's workflows by natural language. Gated for production users on the AppFunctions Early Access Program (applied 2026-05-25, see `posterpdf-eap-state` memory).
- **Part 2 — In-app Gemini Q&A:** A sparkle icon in the top app bar opens a modal sheet with voice + text input. Backed by `gemini-3-5-flash` (vision + tool-calling) on Vertex AI. Tools registered with the same function signatures as Part 1, so Gemini can answer questions OR invoke the workflow directly.

## Non-goals

- Gemini Live API (persistent WebSocket / streaming voice/vision). Out of scope for v1 — the app's batch nature doesn't justify Live's cost shape (~$0.06–$0.18/min vs. ~$0.001–$0.005 per one-shot call). User explicitly chose one-shot Q&A.
- System-Gemini AppFunction invocation on devices that don't yet have the Android 16 framework rolled out. We rely on the platform to handle backward fallback (App Actions / shortcuts.xml for older Assistant variants).
- Tool-calling that mutates user state beyond what a manual UI action would do (e.g., no "delete all my saved posters" function).
- An in-app voice "OS" — input is single-turn, no continuous mic streaming.

## Architecture

```
┌─────────────────────────────────────────────┐
│  Android client (PosterPDF, Android 16+)    │
│                                             │
│  ┌─────────────────────────────────────┐    │
│  │  PosterPdfAgentFunctions (service)  │    │
│  │  ┌──────────────────────────────┐   │    │
│  │  │ pure Kotlin business logic   │   │ ◀──┼─── invoked by AppFunctions runtime
│  │  │ (one impl per function)      │   │    │    (system Gemini path)
│  │  └──────────┬───────────────────┘   │    │
│  │             │                       │    │
│  │  @AppFunction wrappers ─────────────┤    │
│  │  generatePrintReadyPoster(…)        │    │
│  │  upscaleImage(…)                    │    │
│  │  generatePosterPdf(…)               │    │
│  │  viewLastPoster()                   │    │
│  │  redoPosterWithSettings(…)          │    │
│  │  sharePoster(…)                     │    │
│  └─────────────────────────────────────┘    │
│                  ▲                          │
│                  │ tool calls               │
│  ┌───────────────┴─────────────────────┐    │
│  │  In-app Q&A sheet (sparkle icon)    │    │
│  │  voice + text input  →  askGemini   │    │
│  └─────────────────────────────────────┘    │
└──────────────────────┬──────────────────────┘
                       │ Cloud Functions callable
                       ▼
       ┌───────────────────────────────────────────┐
       │  askGemini Cloud Function                 │
       │  (Vertex AI Gemini 3.5 Flash)             │
       │  + image vision context                   │
       │  + tool definitions (same as @AppFunction)│
       │  + rate-limit: 10 queries/user/day        │
       └───────────────────────────────────────────┘
```

Function definitions are written once in `PosterPdfAgentFunctions` (a service class injected with the existing `MainViewModel` for state access). Two layers consume them: AppFunctions runtime (for system Gemini) and the in-app Q&A's tool-calling (for in-sheet Gemini). Single source of truth for "what PosterPDF can do for Gemini."

## Part 1 — AppFunctions surface

### Function inventory

The headline use case is **invocation from inside a Gemini app session** — e.g., a user generates an image with Gemini, then says "make this into a 24×36 poster." Gemini already has the image in its session context; we want PosterPDF to run that workflow with zero context switches if possible. Headless paid-tier upscale is allowed when the user has confirmed the cost in-Gemini before the function actually runs.

| Function | KDoc summary (and shape) | Invocation mode |
|---|---|---|
| `quoteUpscaleCost(sourceImageUri, upscaleModel, targetWidthInches, targetHeightInches, paperSize?)` | Returns the credit cost + current balance + can-afford flag + plain-English explanation for the user. NON-MUTATING — safe to call before any confirmation. Used by Gemini to ask the user "this will cost X credits, you have Y, continue?" | Always headless. Read-only quote. |
| `generatePrintReadyPoster(sourceImageUri, targetWidthInches, targetHeightInches, paperSize?, upscaleModel?, headless=false, confirmCreditCost=false)` | Composite headline: pick image, optional upscale, tile, generate PDF. | See "Headless + paid-tier confirmation" below. |
| `upscaleImage(sourceImageUri, model, headless=false, confirmCreditCost=false)` | Just the upscale step. Returns content URI of the upscaled image. | Same confirmation pattern as `generatePrintReadyPoster` for paid models. |
| `generatePosterPdf(sourceImageUri, widthInches, heightInches, paperSize, marginsInches?, overlapInches?)` | Just the PDF-tiling step (no upscale, no credit spend). Returns content URI of the PDF. | Always headless — no credit spend, no destructive action. |
| `viewLastPoster()` | Opens the user's most recent generated PDF inside PosterPDF. | Deep-link only (it's a UI action). |
| `redoPosterWithSettings(historyId, widthInches?, heightInches?, upscaleModel?, headless=false, confirmCreditCost=false)` | Re-generate a previous poster with new parameters. | Same confirmation pattern as `generatePrintReadyPoster`. |
| `sharePoster(historyId)` | Opens the system share sheet for a saved PDF. | Deep-link only. |

### Headless + paid-tier confirmation flow

Per design discussion: paid-tier upscale through headless mode IS allowed, but only after Gemini has surfaced the cost to the user and the user has explicitly confirmed. The confirmation is enforced by a `confirmCreditCost=true` parameter on the function call.

**Expected Gemini conversation:**

> User: "Hey Gemini, take this image and make me a 24×36 poster with Imagen upscaling."
>
> Gemini: *(calls `quoteUpscaleCost(...)`)* → "That'll use about 87 credits ($0.87). You have 200 credits. Want me to go ahead?"
>
> User: "Yes."
>
> Gemini: *(calls `generatePrintReadyPoster(..., headless=true, confirmCreditCost=true)`)* → "Done — your poster is ready. Want me to share it or open it in PosterPDF?"

**Routing logic inside each paid-capable function:**

```kotlin
private fun routeRequest(
    model: UpscaleModel,
    headless: Boolean,
    confirmCreditCost: Boolean,
    sourceImageUri: String,
    /* … other args … */
): AppFunctionResponse {
    val isFreeTier = model == UpscaleModel.NONE || model == UpscaleModel.FREE_LOCAL
    return when {
        !headless -> launchDeepLink(/* … */)
        headless && (isFreeTier || confirmCreditCost) -> runInline(/* … */)
        headless && !isFreeTier && !confirmCreditCost ->
            throw AppFunctionExecutionException(
                "Paid upscale models require explicit cost confirmation in headless " +
                "mode. Call quoteUpscaleCost first, present the cost to the user, " +
                "then re-invoke this function with confirmCreditCost=true."
            )
        else -> launchDeepLink(/* … */)
    }
}
```

KDoc on each paid-capable function explicitly tells Gemini: "If invoked headlessly with a paid `upscaleModel`, you MUST call `quoteUpscaleCost` first, surface the cost to the user, get explicit confirmation, then re-invoke with `confirmCreditCost=true`. Direct headless invocation of a paid model without confirmation will fail."

This pattern keeps the user in the loop for credit-spending decisions (no surprise charges) while preserving the seamless in-Gemini-session flow.

### Cross-app image URIs (Gemini session context)

Image URIs handed to our AppFunctions may come from outside PosterPDF — Gemini's session storage, another app's content provider, a web-fetch result. The function bodies handle this by copying source bytes to our app-private storage on entry (mirrors the existing RC55 copy-on-import pattern in `MainViewModel.updateImage()`):

1. Resolve the input `Uri` via `ContentResolver.openInputStream()`. The AppFunctions runtime grants read access via the system Gemini service's URI permission delegation.
2. Copy bytes to `filesDir/imported_<epochMs>.bin` (matches RC57's unique-filename rule).
3. Proceed with the local file URI.

This means PosterPDF works seamlessly whether the source image lives in the user's gallery, a Google Photos cloud URI, Gemini's session storage, or a content provider from another app. No special-casing per provider.

### Invocation routing summary

- **Deep-link path** (default for paid models in non-headless mode): Intent with extras pre-filled, launch `MainActivity` via `AppFunctionContext.applicationContext.startActivity(intent)`. Returns an `AppFunctionResponse` signaling "user-confirmation-required" so Gemini shows "Opening PosterPDF…"
- **Headless path** (free tier OR `confirmCreditCost=true`): Runs the existing ViewModel-side logic inline. Returns the result URI/string directly. Gemini surfaces it as a chat response.

### KDoc quality bar

Google's published guidance: KDoc IS the agent-facing schema. KSP reads the comments and generates the JSON schema Gemini sees when deciding whether to call your function. Every parameter gets:

- A description sentence in plain English (no jargon, no abbreviations).
- A "use when" line in the function-level KDoc.
- A type hint for non-obvious shapes (e.g., `Uri` parameters note "must be a content:// URI accessible to PosterPDF").

The codebase already enforces style discipline via PR review — we extend the same bar to AppFunction KDocs and add a code-review checklist.

### Manifest changes

Two additions:

```xml
<service
    android:name="androidx.appfunctions.service.AppFunctionService"
    android:exported="true"
    android:permission="android.permission.BIND_APPFUNCTION_SERVICE" />

<meta-data
    android:name="android.app.shortcuts"
    android:resource="@xml/shortcuts" />
```

`res/xml/shortcuts.xml` declares App Actions capability blocks for older-device Assistant fallback:

```xml
<shortcuts xmlns:android="http://schemas.android.com/apk/res/android">
    <capability android:name="actions.intent.CREATE_POSTER">
        <intent
            android:targetPackage="com.posterpdf"
            android:targetClass="com.posterpdf.MainActivity">
            <parameter
                android:name="image.url"
                android:key="sourceImageUri" />
            <parameter
                android:name="dimension.width"
                android:key="targetWidthInches" />
            <parameter
                android:name="dimension.height"
                android:key="targetHeightInches" />
        </intent>
    </capability>
</shortcuts>
```

(BII name `actions.intent.CREATE_POSTER` is illustrative — confirm exact name against Google's published Built-In Intents list during implementation. If no exact BII match exists, register as a custom intent.)

### Permissions

- `BIND_APPFUNCTION_SERVICE` — already system-only, automatically enforced.
- No new runtime permissions needed for the headless path (functions operate on URIs already granted to the user via the picker).
- For sources outside our control (e.g., Gemini hands us a content URI from a different app), `Intent.FLAG_GRANT_READ_URI_PERMISSION` lets us read it inside our process.

### EAP gating

- Code compiles and registers regardless of EAP approval.
- System Gemini does not actually invoke the functions for end users until either (a) EAP approval clears (we applied 2026-05-25) or (b) AppFunctions framework goes GA publicly.
- Don't ship UI copy or release notes that promise "Hey Gemini, make me a poster" works for users until the EAP gate clears.

## Part 2 — In-app Gemini Q&A

### UI placement: sparkle in top bar

A sparkle icon (`auto_awesome` Material Symbol) sits in the top app bar. Google's first-party apps (Photos, Keep, Maps, Gmail) use this exact pattern for Gemini affordances.

**Top-bar width budget:** Adding the sparkle next to the existing credit chip + account avatar tightens portrait further. Mitigation (per the RC56 top-bar work):

- Signed-in narrow portrait: credit chip collapses to balance-only (drops the inline "Upgrade" pill); sparkle sits between balance and avatar.
- Signed-out narrow portrait: sparkle still renders (Gemini Q&A doesn't require sign-in for vision/text Q&A — it's free) but the credit chip is already hidden in this case (per RC56), so width is fine.
- Landscape: all elements render at full size.

### Sheet contents

Tap sparkle → modal bottom sheet:

```
┌─────────────────────────────────────┐
│  Ask Gemini about your poster       │
│                                     │
│  ┌─────────────────────────────┐   │
│  │  [your selected image]      │   │
│  │  thumbnail                  │   │
│  └─────────────────────────────┘   │
│                                     │
│   [🎤  Hold to talk]                │
│                                     │
│  — or type —                        │
│  [TextField                  ] [▶]  │
│                                     │
│  💡 Try asking:                     │
│  [What paper size for this?]        │
│  [Is this image sharp enough?]      │
│  [Cheapest model that hits 300 DPI?]│
└─────────────────────────────────────┘
```

- **Image thumbnail:** Current `selectedImageUri` (or empty-state if no image yet). Provides vision context to Gemini.
- **Voice input:** `SpeechRecognizer` (on-device, free, Android 9+). Tap-to-toggle recording. Result transcribed into the TextField, user reviews + sends. Inline confidence threshold guard (don't send below ~70%).
- **Text input:** Standard `TextField` for noisy environments / accessibility.
- **Suggestion chips:** 3 chips seeded by current app state — image selected, current dimensions, current upscale model. Hard-coded prompts mapped to template strings.
- **Response area** (not shown above for brevity): collapses sheet content when response arrives, shows Gemini's reply with `TextToSpeech` playback toggle, plus a "Try this" button if Gemini suggested an action that's representable as an AppFunction (one-tap invoke).

### Backend: `askGemini` callable

New Cloud Function in `backend/functions/src/`:

```ts
export const askGemini = onCall(
  { secrets: [], region: 'us-central1' },
  async (request) => {
    const { prompt, imageGsUri, currentSettings } = request.data;
    const uid = request.auth?.uid;

    await assertWithinRateLimit(uid);  // 10/day per uid

    const tools = buildToolDefinitions();  // mirrors AppFunction signatures
    const systemContext = buildSystemContext(currentSettings);

    const response = await callGemini({
      model: 'gemini-3-5-flash',
      systemInstruction: systemContext,
      tools,
      contents: [
        { role: 'user', parts: [
          ...(imageGsUri ? [{ fileData: { mimeType: 'image/png', fileUri: imageGsUri } }] : []),
          { text: prompt },
        ]},
      ],
    });

    return {
      text: response.text,
      toolCall: response.functionCall,  // null or an AppFunction-shaped action
    };
  }
);
```

- **Model:** `gemini-3-5-flash` (per `posterpdf-gemini-model-rule` memory; user explicitly chose 3.5 because 2.5 is approaching deprecation).
- **SDK:** Node `@google/genai` (current GA SDK, replaces the deprecated `vertexai.generative_models` Python SDK that was sunset June 2025; the Node equivalent on Vertex AI uses the same `@google/genai` package).
- **System context:** Includes the app's current state (selected image MP, target dimensions, paper size, current model choice) and a brief tool catalog.
- **Vision input:** Pass the `selectedImageUri`'s gs:// equivalent (we upload to a tmp GCS path first if the URI is a content URI). Use `fileUri` not `inlineData` to keep request bodies small.
- **Tool-calling result:** If Gemini's reply includes a function call, the client receives the function name + arguments. Client then either (a) invokes the AppFunction body directly (no system-Gemini round-trip), or (b) routes to the existing app screen with parameters pre-filled.

### Rate limit + cost containment

- 10 queries per user per day, tracked in Firestore at `users/{uid}/quota/gemini_qa` with a daily rollover.
- Soft-limit message at limit: "Come back tomorrow — Gemini's free here, but I have to ration it. Need more? Try the system Gemini app for general questions."
- Per-call cost ~$0.001–$0.005 → at 1000 DAU × 10/day = ~$50/day worst-case, ~$15/day expected (most users won't hit limit). Absorbable as a free feature.

### Safety + scope

- Gemini's built-in safety filters apply.
- Out-of-scope queries ("write me a poem"): system instruction directs Gemini to politely redirect ("I'm specifically here for PosterPDF help — try the Gemini app on your phone for general questions").
- Sensitive content in user images (NSFW, copyright): trust Gemini's filters; on filter trigger, surface a generic "Couldn't process that image" with no detail.

## Cross-cutting: single function source

The Kotlin function bodies on `PosterPdfAgentFunctions` are the single implementation. Two layers wrap them:

```
┌───────────────────────────┐
│ PosterPdfAgentFunctions   │  ← business logic, pure Kotlin
│   .generatePrintReady…    │
│   .upscaleImage…          │
└─────────┬─────────────────┘
          │
   ┌──────┴───────────────────────────────┐
   │                                      │
┌──┴────────────┐                  ┌──────┴─────────────┐
│ @AppFunction  │                  │ tool definitions   │
│ wrappers      │                  │ for askGemini      │
│ (system Gemini│                  │ (in-app Q&A)       │
│  invocation)  │                  │                    │
└───────────────┘                  └────────────────────┘
```

- AppFunction wrappers carry the manifest declarations, deep-link/headless routing, and KDoc-as-schema.
- Tool definitions for `askGemini` are generated at build time from the same KDoc comments via a Gradle task — keeps the schema in sync between the two consumers automatically.

This avoids the "Gemini in two surfaces sees different tool definitions" failure mode that's easy to fall into when each surface gets its own schema file.

## Testing strategy

- **Unit tests:**
  - Each AppFunction body: happy path + invalid-parameter rejection + headless-guard-rejects-paid-model.
  - Deep-link Intent construction asserts all extras are populated.
  - Headless return value shape (URI string, success/error wrapper).
  - System-context builder produces correct JSON given app state.
- **Mock-Gemini integration test:** Stub `callGemini` with canned function-call responses; assert tool-call routing reaches the right AppFunction body.
- **Manual end-to-end tests:**
  - System Gemini path (post-EAP): voice "Hey Gemini, turn this photo into a 24×36 poster using PosterPDF" → confirm deep-link launch with prefilled state.
  - In-app sparkle: voice input → response with text + tool-call suggestion → tap-to-invoke flow.
  - Rate-limit boundary: 11 queries in one day → 11th gets soft-limit message.

## Sequencing

| Phase | Scope | Ships independently? |
|---|---|---|
| B1 | AppFunctions surface (Part 1). Compile-time + manifest + service class. Hidden behind EAP gate for end users; visible to dev/test devices via adb. | Yes, but production benefit gated on EAP approval. |
| B2 | In-app Gemini Q&A sheet (Part 2). Sparkle icon, sheet UI, `askGemini` callable, rate-limit. | Yes — fully usable by end users on launch day; no EAP dependency. |

B1 and B2 share the `PosterPdfAgentFunctions` service class; building B2 effectively pre-builds the tool surface B1 exposes externally. Recommend B2 first (visible, user-facing benefit) then B1 (platform integration, gated benefit) — but the dependency points the other way for code structure, so the agent-functions class lands as foundational work for both.

## Open verification items (resolved during implementation)

1. **Exact Built-In Intent name for poster creation** — `actions.intent.CREATE_POSTER` is illustrative. Verify against Google's published BII catalog; if no exact match, register as a custom intent capability.
2. **AppFunctions Jetpack library version** — pin to current stable (or alpha as available) at implementation time. Library has been in alpha as of May 2026; track the GA release.
3. **`@google/genai` SDK version + Vertex AI region availability for `gemini-3-5-flash`** — confirm `us-central1` supports the 3.5 Flash model; if not, switch backend region or fall back to `us-east5`.
4. **Top-bar width budget after sparkle insertion** — empirically verify on a 360dp portrait device that sparkle + collapsed-chip + avatar all render without re-clipping. May need to adjust the chip's collapse threshold.
5. **Rate-limit storage rule changes** — `users/{uid}/quota/{key}` collection needs Firestore rules update so users can read their own quota count (for showing the "X queries left today" hint).
6. **Cross-app URI permission delegation by AppFunctions runtime** — verify how the system Gemini service grants read access to URIs from its session storage when it invokes our AppFunctions. Test path: trigger an AppFunction via system Gemini with a URI from another app's content provider, confirm our `ContentResolver.openInputStream()` succeeds without explicit `FLAG_GRANT_READ_URI_PERMISSION`. If grant is required, AppFunctions framework should handle it; if not, file a bug with the Android team.
7. **`quoteUpscaleCost` cost-calculation parity with the modal** — the quote must exactly match what `creditsForOption()` + `pickScale()` produce inside `LowDpiUpgradeModal.kt`. Shared helper function (probably extracted to a common Kotlin module) avoids the two paths drifting apart.

## Out of scope

- Gemini Live API (persistent streaming session). Deferred until/unless user behavior shows demand.
- Multi-turn conversation memory. One-shot only; each query starts fresh.
- In-app dictation for poster captions / annotations. Voice input is for Q&A only, not data entry into the main app flows.
- Gemini-generated images (text-to-image). Out of scope; this app is image-upscale + tiling, not image-generation.
- Custom tool definitions beyond the AppFunction set (e.g., "explain DPI to me") — Gemini's general knowledge handles these without a tool; we just give it the AppFunctions for action-taking.

---

## Implementation order summary

1. Extract `creditsForOption()` + `pickScale()` to a shared module so the in-modal flow and `quoteUpscaleCost` use the exact same math (parity gate before either consumer goes live).
2. `PosterPdfAgentFunctions` service class with pure Kotlin function bodies, including `quoteUpscaleCost`, the deep-link/headless/confirmCreditCost routing logic, and the cross-app URI copy-on-entry. Foundational for both parts.
3. `@AppFunction` wrappers + manifest + `shortcuts.xml` (Part 1). KDocs include explicit "call quoteUpscaleCost first" guidance for paid models.
4. Build-time Gradle task to emit tool-definition JSON from KDoc (cross-cutting glue).
5. `askGemini` Cloud Function with rate-limit + Vertex AI Gemini 3.5 Flash integration (Part 2 backend).
6. Sparkle icon + modal sheet UI + voice input + suggestion chips (Part 2 frontend).
7. Tool-call routing client-side (handle `toolCall` field in `askGemini` response).
8. Unit + mock-integration tests at each layer, including the headless-paid-without-confirmation rejection path.
9. Manual end-to-end verification: in-app Q&A first; system Gemini invocation post-EAP, including the cross-app-URI scenario (image from a Gemini app session).
