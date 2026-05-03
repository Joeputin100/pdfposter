package com.posterpdf.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * H-P2.3 — FAQ screen.
 *
 * Eight questions covering credits, offline use, signing fingerprint, retention
 * grace period, and data deletion. Tap a row to expand its answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaqScreen(onBack: () -> Unit) {
    val faqs = listOf(
        "Why does it ask for credits?" to
            "Credits pay for AI upscale calls — Topaz, Recraft, AuraSR, Magnific. " +
                "Each provider charges per image, so we charge credits per image. " +
                "PosterPDF itself (poster generation, on-device upscale, history, " +
                "30-day cloud storage) is free with any image source. Credits only " +
                "apply when you choose an AI upscale option.",
        "Why are my credits worth less than the price I paid?" to
            "Margin transparency: the underlying AI providers charge us about " +
                "two-thirds of what you pay. The other third covers Play Store's " +
                "15% cut, server costs (Cloud Functions + Firestore + Storage), and " +
                "leaves a small margin to fund development. We aim for ~50% above " +
                "raw FAL.ai cost — that's enough to keep the lights on, not enough " +
                "to make us rich. The exact USD-equivalent is shown on every credit " +
                "pack so there are no surprises.",
        "Can I use it offline?" to
            "Yes for the core flow: image picker, poster generation, on-device " +
                "free upscale, and View/Save/Share all work without internet. You " +
                "need internet for: signing in with Google, AI upscale (Topaz, " +
                "Recraft, etc.), buying credits, and cloud storage syncing. History " +
                "metadata syncs in the background when you reconnect.",
        "What's the SHA-1 fingerprint for?" to
            "Google Sign-In binds the app's APK signing certificate to your " +
                "Firebase Auth project. The SHA-1 fingerprint of our release " +
                "keystore is registered with Firebase, so only an APK signed with " +
                "that exact keystore can use Google Sign-In. This stops bad actors " +
                "from publishing a counterfeit APK that could phish your Google " +
                "account. If sign-in fails right after install, the most common " +
                "cause is an APK signed with a debug or third-party keystore.",
        "What happens if I run out of credits?" to
            "Two things, and they're independent. (1) AI upscale options just " +
                "become unavailable in the modal — the buttons turn into 'Get more " +
                "credits' prompts. Free upscale and PDF generation keep working. " +
                "(2) If you also have cloud storage on a paid retention tier, your " +
                "stored PDFs enter a 30-day grace period: we email a warning, and " +
                "after 30 days the cloud copies are deleted. Local copies and " +
                "history entries are not affected.",
        "Why do PDFs take ~30s to generate?" to
            "Each tile is rendered separately at the target paper size, with the " +
                "image cropped and rescaled per-tile. A 4×4 grid means 16 image " +
                "rescaling operations, each followed by PDF page composition with " +
                "rulers, labels, crop marks, and the brand footer. We could cache " +
                "intermediate results, but the per-tile rescale is the honest way " +
                "to keep printed pixels sharp. Larger posters take longer.",
        "Can I print at a non-standard paper size?" to
            "Yes — pick 'Custom' in the paper-size selector. You'll get two " +
                "additional fields for paper width and height. Use this if your " +
                "printer takes A5, B5, photo paper, or any size we don't list. The " +
                "tile math works the same way; we just trust the dimensions you type.",
        "What if I want my data deleted?" to
            "Three paths, depending on scope. Local-only: Settings → Reset to " +
                "Defaults wipes preferences and resets the app on this device. " +
                "Per-poster cloud copy: open History, find the row, tap the cloud-" +
                "off (red) icon. Account-wide deletion (auth record + all Firestore " +
                "history + all cloud-stored PDFs): email support@joeputin.com from " +
                "the address tied to your Google sign-in and we'll process the " +
                "request manually within 7 days.",
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "FAQ",
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            faqs.forEachIndexed { index, (q, a) ->
                FaqRow(question = q, answer = a, defaultExpanded = index == 0)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FaqRow(question: String, answer: String, defaultExpanded: Boolean) {
    var expanded by remember { mutableStateOf(defaultExpanded) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    question,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Text(answer, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
