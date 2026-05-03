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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
    val tools = listOf(
        ToolOption("Canva", "~\$15/mo", "Designer-friendly. Built-in upscaler under Edit Image → Magic Studio."),
        ToolOption("OpenArt", "\$10/mo", "Heavy AI library; the 'Upscaler' tab handles 4× and 8×."),
        ToolOption("FAL Topaz", "~\$0.01/MP", "Pay-per-image, no subscription. Professional-grade quality."),
        ToolOption("Magnific", "\$30/mo", "Premium creative upscaler. Slowest but most stylized."),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Bring your own upscaled image",
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
                    title = "Pick your tool",
                    body = "Any of these will work — pick whichever you already have or like the price of:",
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tools.forEach { ToolRow(it) }
                }

                Step(
                    number = 2,
                    title = "Run upscale",
                    body = "Open your chosen tool, upload the original image, and run upscale at 4× or 8×. Higher = sharper, but takes longer and costs more.",
                )
                Step(
                    number = 3,
                    title = "Save to phone",
                    body = "Download the result to your Downloads folder (or anywhere accessible by Android's file picker — Drive, Photos, etc. all work).",
                )
                Step(
                    number = 4,
                    title = "Continue here",
                    body = "Tap 'Choose file' below — pick your upscaled file. PosterPDF will use it directly without running its own upscaler.",
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    onPickAlreadyUpscaled()
                },
            ) { Text("Choose file") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private data class ToolOption(val name: String, val price: String, val note: String)

@Composable
private fun ToolRow(tool: ToolOption) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tool.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    tool.price,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
