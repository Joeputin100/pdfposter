# Spec A — Vertex AI Imagen Upscale Integration

**Author:** brainstormed 2026-05-25 with Claude (session "posterpdf")
**Status:** Approved design — awaiting implementation plan via `superpowers:writing-plans`
**Related:** [Spec B — AppFunctions + In-app Gemini Q&A](2026-05-25-appfunctions-gemini-design.md), [Spec C — Launch-readiness checklist](2026-05-25-launch-readiness-design.md)

---

## Goal

Add **Google Imagen 4 upscale** as a paid model option in PosterPDF's upscale pipeline, slotting between Recraft Crisp and CCSR in the pricing tier. The integration uses Vertex AI's `imagen-4.0-upscale-preview:predict` endpoint, authenticates via the existing Cloud Function service account (no new secret), and inherits the existing Firestore-document state-machine + credit-refund flow.

This is a Pure-Google addition to the stack — relevant to Spec C's editorial pitch ("textbook Google ecosystem showcase: Kotlin + Ktor + Firebase + Vertex AI + Android 16 MD3E").

## Non-goals

- Replacing FAL-routed models (Topaz / Recraft / AuraSR / ESRGAN-cloud / CCSR all remain).
- Switching Imagen to a "stable" model on the same vendor. **Imagen 3 has no public upscale capability** — Vertex's `imagegeneration@006` (Imagen 3 standard) and `@005` (Imagen 3 Fast) are generation-only. The `imagen-4.0-upscale-preview` model and the older `imagegeneration@002` (Imagen 1/2) are the only Imagen variants with a real upscale endpoint. The Python SDK confirms this: `upscale_image()` is hard-coded to `imagegeneration@002`. We're shipping the newer `imagen-4.0-upscale-preview` per the user's "latest version" intent.
- Automatic fallback to `imagegeneration@002` on preview-API breakage. If Google changes the Imagen 4 preview schema we'll hotfix; we won't auto-route to the old model.
- "Creative" upscale that re-imagines detail. We want **precise super-resolution only** — see "Precise vs creative upscale" below.

## Architecture

```
┌──────────────────┐         ┌──────────────────────────┐
│  Android client  │         │  Cloud Functions backend │
│                  │         │  (functions/src/upscale.ts) │
│  UpscaleModel    │         │                          │
│  .IMAGEN         │─── callable ────▶  requestUpscale  │
│                  │                  │       │         │
│  LowDpiUpgrade-  │                  │   modelId ===   │
│  Modal card      │                  │     'imagen'?   │
│  (Google badge)  │                  │      ├── yes ─▶ callVertexImagen()
│                  │                  │      └── no  ─▶ submitFalJob()   (unchanged)
└──────────────────┘                  │                          │
                                      │  Firestore state machine │
                                      │  requested → processing  │
                                      │  → completed / failed    │
                                      └──────────────────────────┘
                                                  │
                                       on `failed` ▼
                                      ┌──────────────────────────┐
                                      │ existing onUpscaleFailed │
                                      │ trigger (credit refund)  │
                                      └──────────────────────────┘
```

The split is **route-by-modelId**, not provider abstraction. `callVertexImagen()` lives next to `submitFalJob()` and shares only the state-machine document writes + refund trigger. The two providers have different request shapes, different auth surfaces, and different sync/async semantics — abstracting them into a single `UpscaleProvider` interface would be premature.

## Vertex API contract

**Endpoint** (synchronous):

```
POST https://us-central1-aiplatform.googleapis.com/v1/projects/static-webbing-461904-c4/locations/us-central1/publishers/google/models/imagen-4.0-upscale-preview:predict
```

**Auth:** OAuth2 bearer token from the Cloud Function's default service account. The Cloud Functions runtime already has Application Default Credentials available via `google-auth-library` (already in the backend's `node_modules` tree from Firebase Admin SDK). Token fetched per-request and not cached because the function instance lifecycle handles that for us.

