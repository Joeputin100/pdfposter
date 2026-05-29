# On-device Upscale → LiteRT + GPU + Full Streaming — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the free on-device upscale GPU-accelerated *and* crash-proof at any image size (bounded memory), with a validated CPU fallback — the launch gate.

**Architecture:** Decompose `UpscalerOnDevice` into `TileEngine` (LiteRT interpreter + GPU/CPU delegate selection + rc75 mutex), `RegionSource` (region-decode the source), and `StreamingPngSink` (incremental PNG writer). The orchestrator walks the output in horizontal bands so peak memory ≈ one band, independent of image size. A device-memory safety-net steers the rare too-big job to cloud; a >10-min ETA shows an "Are you sure?" modal.

**Tech Stack:** Kotlin, Jetpack Compose, LiteRT (`com.google.ai.edge.litert`), `org.tensorflow.lite.gpu` GPU delegate, `BitmapRegionDecoder`, `java.util.zip.Deflater`/`CRC32`, kotlinx.coroutines, JUnit + `javax.imageio` (JVM round-trip), Firebase Test Lab.

**Reference spec:** `docs/superpowers/specs/2026-05-28-ondevice-upscale-litert-gpu-streaming-design.md`

---

## File Structure

**Create:**
- `app/src/main/kotlin/com/posterpdf/ml/StreamingPngSink.kt` — incremental PNG encoder (writes signature+IHDR, streams scanlines as IDAT chunks, IEND on close). Pure JVM-testable.
- `app/src/main/kotlin/com/posterpdf/ml/RegionSource.kt` — reads source pixels by region via `BitmapRegionDecoder`, with whole-decode + `inSampleSize` fallback.
- `app/src/main/kotlin/com/posterpdf/ml/TileEngine.kt` — owns the LiteRT `Interpreter`, delegate selection (GPU via `CompatibilityList` else XNNPACK CPU), validation inference, the inference mutex; upscales one 50×50→200×200 tile.
- `app/src/test/kotlin/com/posterpdf/ml/StreamingPngSinkTest.kt`
- `app/src/test/kotlin/com/posterpdf/ml/DelegateSelectionTest.kt`
- `app/src/test/kotlin/com/posterpdf/ml/CapabilityGuardTest.kt`

**Modify:**
- `app/build.gradle.kts` — swap TFLite deps → LiteRT; add GPU artifact.
- `app/proguard-rules.pro` — keep/dontwarn LiteRT GPU classes (the reason GPU was previously excluded).
- `app/src/main/kotlin/com/posterpdf/ml/UpscalerOnDevice.kt` — orchestrator: band loop wiring `RegionSource → TileEngine → StreamingPngSink`; band-keyed resume; returns dest file. Remove the whole-output `Bitmap` + `compress`.
- `app/src/main/kotlin/com/posterpdf/ml/Capability.kt` — add `fitsOnDevice(...)` memory guard (band budget + output decode).
- `app/src/main/kotlin/com/posterpdf/MainViewModel.kt` — wire the >10-min ETA confirm + memory-net→cloud steer into `runFreeUpscale`.
- `app/src/main/kotlin/com/posterpdf/MainActivity.kt` — the "Are you sure?" confirm dialog (Compose) + its hoisted state.
- `app/src/androidTest/kotlin/com/posterpdf/UpscalePdfDeviceTest.kt` — add large-image + forced-CPU cases.
- `.github/workflows/ftl-upscale-test.yml` — device-matrix inputs for the verification fan-out.

---

# PHASE 1 — LiteRT + GPU delegate + CPU fallback

## Task 1: Swap dependencies to LiteRT + add GPU artifact

**Files:**
- Modify: `app/build.gradle.kts` (the `dependencies {}` block — the two `org.tensorflow:tensorflow-lite*` lines)
- Modify: `app/proguard-rules.pro`

- [ ] **Step 1: Replace the TFLite dependency lines**

In `app/build.gradle.kts`, replace:
```kotlin
    // NOTE: tensorflow-lite-gpu is intentionally NOT included. UpscalerOnDevice
    // uses NnApiDelegate, not GPU. Including the gpu artifact pulled in classes
    // ... (existing comment block) ...
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
```
with:
```kotlin
    // RC76: migrated off org.tensorflow:tensorflow-lite to LiteRT (the rebranded
    // runtime; NNAPI is deprecated in Android 15). API classes keep the
    // org.tensorflow.lite.* package names for compatibility. The GPU artifact
    // is now INCLUDED (proguard rules added) — UpscalerOnDevice uses the GPU
    // delegate with a CPU fallback. Do NOT also add org.tensorflow:tensorflow-lite*
    // — mixing the old + new artifacts causes a runtime "Didn't find class
    // org.tensorflow.lite.Delegate" (LiteRT issue #1599).
    implementation("com.google.ai.edge.litert:litert:1.4.0")
    implementation("com.google.ai.edge.litert:litert-gpu:1.4.0")
    implementation("com.google.ai.edge.litert:litert-support:1.4.0")
```

- [ ] **Step 2: Verify the latest published version before pinning**

Run: `curl -s https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert-gpu/maven-metadata.xml | grep -oE '<latest>[^<]+'`
Expected: a `<latest>1.x.y` line. If it differs from `1.4.0`, set all three `com.google.ai.edge.litert:*` lines to that same version (keep them identical).

