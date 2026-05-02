package com.posterpdf.data.backend

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Auth-aware façade over BackendApi.
 *
 * Translates wire DTOs into domain types (HistoryItem) and centralises all
 * "fail soft" error handling so transient failures (no network, backend not
 * deployed yet, anon auth disabled) don't crash the app.
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
            api.addHistory(token, type, sourceHash, localUri, remoteUri, metadata.toJsonObject())
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
            api.listHistory(token, limit).items.map { it.toDomain() }
        } catch (t: Throwable) {
            Log.w(TAG, "listHistory failed: ${t.message}")
            emptyList()
        }
    }

    private fun HistoryItemDto.toDomain(): HistoryItem {
        val meta = metadata.toAnyMap()
        return HistoryItem(
            id = id,
            type = type,
            sourceHash = sourceHash,
            localUri = localUri,
            remoteUri = remoteUri,
            metadataJson = meta.toString(),
            createdAtMillis = parseFirestoreTimestamp(createdAt as? JsonObject),
            metadata = meta,
        )
    }

    /** Firestore timestamps come over JSON as {_seconds, _nanoseconds}. */
    private fun parseFirestoreTimestamp(obj: JsonObject?): Long? {
        if (obj == null) return null
        val seconds = (obj["_seconds"] ?: obj["seconds"])?.jsonPrimitive?.doubleOrNull
            ?: return null
        val nanos = (obj["_nanoseconds"] ?: obj["nanoseconds"])?.jsonPrimitive?.doubleOrNull ?: 0.0
        return (seconds * 1000.0 + nanos / 1_000_000.0).toLong()
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

private fun Map<String, Any?>.toJsonObject(): JsonObject =
    JsonObject(mapValues { (_, v) -> v.toJsonPrimitive() })

private fun Any?.toJsonPrimitive(): JsonPrimitive = when (this) {
    null -> JsonPrimitive(null as String?)
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    else -> JsonPrimitive(toString())
}

private fun JsonObject.toAnyMap(): Map<String, Any?> =
    mapValues { (_, v) ->
        when (v) {
            is JsonPrimitive -> v.unwrap()
            is JsonObject -> v.toAnyMap()
            else -> v.toString()
        }
    }

private fun JsonPrimitive.unwrap(): Any? = when {
    isString -> content
    booleanOrNull != null -> boolean
    doubleOrNull != null -> {
        val d = double
        if (d == d.toLong().toDouble()) d.toLong() else d
    }
    else -> content
}
