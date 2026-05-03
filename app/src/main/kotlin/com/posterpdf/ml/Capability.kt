package com.posterpdf.ml

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

    // TFLite already operates tile-by-tile (50×50 → 200×200), so the working
    // set is bounded by tile size — not output image size. We never RED-block
    // on RAM; the worst case is just a slow run (YELLOW). Hard RED is reserved
    // for 32-bit-only ABIs that can't load the TFLite delegate at all.
    private const val WORKING_SET_MULTIPLIER = 3L
    private const val YELLOW_THRESHOLD_NUMERATOR = 15L
    private const val THRESHOLD_DENOMINATOR = 100L

    /**
     * Assess on-device upscale capability for [inputMp] megapixels at linear
     * [scale]. We never refuse a job on RAM grounds — the tile loop will
     * complete eventually on any 64-bit device. Returns YELLOW (slow) or
     * GREEN (fast); RED only for the 32-bit-ABI case.
     */
    fun assessLocalUpscale(
        inputMp: Int,
        scale: Int,
        ctx: Context,
    ): DeviceCapability {
        if (Build.SUPPORTED_64_BIT_ABIS.isEmpty()) {
            return DeviceCapability(
                tier = CapabilityTier.RED,
                reason = "Your device can't run on-device AI",
                recommendedMaxOutputMp = 0,
            )
        }

        val outputMp = inputMp.toLong() * scale * scale
        val outputBytes = outputMp * 1_000_000L * 4L
        val needBytes = outputBytes * WORKING_SET_MULTIPLIER

        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val largeMemBytes = am.largeMemoryClass.toLong() * 1024L * 1024L
        val yellowLimit = largeMemBytes * YELLOW_THRESHOLD_NUMERATOR / THRESHOLD_DENOMINATOR

        return when {
            needBytes > yellowLimit -> DeviceCapability(
                tier = CapabilityTier.YELLOW,
                reason = "Will take a few minutes on your device",
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