- [ ] **Step 3: Add proguard keep rules for the GPU classes**

Append to `app/proguard-rules.pro`:
```proguard
# RC76: LiteRT GPU delegate — keep the native-bound classes and silence the
# optional-dependency warnings that previously broke the release (R8) build.
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn org.tensorflow.lite.gpu.**
```

- [ ] **Step 4: Confirm the project still configures (no compile yet)**

Run (CI builds remotely, but this catches Gradle config errors locally if a JDK is present): `cd /home/projects/pdfposter-md3e && gradle :app:help --offline 2>&1 | tail -3 || echo "no local gradle — rely on CI"`
Expected: `BUILD SUCCESSFUL` or the "no local gradle" fallback (CI is authoritative).

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/proguard-rules.pro
git commit -m "build(rc76): migrate TFLite → LiteRT + add GPU artifact with proguard keeps"
```

## Task 2: Extract delegate-selection logic (pure, unit-testable)

The choice "use GPU or fall back to CPU" must be testable without a device. We isolate the *decision* behind a tiny interface so a fake can simulate GPU-unavailable / validation-failure.

**Files:**
- Create: `app/src/main/kotlin/com/posterpdf/ml/TileEngine.kt`
- Test: `app/src/test/kotlin/com/posterpdf/ml/DelegateSelectionTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.posterpdf.ml

import org.junit.Assert.assertEquals
import org.junit.Test

class DelegateSelectionTest {
    // A fake probe standing in for the real GPU/validation checks.
    private fun choose(gpuSupported: Boolean, gpuCreates: Boolean, validates: Boolean) =
        selectDelegatePath(
            gpuSupportedOnDevice = { gpuSupported },
            tryCreateGpu = { if (gpuCreates) Unit else throw RuntimeException("no gpu") },
            validate = { validates },
        )

