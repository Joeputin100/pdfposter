package com.posterpdf.data.backend

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Wire-level @Serializable DTOs for the BackendApi. Domain types in
 * BackendModels.kt stay decoupled — BackendClient does the mapping.
 *
 * `metadata` and per-item dynamic content stays as JsonObject because the
 * shape varies by record type (pdf_local, upscale_local, …) and we don't
 * want to force a strict union on the client.
 */

@Serializable
data class BootstrapResponseDto(
    val uid: String,
    val freeUpscalesRemaining: Int = 0,
    val paidCreditsAvailable: Int = 0,
    val nagDismissed: Boolean = false,
    val supportPurchaseActive: Boolean = false,
)

@Serializable
data class QuoteRequestDto(val outputMegapixels: Double)

@Serializable
data class QuoteResponseDto(
    val outputMegapixels: Double,
    val credits: Int,
    val falBaseUsd: Double,
    val chargedUsd: Double,
    val note: String = "",
)

@Serializable
data class StageRequestDto(val sourceHash: String, val outputMegapixels: Double)

@Serializable
data class StageResponseDto(val transactionId: String, val status: String)

@Serializable
data class TxRequestDto(val transactionId: String)

@Serializable
data class TxResponseDto(val transactionId: String, val status: String)

@Serializable
data class HistoryAddRequestDto(
    val type: String,
    val sourceHash: String,
    val localUri: String,
    val remoteUri: String,
    val metadata: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class HistoryAddResponseDto(val id: String)

@Serializable
data class HistoryListResponseDto(val items: List<HistoryItemDto> = emptyList())

@Serializable
data class HistoryItemDto(
    val id: String,
    val type: String = "",
    val sourceHash: String = "",
    val localUri: String = "",
    val remoteUri: String = "",
    val metadata: JsonObject = JsonObject(emptyMap()),
    val createdAt: JsonElement? = null,
)

// RC12c — debug fixture for FCM push testing.
@Serializable
data class TestStorageEventRequestDto(val type: String)

@Serializable
data class TestStorageEventResponseDto(
    val delivered: Int = 0,
    val title: String = "",
    val body: String = "",
    val notificationId: String = "",
)
