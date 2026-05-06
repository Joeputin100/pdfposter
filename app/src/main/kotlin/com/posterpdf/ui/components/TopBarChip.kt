package com.posterpdf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.posterpdf.data.backend.AuthSession

/**
 * RC35: OpenArt-style credit chip — coin + balance + Upgrade button —
 * for the top bar. Replaces the prior `BadgedBox` whose Badge clipped at
 * 99 (3 chars max). The chip wraps content so 4-digit balances display
 * cleanly; the inner [CreditBadgeInline] handles the digit cascade.
 *
 * Tap-target geometry: tapping the coin/number opens the purchase sheet
 * (same as the legacy badge); tapping Upgrade opens the same sheet — we
 * don't differentiate yet, but the visual affordance separates "see
 * balance" from "get more" so users coming from OpenArt's pattern feel
 * at home.
 */
@Composable
fun CreditChip(
    balance: Int,
    isAdmin: Boolean,
    onTapBalance: () -> Unit,
    onTapUpgrade: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clickable(onClick = onTapBalance)
                    .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CreditBadgeInline(balance = balance, isAdmin = isAdmin)
            }
            // Upgrade pill — pill-shaped on a slightly elevated tint to
            // read as a primary CTA inside the chip without dwarfing
            // the credit number.
            Box(
                modifier = Modifier
                    .padding(end = 4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onTapUpgrade)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    "Upgrade",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * RC35: Account menu — Google profile photo (or generic avatar) opens a
 * dropdown with Manage account / Creation history / Credits history /
 * Sign out. When signed-out (anonymous or never-signed-in) the avatar
 * is a tap-to-sign-in affordance and the menu collapses to a single
 * Sign in item.
 */
@Composable
fun AccountAvatarMenu(
    session: AuthSession,
    onManageAccount: () -> Unit,
    onCreationHistory: () -> Unit,
    onCreditsHistory: () -> Unit,
    onSignOut: () -> Unit,
    onSignIn: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val signedIn = session.signedIn && !session.isAnonymous

    Box {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(50))
                .clickable { if (signedIn) open = true else onSignIn() }
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            val photo = session.photoUrl
            if (signedIn && photo != null) {
                coil.compose.AsyncImage(
                    model = photo,
                    contentDescription = "Account",
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(50)),
                )
            } else {
                Icon(
                    Icons.Filled.AccountCircle,
                    contentDescription = "Sign in",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Manage account") },
                onClick = { open = false; onManageAccount() },
                leadingIcon = { Icon(Icons.Filled.ManageAccounts, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text("Creation history") },
                onClick = { open = false; onCreationHistory() },
                leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text("Credits history") },
                onClick = { open = false; onCreditsHistory() },
                leadingIcon = { Icon(Icons.Filled.History, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text("Sign out") },
                onClick = { open = false; onSignOut() },
                leadingIcon = { Icon(Icons.Filled.Logout, contentDescription = null) },
            )
        }
    }
}
