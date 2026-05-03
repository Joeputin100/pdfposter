package com.posterpdf.ui.screens

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * H-P2.1 — Getting Started screen.
 *
 * Linked from the hamburger drawer. One-paragraph intro, a "What You Get for Free"
 * block (avoids underselling the paid AI upscale tier), then a 3-step guided tour.
 * Screenshot placeholders are reserved for later — strings call out where they go.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GettingStartedScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Getting Started",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "PosterPDF turns any image into a printable wall poster, tiled across " +
                    "letter or A4 pages so you can assemble it at home. Pick a photo, " +
                    "set how big you want the finished poster, and we'll generate a " +
                    "print-ready PDF you can send straight to your printer.",
                style = MaterialTheme.typography.bodyMedium,
            )

            SectionHeader("What You Get for Free")
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FreeFeature(Icons.Default.WorkspacePremium, "No catch", "No ads, no watermarks, no reduced functionality. Every feature works the same whether you spend a cent or not.")
                    FreeFeature(Icons.Default.PictureAsPdf, "Poster generation", "Tile any image across multiple pages — no watermarks, no page limit.")
                    FreeFeature(Icons.Default.CheckCircle, "All paper sizes", "Letter, Legal, Tabloid, A3, A4, plus a Custom size for unusual printers.")
                    FreeFeature(Icons.Default.AutoAwesome, "On-device upscale", "4× upscale runs locally on your phone — no internet, no credits.")
                    FreeFeature(Icons.Default.SdStorage, "30-day cloud storage", "We hold your generated PDFs for 30 days so you can re-download from another device.")
                    FreeFeature(Icons.Default.History, "History forever", "Every poster you make stays in your local history on this phone.")
                }
            }
            Text(
                "AI upscale (Topaz, Recraft, Magnific…) is opt-in and uses credits. " +
                    "We charge enough above the AI provider's per-image cost to cover Play " +
                    "Store fees and servers — see the FAQ for the exact margin breakdown.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeader("3-Step Tour")
            TourStep(
                number = 1,
                title = "Pick an image",
                body = "Tap the photo card at the top. Use the highest-resolution copy you have — small phone screenshots will look pixelated when blown up to wall-poster size.",
                screenshotHint = "[screenshot: image picker]",
            )
            TourStep(
                number = 2,
                title = "Set the size",
                body = "Type the width and height of the finished poster. Lock the aspect ratio (the chain icon) to keep your image proportions, or unlock to crop. Pick a paper size that matches your printer — Letter is the home/office default in North America; A4 elsewhere.",
                screenshotHint = "[screenshot: size + paper selector]",
            )
            TourStep(
                number = 3,
                title = "Generate",
                body = "Tap View, Save, or Share. View opens the PDF in your reader; Save lets you choose a folder; Share hands the PDF to any app (email, Drive, print). If your image is below 150 DPI at the chosen size, we'll prompt you to upscale first.",
                screenshotHint = "[screenshot: action buttons]",
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun FreeFeature(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TourStep(number: Int, title: String, body: String, screenshotHint: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(32.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            number.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Text(body, style = MaterialTheme.typography.bodySmall)
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().height(120.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(
                        screenshotHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