### Precise vs creative upscale

We want **precise super-resolution** — preserve the user's original detail without hallucinating new content. This is the only behavior that makes sense for a poster-printing workflow: the printed output must match what the user expects from their source image.

The Imagen 4 upscale API does NOT expose an explicit "precise" vs "creative" mode toggle (verified May 2026: only `upscaleConfig.upscaleFactor` is documented inside `upscaleConfig`). Precision is guaranteed by request shape:

- **`prompt`** field is an EMPTY STRING `""`. The Python SDK's `upscale_image()` source code does exactly this (`instance = {"prompt": ""}` at `vertexai/vision_models/_vision_models.py:1338`). A non-empty prompt may cause the model to interpret the upscale as a guided regeneration; empty prompt gates the model into super-resolution behavior.
- **No editing/guidance parameters** sent. The transcript that originally led us to this work cited `enhanceInputImage: true` as a Vertex parameter, but this is NOT in the current public Imagen 4 upscale API. We do not send it. If Google adds it to a future API revision and it's verifiably a no-hallucination toggle, we can opt in then.
- **`mode: "upscale"`** in `parameters`. This is required by the API; it selects the upscale code path within the Imagen 4 preview model.

**Request body (precise upscale):**

```json
{
  "instances": [{
    "prompt": "",
    "image": { "gcsUri": "gs://<bucket>/<input-path>" }
  }],
  "parameters": {
    "sampleCount": 1,
    "mode": "upscale",
    "storageUri": "gs://<bucket>/<output-path>",
    "outputOptions": {
      "mimeType": "image/png",
      "compressionQuality": 100
    },
    "upscaleConfig": {
      "upscaleFactor": "x2" | "x3" | "x4"
    }
  }
}
```

**Response on success** (when `storageUri` is set, the API writes the bytes to GCS and returns a pointer):

```json
{
  "predictions": [{
    "mimeType": "image/png",
    "storageUri": "gs://<bucket>/<output-path>"
  }]
}
```

**Error paths:** Vertex returns a 4xx with a `code` + `status` field. We map them as follows:

| Vertex status | Firestore `failure_reason` | User-facing message |
|---|---|---|
| `INVALID_ARGUMENT` (size/format) | `invalid_input` | "This image format isn't supported by Imagen. Try a different model." |
| `RESOURCE_EXHAUSTED` | `quota_exceeded` | "Imagen is temporarily unavailable. Try again in a few minutes." |
| Safety-classifier block (`code: 400` with safety attribute) | `content_filter` | "This image couldn't be processed. You can try a different upscaler — your credits weren't charged." |
| `DEADLINE_EXCEEDED` (≥60s) | `timeout` | "Imagen took too long. Your credits weren't charged." |
| Any 5xx / network failure | `server_error` | "Something went wrong. Your credits weren't charged." |
| Unknown / parsing error | `unknown` | Generic "couldn't process" message |

Refund happens automatically via the existing Firestore trigger on `state → failed`. The trigger reads the `failure_reason` for analytics but the refund itself is unconditional on failure state — matches the user's pre-existing design intent ("users are refunded immediately for failed generations").

## Constraints from Imagen 4

- **Output cap:** 17 MP. We add `IMAGEN_MAX_OUTPUT_MP = 17` to `assertModelCapacity()` for consistency with the existing `CCSR_MAX_OUTPUT_MP = 8` and `RECRAFT_MAX_INPUT_MP = 1.5` guards. If a user requests a poster whose target dimensions × selected scale exceed 17 MP at Imagen, the backend rejects with `INVALID_ARGUMENT` before any API call and the client surfaces an "Imagen can only handle outputs up to 17 megapixels — try Topaz for higher resolution" hint.
- **Supported scales:** `x2`, `x3`, `x4`. We register `supportedScales: [2, 3, 4]` and let the existing `pickScale()` choose dynamically by target DPI. CCSR's pattern (4x default, but pickScale picks lower if appropriate) carries over.
- **Input MIME:** `image/png` or `image/jpeg`. SVG sources route through the existing rasterize-at-target-resolution path in the client before upload.
- **Safety classifier:** Google's enterprise safety filters are stricter than FAL's. The transcript noted "occasional false positives on user-uploaded poster graphics" — expected behavior, handled by the refund flow.

