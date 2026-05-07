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
import com.posterpdf.R

/**
 * Phase H-P3.2 — account-setup question on storage retention.
 *
 * Presented at first sign-in (and also via the Settings hamburger as a
 * later override). Persists to `users/{uid}.storageRetentionMode` =
 * 'paid' | 'auto-delete'.
 *
 * RC13 — copy reflects the new RC12 per-user batched billing model:
 * bytes × $0.026/GB-month × 1.5 markup, ceiled to next cent (1¢ = 1
 * credit). For an average ~10MB poster, 100 stored posters ≈ 1 GB ≈
 * 4 credits/month, with a 1-credit floor for any non-zero storage.
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
                    stringResource(R.string.storage_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChoiceRow(
                    selected = selected == "auto-delete",
                    onClick = { selected = "auto-delete" },
                    title = stringResource(R.string.storage_auto_delete_title),
                    body = stringResource(R.string.storage_auto_delete_body),
                )
                ChoiceRow(
                    selected = selected == "paid",
                    onClick = { selected = "paid" },
                    title = stringResource(R.string.storage_paid_title),
                    body = stringResource(R.string.storage_paid_body),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.storage_save_button)) }
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
