package com.pdfposter.data.backend

/**
 * Domain types consumed by the UI/ViewModel layer.
 *
 * Wire-level @Serializable DTOs live in BackendDtos.kt; BackendClient is
 * responsible for mapping between the two.
 */
data class HistoryItem(
    val id: String,
    val type: String,
    val sourceHash: String,
    val localUri: String,
    val remoteUri: String,
    val metadataJson: String,
    val createdAtMillis: Long? = null,
    val metadata: Map<String, Any?> = emptyMap(),
)
