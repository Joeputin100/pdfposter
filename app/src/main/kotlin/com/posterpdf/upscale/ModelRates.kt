package com.posterpdf.upscale

import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import com.google.firebase.functions.FirebaseFunctions
import com.posterpdf.ui.components.UpscaleModel
import kotlinx.coroutines.tasks.await

/**
 * rc83: live per-model rates from the `getModelPricing` callable (which
 * proxies fal.ai's own billing metadata — see backend falPricing.ts).
 *
 * The card prices, the RC61 dynamic sort, and Gemini's quoteUpscaleCost all
 * flow through PricingMath.cogsForOption, which consults [rates] first and
 * falls back to the baked-in UpscaleOption constants when we're offline or
 * the fetch hasn't landed yet. Display prices may therefore be up to
 * ~5 minutes stale — that's fine: the BACKEND re-quotes from live pricing
 * at charge time (60s cache), so the debited amount is always current.
 *
 * [version] is a Compose state read by LowDpiUpgradeModal so a rates update
 * re-sorts and re-prices the visible cards immediately.
 */
object ModelRates {
    private const val TAG = "ModelRates"
    private const val STALE_MS = 5 * 60_000L

    data class LiveRate(
        val available: Boolean,
        val unit: String?,        // "megapixels" | "compute seconds" | "images"
        val unitPrice: Double?,
        val secBase: Double?,
        val secPerMp: Double?,
    )

    /** Bumped on every successful refresh; observed by Compose. */
    val version = mutableIntStateOf(0)

    @Volatile
    var rates: Map<UpscaleModel, LiveRate> = emptyMap()
        private set

    @Volatile
    private var fetchedAtMs: Long = 0L

    fun isAvailable(model: UpscaleModel): Boolean =
        rates[model]?.available ?: true  // unknown → show (backend re-checks)

    suspend fun refreshIfStale(maxAgeMs: Long = STALE_MS) {
        if (System.currentTimeMillis() - fetchedAtMs < maxAgeMs) return
        try {
            val result = FirebaseFunctions.getInstance("us-central1")
                .getHttpsCallable("getModelPricing")
                .call()
                .await()
            @Suppress("UNCHECKED_CAST")
            val data = result.data as? Map<String, Any?> ?: return
            @Suppress("UNCHECKED_CAST")
            val models = data["models"] as? Map<String, Map<String, Any?>> ?: return
            val parsed = buildMap {
                for ((key, m) in models) {
                    val enumModel = UpscaleModel.entries.firstOrNull {
                        it.name.equals(key, ignoreCase = true)
                    } ?: continue
                    put(
                        enumModel,
                        LiveRate(
                            available = (m["available"] as? Boolean) ?: true,
                            unit = m["unit"] as? String,
                            unitPrice = (m["unitPrice"] as? Number)?.toDouble(),
                            secBase = (m["secBase"] as? Number)?.toDouble(),
                            secPerMp = (m["secPerMp"] as? Number)?.toDouble(),
                        ),
                    )
                }
            }
            if (parsed.isNotEmpty()) {
                rates = parsed
                fetchedAtMs = System.currentTimeMillis()
                version.intValue++
                Log.i(TAG, "live rates refreshed: ${parsed.size} models")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "getModelPricing failed (baked-in fallback stays active): ${t.message}")
        }
    }
}