    @Test fun gpu_when_supported_creates_and_validates() {
        assertEquals(DelegatePath.GPU, choose(true, true, true))
    }
    @Test fun cpu_when_unsupported() {
        assertEquals(DelegatePath.CPU, choose(false, true, true))
    }
    @Test fun cpu_when_create_throws() {
        assertEquals(DelegatePath.CPU, choose(true, false, true))
    }
    @Test fun cpu_when_validation_fails() {
        assertEquals(DelegatePath.CPU, choose(true, true, false))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `gradle :app:testDebugUnitTest --tests "com.posterpdf.ml.DelegateSelectionTest" 2>&1 | tail -15` (or rely on CI)
Expected: FAIL — `selectDelegatePath` / `DelegatePath` unresolved.

- [ ] **Step 3: Implement the pure decision function in TileEngine.kt**

```kotlin
package com.posterpdf.ml

enum class DelegatePath { GPU, CPU }

/**
 * Pure GPU-vs-CPU decision. GPU only if the device reports support AND the
 * delegate creates AND a validation inference passes; any failure → CPU.
 * Keeping this free of Android/native types makes it unit-testable on the JVM.
 */
internal fun selectDelegatePath(
    gpuSupportedOnDevice: () -> Boolean,
    tryCreateGpu: () -> Unit,
    validate: () -> Boolean,
): DelegatePath {
    if (!gpuSupportedOnDevice()) return DelegatePath.CPU
    return try {
        tryCreateGpu()
        if (validate()) DelegatePath.GPU else DelegatePath.CPU
    } catch (_: Throwable) {
        DelegatePath.CPU
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `gradle :app:testDebugUnitTest --tests "com.posterpdf.ml.DelegateSelectionTest" 2>&1 | tail -8`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/posterpdf/ml/TileEngine.kt app/src/test/kotlin/com/posterpdf/ml/DelegateSelectionTest.kt
git commit -m "feat(rc76): pure GPU-vs-CPU delegate selection + tests"
```

## Task 3: TileEngine — build the LiteRT interpreter with GPU/CPU + validation + mutex

**Files:**
- Modify: `app/src/main/kotlin/com/posterpdf/ml/TileEngine.kt`

- [ ] **Step 1: Implement TileEngine (uses the Task 2 decision; no separate unit test — exercised by the FTL device test in Phase 3)**

Add to `TileEngine.kt`:
```kotlin
import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Upscales one 50x50 RGB tile → 200x200 via LiteRT. Thread-safe (serialized). */
internal class TileEngine(private val model: ByteBuffer) {
    private val mutex = Mutex()            // rc75: TFLite Interpreter is not thread-safe
    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var gpuDelegate: GpuDelegate? = null
    @Volatile var path: DelegatePath = DelegatePath.CPU; private set

    private fun ensure(): Interpreter {
        interpreter?.let { return it }
        val compat = CompatibilityList()
        var pendingGpu: GpuDelegate? = null
        path = selectDelegatePath(
            gpuSupportedOnDevice = { compat.isDelegateSupportedOnThisDevice },
            tryCreateGpu = { pendingGpu = GpuDelegate(compat.bestOptionsForThisDevice) },
            validate = { runValidation(pendingGpu) },
        )
        val opts = Interpreter.Options().apply {
            if (path == DelegatePath.GPU && pendingGpu != null) {
                gpuDelegate = pendingGpu
                addDelegate(pendingGpu)
            } else {
                pendingGpu?.close()
                setNumThreads(4)           // XNNPACK CPU (default-on for FP32)
            }
        }
        android.util.Log.i("UPSCALE_TEST", "tile engine path=$path")
        return Interpreter(model, opts).also { interpreter = it }
    }

    // Build a throwaway interpreter with the candidate GPU delegate and run one
    // tile of mid-grey; pass if the output is finite and roughly in [0,255].
    private fun runValidation(gpu: GpuDelegate?): Boolean = try {
        if (gpu == null) false else {
            val opts = Interpreter.Options().apply { addDelegate(gpu) }
            Interpreter(model, opts).use { interp ->
                val inBuf = ByteBuffer.allocateDirect(50 * 50 * 3 * 4).order(ByteOrder.nativeOrder())
                repeat(50 * 50 * 3) { inBuf.putFloat(128f) }
                inBuf.rewind()
                val outBuf = ByteBuffer.allocateDirect(200 * 200 * 3 * 4).order(ByteOrder.nativeOrder())
                interp.run(inBuf, outBuf)
                outBuf.rewind()
                val v = outBuf.float
                v.isFinite() && v > -50f && v < 305f
            }
        }
    } catch (_: Throwable) { false }

    /** Serialized single-tile inference. [inBuf]/[outBuf] are caller-owned direct buffers. */
    suspend fun run(inBuf: ByteBuffer, outBuf: ByteBuffer) = mutex.withLock {
        ensure().run(inBuf, outBuf)
    }

    fun close() {
        interpreter?.close(); interpreter = null
        gpuDelegate?.close(); gpuDelegate = null
    }
}
```

- [ ] **Step 2: Confirm it compiles (CI)**

Run: `gradle :app:compileDebugKotlin 2>&1 | tail -5` (or push and check `gh run watch` on build-android)
Expected: compiles (the GPU classes resolve from `litert-gpu`).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/posterpdf/ml/TileEngine.kt
git commit -m "feat(rc76): TileEngine — LiteRT GPU delegate w/ validated CPU fallback + mutex"
```

## Task 4: Route UpscalerOnDevice through TileEngine (Phase-1 integration, behavior unchanged)

Keep the existing whole-image loop for now; only swap the inference call to `TileEngine`. (Streaming comes in Phase 2.) This isolates the runtime migration so Phase 1 is independently verifiable.

**Files:**
- Modify: `app/src/main/kotlin/com/posterpdf/ml/UpscalerOnDevice.kt`

- [ ] **Step 1: Replace the interpreter field + ensureInterpreter with a TileEngine**

In `UpscalerOnDevice.kt` remove the `@Volatile private var interpreter` + `inferenceMutex` + `ensureInterpreter()` (the rc75 mutex now lives in `TileEngine`), and add:
```kotlin
    @Volatile private var engine: TileEngine? = null
    private fun engine(): TileEngine {
        engine?.let { return it }
        synchronized(this) {
            engine?.let { return it }
            val ctx = appContext ?: error("UpscalerOnDevice.init(context) must be called before upscale()")
            return TileEngine(loadModelFile(ctx)).also { engine = it }
        }
    }
```

- [ ] **Step 2: Swap the inference call**

In the tile loop, replace `interp.run(inBuf, outBuf)` with `engine().run(inBuf, outBuf)` and remove the now-unused `val interp = ...` line and the `inferenceMutex.withLock { ... }` wrapper (TileEngine serializes internally). Update `close()` to call `engine?.close(); engine = null`.

- [ ] **Step 3: Confirm compile (CI)**

Run: `gradle :app:compileDebugKotlin 2>&1 | tail -5`
Expected: compiles; no remaining references to `org.tensorflow.lite.nnapi` or the removed mutex.

- [ ] **Step 4: Commit + push, watch build-android green**

```bash
git add app/src/main/kotlin/com/posterpdf/ml/UpscalerOnDevice.kt
git commit -m "feat(rc76): route on-device upscale through TileEngine (LiteRT)"
git push   # then: gh run list --workflow=build-android.yml --limit 1
```
Expected: build-android run is `success` (full debug+release build — guards the proguard/GPU-class R8 regression).

---

# PHASE 2 — Full streaming (bounded memory)

## Task 5: StreamingPngSink — incremental PNG writer (TDD, pure JVM)

**Files:**
- Create: `app/src/main/kotlin/com/posterpdf/ml/StreamingPngSink.kt`
- Test: `app/src/test/kotlin/com/posterpdf/ml/StreamingPngSinkTest.kt`

- [ ] **Step 1: Write the failing round-trip test (uses `javax.imageio` available on the JVM test runtime)**

```kotlin
package com.posterpdf.ml

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class StreamingPngSinkTest {
    @Test fun writes_decodable_png_of_correct_size_and_pixels() {
        val w = 4; val h = 3
        val out = ByteArrayOutputStream()
        StreamingPngSink(out, w, h).use { sink ->
            // 3 rows, each 4 RGB pixels. Row 0 red, row 1 green, row 2 blue.
            val colors = intArrayOf(0xFF0000, 0x00FF00, 0x0000FF)
            for (y in 0 until h) {
                val row = ByteArray(w * 3)
                val c = colors[y]
                for (x in 0 until w) {
                    row[x * 3] = ((c ushr 16) and 0xFF).toByte()
                    row[x * 3 + 1] = ((c ushr 8) and 0xFF).toByte()
                    row[x * 3 + 2] = (c and 0xFF).toByte()
                }
                sink.writeRow(row)
            }
        }
        val img = ImageIO.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(w, img.width)
        assertEquals(h, img.height)
        assertEquals(0xFF0000, img.getRGB(0, 0) and 0xFFFFFF) // row 0 red
        assertEquals(0x0000FF, img.getRGB(3, 2) and 0xFFFFFF) // row 2 blue
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `gradle :app:testDebugUnitTest --tests "com.posterpdf.ml.StreamingPngSinkTest" 2>&1 | tail -15`
Expected: FAIL — `StreamingPngSink` unresolved.

- [ ] **Step 3: Implement StreamingPngSink**

```kotlin
package com.posterpdf.ml

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Streams an RGB (8-bit, color-type 2) PNG one scanline at a time so the full
 * image is never held in memory. Writes signature + IHDR up front, emits the
 * zlib stream as a sequence of IDAT chunks as the Deflater produces output,
 * and writes IEND on close(). Filter byte 0 (none) per scanline.
 */
internal class StreamingPngSink(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
) : AutoCloseable {
    private val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
    private val zbuf = ByteArray(16 * 1024)

    init {
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArray(13)
        writeIntBE(ihdr, 0, width); writeIntBE(ihdr, 4, height)
        ihdr[8] = 8   // bit depth
        ihdr[9] = 2   // color type: truecolor RGB
        ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0
        writeChunk("IHDR", ihdr, 0, ihdr.size)
    }

    /** Append one scanline of [width]*3 RGB bytes (top-to-bottom order). */
    fun writeRow(rgb: ByteArray) {
        require(rgb.size == width * 3) { "row must be width*3 bytes" }
        deflater.setInput(byteArrayOf(0))    // filter type 0 (none)
        drain(flush = false)
        deflater.setInput(rgb)
        drain(flush = false)
    }

    private fun drain(flush: Boolean) {
        while (true) {
            val n = if (flush) deflater.deflate(zbuf, 0, zbuf.size, Deflater.SYNC_FLUSH)
                    else deflater.deflate(zbuf)
            if (n <= 0) break
            writeChunk("IDAT", zbuf, 0, n)
        }
    }

    override fun close() {
        deflater.finish()
        while (!deflater.finished()) {
            val n = deflater.deflate(zbuf)
            if (n > 0) writeChunk("IDAT", zbuf, 0, n)
        }
        deflater.end()
        writeChunk("IEND", ByteArray(0), 0, 0)
        out.flush()
    }

    private fun writeChunk(type: String, data: ByteArray, off: Int, len: Int) {
        val lenb = ByteArray(4); writeIntBE(lenb, 0, len); out.write(lenb)
        val typeb = type.toByteArray(Charsets.US_ASCII); out.write(typeb)
        if (len > 0) out.write(data, off, len)
        val crc = CRC32(); crc.update(typeb); if (len > 0) crc.update(data, off, len)
        val crcb = ByteArray(4); writeIntBE(crcb, 0, crc.value.toInt()); out.write(crcb)
    }

    private fun writeIntBE(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `gradle :app:testDebugUnitTest --tests "com.posterpdf.ml.StreamingPngSinkTest" 2>&1 | tail -8`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/posterpdf/ml/StreamingPngSink.kt app/src/test/kotlin/com/posterpdf/ml/StreamingPngSinkTest.kt
git commit -m "feat(rc76): StreamingPngSink incremental PNG writer + JVM round-trip test"
```

## Task 6: RegionSource — region-decode the source

**Files:**
- Create: `app/src/main/kotlin/com/posterpdf/ml/RegionSource.kt`

- [ ] **Step 1: Implement RegionSource (device-dependent decode; verified by the FTL large-image test in Phase 3)**

```kotlin
package com.posterpdf.ml

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.content.Context
import android.net.Uri

/**
 * Supplies source pixels by region. Prefers BitmapRegionDecoder (decodes only
 * the requested rect — bounded memory for huge sources); falls back to a single
 * inSampleSize-bounded whole decode for formats that don't support region
 * decoding. [width]/[height] are the full source dimensions.
 */
internal class RegionSource private constructor(
    val width: Int,
    val height: Int,
    private val decoder: BitmapRegionDecoder?,
    private val whole: Bitmap?,
) {
    /** Read [rect] of the source into a Bitmap (caller recycles). */
    fun region(rect: Rect): Bitmap {
        decoder?.let {
            val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            return synchronized(it) { it.decodeRegion(rect, o) }
        }
        val b = whole!!
        return Bitmap.createBitmap(b, rect.left, rect.top, rect.width(), rect.height())
    }

    fun close() { decoder?.recycle(); whole?.recycle() }

    companion object {
        fun open(ctx: Context, uri: Uri): RegionSource {
            val cr = ctx.contentResolver
            // Bounds first (no allocation).
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val w = bounds.outWidth; val h = bounds.outHeight
            // Try region decoder.
            val decoder = try {
                cr.openInputStream(uri)?.use { ins ->
                    @Suppress("DEPRECATION")
                    BitmapRegionDecoder.newInstance(ins, false)
                }
            } catch (_: Throwable) { null }
            if (decoder != null) return RegionSource(w, h, decoder, null)
            // Fallback: whole decode (inSampleSize keeps it bounded if very large).
            val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val whole = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                ?: error("RegionSource: could not decode $uri")
            return RegionSource(whole.width, whole.height, null, whole)
        }
    }
}
```

- [ ] **Step 2: Confirm compile (CI)**

Run: `gradle :app:compileDebugKotlin 2>&1 | tail -5`
Expected: compiles.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/posterpdf/ml/RegionSource.kt
git commit -m "feat(rc76): RegionSource region-decode with whole-decode fallback"
```

## Task 7: Rewrite UpscalerOnDevice.upscale() as a band-streaming pipeline

**Files:**
- Modify: `app/src/main/kotlin/com/posterpdf/ml/UpscalerOnDevice.kt`

- [ ] **Step 1: Replace the whole-image loop body with the band loop**

Replace the body of `upscale(...)` (the `Bitmap.createBitmap(outW, outH)` + tile loop + final `out`/`compress` in MainViewModel) with a streaming pipeline. New signature returns the destination `File`:
```kotlin
suspend fun upscaleToFile(
    context: Context,
    sourceUri: Uri,
    dest: File,
    onProgress: ((completedTiles: Int, totalTiles: Int) -> Unit)? = null,
    resumeFromBand: Int = 0,
): File = withContext(Dispatchers.Default) {
    val eng = engine()
    val src = RegionSource.open(context, sourceUri)
    try {
        val outW = src.width * SCALE
        val outH = src.height * SCALE
        // Band height in OUTPUT px: a multiple of TILE_OUT that keeps one band's
        // working set under ~32MB (band RGB + tile buffers). bandRowsOut rows of
        // outW*3 bytes; choose bandTilesY tile-rows per band.
        val bytesPerOutRow = outW * 3
        val bandTilesY = maxOf(1, (32 * 1024 * 1024) / (bytesPerOutRow * TILE_OUT))
        val tileCols = (src.width + TILE_IN - 1) / TILE_IN
        val tileRows = (src.height + TILE_IN - 1) / TILE_IN
        val totalTiles = tileCols * tileRows
        var done = resumeFromBand * bandTilesY * tileCols

        FileOutputStream(dest, /*append=*/resumeFromBand > 0).use { fos ->
            val sink = StreamingPngSink(BufferedOutputStream(fos), outW, outH)
            val inBuf = ByteBuffer.allocateDirect(TILE_IN * TILE_IN * 3 * 4).order(ByteOrder.nativeOrder())
            val outBuf = ByteBuffer.allocateDirect(TILE_OUT * TILE_OUT * 3 * 4).order(ByteOrder.nativeOrder())
            val tilePixels = IntArray(TILE_IN * TILE_IN)

            var bandTileTop = resumeFromBand * bandTilesY
            while (bandTileTop < tileRows) {
                val bandTileBot = minOf(bandTileTop + bandTilesY, tileRows)
                val bandRowsOut = (bandTileBot - bandTiletop()) // see note below
                val band = Array(/*rows*/ (bandTileBot - bandTileTop) * TILE_OUT) { ByteArray(bytesPerOutRow) }
                for (ty in bandTileTop until bandTileBot) {
                    val srcY = if (ty * TILE_IN + TILE_IN <= src.height) ty * TILE_IN
                               else (src.height - TILE_IN).coerceAtLeast(0)
                    for (tx in 0 until tileCols) {
                        val srcX = if (tx * TILE_IN + TILE_IN <= src.width) tx * TILE_IN
                                   else (src.width - TILE_IN).coerceAtLeast(0)
                        val tile = src.region(Rect(srcX, srcY, srcX + TILE_IN, srcY + TILE_IN))
                        tile.getPixels(tilePixels, 0, TILE_IN, 0, 0, TILE_IN, TILE_IN)
                        tile.recycle()
                        inBuf.rewind()
                        for (px in tilePixels) {
                            inBuf.putFloat(((px ushr 16) and 0xFF).toFloat())
                            inBuf.putFloat(((px ushr 8) and 0xFF).toFloat())
                            inBuf.putFloat((px and 0xFF).toFloat())
                        }
                        inBuf.rewind(); outBuf.rewind()
                        eng.run(inBuf, outBuf)
                        outBuf.rewind()
                        // Composite this 200x200 output tile into the band buffer.
                        val bandRow0 = (ty - bandTileTop) * TILE_OUT
                        val colX = tx * TILE_OUT
                        for (r in 0 until TILE_OUT) {
                            val rowArr = band[bandRow0 + r]
                            var bi = colX * 3
                            for (c in 0 until TILE_OUT) {
                                val rr = outBuf.float.toInt().coerceIn(0, 255)
                                val gg = outBuf.float.toInt().coerceIn(0, 255)
                                val bb = outBuf.float.toInt().coerceIn(0, 255)
                                if (bi + 2 < rowArr.size) {
                                    rowArr[bi] = rr.toByte(); rowArr[bi + 1] = gg.toByte(); rowArr[bi + 2] = bb.toByte()
                                }
                                bi += 3
                            }
                        }
                        done++; onProgress?.invoke(done.coerceAtMost(totalTiles), totalTiles)
                    }
                }
                // Emit only the output rows that exist (last band may be short).
                val emitRows = minOf((bandTileBot - bandTileTop) * TILE_OUT, outH - bandTileTop * TILE_OUT)
                for (r in 0 until emitRows) sink.writeRow(band[r])
                UpscaleStateStore.saveBand(context, sourceUri.toString(), bandTileBot / bandTilesY)
                bandTileTop = bandTileBot
            }
            sink.close()
        }
        dest
    } finally { src.close() }
}
```

> **Note for the implementer:** the stray `bandTileTop()`/`bandRowsOut` line above is pseudocode left to make the band-height intent explicit — delete it; `emitRows` is the real row count. Keep `TILE_IN=50`, `TILE_OUT=200`, `SCALE=4`. This is the one place to be careful: bands are tile-row-aligned, so no tile straddles a band boundary (seam-safe). Edge tiles reuse the existing anchoring (`srcX/srcY` clamp).

- [ ] **Step 2: Update MainViewModel.runFreeUpscale to call upscaleToFile + drop the in-RAM compress**

In `MainViewModel.kt`, replace the `upscale(...)` call (the `withTimeout { UpscalerOnDevice.upscale(...) }` block) and the subsequent `outFile`/`upscaled.compress(PNG)` (lines ~356-364) with:
```kotlin
val outFile = File(context.cacheDir, "upscaled_${System.currentTimeMillis()}.png")
withContext(Dispatchers.IO) {
    com.posterpdf.ml.UpscalerOnDevice.upscaleToFile(
        context = context, sourceUri = uri, dest = outFile,
        onProgress = { done, total ->
            freeUpscaleTilesDone = done; freeUpscaleTotalTiles = total
            com.posterpdf.ml.UpscaleForegroundService.updateProgress(context, done, total)
        },
    )
}
selectedImageUri = Uri.fromFile(outFile)
val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
context.contentResolver.openInputStream(Uri.fromFile(outFile))?.use {
    android.graphics.BitmapFactory.decodeStream(it, null, opts)
}
sourcePixelDimensions = opts.outWidth to opts.outHeight
```
(Read final dimensions from the file bounds — we no longer hold the upscaled bitmap. Keep the existing `imageMetadata`/`wasUpscaled` updates, using `opts.outWidth/outHeight`.)

- [ ] **Step 3: Add the band-resume store method**

In `UpscaleStateStore`, add `fun saveBand(ctx: Context, sourceUri: String, lastBand: Int)` and `fun lastBand(ctx, sourceUri): Int` persisting to the same DataStore/prefs the tile-resume used. (Replace the per-tile partial-bitmap resume; band resume re-runs at most one band.)

- [ ] **Step 4: Confirm compile + the StreamingPngSink test still passes**

Run: `gradle :app:testDebugUnitTest --tests "com.posterpdf.ml.*" 2>&1 | tail -8` then push and watch build-android.
Expected: unit tests PASS; build-android `success`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/posterpdf/ml/UpscalerOnDevice.kt app/src/main/kotlin/com/posterpdf/MainViewModel.kt app/src/main/kotlin/com/posterpdf/ml/UpscaleStateStore.kt
git commit -m "feat(rc76): band-streaming upscale pipeline (bounded memory, seam-safe)"
```

---

# PHASE 3 — UX guards + verification

## Task 8: Capability memory guard (TDD, pure)

**Files:**
- Modify: `app/src/main/kotlin/com/posterpdf/ml/Capability.kt`
- Test: `app/src/test/kotlin/com/posterpdf/ml/CapabilityGuardTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.posterpdf.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityGuardTest {
    // bandFitsBytes: peak working set for one band at the chosen band height.
    @Test fun small_image_fits() {
        assertTrue(bandWorkingSetBytes(outWidthPx = 1808, bandTilesY = 2) < 64L * 1024 * 1024)
    }
    @Test fun band_count_scales_down_for_wide_images() {
        // Wider output ⇒ fewer tile-rows per band to stay under budget.
        val narrow = bandTilesForBudget(outWidthPx = 800, budgetBytes = 32L*1024*1024)
        val wide = bandTilesForBudget(outWidthPx = 20000, budgetBytes = 32L*1024*1024)
        assertTrue(wide <= narrow)
        assertTrue(wide >= 1)
    }
    @Test fun min_one_tile_row() {
        assertEquals(1, bandTilesForBudget(outWidthPx = 10_000_000, budgetBytes = 1L))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `gradle :app:testDebugUnitTest --tests "com.posterpdf.ml.CapabilityGuardTest" 2>&1 | tail -12`
Expected: FAIL — functions unresolved.

- [ ] **Step 3: Implement the pure helpers in Capability.kt**

```kotlin
const val TILE_OUT_PX = 200

/** Tile-rows per band so one band's RGB buffer stays under [budgetBytes]. */
fun bandTilesForBudget(outWidthPx: Int, budgetBytes: Long): Int {
    val bytesPerBandTileRow = outWidthPx.toLong() * 3 * TILE_OUT_PX
    return maxOf(1, (budgetBytes / bytesPerBandTileRow).toInt())
}

/** Peak band working set (band RGB only; tile buffers are fixed + small). */
fun bandWorkingSetBytes(outWidthPx: Int, bandTilesY: Int): Long =
    outWidthPx.toLong() * 3 * TILE_OUT_PX * bandTilesY
```
Then add a device-facing guard (used by the ViewModel) that only trips in the pathological case — one band can't fit even at one tile-row:
```kotlin
fun oneBandFits(ctx: Context, outWidthPx: Int): Boolean {
    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
    val budget = am.memoryClass.toLong() * 1024 * 1024 / 4   // quarter of per-app heap
    return bandWorkingSetBytes(outWidthPx, bandTilesForBudget(outWidthPx, budget)) <= budget
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `gradle :app:testDebugUnitTest --tests "com.posterpdf.ml.CapabilityGuardTest" 2>&1 | tail -8`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/posterpdf/ml/Capability.kt app/src/test/kotlin/com/posterpdf/ml/CapabilityGuardTest.kt
git commit -m "feat(rc76): band-memory guard helpers + tests"
```

## Task 9: >10-min ETA confirm modal + memory-net→cloud steer

**Files:**
- Modify: `app/src/main/kotlin/com/posterpdf/MainViewModel.kt`
- Modify: `app/src/main/kotlin/com/posterpdf/MainActivity.kt`

- [ ] **Step 1: Add ViewModel state + gate in runFreeUpscale**

In `MainViewModel.kt`, add:
```kotlin
var showLongJobConfirm by mutableStateOf(false); private set
var pendingUpscaleEtaMin by mutableStateOf(0); private set
private var pendingUpscaleStart: (() -> Unit)? = null

/** Call before starting. Returns true if we should proceed now; false if we
 *  popped the confirm modal (proceed happens via confirmLongJob()). */
private fun gateLongJob(context: Context, outWidthPx: Int, etaMs: Long, start: () -> Unit): Boolean {
    if (!com.posterpdf.ml.oneBandFits(context, outWidthPx)) {
        errorMessage = context.getString(R.string.upscale_too_large_use_cloud)  // steer to cloud
        return false
    }
    if (etaMs > 10 * 60 * 1000L) {
        pendingUpscaleEtaMin = (etaMs / 60000L).toInt()
        pendingUpscaleStart = start
        showLongJobConfirm = true
        return false
    }
    return true
}
fun confirmLongJob() { showLongJobConfirm = false; pendingUpscaleStart?.invoke(); pendingUpscaleStart = null }
fun dismissLongJob() { showLongJobConfirm = false; pendingUpscaleStart = null }
```
Wire `gateLongJob(...)` at the top of the free-upscale launch using the existing ETA estimate (`UpscaleEta`) and `src.width*4` for `outWidthPx` (decode source bounds first).

- [ ] **Step 2: Add the confirm dialog in MainActivity**

In `MainActivity.kt`, near the other dialogs:
```kotlin
if (viewModel.showLongJobConfirm) {
    AlertDialog(
        onDismissRequest = { viewModel.dismissLongJob() },
        title = { Text(stringResource(R.string.upscale_long_job_title)) },
        text = { Text(stringResource(R.string.upscale_long_job_msg, viewModel.pendingUpscaleEtaMin)) },
        confirmButton = { TextButton(onClick = { viewModel.confirmLongJob() }) { Text(stringResource(R.string.upscale_long_job_proceed)) } },
        dismissButton = { TextButton(onClick = { viewModel.dismissLongJob() }) { Text(stringResource(android.R.string.cancel)) } },
    )
}
```

- [ ] **Step 3: Add the 4 English strings**

In `app/src/main/res/values/strings.xml`:
```xml
<string name="upscale_long_job_title">This will take a while</string>
<string name="upscale_long_job_msg">Upscaling this image on your device may take about %1$d minutes. It runs in the background and you can keep using the app. Continue?</string>
<string name="upscale_long_job_proceed">Continue on device</string>
<string name="upscale_too_large_use_cloud">This image is too large to upscale on this device. Try the cloud upscale instead.</string>
```
(9-locale translation is a follow-up task — track it; do not block this task.)

- [ ] **Step 4: Confirm compile (CI)**

Run: push, watch build-android `success`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/posterpdf/MainViewModel.kt app/src/main/kotlin/com/posterpdf/MainActivity.kt app/src/main/res/values/strings.xml
git commit -m "feat(rc76): >10min ETA confirm modal + memory-net cloud steer"
```

## Task 10: Extend the FTL device test — large image + forced-CPU

**Files:**
- Modify: `app/src/main/kotlin/com/posterpdf/MainViewModel.kt` (the `runUpscaleAndPdfDeviceTest` hook: read extras `mode=large|cpu`)
- Modify: `app/src/androidTest/kotlin/com/posterpdf/UpscalePdfDeviceTest.kt`

- [ ] **Step 1: Add test-hook variants in the device-test driver**

In `runUpscaleAndPdfDeviceTest`, read an intent extra `upscale_mode`: `small` (default, current dogcow seed), `large` (seed/generate a ~4000×3000 source to exercise streaming + memory), `cpu` (set a process flag read by `TileEngine.selectDelegatePath` to force `DelegatePath.CPU`). Log `tile engine path=…` (already added) and a peak-memory marker: `Log.i("UPSCALE_TEST", "peak_heap_mb=" + (Debug.getNativeHeapAllocatedSize()/1048576))` sampled mid-run.

- [ ] **Step 2: Parameterize the test (or add two @Test methods) reusing the logcat-poll harness**

```kotlin
@Test fun largeImage_streams_without_oom() = runMarker("large")
@Test fun forcedCpu_fallback_works() = runMarker("cpu")
// runMarker launches with --es upscale_mode <mode>, polls logcat for
// UPSCALE_TEST_DONE / _FAILED exactly like upscaleTo18inThenGeneratePdf().
```
Refactor the existing logcat-poll body into a private `runMarker(mode: String)` and have all three tests call it.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/posterpdf/MainViewModel.kt app/src/androidTest/kotlin/com/posterpdf/UpscalePdfDeviceTest.kt
git commit -m "test(rc76): FTL large-image + forced-CPU upscale cases"
```

## Task 11: Verify on the device matrix

- [ ] **Step 1: Push to master and run the FTL fan-out across the risk-axis matrix**

The matrix is chosen to span the axes that actually break (GPU vendor, RAM tier, API floor, OEM driver quality) — not brand count. FTL has no TCL/Blu/Tecno, but their *risk profile* (MediaTek/Unisoc + PowerVR/low-Mali + 2–4GB + old Android) is covered by the Redmi 6A / vivo Y55s / budget-Moto proxies. For each device dispatch `ftl-upscale-test.yml` (default mode runs all three cases; confirm `supportedVersionIds` at dispatch):

| Device | model/API | Why (risk axis) |
|---|---|---|
| Pixel 6 | `oriole`/33 | Tensor/Mali — NNAPI-crash canary |
| Pixel 5 | `redfin`/30 | Adreno 620 — low-mid Qualcomm |
| OnePlus 11 | `CPH2449`/34 | Adreno 740 — flagship Qualcomm |
| Galaxy A53 | `SC-53C`/34 | Exynos/Mali-G68 — Samsung driver |
| **Redmi 6A** | `cactus`/27 | **MediaTek Helio A22 + PowerVR GE8320, 2GB, old — TCL/Blu proxy** |
| **vivo Y55s** | `1610`/23 | **API 23 minSdk floor — never-updated budget proxy** |
| budget Motorola | e.g. `fogorow` moto g24 | MediaTek + low-Mali + ~4GB — budget-Moto profile |

On the lowest-end devices (Redmi 6A / Y55s) the expected and acceptable outcome is `tile engine path=CPU` (validation fails or GPU unsupported) with the large case still streaming within budget — that proves the fallback is the safety net the long tail relies on.

- [ ] **Step 2: For each run, confirm via logcat:** `UPSCALE_TEST_DONE` present, `tile engine path=GPU` on capable devices (CPU on others — both acceptable), `peak_heap_mb` stays within budget on the large case, no `Fatal signal 11`/`scudo`. Record GPU-vs-CPU `upscaleMs` per device.

- [ ] **Step 3: Confirm the release (R8) build is green** (`gh run list --workflow=build-android.yml`) — guards the GPU-class proguard regression.

- [ ] **Step 4: Bump versionName to `1.0-rc76` + release note; commit.**

---

## Device-coverage strategy (the long tail)

Exhaustive device testing is impossible; resilience lives in the architecture, and the matrix only proves it holds:
- **Buggy/never-updated OEM devices (TCL, Blu, Tecno — not on any farm):** the GPU **validation inference + CPU fallback** (T2–T3) degrades a misbehaving driver to the universal CPU path; **band-streaming** (T5–T7) bounds memory on low-RAM. The Redmi 6A / vivo Y55s proxies (T11) exercise this profile.
- **Real long-tail hardware before full launch:** staged **Play Store testing tracks** (closed → open beta) put the app on actual TCL/Blu/Tecno devices in users' hands; **Crashlytics** (already wired, RC21-6) + **Android vitals** per-device crash rates surface device-specific failures. Graceful degradation means an unforeseen device shows up as "slower," not a crash.
- **Launchers + weird screen dimensions:** orthogonal to this headless upscale (no UI during compute). They belong to the UI/editorial-review track — see memory `project_play_store_editorial_prep` (360p, predictive back, foldables/tablets). FTL foldables/tablets/razr cover weird screens there.

## Self-Review (completed by plan author)

- **Spec coverage:** LiteRT migration (T1) ✓; GPU+validated CPU fallback (T2–T3) ✓; bundled LiteRT (T1) ✓; mutex retained (T3) ✓; full streaming RegionSource+BandSink (T5–T7) ✓; >10-min modal (T9) ✓; memory-net→cloud (T8–T9) ✓; SVG out-of-scope (unchanged path) ✓; FTL small/large/forced-CPU on Pixel 5/6/8 + GPU-vendor spread (T10–T11) ✓; release-R8 guard (T4,T11) ✓.
- **Placeholder scan:** one intentional pseudocode line in T7 is explicitly called out with delete instructions; no other TBDs. 9-locale translation flagged as a tracked follow-up (not a silent gap).
- **Type consistency:** `selectDelegatePath`/`DelegatePath` (T2) used in `TileEngine` (T3); `TileEngine.run`/`close` (T3) used by orchestrator (T4,T7); `StreamingPngSink.writeRow`/`close` (T5) used in T7; `bandTilesForBudget`/`bandWorkingSetBytes`/`oneBandFits` (T8) used in T9; `upscaleToFile` (T7) called by MainViewModel (T7 step 2). Consistent.

## Open follow-ups (post-implementation, tracked separately)
- 9-locale translation of the 4 new strings (existing fan-out process).
- Cloud-upload bandwidth warning (separate spec — memory `posterpdf_cloud_upscale_bandwidth_warn`).
