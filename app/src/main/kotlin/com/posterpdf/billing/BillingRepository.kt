package com.posterpdf.billing

// ASSUMES (to be implemented in G12 by another agent):
//   suspend fun BackendApi.getPricing(): PricingResponseDto
//   suspend fun BackendApi.grantTestCredits(credits: Int): Int           // returns new balance
//   suspend fun BackendApi.redeemPurchase(purchaseToken: String, productId: String): Int  // returns new balance
//
// Until those exist, the calls below are stubbed out (commented) so this
// file compiles standalone. Nothing in the rest of the app depends on this
// class yet — wiring into MainViewModel / MainActivity is G12's job.

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.posterpdf.BuildConfig
import com.posterpdf.data.backend.BackendApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wraps Google Play Billing Library v7 with a coroutine-friendly surface.
 *
 * Lifecycle / correctness notes (Play Billing v7 is finicky):
 *
 *  1. **Acknowledgement deadline.** Google refunds any purchase that hasn't
 *     been acknowledged within 3 days. We acknowledge consumables implicitly
 *     via [BillingClient.consumeAsync] (consume = ack + invalidate). Non-
 *     consumables (none today, but supportPurchaseActive in the future) go
 *     through [BillingClient.acknowledgePurchase]. Both paths run inside the
 *     [purchaseMutex] block so the redeem -> ack chain can't race.
 *
 *  2. **Restore on app start.** [queryAndRestorePurchases] re-redeems any
 *     unconsumed purchase token still sitting on the device — covers the
 *     case where the backend redeem call succeeded but the consume call was
 *     interrupted (network drop, process death). The backend's
 *     `/redeemPurchase` is idempotent on `(purchaseToken)`, so re-submitting
 *     a token that already granted credits returns the existing balance
 *     without double-granting.
 *
 *  3. **Compose recomposition vs. callback.** [PurchasesUpdatedListener] is
 *     called on the main thread by the library. We hand the purchase to a
 *     coroutine on [scope] so we never block the UI and never run network
 *     code on the main thread. Compose observes [creditBalance] which is
 *     a [StateFlow] (stable, recomposition-safe).
 *
 *  4. **Rapid double-tap.** [purchaseMutex] serializes the
 *     redeem-then-consume pipeline so two simultaneous purchase callbacks
 *     can't both increment credits before the consume settles.
 *
 *  5. **Connection death.** Play Store updates / disables silently kill the
 *     billing service. [ensureConnected] re-establishes via exponential
 *     backoff before every operation that needs a live client.
 *
 * **TEST_MODE** ([Companion.TEST_MODE]) is on for `BuildConfig.DEBUG` builds
 * only. It bypasses the real BillingClient entirely and calls
 * `backendApi.grantTestCredits(...)` so dev builds can exercise the credit
 * flow without a Play Console publish + the Google Play Developer API
 * verification chain (G2.5).
 */
