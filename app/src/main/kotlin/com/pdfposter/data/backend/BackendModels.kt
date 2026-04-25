package com.pdfposter.data.backend

data class BackendBootstrap(
    val uid: String,
    val freeUpscalesRemaining: Int,
    val paidCreditsAvailable: Int,
    val nagDismissed: Boolean,
    val supportPurchaseActive: Boolean,
)

data class CreditQuote(
    val outputMegapixels: Double,
    val credits: Int,
    val falBaseUsd: Double,
    val chargedUsd: Double,
    val note: String,
)

data class StageResult(
    val transactionId: String,
    val status: String,
)

data class CommitResult(
    val transactionId: String,
    val status: String,
)

data class RefundResult(
    val transactionId: String,
    val status: String,
)

data class HistoryItem(
    val id: String,
    val type: String,
    val sourceHash: String,
    val localUri: String,
    val remoteUri: String,
    val metadataJson: String,
)
