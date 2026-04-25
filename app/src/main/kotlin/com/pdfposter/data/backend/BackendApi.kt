package com.pdfposter.data.backend

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType

/**
 * Phase-2 backend client contract.
 *
 * Notes:
 * - This intentionally uses loose payload maps for now so we can move quickly
 *   without adding a serialization plugin migration in this phase.
 * - In phase 3 we should migrate to strict @Serializable DTOs and typed responses.
 */
class BackendApi(
    private val client: HttpClient,
    private val baseUrl: String,
) {
    suspend fun bootstrap(idToken: String): Map<String, Any?> {
        return client.post("$baseUrl/v1/bootstrap") {
            header(HttpHeaders.Authorization, "Bearer $idToken")
            contentType(ContentType.Application.Json)
            setBody(emptyMap<String, String>())
        }.body()
    }

    suspend fun quote(idToken: String, outputMegapixels: Double): Map<String, Any?> {
        return client.post("$baseUrl/v1/credits/quote") {
            header(HttpHeaders.Authorization, "Bearer $idToken")
            contentType(ContentType.Application.Json)
            setBody(mapOf("outputMegapixels" to outputMegapixels))
        }.body()
    }

    suspend fun stageCredits(idToken: String, sourceHash: String, outputMegapixels: Double): Map<String, Any?> {
        return client.post("$baseUrl/v1/credits/stage") {
            header(HttpHeaders.Authorization, "Bearer $idToken")
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "sourceHash" to sourceHash,
                    "outputMegapixels" to outputMegapixels,
                )
            )
        }.body()
    }

    suspend fun commitCredits(idToken: String, transactionId: String): Map<String, Any?> {
        return client.post("$baseUrl/v1/credits/commit") {
            header(HttpHeaders.Authorization, "Bearer $idToken")
            contentType(ContentType.Application.Json)
            setBody(mapOf("transactionId" to transactionId))
        }.body()
    }

    suspend fun refundCredits(idToken: String, transactionId: String): Map<String, Any?> {
        return client.post("$baseUrl/v1/credits/refund") {
            header(HttpHeaders.Authorization, "Bearer $idToken")
            contentType(ContentType.Application.Json)
            setBody(mapOf("transactionId" to transactionId))
        }.body()
    }

    suspend fun addHistory(
        idToken: String,
        type: String,
        sourceHash: String,
        localUri: String,
        remoteUri: String,
        metadata: Map<String, Any?>,
    ): Map<String, Any?> {
        return client.post("$baseUrl/v1/history/add") {
            header(HttpHeaders.Authorization, "Bearer $idToken")
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "type" to type,
                    "sourceHash" to sourceHash,
                    "localUri" to localUri,
                    "remoteUri" to remoteUri,
                    "metadata" to metadata,
                )
            )
        }.body()
    }

    suspend fun listHistory(idToken: String, limit: Int = 50): Map<String, Any?> {
        return client.get("$baseUrl/v1/history/list?limit=$limit") {
            header(HttpHeaders.Authorization, "Bearer $idToken")
        }.body()
    }
}
