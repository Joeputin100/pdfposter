package com.posterpdf.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.posterpdf.R

/**
 * H-P2.6 — "Show me how to do it…" walkthrough.
 *
 * Shown when the user taps the "Show me how…" button on the BringYourOwn
 * upscale card. Skimmable 4-step guide for picking an external upscale tool,
 * running it, saving the result, and returning to PosterPDF.
 *
 * Final "Choose file" button calls [onPickAlreadyUpscaled], which is the same
 * callback the original button used — this dialog is just an explainer that
 * sits in front of the file picker.
 */
@Composable
fun BringYourOwnHelpDialog(
    onDismiss: () -> Unit,
    onPickAlreadyUpscaled: () -> Unit,
) {
    // RC23: dropped per-tool prices. Pricing changes more often than the
    // app's release cadence, and listing competitors' rates risked
    // showing stale numbers or implying endorsement of specific
    // pricing tiers. Names + value props only — users will check the
    // current price on whichever tool they pick.
    val tools = listOf(
        ToolOption("Canva", stringResource(R.string.byo_tool_canva_note_inline)),
        ToolOption("OpenArt", stringResource(R.string.byo_tool_openart_note_inline)),
        ToolOption("FAL Topaz", stringResource(R.string.byo_tool_fal_note_inline)),
        ToolOption("Magnific", stringResource(R.string.byo_tool_magnific_note_inline)),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.byo_dialog_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Step(
                    number = 1,
                    title = stringResource(R.string.byo_help_step1_title),
                    body = stringResource(R.string.byo_help_step1_body),
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tools.forEach { ToolRow(it) }
                }

                Step(
                    number = 2,
                    title = stringResource(R.string.byo_help_step2_title),
                    body = stringResource(R.string.byo_help_step2_body),
                )
                Step(
                    number = 3,
                    title = stringResource(R.string.byo_help_step3_title),
                    body = stringResource(R.string.byo_help_step3_body),
                )
                Step(
                    number = 4,
                    title = stringResource(R.string.byo_help_step4_title),
                    body = stringResource(R.string.byo_help_step4_body),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    onPickAlreadyUpscaled()
                },
            ) { Text(stringResource(R.string.byo_choose_file)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

private data class ToolOption(val name: String, val note: String)

@Composable
private fun ToolRow(tool: ToolOption) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                tool.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(tool.note, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Step(number: Int, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(28.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().height(28.dp)) {
                Text(
                    number.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}
