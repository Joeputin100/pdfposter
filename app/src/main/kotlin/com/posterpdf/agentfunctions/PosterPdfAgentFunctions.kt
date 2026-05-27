package com.posterpdf.agentfunctions

import android.content.Context
import com.posterpdf.ui.components.UpscaleModel
import com.posterpdf.ui.components.UpscaleOption
import com.posterpdf.upscale.creditsForOption
import com.posterpdf.upscale.pickScale

/**
 * Spec B Phase B0: shared service class holding the business logic for the
 * agent functions Gemini (system or in-app) can invoke. This file is the
 * single source of truth; the in-app Q&A consumes its outputs as tool
 * call results (the askGemini Cloud Function gets the FUNCTION SIGNATURES
 * via the buildToolDefinitions() helper in askGemini.ts; the bodies live
 * here on-device).
 *
 * A future @AppFunction-wrapped Phase B1 (gated on the AppFunctions EAP)
 * will wrap the same method bodies — splitting the body from the
 * annotations lets us unit-test the logic without an instrumentation
 * runner.
 *
 * This Phase B0 ships only [quoteUpscaleCost] and the headless-routing
 * helper. The remaining six agent functions (generatePrintReadyPoster,
 * upscaleImage, generatePosterPdf, viewLastPoster, redoPosterWithSettings,
 * sharePoster) land in a follow-up plan when there's a real consumer
 * for them (system Gemini post-EAP, or the in-app Q&A's "do it now"
 * follow-through).
 */
internal class PosterPdfAgentFunctions(
    private val appContext: Context,
    private val allOptions: List<UpscaleOption>,
) {
    /**
     * Read-only quote. Returns the credit cost + USD cost + current balance
     * + can-afford flag + plain-English explanation for a (model, source-
     * image, target-poster) combo. Gemini calls this before a paid headless
     * invocation so the user can confirm.
     */
    fun quoteUpscaleCost(
        upscaleModel: UpscaleModel,
        targetWidthInches: Double,
        targetHeightInches: Double,
        inputMp: Double,
        currentCreditBalance: Int,
        targetDpi: Int = 150,
    ): UpscaleCostQuote {
        val option = allOptions.first { it.model == upscaleModel }
        val scale = pickScale(option, inputMp, targetWidthInches, targetHeightInches, targetDpi)
        val credits = creditsForOption(option, inputMp, scale)
        val usdCost = credits / 100.0  // 1 credit = 1¢
        val outputMp = inputMp * scale * scale
        return UpscaleCostQuote(
            estimatedCredits = credits,
            estimatedUsdCost = usdCost,
            currentCreditBalance = currentCreditBalance,
            canAfford = currentCreditBalance >= credits,
            modelDisplayName = appContext.getString(option.displayNameRes),
            scaleFactor = scale,
            outputMegapixels = outputMp,
            explanation = "This will use about $credits credits ($${"%.2f".format(usdCost)}). " +
                "You have $currentCreditBalance credits.",
        )
    }
}

/** Result returned by [PosterPdfAgentFunctions.quoteUpscaleCost]. */
data class UpscaleCostQuote(
    val estimatedCredits: Int,
    val estimatedUsdCost: Double,
    val currentCreditBalance: Int,
    val canAfford: Boolean,
    val modelDisplayName: String,
    val scaleFactor: Int,
    val outputMegapixels: Double,
    val explanation: String,
)

/** Outcome of the headless-eligibility check. Used by paid-capable agent
 *  functions (deferred to a follow-up plan) to decide whether to run
 *  inline, prompt for confirmation, or deep-link into the UI. */
sealed class HeadlessRoutingResult {
    /** Headless execution allowed — run the function inline. */
    object Allowed : HeadlessRoutingResult()
    /** Headless requested for a paid model without cost confirmation —
     *  Gemini must call quoteUpscaleCost first, present the cost to the
     *  user, then re-invoke with confirmCreditCost = true. */
    object RequiresConfirmation : HeadlessRoutingResult()
    /** Not headless — deep-link into the app's UI. */
    object DeepLink : HeadlessRoutingResult()
}

/**
 * Decide whether a paid-capable agent function should run inline, prompt
 * the user for cost confirmation, or deep-link into the UI.
 *
 *   - !headless                              → DeepLink
 *   - headless + free-tier (NONE/FREE_LOCAL) → Allowed
 *   - headless + paid + confirmCreditCost    → Allowed
 *   - headless + paid + !confirmCreditCost   → RequiresConfirmation
 */
fun isHeadlessAllowed(
    model: UpscaleModel,
    headless: Boolean,
    confirmCreditCost: Boolean,
): HeadlessRoutingResult {
    if (!headless) return HeadlessRoutingResult.DeepLink
    val isFreeTier = model == UpscaleModel.NONE || model == UpscaleModel.FREE_LOCAL
    if (isFreeTier) return HeadlessRoutingResult.Allowed
    if (confirmCreditCost) return HeadlessRoutingResult.Allowed
    return HeadlessRoutingResult.RequiresConfirmation
}
