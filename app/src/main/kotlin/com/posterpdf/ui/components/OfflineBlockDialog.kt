package com.posterpdf.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.posterpdf.R

/**
 * Cloud connection & upload-speed gate (2026-05-29) — block dialog shown when
 * there's no validated internet connection and the user tried a cloud upscale.
 * Single dismiss button ("Got it"); the copy steers them to the free
 * on-device upscale, which works offline. Microcopy is locked by the spec.
 */
@Composable
fun OfflineBlockDialog(
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cloud_offline_title)) },
        text = { Text(stringResource(R.string.cloud_offline_body)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_got_it))
            }
        },
    )
}
