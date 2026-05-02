package com.pdfposter.ml

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * Result of asking "can this device run on-device upscale at this size?"
 *
 * The modal uses [tier] to decide whether to enable the on-device button,
 * [reason] to explain itself when it's not GREEN, and
 * [recommendedMaxOutputMp] as the largest size we'd be willing to attempt
 * on this device — useful when the user later picks a different image.
 */
data class DeviceCapability(
    val tier: CapabilityTier,
    val reason: String?,
    val recommendedMaxOutputMp: Int,
)

enum class CapabilityTier { GREEN, YELLOW, RED }

object Capability {

    // We need three buffers in flight during inference (input, output,
    // intermediate) plus model overhead. Triple the raw output size is a
    // reasonable working-set estimate for the ESRGAN tile loop.
    private const val WORKING_SET_MULTIPLIER = 3L

    // Above 30% of largeMemoryClass we expect OOM under any GC pressure;
    // 15-30% is feasible but means tile-by-tile inference and slow runs.
    private const val RED_THRESHOLD_NUMERATOR = 30L
    private const val YELLOW_THRESHOLD_NUMERATOR = 15L
    private const val THRESHOLD_DENOMINATOR = 100L

    /**
     * Assess whether the current device can locally upscale [inputMp]
     * megapixels at the given linear [scale] (4 or 8). [ctx] is used to
     * read the per-app large-heap budget via [ActivityManager].
     *
     * The math: output area scales with `scale²`; an RGBA8888 bitmap is
     * 4 bytes/pixel; we need ~3× the output buffer in RAM during
     * inference (input + output + working set). Compare that against
     * 30% / 15% of the app's `largeMemoryClass` to bucket the device.
     */
    fun assessLocalUpscale(
        inputMp: Int,
        scale: Int,
        ctx: Context,
    ): DeviceCapability {
        // 32-bit-only devices can't address >2 GB and TFLite's GPU/NNAPI
        // delegates ship 64-bit-only. Refuse before doing any RAM math.
        if (Build.SUPPORTED_64_BIT_ABIS.isEmpty()) {
            return DeviceCapability(
                tier = CapabilityTier.RED,
                reason = "32-bit device — TFLite requires a 64-bit ABI",
                recommendedMaxOutputMp = 0,
            )
        }

        val outputMp = inputMp.toLong() * scale * scale
        val outputBytes = outputMp * 1_000_000L * 4L
        val needBytes = outputBytes * WORKING_SET_MULTIPLIER

        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val largeMemMb = am.largeMemoryClass.toLong()
        val largeMemBytes = largeMemMb * 1024L * 1024L

        val redLimit = largeMemBytes * RED_THRESHOLD_NUMERATOR / THRESHOLD_DENOMINATOR
        val yellowLimit = largeMemBytes * YELLOW_THRESHOLD_NUMERATOR / THRESHOLD_DENOMINATOR

        // recommendedMaxOutputMp is what we'd accept on this device:
        // 30% of large heap / 12 bytes-per-MP-of-output (RGBA × working set).
        val recommendedMax = (redLimit / (12L * 1_000_000L)).toInt().coerceAtLeast(0)

        return when {
            needBytes > redLimit -> DeviceCapability(
                tier = CapabilityTier.RED,
                reason = "Not enough RAM for this size on-device " +
                    "(${largeMemMb}MB heap; need ~${needBytes / 1024 / 1024}MB)",
                recommendedMaxOutputMp = recommendedMax,
            )
            needBytes > yellowLimit -> DeviceCapability(
                tier = CapabilityTier.YELLOW,
                reason = "Tight on memory — will use tile mode (slower)",
                recommendedMaxOutputMp = outputMp.toInt(),
            )
            else -> DeviceCapability(
                tier = CapabilityTier.GREEN,
                reason = null,
                recommendedMaxOutputMp = outputMp.toInt(),
            )
        }
    }
}