## Android client changes

### `LowDpiUpgradeModal.kt`

Add to the `UpscaleModel` enum (line 103):

```kotlin
enum class UpscaleModel { NONE, FREE_LOCAL, TOPAZ, RECRAFT, AURASR, ESRGAN, CCSR, IMAGEN }
```

Add to `ALL_OPTIONS` list, positioned between Recraft and CCSR in the array order (this controls 2-col grid placement). New option:

```kotlin
UpscaleOption(
    model = UpscaleModel.IMAGEN,
    displayNameRes = R.string.upscale_option_imagen_name,        // "Google Imagen"
    prosRes = R.string.upscale_option_imagen_pros,                // "Photo-faithful · texture-preserving · made by Google"
    consRes = R.string.upscale_option_imagen_cons,                // "x4 max · 17 MP output cap · subject to Google safety filters"
    scale = 4,
    supportedScales = listOf(2, 3, 4),
    // pricing populated from backend MODELS map via the existing flow
)
```

### `UpscaleOptionCard` brand badge

The Google-brand-mark drawable goes into `res/drawable/google_brand_badge.xml` (vector). Renders in the card's brand-stripe overlay zone, same slot Topaz uses for its "GIGAPIXEL" mark and CCSR uses for "CASCADED CONDITIONAL SR".

Per the codebase's lossless-only output rule, any raster brand badge would be PNG; using vector here side-steps the issue.

### i18n strings

New keys added to `values/strings.xml` and translated to all 9 locales:

- `upscale_option_imagen_name` — "Google Imagen"
- `upscale_option_imagen_pros` — Pros copy (tight, Material 3 body-small budget)
- `upscale_option_imagen_cons` — Cons copy (same budget)
- `vm_error_imagen_content_filter` — User-facing message for safety-classifier blocks
- `vm_error_imagen_too_large` — User-facing message for >17 MP output requests
- 3-locale fan-out via the existing translation-subagent workflow (per memory: subagents use Opus 4.7 max ultrathink)

## Backend changes

### `backend/functions/src/upscale.ts`

1. Add `'imagen'` to the `UpscaleModel` union type.
2. Add `MODELS['imagen']` entry:
   ```ts
   imagen: {
     vendor: 'google',
     supportedScales: [2, 3, 4],
     maxOutputMp: 17,
     pricingPerOutputMp: 0.0X,  // VERIFY: actual Imagen 4 preview pricing not yet on Google's public pricing page; defaults to a conservative midpoint between Recraft Crisp and CCSR until confirmed
     // No `endpoint` (FAL-style) — routes to callVertexImagen instead
   }
   ```
3. Add `IMAGEN_MAX_OUTPUT_MP = 17` and an `assertModelCapacity` branch for it (parallel to the existing CCSR_MAX_OUTPUT_MP / RECRAFT_MAX_INPUT_MP guards).
4. New function `callVertexImagen(modelId, sourceGsUri, scale, outputGsUri)` that issues the synchronous POST, parses the response (which contains a `storageUri` pointer if we set `parameters.storageUri`), and returns the output gs:// URI.
5. In the `requestUpscale` callable: if `modelId === 'imagen'`, branch to `callVertexImagen` instead of `submitFalJob` + `pollFalJob` + `fetchFalResult`. Result is written to the same Firestore doc fields the FAL path uses (`outputGsUri`, `outputMp`, `completedAt`).

### Auth wiring

