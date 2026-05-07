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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.posterpdf.R

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
                        stringResource(R.string.help_screen_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.help_back_cd))
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
                title = stringResource(R.string.help_paper_sizes_title_inline),
                body = stringResource(R.string.help_paper_sizes_body_inline),
            )
            HelpTopic(
                title = stringResource(R.string.help_low_dpi_title_inline),
                body = stringResource(R.string.help_low_dpi_body_inline),
            )
            HelpTopic(
                title = stringResource(R.string.help_upscale_title_inline),
                body = stringResource(R.string.help_upscale_body_inline),
            )
            HelpTopic(
                title = stringResource(R.string.help_signin_title_inline),
                body = stringResource(R.string.help_signin_body_inline),
            )
            HelpTopic(
                title = stringResource(R.string.help_history_title_inline),
                body = stringResource(R.string.help_history_body_inline),
            )
            HelpTopic(
                title = stringResource(R.string.help_sharing_title_inline),
                body = stringResource(R.string.help_sharing_body_inline),
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
