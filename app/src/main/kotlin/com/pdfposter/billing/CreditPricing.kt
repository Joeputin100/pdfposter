package com.pdfposter.billing

import android.os.SystemClock
import com.pdfposter.data.backend.BackendApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One row of the dynamic credit ladder served by the backend's `/pricing` endpoint.
 *
 * The backend may rebalance credits-per-dollar over time (promotions, A/B tests),
 * so the client never hard-codes the mapping — it caches the latest payload.
 */
data class SkuOffering(
    /** Google Play product id, e.g. "credits_small". Stable across pricing updates. */
    val productId: String,
    /** Localized display string, e.g. "$1.99". Provided by the backend or ProductDetails. */
    val priceDisplay: String,
    /** Number of credits granted on a successful purchase of this SKU. */
    val credits: Int,
)

/**
 * In-memory + 1-hour cached fetcher for the credit pricing ladder.
 *
 * Falls back to a hardcoded default ladder on network failure so the
 * buy-credits UI never renders an empty list. The defaults match the values
 * quoted in `docs/pricing-policy.md` as of 2026-05-01.
 *
 * ASSUMES (to be implemented in G12 by another agent):
 *   suspend fun BackendApi.getPricing(): PricingResponseDto
 *
 * The DTO is expected to expose a list of (productId, priceDisplay, credits)
 * rows; this class adapts that shape into [SkuOffering]s. Until G12 wires it
 * up, [refreshIfStale] keeps the offerings on [DEFAULT_LADDER] silently — the
 * UI keeps working.
 */
class CreditPricing(
    @Suppress("unused") // wired up in G12
    private val backendApi: BackendApi,
) {

    private val _offerings = MutableStateFlow(DEFAULT_LADDER)

    /** Hot stream of the current offerings. Always non-empty. */
    val offerings: StateFlow<List<SkuOffering>> = _offerings.asStateFlow()

    private val refreshMutex = Mutex()
    @Volatile
    private var lastRefreshElapsedMs: Long = 0L

    /**
     * Refresh from the backend if the cache is older than [CACHE_TTL_MS].
     *
     * Network / parse errors are swallowed — callers keep observing the
     * previous (or default) ladder via [offerings]. The cache timestamp is
     * only advanced on success, so a failed refresh retries on the next call.
     */
    suspend fun refreshIfStale(nowMs: Long = SystemClock.elapsedRealtime()) {
        if (lastRefreshElapsedMs != 0L && nowMs - lastRefreshElapsedMs < CACHE_TTL_MS) return
        refreshMutex.withLock {
            // Re-check inside the lock to avoid duplicate fetches under concurrent callers.
            if (lastRefreshElapsedMs != 0L && nowMs - lastRefreshElapsedMs < CACHE_TTL_MS) return
            try {
                // G12: replace with real `backendApi.getPricing()` call + DTO mapping:
                //   val dto = backendApi.getPricing()
                //   _offerings.value = dto.offerings.map {
                //       SkuOffering(it.productId, it.priceDisplay, it.credits)
                //   }
                lastRefreshElapsedMs = nowMs
            } catch (_: Throwable) {
                // Stay on the previous ladder. Don't advance the timestamp so the
                // next call retries.
            }
        }
    }

    /** Convenience accessor — returns null if [productId] is not in the current ladder. */
    fun creditsFor(productId: String): Int? =
        _offerings.value.firstOrNull { it.productId == productId }?.credits

    companion object {
        /** 1 hour, per the spec. */
        const val CACHE_TTL_MS: Long = 60 * 60 * 1000L

        /**
         * Hardcoded fallback ladder, mirrored from `docs/pricing-policy.md` as of
         * 2026-05-01. The server is authoritative on actual credit grants via
         * `/redeemPurchase` — these values are only used to render an SKU list
         * before the first successful pricing fetch.
         */
        val DEFAULT_LADDER: List<SkuOffering> = listOf(
            SkuOffering(productId = "credits_small",  priceDisplay = "$4.99",  credits = 40),
            SkuOffering(productId = "credits_medium", priceDisplay = "$9.99",  credits = 85),
            SkuOffering(productId = "credits_large",  priceDisplay = "$19.99", credits = 180),
            SkuOffering(productId = "credits_jumbo",  priceDisplay = "$39.99", credits = 380),
        )
    }
}
