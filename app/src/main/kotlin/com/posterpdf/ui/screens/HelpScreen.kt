package com.posterpdf.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * H-P2.2 — Help screen.
 *
 * How-to topics: paper sizes, low-DPI fix, upscale options, sign-in, history,
 * sharing. Plain-language explanations matching what a user actually sees on
 * screen — no marketing fluff.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Help",
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HelpTopic(
                title = "Paper sizes",
                body = "PosterPDF supports Letter (8.5×11 in), Legal (8.5×14 in), Tabloid " +
                    "(11×17 in), A4 (8.27×11.69 in), A3 (11.69×16.54 in), and a Custom " +
                    "size for unusual printers. Letter is the home/office default in " +
                    "North America; A4 is standard everywhere else. Pick whichever " +
                    "matches the paper actually loaded in your printer — the app will " +
                    "tile your poster across as many pages as needed.",
            )
            HelpTopic(
                title = "Low-DPI warning",
                body = "If your image has fewer pixels than the printed size demands, " +
                    "we show a yellow warning and tap-to-fix banner under the preview. " +
                    "150 DPI is the rule-of-thumb minimum for sharp prints. Below that, " +
                    "your poster will look blurry or pixelated. Tap the banner to open " +
                    "the upscale modal — it shows what your output will actually look " +
                    "like at each upscale option, with cost in credits.",
            )
            HelpTopic(
                title = "Upscale options",
                body = "Five default cards: 'Now (pixelated)' shows what you get with " +
                    "no upscale; 'Free upscale' runs ESRGAN locally on your phone (no " +
                    "internet, no credits, ~30s); 'Topaz 4×' is the highest-quality " +
                    "paid option; 'Recraft Crisp' is photo-faithful and ~40× cheaper " +
                    "than Topaz; 'Bring your own' lets you load a file you upscaled " +
                    "elsewhere (Canva, OpenArt, Magnific, or Topaz desktop). Tap " +
                    "'See other AI options' to expand to Topaz 8×, AuraSR, and ESRGAN.",
            )
            HelpTopic(
                title = "Sign-in",
                body = "First launch creates an anonymous Firebase Auth session — your " +
                    "history works immediately, no account needed. To keep history " +
                    "across devices (or restore after reinstalling), open the side " +
                    "menu and tap 'Sign in with Google'. We only collect your name " +
                    "and email; we don't post on your behalf, read other Google data, " +
                    "or share with third parties.",
            )
            HelpTopic(
                title = "History",
                body = "Every poster you generate appears in the History list. Each row " +
                    "has 4 buttons: View opens the local PDF; Share hands it to email " +
                    "or messaging apps; Download (cloud icon) pulls a copy from cloud " +
                    "storage if you opted in; Delete (cloud-off icon) removes the cloud " +
                    "copy without touching your local file or history entry.",
            )
            HelpTopic(
                title = "Sharing",
                body = "The new 'Share…' button on the main screen lets you send the " +
                    "current poster to any app — Gmail, WhatsApp, Drive, your printer's " +
                    "companion app, etc. The PDF is generated fresh each time so it " +
                    "always reflects the current settings.",
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HelpTopic(title: String, body: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}
