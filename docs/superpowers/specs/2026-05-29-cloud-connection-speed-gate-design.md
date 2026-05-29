# Cloud Connection & Upload-Speed Gate — Design

**Date:** 2026-05-29 · **Status:** approved (design + microcopy locked with user) · **Type:** new feature

## Goal

Before a **cloud** upscale (FAL / Imagen — the paths that upload the source image), check the network and measure the **real upload throughput**, then:

1. **Offline** → block the job with gentle guidance + point to the free on-device upscale.
2. **Slow** (source upload would take **> 1 min**) → a polite, dismissible "this may take a while" warning (Continue / Cancel).
3. **Fine** → proceed silently.

Bonus: reuse the measured throughput to replace the static **500 KB/s** guess that the existing ETA (`UpscaleEta.etaForFal`) uses today, so the *displayed* ETA also gets more accurate.

## Decisions (from brainstorming)

| Question | Decision |
|---|---|
| How to measure speed | **Active upload probe** — time a small real upload. (Not the OS link estimate: too unreliable. Not hybrid live-monitoring: YAGNI.) |
| Offline behavior | **Block with guidance** + offer on-device. |
| What the >1-min gate measures | **Upload time only** (the user's spec). Download is out of scope. |
| Tone | Gentle suggestion, never an imperative. Microcopy locked below. |

## Architecture

A dedicated **`NetworkSpeedProbe`** does the connectivity check + upload probe. The **ViewModel** calls it *before* it launches the cloud upscale (so the warning is genuinely pre-flight), reuses the existing `UpscaleEta` math for the threshold, and surfaces the result through two modals that mirror the existing on-device `showLongJobConfirm` "Are you sure?" pattern.

```
user taps cloud model
   └─ MainViewModel.gateCloudUpload(ctx, inputBytes, onProceed)
        └─ NetworkSpeedProbe.probe(ctx)  ──► ConnectionStatus
             • Offline      → showOfflineBlock = true            (block)
             • ProbeFailed  → showCloudSpeedWarn = true          (fail-safe: warn)
             • Measured(bps)→ shouldWarnSlowUpload(inputBytes,bps)?
                                yes → showCloudSpeedWarn + pendingCloudUploadEta
                                no  → onProceed()
        confirmCloudUpload() → onProceed();  dismissCloudUpload()/dismissOfflineBlock() → cancel
```

## Components

### 1. `app/src/main/kotlin/com/posterpdf/data/backend/NetworkSpeedProbe.kt` (new)
- `sealed interface ConnectionStatus { object Offline; data class Measured(val bytesPerSecondUp: Long); object ProbeFailed }`
- `suspend fun probe(context: Context): ConnectionStatus`
  - **Connectivity:** `ConnectivityManager.activeNetwork` + `getNetworkCapabilities`; require `NET_CAPABILITY_INTERNET` and `NET_CAPABILITY_VALIDATED`. Missing → `Offline`.
  - **Probe:** upload a ~**512 KB** byte array to Firebase Storage and time it (512 KB balances accuracy — 256 KB underestimates due to TCP slow-start — against cost). `bytesPerSecondUp = 512*1024 / elapsedSeconds`. Then **delete** the probe object.
  - **Constraint:** write the probe to a path the **existing Storage rules already permit** — reuse the same prefix `AiUpscaleRepository` uploads to. (Storage *rules deploy* is blocked pending the Console "Get Started" toggle, so we must NOT require new rules.)
  - **Cache:** keep the last `Measured` in memory with a timestamp; reuse it within **45 s** so a quick retry doesn't re-probe.
  - **Timeout / exception:** wrap the probe in a ~10 s timeout; any failure → `ProbeFailed` (caller warns rather than silently grinding). Never crash the upscale flow.
- Inject the probe behind a small interface (`interface UploadSpeedProbe { suspend fun probe(ctx): ConnectionStatus }`) so the ViewModel can be tested with a fake.

### 2. Pure helpers — extend `app/src/main/kotlin/com/posterpdf/ml/UpscaleEta.kt`
- `const val SLOW_UPLOAD_THRESHOLD_SEC = 60`
- `fun uploadEtaSeconds(inputBytes: Long, bytesPerSecond: Long): Double?` — `inputBytes / bytesPerSecond` (null if `bytesPerSecond <= 0` or `inputBytes <= 0`).
- `fun shouldWarnSlowUpload(inputBytes: Long, bytesPerSecond: Long): Boolean` — `(uploadEtaSeconds(...) ?: Double.MAX_VALUE) > SLOW_UPLOAD_THRESHOLD_SEC`.
- These are dependency-free → **real unit tests** (TDD).

### 3. `MainViewModel` (mirror `gateLongJob`/`confirmLongJob`/`dismissLongJob` at ~215–257)
- State: `showOfflineBlock`, `showCloudSpeedWarn` (Booleans), `pendingCloudUploadEta: IntRange?` (seconds, for `formatEta`), `pendingCloudUpscale: (() -> Unit)?`, and `lastMeasuredBytesPerSecond: Long?` (for the ETA-display bonus).
- `fun gateCloudUpload(context, inputBytes, onProceed)` — coroutine: probe → branch as in the diagram. Stash `onProceed` + the ETA before showing the warning.
- `fun confirmCloudUpload()` / `dismissCloudUpload()` / `dismissOfflineBlock()`.
- **Wire** `gateCloudUpload` in front of the existing cloud-upscale launch (the function that calls `AiUpscaleRepository`). The implementer locates that call site and routes it through the gate (the on-device free path keeps its own `gateLongJob`).
- **Bonus:** when `Measured`, store `lastMeasuredBytesPerSecond` and pass it to `etaForFal` instead of `DEFAULT_BYTES_PER_SECOND` (`LowDpiUpgradeModal.kt:272`) when a fresh value exists.

### 4. UI — `app/src/main/kotlin/com/posterpdf/ui/components/`
- `CloudSpeedWarningModal` — mirror the on-device long-job confirm modal. Body uses the locked copy with `formatEta(pendingCloudUploadEta)`. Buttons **Continue / Cancel**.
- `OfflineBlockDialog` — single dismiss ("Got it") + the offline copy.
- Render both where `showLongJobConfirm` is rendered in `MainScreen`/`MainActivity`.

### 5. i18n
- Add English strings (below). Then 9-locale fan-out (de, es, fr, hi, it, ja, ko, pt, zh) — may be a follow-up task.

## Microcopy (LOCKED — concise variant)

- **Slow-upload — title:** "This may take a while"
- **Slow-upload — body:** `This upload may take %1$s on your current connection. If Wi-Fi or a stronger signal is handy, it could go faster — but it's fine to continue as is.`
  (`%1$s` = `formatEta(...)`, e.g. "about 2 minutes" — no duplicated "about" since the body omits it.)
- **Slow-upload — buttons:** `Continue` / `Cancel` (reuse existing string resources; *not* "Continue anyway").
- **Offline — title:** "No connection"
- **Offline — body:** "You're not connected to the internet right now. Cloud upscaling needs a connection — once you're back online you can try again. In the meantime, on-device upscaling works offline."
- **Offline — button:** "Got it"

## Error handling

| Condition | Behavior |
|---|---|
| No validated network | `Offline` → block dialog |
| Probe timeout / exception / Storage error | `ProbeFailed` → show the slow warning (conservative), never block the flow from proceeding |
| `bytesPerSecond <= 0` | Treated as "unknown" by the pure helpers → warn |

## Testing

- **Unit (TDD):** `UpscaleEtaTest` additions for `uploadEtaSeconds` + `shouldWarnSlowUpload` — fast link (no warn), slow link (warn), the 60 s boundary, and the `bps <= 0` guard.
- **ViewModel:** with a `FakeUploadSpeedProbe` returning each `ConnectionStatus`, assert the right state flips (offline-block / speed-warn / proceed). 
- **Probe itself:** Firebase + Android — not unit-tested; verified manually / on device. Device CI is flaky, so correctness rests on the unit-tested decision logic.

## Out of scope (YAGNI)

Live mid-upload throughput monitoring; persistent speed history; user-configurable threshold; gating on download time; backend changes.

## Implementation tasks (TDD, commit per task)

- **T1** — Pure helpers in `UpscaleEta.kt` + unit tests.
- **T2** — `NetworkSpeedProbe` (`ConnectionStatus`, connectivity check, 512 KB timed probe on a permitted Storage path, delete, 45 s cache, 10 s timeout) behind `UploadSpeedProbe`.
- **T3** — `MainViewModel` gate state + `gateCloudUpload`/confirm/dismiss; route the cloud-upscale launch through it; ETA-display bonus.
- **T4** — `CloudSpeedWarningModal` + `OfflineBlockDialog`, wired into `MainScreen`.
- **T5** — English i18n strings (locked copy) via `formatEta`.
- **T6** — (follow-up) 9-locale translation fan-out.
