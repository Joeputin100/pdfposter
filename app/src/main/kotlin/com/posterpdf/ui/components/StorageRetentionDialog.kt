package com.posterpdf.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Phase H-P3.2 — account-setup question on storage retention.
 *
 * Presented at first sign-in (and also via the Settings hamburger as a
 * later override). Persists to `users/{uid}.storageRetentionMode` =
 * 'paid' | 'auto-delete'.
 *
 * "About 1 cent per poster per month" reflects the per-file storage
 * billing rate set up in backend/storageBilling.ts (1 credit/file/month
 * at 1¢ retail per credit).
 */
@Composable
fun StorageRetentionDialog(
    initialMode: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var selected by remember { mutableStateOf(initialMode.ifEmpty { "paid" }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.storage_dialog_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Your local copies and the history list always stay forever. " +
                        "This choice is only about cloud-stored poster PDFs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChoiceRow(
                    selected = selected == "auto-delete",
                    onClick = { selected = "auto-delete" },
                    title = "Auto-delete after 30 days",
                    body = "Keeps your storage bill at zero. After 30 days the cloud " +
                        "copy is removed; local + history-list stay intact.",
                )
                ChoiceRow(
                    selected = selected == "paid",
                    onClick = { selected = "paid" },
                    title = "Keep storing them",
                    body = "About 1¢ per poster per month after the first 30 days, " +
                        "deducted from your credit balance. If you run out of " +
                        "credits we hold the file for 30 more days and email you " +
                        "before deleting.",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.storage_decide_later)) }
        },
    )
}

@Composable
private fun ChoiceRow(
    selected: Boolean,
    onClick: () -> Unit,
    title: String,
    body: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 4.dp, top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
