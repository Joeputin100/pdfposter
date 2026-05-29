package com.posterpdf.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * On-device x4 super-resolution upscaler backed by the ESRGAN-TF2 TFLite model
 * (substituted for the plan's Real-ESRGAN — see Phase G8 notes).
 *
 * Model contract (verified at download time):
 *   input  : float32[1, 50, 50, 3]   pixel values in [0, 255]
 *   output : float32[1, 200, 200, 3] pixel values in [0, 255]
 *
 * Inputs larger than 50x50 are tiled internally; output tiles are composited
 * into a Bitmap of size (width * 4, height * 4).
 *
 * Initialize once with [init]; reuse across calls. The Interpreter is created
 * lazily on first inference and cached for subsequent calls.
 */
object UpscalerOnDevice {

    private const val MODEL_ASSET = "esrgan_x4.tflite"
    private const val TILE_IN = 50
    private const val TILE_OUT = 200 // 4 * TILE_IN
    private const val SCALE = 4

    // RC76: target peak working set for one streamed band (band RGB rows). The
    // fixed tile in/out buffers (~0.7 MB) + Deflater window are negligible on
    // top. A band is sized to a whole number of 200px tile-rows under this.
    private const val BAND_BUDGET_BYTES = 32L * 1024 * 1024

    @Volatile private var appContext: Context? = null

    // RC76: the LiteRT Interpreter + delegate selection + the rc75 inference
    // mutex (TFLite's Interpreter is NOT thread-safe) now live in TileEngine.
    // Every inference path goes through upscale() → engine().run(), and
    // TileEngine serializes all run() calls, so benchmark + free upscale + any
    // future caller still never execute a concurrent native inference.
    @Volatile private var engine: TileEngine? = null

    /** Call once (e.g. from Application or first use site) to bind a Context. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun engine(): TileEngine {
        engine?.let { return it }
        synchronized(this) {
            engine?.let { return it }
            val ctx = appContext
                ?: error("UpscalerOnDevice.init(context) must be called before upscale()")
            return TileEngine(loadModelFile(ctx)).also { engine = it }
        }
    }

    private fun loadModelFile(ctx: Context): MappedByteBuffer {
        val afd = ctx.assets.openFd(MODEL_ASSET)
        FileInputStream(afd.fileDescriptor).use { fis ->
            return fis.channel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.declaredLength
            )
        }
    }

    /**
     * RC76 — band-streaming 4x upscale with BOUNDED memory. The old path
     * composited the whole (4x) output into one in-RAM Bitmap and PNG-encoded
     * it whole: a 12 MP source → ~192 MP → ~768 MB → guaranteed OOM. This
     * version walks the OUTPUT in full-width horizontal bands (each band a
     * whole number of 200px tile-rows), region-reads only the source a band
     * needs ([RegionSource]), upscales tile-by-tile on the [TileEngine], and
     * streams each band straight to [dest] as an incremental PNG
     * ([StreamingPngSink]). Peak heap ≈ one band + fixed tile buffers,
     * independent of image size.
     *
     * Seam-safety: bands are tile-row-aligned so no 200px output tile straddles
     * a band boundary. Edge tiles (when src isn't a multiple of 50) reuse the
     * proven anchoring (clamp srcX/srcY so the model always gets a full 50x50),
     * and each tile writes ONLY the output region it is responsible for
     * ([destColStart, destColEnd) x [destRowStart, destRowEnd)), clipped to the
     * output bounds — replicating the old Canvas src/dst-rect overlap handling
     * so the overlapping (re-sampled) columns/rows are never double-composited.
     *
     * Progress is reported per tile (UI continuity, RC10/RC11). On each
     * completed band we persist [UpscaleStateStore.saveBand] for resume.
     *
     * Resume note: a streamed PNG is a SINGLE continuous zlib stream split
     * across IDAT chunks, so a partially-written file cannot be safely
     * continued by appending a second Deflater's output (and its IHDR is
     * already written). [resumeFromBand] is therefore best-effort: this impl
     * re-encodes from band 0 (a process kill re-runs the upscale to completion
     * rather than corrupting the file). The band-keyed state is kept so a
     * future raw-scratch prefix can restore the skip-completed-bands
     * optimization without changing callers. Returns [dest].
     *
     * @param sourceUri the source image to upscale (read by region).
     * @param dest the destination PNG file (overwritten).
     */
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
            val srcW = src.width
            val srcH = src.height
            require(srcW > 0 && srcH > 0) { "Source has no pixels ($srcW x $srcH)" }
            val outW = srcW * SCALE
            val outH = srcH * SCALE
            val bytesPerOutRow = outW * 3

            val tileCols = (srcW + TILE_IN - 1) / TILE_IN
            val tileRows = (srcH + TILE_IN - 1) / TILE_IN
            val totalTiles = tileCols * tileRows

            // Tiny source (< one 50px tile in some dimension): can't region-read
            // a full 50x50, so handle it with a single whole-decode stretch →
            // upscale → downscale, streamed row-by-row. Rare; bounded (tiny).
            if (srcW < TILE_IN || srcH < TILE_IN) {
                streamTinySource(src, dest, outW, outH, onProgress)
                return@withContext dest
            }

            // Band height (in OUTPUT px) = bandTilesY tile-rows, chosen so one
            // band's RGB working set stays under ~32 MB. Long math avoids
            // overflow for very wide outputs.
            val bandTilesY = maxOf(
                1,
                (BAND_BUDGET_BYTES / (bytesPerOutRow.toLong() * TILE_OUT)).toInt(),
            )
            if (resumeFromBand > 0) {
                android.util.Log.i(
                    "UPSCALE_TEST",
                    "upscaleToFile: resumeFromBand=$resumeFromBand requested; " +
                        "re-encoding from band 0 (streamed PNG can't be appended)",
                )
            }

            // Reusable buffers — allocated ONCE for the whole upscale.
            val inBuf = ByteBuffer.allocateDirect(TILE_IN * TILE_IN * 3 * 4)
                .order(ByteOrder.nativeOrder())
            val outBuf = ByteBuffer.allocateDirect(TILE_OUT * TILE_OUT * 3 * 4)
                .order(ByteOrder.nativeOrder())
            val tilePixels = IntArray(TILE_IN * TILE_IN)
            val outPixels = IntArray(TILE_OUT * TILE_OUT)
            // One band buffer reused across ALL bands (this is the whole point of
            // streaming — bounded peak heap). Sized for a full band; the short
            // last band uses only its first rows. Every emitted cell is fully
            // overwritten each band (tiles cover all cols of all emitted rows),
            // so reuse never leaks stale pixels.
            val band = Array(bandTilesY * TILE_OUT) { ByteArray(bytesPerOutRow) }

            var done = 0
            var bandIndex = 0
            FileOutputStream(dest).use { fos ->
                val sink = StreamingPngSink(BufferedOutputStream(fos), outW, outH)
                var bandTileTop = 0
                while (bandTileTop < tileRows) {
                    val bandTileBot = minOf(bandTileTop + bandTilesY, tileRows)
                    for (ty in bandTileTop until bandTileBot) {
                        val idealY = ty * TILE_IN
                        // Anchor the bottom edge tile-row back so the model
                        // still gets a full 50px-tall region.
                        val srcY = if (idealY + TILE_IN <= srcH) idealY
                                   else (srcH - TILE_IN).coerceAtLeast(0)
                        // Output rows this tile-row owns (its non-overlapping,
                        // in-bounds slice), absolute in the output image.
                        val destRowStart = idealY * SCALE
                        val destRowEnd = minOf((idealY + TILE_IN) * SCALE, outH)
                        for (tx in 0 until tileCols) {
                            val idealX = tx * TILE_IN
                            val srcX = if (idealX + TILE_IN <= srcW) idealX
                                       else (srcW - TILE_IN).coerceAtLeast(0)
                            val destColStart = idealX * SCALE
                            val destColEnd = minOf((idealX + TILE_IN) * SCALE, outW)

                            val tile = src.region(
                                Rect(srcX, srcY, srcX + TILE_IN, srcY + TILE_IN),
                            )
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

                            // Unpack the 200x200 tile output once, clamped.
                            outBuf.rewind()
                            for (i in 0 until TILE_OUT * TILE_OUT) {
                                val r = outBuf.float.toInt().coerceIn(0, 255)
                                val g = outBuf.float.toInt().coerceIn(0, 255)
                                val b = outBuf.float.toInt().coerceIn(0, 255)
                                outPixels[i] = (r shl 16) or (g shl 8) or b
                            }

                            // Composite only this tile's owned output region,
                            // sampling the tile by its anchored local offset.
                            for (oy in destRowStart until destRowEnd) {
                                val localRow = oy - srcY * SCALE      // in [0,200)
                                val rowArr = band[oy - bandTileTop * TILE_OUT]
                                val tileRowBase = localRow * TILE_OUT
                                var bi = destColStart * 3
                                for (ox in destColStart until destColEnd) {
                                    val rgb = outPixels[tileRowBase + (ox - srcX * SCALE)]
                                    rowArr[bi] = ((rgb ushr 16) and 0xFF).toByte()
                                    rowArr[bi + 1] = ((rgb ushr 8) and 0xFF).toByte()
                                    rowArr[bi + 2] = (rgb and 0xFF).toByte()
                                    bi += 3
                                }
                            }

                            done++
                            onProgress?.invoke(done.coerceAtMost(totalTiles), totalTiles)
                        }
                    }
                    // Emit only the output rows that exist (last band is short).
                    val emitRows = minOf(
                        (bandTileBot - bandTileTop) * TILE_OUT,
                        outH - bandTileTop * TILE_OUT,
                    )
                    for (r in 0 until emitRows) sink.writeRow(band[r])
                    // Persist the count of fully-completed bands (monotonic), so
                    // a future skip-resume can restart at lastBand*bandTilesY
                    // tile-rows. (This impl re-encodes from 0 — see KDoc.)
                    bandIndex++
                    UpscaleStateStore.saveBand(context, sourceUri.toString(), bandIndex)
                    bandTileTop = bandTileBot
                }
                sink.close()
            }
            dest
        } finally {
            src.close()
        }
    }

    /**
     * Fallback for sources smaller than one 50px tile: decode the whole (tiny)
     * source, stretch to 50x50, upscale to 200x200, then downscale (nearest-
     * neighbour) to the true outW x outH and stream the rows. Mirrors the old
     * in-RAM path's tiny-source branch; bounded because the source is tiny.
     */
    private suspend fun streamTinySource(
        src: RegionSource,
        dest: File,
        outW: Int,
        outH: Int,
        onProgress: ((completedTiles: Int, totalTiles: Int) -> Unit)?,
    ) {
        val eng = engine()
        // One whole-source region read (rect == full bounds), then stretch.
        val whole = src.region(Rect(0, 0, src.width, src.height))
        val padded = Bitmap.createScaledBitmap(whole, TILE_IN, TILE_IN, true)
        if (padded !== whole) whole.recycle()
        val tilePixels = IntArray(TILE_IN * TILE_IN)
        padded.getPixels(tilePixels, 0, TILE_IN, 0, 0, TILE_IN, TILE_IN)
        if (padded !== whole) padded.recycle()

        val inBuf = ByteBuffer.allocateDirect(TILE_IN * TILE_IN * 3 * 4)
            .order(ByteOrder.nativeOrder())
        for (px in tilePixels) {
            inBuf.putFloat(((px ushr 16) and 0xFF).toFloat())
            inBuf.putFloat(((px ushr 8) and 0xFF).toFloat())
            inBuf.putFloat((px and 0xFF).toFloat())
        }
        inBuf.rewind()
        val outBuf = ByteBuffer.allocateDirect(TILE_OUT * TILE_OUT * 3 * 4)
            .order(ByteOrder.nativeOrder())
        eng.run(inBuf, outBuf)
        outBuf.rewind()
        val outPixels = IntArray(TILE_OUT * TILE_OUT)
        for (i in 0 until TILE_OUT * TILE_OUT) {
            val r = outBuf.float.toInt().coerceIn(0, 255)
            val g = outBuf.float.toInt().coerceIn(0, 255)
            val b = outBuf.float.toInt().coerceIn(0, 255)
            outPixels[i] = (r shl 16) or (g shl 8) or b
        }
        FileOutputStream(dest).use { fos ->
            val sink = StreamingPngSink(BufferedOutputStream(fos), outW, outH)
            val row = ByteArray(outW * 3)
            for (oy in 0 until outH) {
                val sy = (oy.toLong() * TILE_OUT / outH).toInt().coerceIn(0, TILE_OUT - 1)
                val base = sy * TILE_OUT
                var bi = 0
                for (ox in 0 until outW) {
                    val sx = (ox.toLong() * TILE_OUT / outW).toInt().coerceIn(0, TILE_OUT - 1)
                    val rgb = outPixels[base + sx]
                    row[bi] = ((rgb ushr 16) and 0xFF).toByte()
                    row[bi + 1] = ((rgb ushr 8) and 0xFF).toByte()
                    row[bi + 2] = (rgb and 0xFF).toByte()
                    bi += 3
                }
                sink.writeRow(row)
            }
            sink.close()
        }
        onProgress?.invoke(1, 1)
    }

    /**
     * Benchmark-only in-RAM 4x upscale (whole-output Bitmap). Used solely by
     * [benchmarkAndCache] on a small synthetic 256x256 sample, so the
     * whole-output bitmap is bounded (~4 MB) and OOM-safe. Real upscales go
     * through the band-streaming [upscaleToFile]. Kept private so no caller
     * can accidentally route a large image through the unbounded path.
     */
    private suspend fun upscale(input: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val srcW = input.width
        val srcH = input.height
        require(srcW > 0 && srcH > 0) { "Input bitmap is empty" }

        // Ensure ARGB_8888 for predictable getPixels() semantics.
        val src = if (input.config == Bitmap.Config.ARGB_8888) input
        else input.copy(Bitmap.Config.ARGB_8888, false)

        val outW = srcW * SCALE
        val outH = srcH * SCALE
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val outCanvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        // Reusable buffers — allocated ONCE for the whole upscale.
        val inBuf = ByteBuffer.allocateDirect(1 * TILE_IN * TILE_IN * 3 * 4)
            .order(ByteOrder.nativeOrder())
        val outBuf = ByteBuffer.allocateDirect(1 * TILE_OUT * TILE_OUT * 3 * 4)
            .order(ByteOrder.nativeOrder())
        val tilePixels = IntArray(TILE_IN * TILE_IN)
        val outPixels = IntArray(TILE_OUT * TILE_OUT)
        val tileOut = Bitmap.createBitmap(TILE_OUT, TILE_OUT, Bitmap.Config.ARGB_8888)

        var y = 0
        while (y < srcH) {
            var x = 0
            val tileSrcY = if (y + TILE_IN <= srcH) y else (srcH - TILE_IN).coerceAtLeast(0)
            val tileH = minOf(TILE_IN, srcH - tileSrcY)
            while (x < srcW) {
                val tileSrcX = if (x + TILE_IN <= srcW) x else (srcW - TILE_IN).coerceAtLeast(0)
                val tileW = minOf(TILE_IN, srcW - tileSrcX)

                if (srcW >= TILE_IN && srcH >= TILE_IN) {
                    src.getPixels(tilePixels, 0, TILE_IN, tileSrcX, tileSrcY, TILE_IN, TILE_IN)
                } else {
                    val padded = Bitmap.createScaledBitmap(src, TILE_IN, TILE_IN, true)
                    padded.getPixels(tilePixels, 0, TILE_IN, 0, 0, TILE_IN, TILE_IN)
                    if (padded !== src) padded.recycle()
                }

                inBuf.rewind()
                for (px in tilePixels) {
                    inBuf.putFloat(((px ushr 16) and 0xFF).toFloat())
                    inBuf.putFloat(((px ushr 8) and 0xFF).toFloat())
                    inBuf.putFloat((px and 0xFF).toFloat())
                }
                inBuf.rewind()
                outBuf.rewind()
                engine().run(inBuf, outBuf)

                outBuf.rewind()
                for (i in 0 until TILE_OUT * TILE_OUT) {
                    val r = outBuf.float.toInt().coerceIn(0, 255)
                    val g = outBuf.float.toInt().coerceIn(0, 255)
                    val b = outBuf.float.toInt().coerceIn(0, 255)
                    outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }

                tileOut.setPixels(outPixels, 0, TILE_OUT, 0, 0, TILE_OUT, TILE_OUT)

                if (srcW >= TILE_IN && srcH >= TILE_IN) {
                    val outSrcLeft = (x - tileSrcX) * SCALE
                    val outSrcTop = (y - tileSrcY) * SCALE
                    val outSrcRight = outSrcLeft + tileW * SCALE
                    val outSrcBottom = outSrcTop + tileH * SCALE
                    outCanvas.drawBitmap(
                        tileOut,
                        Rect(outSrcLeft, outSrcTop, outSrcRight, outSrcBottom),
                        Rect(x * SCALE, y * SCALE, (x + tileW) * SCALE, (y + tileH) * SCALE),
                        paint
                    )
                } else {
                    outCanvas.drawBitmap(
                        tileOut,
                        Rect(0, 0, TILE_OUT, TILE_OUT),
                        Rect(0, 0, outW, outH),
                        paint
                    )
                }

                x += TILE_IN
            }
            y += TILE_IN
        }

        tileOut.recycle()
        if (src !== input) src.recycle()
        out
    }  // close withContext(Dispatchers.Default); TileEngine serializes inference internally

    /** Release native resources. Call from Application.onTerminate or test teardown. */
    fun close() {
        synchronized(this) {
            engine?.close()
            engine = null
        }
    }

    private const val BENCHMARK_TILE = 256
    private const val BENCHMARK_RUNS = 3

    /**
     * Runs ESRGAN on a synthetic 256×256 input, discards the warm-up, then
     * times three more passes and writes the median ms-per-megapixel to
     * DataStore (via [writeBenchmark] in UpscaleEta.kt).
     *
     * Schedule this from a background coroutine on first launch and after
     * [STALE_AFTER_MS]. The modal reads the cached value through
     * [cachedMsPerMegapixel] / [msPerMegapixelFlow] to estimate ETAs.
     */
    suspend fun benchmarkAndCache(ctx: Context): Long = withContext(Dispatchers.Default) {
        init(ctx)
        val sample = Bitmap.createBitmap(BENCHMARK_TILE, BENCHMARK_TILE, Bitmap.Config.ARGB_8888)
        try {
            // Warm-up — JIT + XNNPACK kernel cache. Discarded.
            upscale(sample).recycle()
            val timings = LongArray(BENCHMARK_RUNS)
            for (i in 0 until BENCHMARK_RUNS) {
                val start = System.nanoTime()
                upscale(sample).recycle()
                timings[i] = (System.nanoTime() - start) / 1_000_000L
            }
            timings.sort()
            val medianMs = timings[BENCHMARK_RUNS / 2]
            val outputPixels = BENCHMARK_TILE.toLong() * SCALE * BENCHMARK_TILE * SCALE
            val outputMp = (outputPixels / 1_000_000L).coerceAtLeast(1L)
            val msPerMp = (medianMs / outputMp).coerceAtLeast(1L)
            writeBenchmark(ctx, msPerMp)
            msPerMp
        } finally {
            sample.recycle()
        }
    }
}
