package com.pdfposter.data.backend

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Auth-aware façade over BackendApi.
 *
 * - Pulls a fresh ID token from AuthRepository per call.
 * - Wraps every endpoint so transient failures (no network, backend not
 *   deployed yet, anon auth disabled) don't crash the app — they just
 *   degrade gracefully.
 */
class BackendClient(
    private val api: BackendApi,
    private val auth: AuthRepository,
) {

    suspend fun addHistory(
        type: String,
        sourceHash: String,
        localUri: String,
        remoteUri: String = "",
        metadata: Map<String, Any?> = emptyMap(),
    ): Boolean {
        return try {
            auth.ensureSignedIn()
            val token = auth.getIdToken() ?: return false
            api.addHistory(token, type, sourceHash, localUri, remoteUri, metadata)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "addHistory failed: ${t.message}")
            false
        }
    }

    suspend fun listHistory(limit: Int = 50): List<HistoryItem> {
        return try {
            auth.ensureSignedIn()
            val token = auth.getIdToken() ?: return emptyList()
            val raw = api.listHistory(token, limit)
            val items = raw["items"] as? List<*> ?: return emptyList()
            items.mapNotNull { it.toHistoryItem() }
        } catch (t: Throwable) {
            Log.w(TAG, "listHistory failed: ${t.message}")
            emptyList()
        }
    }

    private fun Any?.toHistoryItem(): HistoryItem? {
        val m = this as? Map<*, *> ?: return null
        return HistoryItem(
            id = m["id"]?.toString().orEmpty(),
            type = m["type"]?.toString().orEmpty(),
            sourceHash = m["sourceHash"]?.toString().orEmpty(),
            localUri = m["localUri"]?.toString().orEmpty(),
            remoteUri = m["remoteUri"]?.toString().orEmpty(),
            metadataJson = (m["metadata"] as? Map<*, *>)?.toString().orEmpty(),
        )
    }

    companion object {
        private const val TAG = "BackendClient"

        fun create(auth: AuthRepository): BackendClient {
            val http = HttpClient(Android) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; isLenient = true })
                }
            }
            val api = BackendApi(http, BackendConfig.BASE_URL)
            return BackendClient(api, auth)
        }
    }
}
