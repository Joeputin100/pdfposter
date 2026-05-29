# Predictive Back Audit (RC77)

**Date:** 2026-05-29 · **Scope:** does PosterPDF correctly support Android's predictive back gesture? (audit only — fixes/enhancements are a follow-up)

## Verdict: predictive-back **SAFE** ✅ (one optional enhancement)

The app **opts in** and routes **every** back interaction through the predictive-back-compatible API, so the system's predictive-back-to-home animation works and nothing intercepts back the legacy way. It does **not** render its own in-app back *preview* animations (plain `BackHandler`, not `PredictiveBackHandler`) — back is correct, just without a custom peek. That's an enhancement opportunity, not a defect.

## Evidence

**Opt-in (required for predictive back):**
- `app/src/main/AndroidManifest.xml:25` → `android:enableOnBackInvokedCallback="true"` ✅

**Back-handling sites — all use `androidx.activity.compose.BackHandler` (OnBackPressedCallback-based, predictive-compatible):**

| Site (MainActivity.kt) | Handles back for | Verdict |
|---|---|---|
| :363 | history screen dismiss | ✅ compatible |
| :367 | credits-history dismiss | ✅ compatible |
| :371 | getting-started dismiss | ✅ compatible |
| :375 | help dismiss | ✅ compatible |
| :379 | FAQ dismiss | ✅ compatible |
| :383 | privacy dismiss | ✅ compatible |
| :387 | support dismiss | ✅ compatible |
| :394 | community dismiss | ✅ compatible |
| :403 | community-post dismiss | ✅ compatible |
| :411 | compose-post dismiss | ✅ compatible |
| :471 | close open docked drawer (`enabled = drawerState.isOpen`) | ✅ compatible |
| :475 | close Compare-upscalers (`enabled = showUpscaleComparison`) | ✅ compatible |
| :478 | close LowDpi modal (`enabled = showLowDpiModal`) | ✅ compatible |

**Legacy handlers:** none. No `onBackPressed()` overrides, no raw `OnBackPressedCallback`/`getOnBackPressedDispatcher` usage — every site is Compose `BackHandler`.

## Optional enhancement (follow-up, not a gap)
The screen-swap `AnimatedContent` + the docked drawers + the modals dismiss instantly on back. Adopting `androidx.activity.compose.PredictiveBackHandler` (back-gesture *progress*) for those would add the modern peek/scale preview as the user swipes. Purely cosmetic polish; current behavior is fully correct and review-safe.

## Cross-launcher note
The predictive-back-to-**home** animation (when back exits the app) is rendered by the **system/launcher**, not the app. Our only responsibility is the manifest opt-in (present) + not blocking back (confirmed). It is therefore not meaningfully testable per-launcher in CI, and there is nothing app-side to change for it.
