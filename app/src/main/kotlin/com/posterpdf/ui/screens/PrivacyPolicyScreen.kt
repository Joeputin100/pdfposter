package com.posterpdf.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * H-P2.4 — Privacy Policy screen.
 *
 * Honest disclosure of every data collection point. Reflects the actual
 * implementation: anonymous Firebase Auth UID at first launch, Google email
 * iff user signs in, Firestore /users/{uid}/history docs (metadata only),
 * Cloud Storage user-pdfs/{uid}/ blobs (only if user opts into retention),
 * and FAL.ai for AI upscale (their TOS governs FAL-side retention).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Privacy Policy",
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
            Text(
                "Effective: 2026-05-03. We try to keep this short and accurate. " +
                    "If anything below doesn't match what the app actually does, " +
                    "treat that as a bug and email support@joeputin.com.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section(
                title = "What we collect at first launch",
                body = "An anonymous Firebase Auth UID — a random opaque string with " +
                    "no personally identifying information. This is what lets your " +
                    "history persist across app restarts on this device.",
            )
            Section(
                title = "What we collect if you sign in with Google",
                body = "Your Google account name and email address. We use these to " +
                    "show you who's signed in, sync your history across devices, and " +
                    "process account-wide deletion requests. We do NOT receive your " +
                    "Google password, contacts, calendar, drive, or other Google data.",
            )
            Section(
                title = "History metadata (Firestore)",
                body = "When you generate a poster, we record: poster width and height, " +
                    "paper size, orientation, margin, overlap, output DPI, page count, " +
                    "and a creation timestamp. This goes to Firestore at " +
                    "/users/{uid}/history. We do NOT store the source image bytes or " +
                    "any pixel data in Firestore.",
            )
            Section(
                title = "PDF blobs (Cloud Storage) — opt-in",
                body = "If you opt into cloud retention (Settings → Cloud storage…), " +
                    "we upload generated PDFs to Cloud Storage at gs://(project)/" +
                    "user-pdfs/{uid}/. The default retention is 30 days; paid tiers " +
                    "extend that. The default Settings choice is local-only — nothing " +
                    "leaves your device unless you opt in.",
            )
            Section(
                title = "AI upscale (FAL.ai)",
                body = "When you tap an AI upscale option (Topaz, Recraft, AuraSR, " +
                    "ESRGAN, Magnific), the source image is sent to FAL.ai for " +
                    "processing, and the upscaled result is returned. FAL.ai retains " +
                    "the input and output for the period set in their TOS — typically " +
                    "minutes to hours, but read their policy directly:",
            )
            ClickableLine(
                "https://fal.ai/legal/privacy",
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://fal.ai/legal/privacy"))
                    context.startActivity(intent)
                },
            )

            Section(
                title = "Retention",
                body = "Set by your Settings → Cloud storage… choice. Free tier: 30 " +
                    "days. Paid tiers: 90 days, 1 year, or indefinite. History " +
                    "metadata in Firestore follows the same retention. Local files " +
                    "and local history stay on your device until you uninstall or " +
                    "tap Reset to Defaults.",
            )
            Section(
                title = "Deletion paths",
                body = "Local data: Settings → Reset to Defaults wipes preferences and " +
                    "the on-device history index. Per-poster cloud copy: open History, " +
                    "tap the red cloud-off button on the row. Account-wide (auth + all " +
                    "Firestore history + all cloud PDFs): email support@joeputin.com " +
                    "from the address tied to your Google sign-in. We process within " +
                    "7 days.",
            )
            Section(
                title = "Third-party services",
                body = "Firebase Auth, Cloud Firestore, Cloud Storage (all Google " +
                    "Cloud); FAL.ai for AI upscale; Google Play Billing for credit " +
                    "purchases. We do not run any analytics or advertising SDK. We " +
                    "have no access to other apps on your phone.",
            )
            Section(
                title = "Contact",
                body = "support@joeputin.com for any privacy question or deletion " +
                    "request. Source code is public at " +
                    "github.com/Joeputin/pdfposter — what you see in this policy " +
                    "matches what's in the code.",
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String, body: String) {
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

@Composable
private fun ClickableLine(text: String, onClick: () -> Unit) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
}