Use `google-auth-library` (already a transitive dep via `firebase-admin`):

```ts
import { GoogleAuth } from 'google-auth-library';
const auth = new GoogleAuth({
  scopes: ['https://www.googleapis.com/auth/cloud-platform'],
});
const client = await auth.getClient();
const tokenResp = await client.getAccessToken();
// Pass tokenResp.token in the Authorization header
```

No new secret to declare in `defineSecret`. The Cloud Function's runtime SA already has Vertex AI access via the `roles/aiplatform.user` binding granted as part of project setup (verify during implementation; grant if missing).

### `backend/scripts/bake-comparison-assets.py`

Extend with an `imagen` branch parallel to the existing FAL-model branches. Issues a `:predict` against the same Vertex endpoint with the canonical demo source image. Writes a lossless PNG into the same staging directory, then the existing post-processing step re-encodes as lossless WebP for `res/raw/`.

The single new asset is `res/raw/upscale_demo_imagen.webp` (lossless), with the existing `UpscaleComparisonScreen` getting a new `R.raw.upscale_demo_imagen` reference in the model→asset map.

## Failure recovery + refund

The Firestore state machine is unchanged:

```
client creates doc → state = requested
                     ↓ (callable picks up)
                  processing
                     ↓
              completed / failed
```

The existing trigger on `state → failed` reads `failure_reason` and increments the user's credit balance. No new trigger code. We only need to ensure `callVertexImagen` writes `failure_reason` consistently on every error path (table above).

## Testing strategy

- **Unit tests:**
  - `callVertexImagen` happy path with a mocked Vertex client (returns synthetic response with `storageUri`).
  - Error mapping per Vertex status code → `failure_reason` table.
  - `assertModelCapacity` rejects >17 MP outputs at all three scale factors.
- **Integration test:** Existing `requestUpscale` callable integration test extended with one Imagen invocation; asserts Firestore doc transitions and refund-on-failure behavior using a mock failure injection.
- **Manual end-to-end:** One real Imagen 4 preview call per scale factor (x2, x3, x4) using the canonical demo image. Verify output quality matches comparison-asset expectations. Verify safety-classifier behavior by submitting a known-borderline image (specifically: a photo with prominent text-on-fabric, which Imagen has historically false-positived on).
- **Comparison asset:** Bake the demo asset once, eye-check against existing demos.

## Open verification items (resolved during implementation)

1. **Actual per-image pricing for Imagen 4 preview** — not yet on Google's public pricing page as of 2026-05-25. Default backend `pricingPerOutputMp` to a midpoint between Recraft Crisp and CCSR (~$0.025–$0.035 per output image); update once Google publishes preview pricing.
2. **IAM `roles/aiplatform.user`** on the Cloud Functions service account — verify present; grant if missing. Required for the `:predict` call to succeed.
3. **Vertex `:predict` exact error response shape for safety-classifier blocks** — Google's docs are vague on whether it's an HTTP 400 with a body field or a 200 with an empty `predictions` array. Implementation will log the raw response for the first block we see, then refine the parsing.

## Out of scope

- Imagen 4 generation (text → image). This spec is upscale-only.
- Imagen 3 fallback if preview API breaks. User opted out of automatic fallback.
- Vertex pricing dashboard / cost-alert tooling. Existing Firebase project's GCP billing alerts cover this.
- Hybrid FAL+Vertex routing for redundancy. Each model maps 1:1 to a provider.

---

## Implementation order summary

1. Backend `callVertexImagen` + tests
2. `MODELS['imagen']` entry + `IMAGEN_MAX_OUTPUT_MP` guard
3. Android `UpscaleModel.IMAGEN` + `ALL_OPTIONS` card + i18n strings (English first, fan-out later)
4. Google brand badge drawable
5. Translation fan-out (9 locales via subagents)
6. Bake comparison asset + wire `UpscaleComparisonScreen`
7. End-to-end manual verification
