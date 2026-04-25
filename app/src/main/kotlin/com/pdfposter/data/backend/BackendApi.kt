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
import kotlinx.serialization.json.JsonObject

/**
 * Strict-typed HTTP client for the Phase-2 backend.
 *
 * All payloads are @Serializable DTOs (see BackendDtos.kt). The HttpClient
 * passed in must have ContentNegotiation+JSON installed — see
 * `BackendClient.create()`.
 */
class BackendApi(
    private val client: HttpClient,
    private val baseUrl: String,
) {
    suspend fun bootstrap(idToken: String): BootstrapResponseDto =
        client.post("$baseUrl/v1/bootstrap") {
            authed(idToken)
            setBody(emptyMap<String, String>())
        }.body()

    suspend fun quote(idToken: String, outputMegapixels: Double): QuoteResponseDto =
        client.post("$baseUrl/v1/credits/quote") {
            authed(idToken)
            setBody(QuoteRequestDto(outputMegapixels))
        }.body()

    suspend fun stageCredits(
        idToken: String,
        sourceHash: String,
        outputMegapixels: Double,
    ): StageResponseDto =
        client.post("$baseUrl/v1/credits/stage") {
            authed(idToken)
            setBody(StageRequestDto(sourceHash, outputMegapixels))
        }.body()

    suspend fun commitCredits(idToken: String, transactionId: String): TxResponseDto =
        client.post("$baseUrl/v1/credits/commit") {
            authed(idToken)
            setBody(TxRequestDto(transactionId))
        }.body()

    suspend fun refundCredits(idToken: String, transactionId: String): TxResponseDto =
        client.post("$baseUrl/v1/credits/refund") {
            authed(idToken)
            setBody(TxRequestDto(transactionId))
        }.body()

    suspend fun addHistory(
        idToken: String,
        type: String,
        sourceHash: String,
        localUri: String,
        remoteUri: String,
        metadata: JsonObject,
    ): HistoryAddResponseDto =
        client.post("$baseUrl/v1/history/add") {
            authed(idToken)
            setBody(HistoryAddRequestDto(type, sourceHash, localUri, remoteUri, metadata))
        }.body()

    suspend fun listHistory(idToken: String, limit: Int = 50): HistoryListResponseDto =
        client.get("$baseUrl/v1/history/list?limit=$limit") {
            authed(idToken)
        }.body()

    private fun io.ktor.client.request.HttpRequestBuilder.authed(idToken: String) {
        header(HttpHeaders.Authorization, "Bearer $idToken")
        contentType(ContentType.Application.Json)
    }
}
