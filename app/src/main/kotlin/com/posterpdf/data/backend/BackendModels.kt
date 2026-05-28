package com.posterpdf.data.backend

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
    /**
     * Phase H-P3 cloud-storage retention. Empty string = no cloud copy
     * (local-only entry); non-empty `gs://...` URI means the PDF is in
     * cloud storage and subject to the retention/billing policy.
     */
    val cloudStorageUri: String = "",
)
