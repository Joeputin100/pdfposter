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
