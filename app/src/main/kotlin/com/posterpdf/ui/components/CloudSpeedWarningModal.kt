package com.posterpdf.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.posterpdf.R
import com.posterpdf.ml.formatEta

/**
 * Cloud connection & upload-speed gate (2026-05-29) — the polite,
 * dismissible "this may take a while" warning shown before a *slow* cloud
 * upload. Mirrors the on-device long-job confirm modal (a plain AlertDialog,
 * Continue / Cancel). Microcopy is locked by the spec.
 *
 * @param etaRange measured upload ETA in seconds (from the ViewModel); null
 *   when the speed is unknown (probe failed) — the body then reads naturally
 *   with a gentle "a while" substitution rather than a bogus number.
 */
@Composable
fun CloudSpeedWarningModal(
    etaRange: IntRange?,
    onContinue: () -> Unit,
    onCancel: () -> Unit,
) {
    val etaText = etaRange?.let { formatEta(it) }
        ?: stringResource(R.string.cloud_speed_warn_eta_unknown)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.cloud_speed_warn_title)) },
        text = { Text(stringResource(R.string.cloud_speed_warn_body, etaText)) },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(stringResource(R.string.common_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