class BillingRepository(
    private val context: Context,
    private val backendApi: BackendApi,
    private val pricing: CreditPricing = CreditPricing(backendApi),
) {

    /** App-scoped supervisor scope. Cancelled by [release]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _creditBalance = MutableStateFlow(CreditBalance.flow.value)

    /** Hot flow of the user's credit balance. Mirrors [CreditBalance.flow]. */
    val creditBalance: StateFlow<Int> = _creditBalance.asStateFlow()

    /** Hot flow of available SKUs with credit grants from the backend `/pricing` ladder. */
    val availableSkus: StateFlow<List<SkuOffering>> = pricing.offerings

    /** Cache of ProductDetails keyed by productId; needed to build a [BillingFlowParams]. */
    private val productDetailsCache = mutableMapOf<String, ProductDetails>()

    /** Serializes purchase-event handling so rapid taps can't double-grant. */
    private val purchaseMutex = Mutex()

    /** Guards [billingClient] init / reuse. */
    private val connectionMutex = Mutex()
    @Volatile
    private var billingClient: BillingClient? = null

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val list = purchases.orEmpty()
                if (list.isEmpty()) return@PurchasesUpdatedListener
                scope.launch { handlePurchases(list) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.i(TAG, "Purchase cancelled by user")
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                // Edge case: purchase succeeded earlier but we didn't consume. Restore now.
                scope.launch { queryAndRestorePurchases() }
            }
            else -> {
                Log.w(TAG, "Purchase update failed: code=${result.responseCode} msg=${result.debugMessage}")
            }
        }
    }

    /**
     * Establish connection to Play Billing. Idempotent; safe to call from
     * MainActivity.onCreate or from the first ViewModel init. Skipped in
     * TEST_MODE — the BillingClient is never instantiated.
     */
    suspend fun initialize() {
        if (TEST_MODE) {
            Log.i(TAG, "TEST_MODE on: skipping Play Billing connection")
            return
        }
        ensureConnected()
        // Warm the ProductDetails cache so launchPurchase() is fast.
        runCatching { queryProductDetails(availableSkus.value.map { it.productId }) }
            .onFailure { Log.w(TAG, "Initial queryProductDetails failed: ${it.message}") }
        // Refresh pricing in the background — failures stay silent.
        scope.launch { runCatching { pricing.refreshIfStale() } }
    }

    /**
     * Restore any unconsumed purchases. Called on app start to recover from
     * a previous crash / kill between BillingClient success and our backend
     * redeem call.
     */
    suspend fun queryAndRestorePurchases() {
        if (TEST_MODE) return
        val client = ensureConnected()
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val result = client.queryPurchasesAsync(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "queryPurchasesAsync failed: ${result.billingResult.debugMessage}")
            return
        }
        val purchased = result.purchasesList.filter {
            it.purchaseState == Purchase.PurchaseState.PURCHASED
        }
        if (purchased.isNotEmpty()) handlePurchases(purchased)
    }

    /**
     * Launch the purchase flow for a given productId.
     *
     * In TEST_MODE this skips Play Billing entirely and calls the backend's
     * `grantTestCredits` faucet (G2.5).
     */
    suspend fun launchPurchase(activity: Activity, productId: String) {
        if (TEST_MODE) {
            launchTestPurchase(productId)
            return
        }
        val client = ensureConnected()
        val productDetails = productDetailsCache[productId]
            ?: queryProductDetails(listOf(productId))[productId]
            ?: error("Unknown productId: $productId")

        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .build()

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()

        val launchResult = client.launchBillingFlow(activity, flowParams)
        if (launchResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "launchBillingFlow failed: ${launchResult.debugMessage}")
        }
        // Result arrives via [purchasesUpdatedListener] → [handlePurchases].
    }

    /**
     * Disconnect the BillingClient. Call from Activity.onDestroy when the
     * app is finishing. Cancels any in-flight redeem/consume coroutines.
     */
    fun release() {
        try {
            billingClient?.endConnection()
        } catch (t: Throwable) {
            Log.w(TAG, "endConnection threw: ${t.message}")
        }
        billingClient = null
        scope.cancel()
    }

    // -- Internal --------------------------------------------------------

    /** TEST_MODE shortcut: ask the backend faucet for the SKU's credit value. */
    private suspend fun launchTestPurchase(productId: String) {
        val credits = pricing.creditsFor(productId)
            ?: availableSkus.value.firstOrNull { it.productId == productId }?.credits
            ?: error("Unknown productId in TEST_MODE: $productId")
        purchaseMutex.withLock {
            val newBalance = try {
                // G12: backendApi.grantTestCredits(credits)
                CreditBalance.flow.value + credits
            } catch (t: Throwable) {
                Log.w(TAG, "grantTestCredits failed: ${t.message}")
                return
            }
            CreditBalance.set(newBalance)
            _creditBalance.value = newBalance
        }
    }

    /**
     * Process a batch of [Purchase]s: redeem each via the backend, then
     * consume (consumables) or acknowledge (non-consumables). Idempotent
     * thanks to backend dedupe on purchaseToken.
     */
    private suspend fun handlePurchases(purchases: List<Purchase>) {
        purchaseMutex.withLock {
            val client = ensureConnected()
            for (purchase in purchases) {
                if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) continue
                val productId = purchase.products.firstOrNull() ?: continue

                val newBalance = redeemWithBackoff(purchase.purchaseToken, productId)
                if (newBalance == null) {
                    Log.w(TAG, "Redeem failed after retries; will retry on next restore")
                    continue
                }
                CreditBalance.set(newBalance)
                _creditBalance.value = newBalance

                if (isConsumable(productId)) {
                    consume(client, purchase.purchaseToken)
                } else if (!purchase.isAcknowledged) {
                    acknowledge(client, purchase.purchaseToken)
                }
            }
        }
    }

    /** Credit SKUs are consumable; entitlements (e.g. supportPurchase) would not be. */
    private fun isConsumable(productId: String): Boolean =
        productId.startsWith("credits_")

    /**
     * Backend redeem with exponential backoff: 250ms, 1s, 4s. Returns the
     * authoritative new balance, or null if all attempts failed.
     */
    private suspend fun redeemWithBackoff(token: String, productId: String): Int? {
        val delaysMs = longArrayOf(250L, 1000L, 4000L)
        for ((attempt, wait) in delaysMs.withIndex()) {
            try {
                // G12: return backendApi.redeemPurchase(token, productId)
                @Suppress("UNUSED_VARIABLE") val unused = backendApi
                @Suppress("UNUSED_VARIABLE") val t = token
                @Suppress("UNUSED_VARIABLE") val p = productId
                // Stub: pretend the server granted the SKU's credit count.
                val grant = pricing.creditsFor(productId) ?: 0
                return CreditBalance.flow.value + grant
            } catch (t: Throwable) {
                Log.w(TAG, "redeemPurchase attempt ${attempt + 1} failed: ${t.message}")
                if (attempt < delaysMs.lastIndex) delay(wait)
            }
        }
        return null
    }

    private suspend fun consume(client: BillingClient, token: String) {
        val params = ConsumeParams.newBuilder().setPurchaseToken(token).build()
        val result = client.consumePurchase(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "consumePurchase failed: ${result.billingResult.debugMessage}")
        }
    }

    private suspend fun acknowledge(client: BillingClient, token: String) {
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()
        val result = client.acknowledgePurchase(params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "acknowledgePurchase failed: ${result.debugMessage}")
        }
    }

    /**
     * Query [ProductDetails] for [productIds] and update [productDetailsCache].
     * Returns the freshly-resolved details by productId.
     */
    private suspend fun queryProductDetails(productIds: List<String>): Map<String, ProductDetails> {
        if (productIds.isEmpty()) return emptyMap()
        val client = ensureConnected()
        val products = productIds.map {
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(it)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
        val result = client.queryProductDetails(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            Log.w(TAG, "queryProductDetails failed: ${result.billingResult.debugMessage}")
            return emptyMap()
        }
        val byId = (result.productDetailsList ?: emptyList()).associateBy { it.productId }
        productDetailsCache.putAll(byId)
        return byId
    }

    /**
     * Lazy-init the BillingClient and start its connection. Re-establishes
     * if the previous client died (e.g. Play Store updated). Protected by
     * [connectionMutex] so concurrent callers share one client.
     */
    private suspend fun ensureConnected(): BillingClient {
        connectionMutex.withLock {
            val existing = billingClient
            if (existing != null && existing.isReady) return existing

            val client = existing ?: BillingClient.newBuilder(context)
                .setListener(purchasesUpdatedListener)
                .enablePendingPurchases(
                    PendingPurchasesParams.newBuilder()
                        .enableOneTimeProducts()
                        .build()
                )
                .build()
                .also { billingClient = it }

            // Connect with exponential backoff: up to 3 attempts.
            var lastErr: BillingResult? = null
            val delaysMs = longArrayOf(0L, 500L, 2000L)
            for (wait in delaysMs) {
                if (wait > 0) delay(wait)
                val result = startConnectionSuspend(client)
                if (result.responseCode == BillingClient.BillingResponseCode.OK) return client
                lastErr = result
            }
            error("BillingClient connect failed: ${lastErr?.responseCode} ${lastErr?.debugMessage}")
        }
    }

    private suspend fun startConnectionSuspend(client: BillingClient): BillingResult {
        if (client.isReady) {
            return BillingResult.newBuilder()
                .setResponseCode(BillingClient.BillingResponseCode.OK)
                .build()
        }
        val deferred = CompletableDeferred<BillingResult>()
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (!deferred.isCompleted) deferred.complete(billingResult)
            }
            override fun onBillingServiceDisconnected() {
                // Don't complete here — startConnection() will surface the failure
                // through onBillingSetupFinished on the retry path. We just log.
                Log.w(TAG, "BillingClient service disconnected")
            }
        })
        return deferred.await()
    }

    companion object {
        private const val TAG = "BillingRepository"

        /**
         * TEST_MODE skips the real BillingClient and routes purchases through
         * the backend's `grantTestCredits` faucet. On for debug builds; off
         * for release. (Future: combine with an `ALLOW_TEST_BILLING` BuildConfig
         * field to allow turning it off in debug too.)
         */
        val TEST_MODE: Boolean = BuildConfig.DEBUG
    }
}
