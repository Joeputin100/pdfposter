# On-device Upscale → LiteRT + GPU + Full Streaming — Design

- **Date:** 2026-05-28
- **Status:** Approved (brainstorm) — pending spec review → writing-plans
- **Owner:** PosterPDF / on-device upscale (`UpscalerOnDevice`)
- **Supersedes behavior of:** rc74 (NNAPI removed) + rc75 (Interpreter mutex) + rc75c (benchmark suppressed in test builds)

## 1. Goal & Context

Make the **free on-device upscale** both **fast** (GPU-accelerated) and **crash-proof at any image size** (memory bounded by a fixed working set, not by the image), with a reliable CPU fallback. This is a **launch gate** (user decision, 2026-05-28).

Why now — what device testing on real hardware (Firebase Test Lab) surfaced:
- The old path used a vendor **NNAPI** delegate that natively **SIGSEGV-crashed** on Pixel 6 / Google Tensor. NNAPI is **deprecated in Android 15**; removed in rc74.
- The true crash root cause was a **TFLite `Interpreter` thread-safety race** (the launch ETA benchmark racing a user upscale); fixed in rc75 with a serializing mutex. Verified green across Pixel 5/6/8.
- Clean cross-device CPU timing (452×357→18", ≈80 tiles): Pixel 5 **42.3s**, Pixel 6 **39.2s**, Pixel 8 **25.2s**. Scales linearly with pixels → large images take minutes on CPU. GPU is the speed fix.
- The current path stitches the whole upscaled image into one in-RAM bitmap (`createBitmap(w×4, h×4)`) and PNG-encodes it whole → a 12MP source → ~192MP → ~768MB → guaranteed OOM. "No input cap" is unsafe without streaming.

**Prior art / rationale:** This is the classic large-image technique — Photoshop on memory-constrained hardware (e.g. a 68k Mac) tiled images and paged tiles to a scratch disk, processing in tile/scanline order rather than all-at-once. The invariant we adopt: **peak memory is set by the band, not the picture.** Modern twist: a GPU delegate does the per-tile math.

## 2. Scope

**In scope:**
- Migrate the runtime from `org.tensorflow:tensorflow-lite` to **bundled LiteRT** (`com.google.ai.edge.litert`).
- Add the **GPU delegate** with a **mandatory XNNPACK CPU fallback** (try-GPU-else-CPU + a validation inference).
- Rework `UpscalerOnDevice` into a **full-streaming** pipeline: region-read the source, band-stream the output to an incremental PNG. Bounded memory regardless of image size.
- Keep the **rc75 inference mutex**.
- A device-memory **safety net** that steers the rare genuinely-too-big job to cloud (should almost never fire under full streaming).
- A **>10-minute ETA "Are you sure?"** confirmation modal before starting a long on-device job.
- Extend the **FTL verification** (small, large, forced-CPU-fallback) on Pixel 5/6/8.

**Out of scope:**
- The ESRGAN model itself (`esrgan_x4.tflite`) — unchanged.
- **SVG sources** — vector input does not need raster upscaling; the upscale path is raster-only.
- The **cloud-upload bandwidth warning** (separate feature; see memory `posterpdf_cloud_upscale_bandwidth_warn`).
- LiteRT-in-Play-Services + Acceleration Service (decided against: our own try-GPU-else-CPU fallback covers device safety without a Play-Services runtime dependency).

## 3. Architecture

`UpscalerOnDevice` decomposes into three independently-testable units behind the existing `suspend fun upscale(...)` entry point:

- **`TileEngine`** — owns the LiteRT `Interpreter`, the delegate selection (GPU or XNNPACK), and the rc75 mutex. One job: upscale a single 50×50 tile → 200×200 (pure: in-buffer → out-buffer). Holds no image-scale state.
- **`RegionSource`** — exposes the source by region using `BitmapRegionDecoder` over the source file URI, decoding only the pixels a tile needs. Falls back to whole-bitmap decode (with `inSampleSize`) when the source format doesn't support region decoding.
- **`BandSink`** — accepts composited output rows (top-to-bottom) and stream-encodes them to the destination PNG via an incremental `java.util.zip.Deflater` writer, never holding the full image. Emits a valid PNG (IHDR → streamed IDAT bands → IEND).

The orchestrator walks the output in horizontal bands, wires `RegionSource → TileEngine → BandSink`, drives progress/resume, and returns the destination file URI (matching today's contract: result is a `cacheDir` PNG assigned to `selectedImageUri`).

Each unit answers: *what it does, how you call it, what it depends on* — and can be unit-tested without a device (TileEngine behind an interpreter interface; RegionSource/BandSink with small fixtures).

## 4. Streaming I/O (the OOM fix)

- **Bands:** process the output in full-width horizontal strips whose height is chosen so one band's working set fits a fixed budget (target ~32MB: band-RGBA + the tile in/out buffers + Deflater window). Output band height is a multiple of `TILE_OUT` (200px).
- **Per band:** region-read the source rows feeding the band (via `RegionSource`) → upscale those tiles on the `TileEngine` → composite into a band-sized buffer → hand rows to `BandSink` → release → next band.
- **Seam correctness:** preserve the existing edge-tile anchoring + overlap, applied within each band; band boundaries align to tile-output rows so no tile straddles two bands in a way that double-composites.
- **Memory invariant:** peak heap ≈ one band + fixed buffers, independent of total image dimensions. A pathological extreme (e.g., enormous width) still bounded; if even one band can't fit, the safety net (§6) triggers.
- **Resume (RC11):** re-key persisted partial state from "last completed tile" to "last completed band." On resume, `BandSink` reopens/append-continues (or restarts from a band boundary using the persisted prefix). Progress reporting stays per-tile for UI continuity.

## 5. GPU + Fallback

- **Delegate selection (once per process, guarded by the mutex):** attempt to create the LiteRT GPU delegate; run a tiny **validation inference** (fixed known input → assert output finite and within expected range, not NaN/zeros). If creation throws *or* validation fails → discard GPU, build the XNNPACK CPU interpreter (the rc75-proven path). Cache the chosen path for the process; record which path ran (logcat marker) for FTL diagnostics.
- **Per-op fallback:** TFLite/LiteRT auto-runs GPU-unsupported ops on CPU; the validation step catches gross incompatibility so we don't ship a silently-wrong or pathologically-slow GPU path.
- **Thread-safety:** the rc75 mutex stays — exactly one inference at a time on the shared interpreter.
- **Release build:** add the proguard `-keep` / `-dontwarn` rules for the LiteRT GPU classes (the reason the GPU artifact was previously excluded — it broke R8). Verify the **release** (R8) build, not just the benchmark variant.

## 6. UX Guards

- **>10-min ETA modal:** before starting, use the existing ETA estimator; if predicted runtime > 10 min, show an "Are you sure?" confirmation (proceed / switch to cloud / cancel).
- **Memory safety net:** extend `Capability.kt` (already queries `ActivityManager`) so that if even a single band can't fit the budget (or the source can't be region-decoded and won't fit whole), the job is declined on-device and offered one-tap cloud — never crash. Per Google's bitmap-memory guidance (size to `getMemoryClass()`, degrade gracefully, no `largeHeap` crutch). With full streaming this should be vanishingly rare.
- **No silent downscale:** we never quietly reduce the requested scale; the user always gets 4× or an explicit cloud offer.

## 7. Verification

Extend the FTL upscale device test (now driven by the trustworthy logcat-marker harness) to three cases, run on Pixel 5 (redfin/30), Pixel 6 (oriole/33), Pixel 8 (shiba/34):
1. **Small image** — fast path; green; capture GPU-vs-CPU timing.
2. **Large image** — proves bounded memory (no OOM) and correct streamed output; assert peak memory stays within budget (sample `Debug.getMemoryInfo`/`Runtime` during the run via a logcat marker).
3. **Forced-CPU-fallback** — a test hook forces the XNNPACK path; proves the fallback produces a correct result.
Plus: the **release (R8) build** compiles and runs (guards the proguard/GPU-class regression). CI `pipefail` already makes a device crash fail the job.

## 8. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| GPU delegate crashes/wrong on some SoC (like NNAPI did) | Validation inference + automatic CPU fallback; FTL matrix incl. Tensor (Pixel 6/8) |
| GPU classes break R8 release minification (known history) | Proguard `-keep`/`-dontwarn`; verify release build in CI |
| Band seams / off-by-one in streamed output | Tile-aligned bands + reuse proven edge-anchoring; golden-image unit test on a small fixture |
| `BitmapRegionDecoder` unsupported for a source format | Fall back to whole-decode + `inSampleSize`; safety net to cloud if still too big |
| Incremental PNG encoder bugs (corrupt file) | Unit-test the `BandSink` against stock decoder round-trip; lossless PNG per project rule |
| Resume semantics change (tile→band) | Persist band boundary + prefix; test interrupt/resume |

## 9. Open Questions

- None blocking. Confirm the per-band memory budget (32MB proposed) during implementation benchmarking; tune per the YELLOW/RED tiers in `Capability.kt`.
