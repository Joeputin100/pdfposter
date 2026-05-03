package com.posterpdf.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * TopAppBar action: sparkles icon with an MD3 Badge showing the AI-credit
 * balance. Tapping opens the purchase sheet (caller's `onClick`).
 *
 * The badge is suppressed when balance == 0 so the icon reads as a plain
 * "buy credits" affordance instead of "you have zero".
 */
@Composable
fun CreditBadge(balance: Int, isAdmin: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        BadgedBox(badge = {
            when {
                isAdmin -> Badge { Text("∞") }
                balance > 0 -> Badge { Text(balance.toString()) }
            }
        }) {
            Icon(Icons.Default.AutoAwesome, contentDescription = "AI credits")
        }
    }
}
