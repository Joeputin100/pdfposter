package com.posterpdf.upscale

import com.posterpdf.ui.components.UpscaleModel
import com.posterpdf.ui.components.UpscaleOption
import kotlin.math.ceil

/**
 * RC65: extracted from LowDpiUpgradeModal.kt so both the modal and the new
 * PosterPdfAgentFunctions.quoteUpscaleCost share a single implementation.
 * Single source of truth so the modal's card price and Gemini's spoken
 * quote can never drift. Verbatim extraction — bodies + signatures
 * unchanged, just lifted to top-level.
 */
internal const val CREDIT_COST_BUDGET_USD = 0.00425

/**
 * Pick the smallest supported scale factor that produces enough pixels for
 * the target DPI on the user's poster size.
 */
internal fun pickScale(
    option: UpscaleOption,
    inputMp: Double,
    posterWInches: Double,
    posterHInches: Double,
    targetDpi: Int,
): Int {
    val targetMp = (posterWInches * targetDpi) * (posterHInches * targetDpi) / 1_000_000.0
    for (s in option.supportedScales) {
        if (inputMp * s * s >= targetMp) return s
    }
    return option.supportedScales.last()
}

internal fun cogsForOption(
    option: UpscaleOption,
    inputMp: Double,
    pickedScale: Int,
): Double {
    if (option.flatUsd > 0.0) return option.flatUsd
    val outputMp = inputMp * pickedScale * pickedScale
    return outputMp * option.perOutputMp
}

/** Free options return 0; paid options return ceil(cogs / budget), min 1. */
internal fun creditsForOption(
    option: UpscaleOption,
    inputMp: Double,
    pickedScale: Int,
): Int {
    if (option.model == UpscaleModel.NONE || option.model == UpscaleModel.FREE_LOCAL) return 0
    val cogs = cogsForOption(option, inputMp, pickedScale)
    return ceil(cogs / CREDIT_COST_BUDGET_USD).toInt().coerceAtLeast(1)
}
