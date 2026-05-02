package com.pdfposter.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * Phases of an AI-upscale request, surfaced to the user via
 * [UpscaleProgressBar]. Determinate phases (Uploading, Downloading) carry a
 * 0..1 progress fraction; indeterminate phases (Queued, Inferring) animate
 * the wavy indicator without a target.
 *
 * The on-device path uses Inferring(estimatedMsLeft) with an updating
 * countdown; the FAL path uses Inferring(null) since FAL doesn't expose
 * granular progress.
 */
sealed class UpscaleProgress {
    object Idle : UpscaleProgress()
    data class Uploading(val pct: Float) : UpscaleProgress()
    object Queued : UpscaleProgress()
    data class Inferring(val estimatedMsLeft: Long?) : UpscaleProgress()
    data class Downloading(val pct: Float) : UpscaleProgress()
    object Done : UpscaleProgress()
}

/**
 * MD3E LinearWavyProgressIndicator wired to a typed [UpscaleProgress] state.
 * Renders a stage label above the bar so users can tell uploading apart from
 * queued apart from inference.
 *
 * For [UpscaleProgress.Idle] and [UpscaleProgress.Done] the composable
 * collapses to nothing — the caller controls when it appears in the layout.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpscaleProgressBar(
    state: UpscaleProgress,
    modifier: Modifier = Modifier,
) {
    if (state is UpscaleProgress.Idle || state is UpscaleProgress.Done) return

    val label = stageLabel(state)
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
        )
        when (state) {
            is UpscaleProgress.Uploading ->
                LinearWavyProgressIndicator(
                    progress = { state.pct.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
            is UpscaleProgress.Downloading ->
                LinearWavyProgressIndicator(
                    progress = { state.pct.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
            UpscaleProgress.Queued, is UpscaleProgress.Inferring ->
                LinearWavyProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
            // Idle/Done returned early above; exhaustive `when` keeps the
            // compiler happy when new states are added.
            UpscaleProgress.Idle, UpscaleProgress.Done -> Unit
        }
        Spacer(Modifier.height(0.dp))
    }
}

private fun stageLabel(state: UpscaleProgress): String = when (state) {
    is UpscaleProgress.Uploading ->
        "Uploading… ${(state.pct * 100).toInt()}%"
    UpscaleProgress.Queued -> "Waiting in queue…"
    is UpscaleProgress.Inferring -> {
        val ms = state.estimatedMsLeft
        if (ms == null) "Upscaling…"
        else "Upscaling… ~${max(1L, ms / 1000)}s left"
    }
    is UpscaleProgress.Downloading ->
        "Downloading… ${(state.pct * 100).toInt()}%"
    UpscaleProgress.Idle -> ""
    UpscaleProgress.Done -> ""
}
