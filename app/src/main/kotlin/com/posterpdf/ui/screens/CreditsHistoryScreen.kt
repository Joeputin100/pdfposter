package com.posterpdf.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.posterpdf.MainViewModel
import kotlinx.coroutines.tasks.await
import java.text.DateFormat
import java.util.Date

/**
 * RC35: Per-account credit transaction ledger. Reachable from the
 * top-bar account-pfp menu. Reads from Firestore:
 *   - /upscaleTransactions  → debits (per-job credit charge / refund)
 *   - /creditPurchases      → credits (Play Billing top-ups, signup bonus)
 *
 * Both collections are filtered by the signed-in user's uid; a
 * server-side firestore.rules pin should enforce that — this screen
 * just reads what the rules allow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditsHistoryScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val uid = viewModel.authSession.uid

    var entries by remember { mutableStateOf<List<CreditEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(uid) {
        if (uid == null) {
            entries = emptyList()
            loading = false
            return@LaunchedEffect
        }
        loading = true
        try {
            val db = FirebaseFirestore.getInstance()
            // Both queries are best-effort — if the collection doesn't
            // exist yet (early-stage user) we just get an empty list.
            val debits = runCatching {
                db.collection("upscaleTransactions")
                    .whereEqualTo("uid", uid)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(100)
                    .get()
                    .await()
                    .documents
                    .mapNotNull { d ->
                        val cost = (d.getLong("creditsCharged") ?: d.getLong("cost") ?: 0L).toInt()
                        val refunded = d.getBoolean("refunded") ?: false
                        val ts = d.getTimestamp("createdAt")?.toDate() ?: return@mapNotNull null
                        CreditEntry(
                            kind = if (refunded) EntryKind.Refund else EntryKind.Debit,
                            label = (d.getString("modelName") ?: d.getString("model") ?: "AI upscale"),
                            credits = if (refunded) cost else -cost,
                            timestamp = ts,
                        )
                    }
            }.getOrDefault(emptyList())
            val purchases = runCatching {
                db.collection("creditPurchases")
                    .whereEqualTo("uid", uid)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(100)
                    .get()
                    .await()
                    .documents
                    .mapNotNull { d ->
                        val credits = (d.getLong("credits") ?: 0L).toInt()
                        val sku = d.getString("sku") ?: "purchase"
                        val ts = d.getTimestamp("createdAt")?.toDate() ?: return@mapNotNull null
                        CreditEntry(
                            kind = EntryKind.Purchase,
                            label = "Purchase: $sku",
                            credits = credits,
                            timestamp = ts,
                        )
                    }
            }.getOrDefault(emptyList())
            entries = (debits + purchases).sortedByDescending { it.timestamp }
        } finally {
            loading = false
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Credits history",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            when {
                loading -> {
                    Text(
                        "Loading…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                uid == null -> {
                    EmptyState(message = "Sign in to view your credit history.")
                }
                entries.isEmpty() -> {
                    EmptyState(message = "No transactions yet. Credit purchases and AI upscale jobs will appear here.")
                }
                else -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(entries) { entry ->
                            CreditRow(entry)
                        }
                    }
                }
            }
        }
    }
}

private enum class EntryKind { Debit, Refund, Purchase }

private data class CreditEntry(
    val kind: EntryKind,
    val label: String,
    /** Positive = added to balance; negative = debited. */
    val credits: Int,
    val timestamp: Date,
)

@Composable
private fun CreditRow(entry: CreditEntry) {
    val (icon: ImageVector, accent: Color) = when (entry.kind) {
        EntryKind.Purchase -> Icons.Filled.ShoppingCart to MaterialTheme.colorScheme.primary
        EntryKind.Refund -> Icons.Filled.Undo to MaterialTheme.colorScheme.tertiary
        EntryKind.Debit -> Icons.Filled.AutoAwesome to MaterialTheme.colorScheme.secondary
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(entry.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (entry.credits > 0) "+${entry.credits}" else entry.credits.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (entry.credits >= 0) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
