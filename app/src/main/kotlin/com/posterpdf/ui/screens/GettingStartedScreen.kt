package com.posterpdf.ui.screens

import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.posterpdf.R

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
                        stringResource(R.string.gs_screen_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.gs_back_cd))
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
                stringResource(R.string.gs_intro_body),
                style = MaterialTheme.typography.bodyMedium,
            )

            SectionHeader(stringResource(R.string.gs_section_what_you_get_for_free))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FreeFeature(Icons.Default.WorkspacePremium, stringResource(R.string.gs_feat_no_catch_title), stringResource(R.string.gs_feat_no_catch_body))
                    FreeFeature(Icons.Default.PictureAsPdf, stringResource(R.string.gs_feat_poster_title), stringResource(R.string.gs_feat_poster_body))
                    FreeFeature(Icons.Default.CheckCircle, stringResource(R.string.gs_feat_paper_title), stringResource(R.string.gs_feat_paper_body))
                    FreeFeature(Icons.Default.AutoAwesome, stringResource(R.string.gs_feat_upscale_title), stringResource(R.string.gs_feat_upscale_body))
                    FreeFeature(Icons.Default.SdStorage, stringResource(R.string.gs_feat_storage_title), stringResource(R.string.gs_feat_storage_body))
                    FreeFeature(Icons.Default.History, stringResource(R.string.gs_feat_history_title), stringResource(R.string.gs_feat_history_body))
                }
            }
            Text(
                stringResource(R.string.gs_paid_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // RC37: 4-step tour. Each card embeds a 9:16 screen recording on
            // the left of the text. Videos auto-play, loop, and are silenced
            // (MediaPlayer.setVolume(0,0) on prepare). Pre-RC37 the cards had
            // text-only "[screenshot: …]" placeholders.
            SectionHeader(stringResource(R.string.gs_section_4_step_tour))
            TourStep(
                number = 1,
                title = stringResource(R.string.gs_tour_pick_image_title),
                body = stringResource(R.string.gs_tour_pick_image_body),
                videoRes = R.raw.gs_select_image,
            )
            TourStep(
                number = 2,
                title = stringResource(R.string.gs_tour_set_size_title),
                body = stringResource(R.string.gs_tour_set_size_body),
                videoRes = R.raw.gs_set_size,
            )
            TourStep(
                number = 3,
                title = stringResource(R.string.gs_tour_generate_title),
                body = stringResource(R.string.gs_tour_generate_body),
                videoRes = R.raw.gs_generate_pdf,
            )
            TourStep(
                number = 4,
                title = stringResource(R.string.gs_tour_upscale_title),
                body = stringResource(R.string.gs_tour_upscale_body),
                videoRes = R.raw.gs_upscale,
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
private fun TourStep(number: Int, title: String, body: String, videoRes: Int) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // RC37: video on the left (9:16, 120dp wide → ~213dp tall), step
        // content on the right. The video host is a VideoView wrapped in
        // AndroidView so we can hook setOnPreparedListener for autoplay /
        // loop / mute without bringing ExoPlayer into the tour-only screen.
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            LoopingMutedVideo(
                videoRes = videoRes,
                modifier = Modifier
                    .width(120.dp)
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
            }
        }
    }
}

/**
 * Auto-playing, looping, muted video host. Uses VideoView so we don't have
 * to add ExoPlayer just for the tour screen. The MediaPlayer's volume is
 * zeroed in onPrepared (the recordings already have no audio track, but
 * this keeps the contract explicit if a recording is later swapped for one
 * that does).
 */
@Composable
private fun LoopingMutedVideo(videoRes: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uri = "android.resource://${context.packageName}/$videoRes"
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoURI(Uri.parse(uri))
                setOnPreparedListener { mp: MediaPlayer ->
                    mp.isLooping = true
                    mp.setVolume(0f, 0f)
                    start()
                }
            }
        },
    )
}
