package com.posterpdf.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.posterpdf.R
import com.posterpdf.ui.theme.BlueprintBlue700
import com.posterpdf.ui.theme.TrimOrange500

/**
 * Catalog entry for an in-app credit pack. Mirrors the eventual
 * `/pricing` endpoint shape but holds inline placeholder values until the
 * BillingClient + backend pricing flow lands (G3 / G6).
 */
private data class CreditSku(
    val sku: String,
    val label: String,
    val credits: Int,
    val price: String,
)

private val PLACEHOLDER_SKUS = listOf(
    CreditSku("credits_starter", "Starter", credits = 199,  price = "$1.99"),
    CreditSku("credits_small",   "Small",   credits = 524,  price = "$4.99"),
    CreditSku("credits_medium",  "Medium",  credits = 1074, price = "$9.99"),
    CreditSku("credits_large",   "Large",   credits = 2199, price = "$19.99"),
)

/**
 * MD3 ModalBottomSheet that shows the user's current AI-credit balance,
 * a SKU list, recent transactions, and a "Restore purchases" affordance.
 *
 * Placeholder behavior (no BillingClient yet):
 *  - SKU prices/credits are hard-coded to the values planned for the
 *    `/pricing` Cloud Function until G3/G6 wires that up.
 *  - "Buy" buttons no-op; callers can pass `onBuy` to log/observe.
 *  - Recent-transactions list is always empty until backend is deployed.
 *  - When the user is anonymous AND balance == 0, we hide the SKU list
 *    and show a "Sign in with Google" CTA instead — the receipt needs a
 *    real (non-anonymous) Firebase uid to be useful.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseSheet(
    balance: Int,
    isAnonymous: Boolean,
    onDismiss: () -> Unit,
    onBuy: (sku: String) -> Unit = {},
    onRestore: () -> Unit = {},
    onSignInClick: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header: balance + sparkles icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = BlueprintBlue700,
                    modifier = Modifier.size(56.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = TrimOrange500,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.purchase_ai_credits),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = BlueprintBlue700,
                    )
                    Text(
                        "Balance: $balance",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider()

            if (isAnonymous && balance == 0) {
                // Anonymous + zero credits: receipts need a real uid before
                // we can sell anything, so route to Google sign-in first.
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.purchase_signin_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(stringResource(R.string.purchase_signin_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = onSignInClick,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BlueprintBlue700),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Login, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.purchase_signin_button))
                    }
                }
            } else {
                Text(stringResource(R.string.purchase_choose_pack),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                PLACEHOLDER_SKUS.forEach { item ->
                    SkuRow(item, onBuy = { onBuy(item.sku) })
                }
            }

            HorizontalDivider()

            // Recent transactions (empty placeholder until backend lands)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.History,
                    null,
                    tint = BlueprintBlue700,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.purchase_recent_transactions),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(stringResource(R.string.purchase_no_transactions),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Restore purchases (placeholder; wired in G3)
            OutlinedButton(
                onClick = onRestore,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Default.Restore, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.purchase_restore))
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SkuRow(item: CreditSku, onBuy: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.AutoAwesome,
                null,
                tint = TrimOrange500,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = BlueprintBlue700,
                )
                Text(
                    "${item.credits} credits  -  ${item.price}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onBuy,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TrimOrange500,
                    contentColor = androidx.compose.ui.graphics.Color.White,
                ),
            ) {
                Text(stringResource(R.string.purchase_buy))
            }
        }
    }
}
