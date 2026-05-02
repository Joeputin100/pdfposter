package com.posterpdf.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
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

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var nnApiDelegate: NnApiDelegate? = null
    @Volatile private var appContext: Context? = null

    /** Call once (e.g. from Application or first use site) to bind a Context. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun ensureInterpreter(): Interpreter {
        interpreter?.let { return it }
        synchronized(this) {
            interpreter?.let { return it }
            val ctx = appContext
                ?: error("UpscalerOnDevice.init(context) must be called before upscale()")
            val model = loadModelFile(ctx)
            val opts = Interpreter.Options().apply {
                // NNAPI on supported devices; falls back to CPU automatically if unavailable.
                try {
                    val delegate = NnApiDelegate()
                    nnApiDelegate = delegate
                    addDelegate(delegate)
                } catch (_: Throwable) {
                    // NNAPI unavailable; CPU path is fine.
                }
                setNumThreads(4)
            }
            return Interpreter(model, opts).also { interpreter = it }
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
     * Upscale [input] by 4x. Any input size is accepted; the bitmap is tiled
     * into 50x50 chunks fed to the model, and the 200x200 outputs are
     * composited into the result bitmap. Edge tiles whose source rect would
     * exceed bitmap bounds are anchored against the right/bottom edge so the
     * model always sees a full 50x50 input.
     */
    suspend fun upscale(input: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val interp = ensureInterpreter()
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

        // Reusable buffers.
        val inBuf = ByteBuffer.allocateDirect(1 * TILE_IN * TILE_IN * 3 * 4)
            .order(ByteOrder.nativeOrder())
        val outBuf = ByteBuffer.allocateDirect(1 * TILE_OUT * TILE_OUT * 3 * 4)
            .order(ByteOrder.nativeOrder())
        val tilePixels = IntArray(TILE_IN * TILE_IN)
        val outPixels = IntArray(TILE_OUT * TILE_OUT)

        var y = 0
        while (y < srcH) {
            var x = 0
            // Anchor edge tiles so we always hand the model a full 50x50 region.
            val tileSrcY = if (y + TILE_IN <= srcH) y else (srcH - TILE_IN).coerceAtLeast(0)
            val tileH = minOf(TILE_IN, srcH - tileSrcY)
            while (x < srcW) {
                val tileSrcX = if (x + TILE_IN <= srcW) x else (srcW - TILE_IN).coerceAtLeast(0)
                val tileW = minOf(TILE_IN, srcW - tileSrcX)

                // If the source is smaller than 50x50, pad by clamping reads to
                // a temporary scaled-up tile via Bitmap.createBitmap with copy.
                val tileBmp = if (srcW >= TILE_IN && srcH >= TILE_IN) {
                    Bitmap.createBitmap(src, tileSrcX, tileSrcY, TILE_IN, TILE_IN)
                } else {
                    // Pad small bitmaps to 50x50 by stretching (rare path —
                    // the caller guarantees >=50x50 in production use).
                    Bitmap.createScaledBitmap(src, TILE_IN, TILE_IN, true)
                }

                // Pack RGB float32 [0..255] into the model input buffer.
                tileBmp.getPixels(tilePixels, 0, TILE_IN, 0, 0, TILE_IN, TILE_IN)
                inBuf.rewind()
                for (px in tilePixels) {
                    inBuf.putFloat(((px ushr 16) and 0xFF).toFloat())
                    inBuf.putFloat(((px ushr 8) and 0xFF).toFloat())
                    inBuf.putFloat((px and 0xFF).toFloat())
                }
                inBuf.rewind()
                outBuf.rewind()
                interp.run(inBuf, outBuf)

                // Unpack model output to ARGB ints, clamping to [0, 255].
                outBuf.rewind()
                for (i in 0 until TILE_OUT * TILE_OUT) {
                    val r = outBuf.float.toInt().coerceIn(0, 255)
                    val g = outBuf.float.toInt().coerceIn(0, 255)
                    val b = outBuf.float.toInt().coerceIn(0, 255)
                    outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }

                val tileOut = Bitmap.createBitmap(TILE_OUT, TILE_OUT, Bitmap.Config.ARGB_8888)
                tileOut.setPixels(outPixels, 0, TILE_OUT, 0, 0, TILE_OUT, TILE_OUT)

                if (srcW >= TILE_IN && srcH >= TILE_IN) {
                    // Slice the meaningful (non-padded) region of the output
                    // and blit it into the destination at the right place.
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
                    // Small-input path: source was stretched to 50x50 to feed
                    // the model; rescale the full 200x200 output back down to
                    // (srcW*4, srcH*4) so the caller still gets a 4x bitmap.
                    outCanvas.drawBitmap(
                        tileOut,
                        Rect(0, 0, TILE_OUT, TILE_OUT),
                        Rect(0, 0, outW, outH),
                        paint
                    )
                }
                tileOut.recycle()
                if (tileBmp !== src) tileBmp.recycle()

                x += TILE_IN
            }
            y += TILE_IN
        }

        if (src !== input) src.recycle()
        out
    }

    /** Release native resources. Call from Application.onTerminate or test teardown. */
    fun close() {
        synchronized(this) {
            interpreter?.close()
            interpreter = null
            nnApiDelegate?.close()
            nnApiDelegate = null
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
            // Warm-up — JIT, kernel cache, NNAPI provisioning. Discarded.
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
