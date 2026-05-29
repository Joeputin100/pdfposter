package com.posterpdf.ml

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

/** Upscales one 50x50 RGB tile → 200x200 via LiteRT. Thread-safe (serialized). */
internal class TileEngine(private val model: ByteBuffer) {
    private val mutex = Mutex()            // rc75: TFLite Interpreter is not thread-safe
    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var gpuDelegate: GpuDelegate? = null
    @Volatile var path: DelegatePath = DelegatePath.CPU; private set

    companion object {
        /** RC76 test-only: when set, [ensure] reports the device as
         *  GPU-unsupported so [selectDelegatePath] resolves to [DelegatePath.CPU],
         *  exercising the CPU fallback on devices that would otherwise pick GPU.
         *  Set by the FTL device-test driver for `--es upscale_mode cpu`. ONLY
         *  honored when [com.posterpdf.BuildConfig.ENABLE_TEST_HOOKS] is true, so
         *  it can never force CPU in a shipping release build. */
        @Volatile var forceCpuForTest = false
    }

    private fun ensure(): Interpreter {
        interpreter?.let { return it }
        val compat = CompatibilityList()
        // Build the REAL GPU interpreter up front and validate on IT. A LiteRT
        // GpuDelegate binds to a single interpreter, so we must NOT reuse one
        // delegate across a throwaway validation interpreter and the kept one —
        // that would leave the kept interpreter holding a stale delegate (and
        // could throw when building it). Constructing inside selectDelegatePath's
        // try means any GPU create/validate failure falls back to CPU cleanly.
        var gpu: GpuDelegate? = null
        var gpuInterp: Interpreter? = null
        path = selectDelegatePath(
            // RC76: the forced-CPU test hook makes "GPU supported" report false so
            // the whole decision collapses to CPU (no GpuDelegate is even built).
            // Gated by ENABLE_TEST_HOOKS so release builds ignore the flag.
            gpuSupportedOnDevice = {
                if (com.posterpdf.BuildConfig.ENABLE_TEST_HOOKS && forceCpuForTest) false
                else compat.isDelegateSupportedOnThisDevice
            },
            tryCreateGpu = {
                val g = GpuDelegate(compat.bestOptionsForThisDevice)
                gpu = g
                gpuInterp = Interpreter(model, Interpreter.Options().addDelegate(g))
            },
            validate = { runValidation(gpuInterp) },
        )
        android.util.Log.i("UPSCALE_TEST", "tile engine path=$path")
        if (path == DelegatePath.GPU && gpuInterp != null) {
            gpuDelegate = gpu
            return gpuInterp!!.also { interpreter = it }
        }
        // CPU fallback: discard any GPU interpreter/delegate; run XNNPACK CPU
        // (default-on for FP32 via setNumThreads).
        gpuInterp?.close()
        gpu?.close()
        return Interpreter(model, Interpreter.Options().apply { setNumThreads(4) })
            .also { interpreter = it }
    }

    // Run one mid-grey tile on the REAL (GPU-delegated) interpreter; pass if the
    // output is finite and roughly in [0,255]. No throwaway interpreter — the
    // delegate stays bound to the single interpreter we keep.
    private fun runValidation(interp: Interpreter?): Boolean = try {
        if (interp == null) false else {
            val inBuf = ByteBuffer.allocateDirect(50 * 50 * 3 * 4).order(ByteOrder.nativeOrder())
            repeat(50 * 50 * 3) { inBuf.putFloat(128f) }
            inBuf.rewind()
            val outBuf = ByteBuffer.allocateDirect(200 * 200 * 3 * 4).order(ByteOrder.nativeOrder())
            interp.run(inBuf, outBuf)
            outBuf.rewind()
            val v = outBuf.float
            v.isFinite() && v > -50f && v < 305f
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
