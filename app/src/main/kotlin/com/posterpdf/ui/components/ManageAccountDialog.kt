package com.posterpdf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.posterpdf.MainViewModel
import java.text.DateFormat
import java.util.Date

/**
 * RC35: Manage Account modal — surfaces credit balance, last activity,
 * Google profile (when signed in), upgrade CTA, and a DANGER ZONE that
 * lets the user erase their account and all server-side data.
 *
 * Erase-account flow:
 *   1. User taps "Erase my account…"
 *   2. Sub-dialog explains scope (everything except minimum-required
 *      financial / transaction history kept for legal records) and
 *      requires the user to type "CANCEL" verbatim before the
 *      confirm button enables.
 *   3. Confirm fires onEraseAccount() — caller is responsible for the
 *      backend call + Firebase auth.delete() + local state reset.
 *
 * Why type-CANCEL instead of type-DELETE: the user explicitly asked for
 * CANCEL. Reads as "cancel my account" rather than "delete my data,"
 * which keeps the verb consistent with the action's user-facing meaning.
 */
@Composable
fun ManageAccountDialog(
    viewModel: MainViewModel,
    creditBalance: Int,
    onDismiss: () -> Unit,
    onUpgrade: () -> Unit,
    onCreditsHistory: () -> Unit,
    onEraseAccount: () -> Unit,
) {
    val session = viewModel.authSession
    var showEraseConfirm by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(28.dp))
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "Manage account",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                )

                // Profile row — Google avatar + name + email when signed in,
                // generic placeholder otherwise.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val photo = session.photoUrl
                    if (photo != null) {
                        coil.compose.AsyncImage(
                            model = photo,
                            contentDescription = null,
                            modifier = Modifier
                                .size(56.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50)),
                        )
                    } else {
                        Icon(
                            Icons.Filled.AccountCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(56.dp),
                        )
                    }
                    Column {
                        Text(
                            session.displayName ?: "Guest",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (session.email != null) {
                            Text(
                                session.email!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (session.isAnonymous) {
                            Text(
                                "Anonymous (sign in to back up your credits)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                HorizontalDivider()

                // Stats: credit balance + last activity. Last-activity is
                // best-effort: we use the most recent of postersMadeCount's
                // last update or last poster timestamp if available; for
                // now we lean on the auth session's lastActivity if set.
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatBlock(
                        label = "Credit balance",
                        value = if (viewModel.isAdmin) "∞" else creditBalance.toString(),
                        modifier = Modifier.weight(1f),
                    )
                    StatBlock(
                        label = "Last activity",
                        value = formatLastActivity(viewModel),
                        modifier = Modifier.weight(1f),
                    )
                }

                Button(
                    onClick = onUpgrade,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Buy credits")
                }
                OutlinedButton(
                    onClick = onCreditsHistory,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("View credits history")
                }

                Spacer(Modifier.height(8.dp))

                // DANGER ZONE — visually separated with an outlined error
                // border + warning icon header so the user can't miss
                // what's about to happen.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "DANGER ZONE",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "Erase your account and all data we hold about it. " +
                            "We keep only the minimum financial records required by law (transaction history). " +
                            "This cannot be undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { showEraseConfirm = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Erase my account…")
                    }
                }

                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Close")
                }
            }
        }
    }

    if (showEraseConfirm) {
        EraseAccountConfirmDialog(
            onCancel = { showEraseConfirm = false },
            onConfirm = {
                showEraseConfirm = false
                onEraseAccount()
            },
        )
    }
}

@Composable
private fun StatBlock(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

private fun formatLastActivity(viewModel: MainViewModel): String {
    // Best-effort: the ViewModel doesn't currently track a "last activity"
    // timestamp directly, so we use today as a stand-in when the user has
    // posters in history, otherwise "—". A future RC can wire in the most
    // recent /upscaleTransactions doc timestamp when this dialog mounts.
    val historyCount = viewModel.historyItems.size
    return if (historyCount > 0) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())
    } else {
        "—"
    }
}

@Composable
private fun EraseAccountConfirmDialog(onCancel: () -> Unit, onConfirm: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val unlocked = typed.trim() == "CANCEL"
    AlertDialog(
        onDismissRequest = onCancel,
        icon = {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text("Erase your account?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "This permanently deletes your profile, credit balance, generated posters, " +
                        "and any cloud-stored upscales. We retain only minimum financial records " +
                        "required by law.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Type CANCEL below to confirm.",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    placeholder = { Text("CANCEL") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = unlocked,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Erase account", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Keep account")
            }
        },
    )
}
