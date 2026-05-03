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
 * Seven plain-English questions covering credits, offline use, the credit-
 * exhaustion case, generation time, custom paper sizes, and data deletion.
 * Tap a row to expand its answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaqScreen(onBack: () -> Unit) {
    val faqs = listOf(
        "Why does it ask for credits?" to
            "Credits pay the AI services that sharpen your photo for big prints. " +
                "We pass that cost on to you per upscale. Everything else — making " +
                "posters, the free on-device sharpener, your history, and 30 days " +
                "of cloud backup — is free, with any photo. You only spend credits " +
                "when you tap one of the AI upscale options.",
        "Why are my credits worth less than the price I paid?" to
            "Honest answer: most of what you pay goes to the AI service doing the " +
                "actual work. The rest covers Play Store's cut, our servers, and a " +
                "small slice that funds ongoing app development. We aim for about " +
                "50% above the raw AI cost — enough to keep the lights on. Every " +
                "credit pack shows the equivalent in dollars so you always know " +
                "what you're buying.",
        "Can I use it offline?" to
            "Yes for everything you actually do — picking a photo, making a " +
                "poster, the free sharpener, and View / Save / Share all work " +
                "without internet. You need a connection only for: signing in with " +
                "Google, AI upscales, buying credits, and cloud backup. When you " +
                "reconnect, your history syncs in the background.",
        "What happens if I run out of credits?" to
            "Two things, and they're separate. (1) AI upscale options become " +
                "unavailable — the buttons turn into 'Get more credits' prompts. " +
                "Free poster-making and the free sharpener keep working. (2) If " +
                "you've paid to keep posters in cloud storage longer than 30 days, " +
                "we'll email a warning and hold them for 30 more days before " +
                "deleting. Your local files and history list never go away.",
        "Why do PDFs take about 30 seconds to make?" to
            "Each printed page is built separately at the right resolution — your " +
                "image gets cropped and resharpened for every tile, then dressed " +
                "up with rulers, page labels, crop marks, and the footer. A 4×4 " +
                "poster means 16 page builds. We could cache the work, but redoing " +
                "it each time is the most reliable way to keep edges sharp. Bigger " +
                "posters take longer.",
        "Can I print at a non-standard paper size?" to
            "Yes — pick 'Custom' in the paper-size selector. You'll get fields " +
                "for paper width and height. Use this if your printer takes A5, " +
                "B5, photo paper, or anything we don't list. The math works the " +
                "same way; we just trust the dimensions you type.",
        "What if I want my data deleted?" to
            "Three paths, depending on what you mean. Just this device: " +
                "Settings → Reset to Defaults clears your preferences and the " +
                "on-device history. One cloud-stored poster: open History, find " +
                "the row, tap the red cloud-with-line icon. Everything you've " +
                "ever done with us (your sign-in, all history, every cloud-stored " +
                "PDF): email support@joeputin.com from the address you signed in " +
                "with. We'll process within 7 days.",
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
