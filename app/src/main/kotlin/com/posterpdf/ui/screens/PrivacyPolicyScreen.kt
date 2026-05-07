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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.posterpdf.R

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
                        stringResource(R.string.privacy_screen_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.privacy_back_cd))
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
                stringResource(R.string.privacy_intro_inline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section(
                title = stringResource(R.string.privacy_first_launch_title),
                body = stringResource(R.string.privacy_first_launch_body),
            )
            Section(
                title = stringResource(R.string.privacy_google_title),
                body = stringResource(R.string.privacy_google_body),
            )
            Section(
                title = stringResource(R.string.privacy_firestore_title),
                body = stringResource(R.string.privacy_firestore_body),
            )
            Section(
                title = stringResource(R.string.privacy_storage_title),
                body = stringResource(R.string.privacy_storage_body),
            )
            Section(
                title = stringResource(R.string.privacy_fal_title),
                body = stringResource(R.string.privacy_fal_body),
            )
            ClickableLine(
                "https://fal.ai/legal/privacy",
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://fal.ai/legal/privacy"))
                    context.startActivity(intent)
                },
            )

            Section(
                title = stringResource(R.string.privacy_retention_title),
                body = stringResource(R.string.privacy_retention_body),
            )
            Section(
                title = stringResource(R.string.privacy_deletion_title),
                body = stringResource(R.string.privacy_deletion_body),
            )
            Section(
                title = stringResource(R.string.privacy_third_party_title),
                body = stringResource(R.string.privacy_third_party_body),
            )
            Section(
                title = stringResource(R.string.privacy_contact_title),
                body = stringResource(R.string.privacy_contact_body),
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
