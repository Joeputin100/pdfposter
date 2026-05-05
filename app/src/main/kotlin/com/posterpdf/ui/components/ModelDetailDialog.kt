package com.posterpdf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * RC22 — full-screen model-detail dialog answering the user's three
 * questions per upscaler:
 *   1. Why would I pick this model?
 *   2. What makes it stand out?
 *   3. Why use it over a less expensive option?
 *
 * Triggered from [UpscaleOptionCard] via the lower-right info button or
 * a double-tap anywhere on the card. Marketing copy lives here in
 * [detailFor] so the card itself stays compact (the in-grid card has
 * pros/cons in 3 lines each; this dialog gets the full pitch).
 *
 * Layout:
 *   • Top bar: model name + close button
 *   • Hero strip: tagline + "Best for" chips
 *   • Three sections: Pick this when… / What stands out / Worth the price
 *   • Footer: same primary CTA the card had ("Upscale", "Sign in",
 *     "Get more credits", or onDeviceUpscale).
 *
 * Designed mobile-first: scrolls vertically, no horizontal split, copy
 * sized for ~5-inch viewports.
 */
@Composable
fun ModelDetailDialog(
    displayName: String,
    bestFor: List<String>,
    pickWhen: String,
    standsOut: String,
    worthThePrice: String,
    onDismiss: () -> Unit,
    primaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    showPrimaryAction: Boolean = true,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        displayName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                HorizontalDivider()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // Best-for chips
                    if (bestFor.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Best for",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                bestFor.forEach { tag ->
                                    AssistChip(
                                        onClick = {},
                                        label = { Text(tag, style = MaterialTheme.typography.labelMedium) },
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                            labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                        ),
                                    )
                                }
                            }
                        }
                    }

                    // Why pick this model
                    DetailSection(
                        icon = Icons.Default.Lightbulb,
                        title = "Pick this when",
                        body = pickWhen,
                        accent = MaterialTheme.colorScheme.tertiary,
                    )
                    // What stands out
                    DetailSection(
                        icon = Icons.Default.Star,
                        title = "What stands out",
                        body = standsOut,
                        accent = MaterialTheme.colorScheme.secondary,
                    )
                    // Worth the price
                    DetailSection(
                        icon = Icons.Default.LocalOffer,
                        title = "Worth the price?",
                        body = worthThePrice,
                        accent = MaterialTheme.colorScheme.primary,
                    )

                    Spacer(Modifier.height(8.dp))
                }

                // Footer with primary action
                if (showPrimaryAction) {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Button(
                            onClick = {
                                onPrimaryAction()
                                onDismiss()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) {
                            Text(primaryActionLabel, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailSection(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    accent: Color,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = accent.copy(alpha = 0.18f),
                modifier = Modifier.size(34.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(icon, contentDescription = null, tint = accent)
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Marketing copy per model. Centralised here so the card doesn't have to
 * hold long strings, and so future copy edits land in one file.
 *
 * The three buckets directly mirror the user's questions:
 *   • pickWhen     — "Why would I pick this model?"
 *   • standsOut    — "What makes this model stand out?"
 *   • worthThePrice — "Why use this model over a less expensive option?"
 *
 * `bestFor` is rendered as 1–3 tag chips at the top of the dialog.
 */
data class ModelDetailCopy(
    val bestFor: List<String>,
    val pickWhen: String,
    val standsOut: String,
    val worthThePrice: String,
)

fun detailFor(model: UpscaleModel): ModelDetailCopy = when (model) {
    UpscaleModel.NONE -> ModelDetailCopy(
        bestFor = listOf("Reference"),
        pickWhen = "When you want to print exactly what you imported, " +
            "without sharpening. Choose this if your source is already " +
            "high-resolution or if you intentionally want a softer, " +
            "lower-DPI look.",
        standsOut = "It's a no-op — no AI is invoked, no credits are used, " +
            "no upload happens. The original pixels go straight into the PDF.",
        worthThePrice = "Free, fastest, fully private. The trade-off is that " +
            "if your source is below the print-quality threshold (~150 DPI " +
            "at your chosen poster size), the result will visibly soften.",
    )
    UpscaleModel.FREE_LOCAL -> ModelDetailCopy(
        bestFor = listOf("Privacy", "Free", "Any photo"),
        pickWhen = "When you want a 4× sharper print without spending credits " +
            "or sending your photo anywhere. The whole upscale runs on your " +
            "phone — nothing leaves the device.",
        standsOut = "The only fully-private option. Uses ESRGAN-TF2, a classic " +
            "super-resolution model bundled with the app and accelerated on " +
            "your phone's NNAPI hardware. No server round-trip, no FAL job, " +
            "no upload of your photo.",
        worthThePrice = "It's free. The trade-off is wall-clock time: a typical " +
            "12 MP photo takes about 5 minutes on a recent phone. If you " +
            "need the result quickly, the cloud options finish in 10-30 " +
            "seconds for a few cents.",
    )
    UpscaleModel.RECRAFT -> ModelDetailCopy(
        bestFor = listOf("Logos", "Text", "Product photos"),
        pickWhen = "When your image has fine text, sharp logos, or clean " +
            "geometric shapes that need to stay crisp at print size. The " +
            "Recraft team trained this model specifically to preserve " +
            "edge clarity instead of softening or hallucinating texture.",
        standsOut = "Best edge fidelity of the four AI models. Lettering stays " +
            "razor-sharp, brand logos keep their exact colour and outline, " +
            "and graphical elements (icons, line art, vector-style images) " +
            "scale up without the painterly mush some general-purpose " +
            "upscalers introduce.",
        worthThePrice = "Costs about the same as ESRGAN but is much better at " +
            "anything with fine text. If your poster is photography-only, " +
            "AuraSR or ESRGAN may give you more natural skin and texture; " +
            "for anything with type, signage, or product packaging, Recraft " +
            "is the clear choice.",
    )
    UpscaleModel.ESRGAN -> ModelDetailCopy(
        bestFor = listOf("General photos", "Predictable", "Cheapest cloud"),
        pickWhen = "When you want classic super-resolution without surprises. " +
            "Real-ESRGAN is the model the wider community has used for " +
            "years; its behaviour is well-understood and predictable.",
        standsOut = "Mature, no-frills sharpening. It doesn't try to invent " +
            "texture or hallucinate detail — it just sharpens what's there. " +
            "Output looks like a higher-resolution version of your input, " +
            "not a different photo.",
        worthThePrice = "Cheapest cloud option. Faster than the on-device " +
            "free path (10-30 seconds vs 5 minutes) and produces cleaner " +
            "results because cloud GPUs run a larger model than your phone " +
            "can fit. Pick AuraSR if you want richer texture, Recraft if " +
            "you have fine text, Topaz if quality is paramount.",
    )
    UpscaleModel.AURASR -> ModelDetailCopy(
        bestFor = listOf("Skin", "Foliage", "Natural texture"),
        pickWhen = "When your photo is rich in natural texture — portraits, " +
            "landscapes, food, fabric, fur — and you want the upscaler to " +
            "ADD plausible detail rather than just sharpen what's there.",
        standsOut = "Trained specifically on photographs to produce natural " +
            "texture at the upscaled resolution. Skin pores, hair strands, " +
            "leaf veins, fabric weave — all the micro-detail that makes " +
            "a photo look like a photo at 300 DPI.",
        worthThePrice = "Pricier than ESRGAN but visibly more detailed on " +
            "natural subjects. The added cost buys you texture that the " +
            "cheaper models can't synthesize. For graphics or text, drop " +
            "back to Recraft. For absolute top quality, Topaz.",
    )
    UpscaleModel.TOPAZ -> ModelDetailCopy(
        bestFor = listOf("Premium", "Print labs", "Portfolio work"),
        pickWhen = "When the print matters — portfolio pieces, framed " +
            "reproductions, gallery showings, anything you'll show off. " +
            "Topaz Gigapixel is the industry-standard tool used by " +
            "professional photographers and print shops.",
        standsOut = "Highest overall quality of the four AI models. Combines " +
            "edge clarity (like Recraft) with texture richness (like " +
            "AuraSR) and applies model-trained tone preservation so the " +
            "upscaled image keeps the colour balance of the original.",
        worthThePrice = "2-4× more expensive than the other AI options, but " +
            "produces visibly sharper, more natural results. If you're " +
            "printing for a client, hanging the result on a wall, or " +
            "submitting to a competition, the cost-per-poster is still " +
            "tiny relative to the framing or paper. For day-to-day prints " +
            "where good-enough is fine, ESRGAN or AuraSR is the better value.",
    )
}
