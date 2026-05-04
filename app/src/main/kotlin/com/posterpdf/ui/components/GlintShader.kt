package com.posterpdf.ui.components

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.posterpdf.ui.util.rememberDeviceTilt
import kotlin.math.cos
import kotlin.math.sin

// ─────────────────────────────────────────────────────────────────────────────
// Public API: Modifier.pulseEffect (and glintEffect alias).
//
// RC16: AGSL holofoil glitter removed per user feedback. The animated
// linear-gradient sweep that previously served as the API 26-32 fallback
// is now the sole effect on every API level, with sensor tilt mixed into
// the phase so phone motion modulates the highlight position. minSdk 23
// devices fall through with no effect (animated brushes were already
// expensive there).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * RC16 — pulse effect, sensor-driven. Was an A/B against the AGSL holofoil
 * glitter; user picked pulse and asked us to remove glitter entirely and
 * tie the sweep to the device tilt. The implementation now offsets the
 * sweep angle by the live tilt vector so phone motion modulates where
 * the highlight lives, instead of relying on a constant infinite-time
 * animation.
 *
 * `glintEffect` is kept as an alias for `pulseEffect` so existing call
 * sites continue to compile without churn — the visual is now identical
 * everywhere.
 */
@Composable
fun Modifier.pulseEffect(active: Boolean): Modifier {
    if (!active) return this
    return when {
        Build.VERSION.SDK_INT >= 26 -> this.then(sensorPulseModifier())
        else -> this
    }
}

@Composable
fun Modifier.glintEffect(active: Boolean): Modifier = pulseEffect(active)

// RC16 — AGSL holofoil glitter shader removed. User picked the simpler
// gradient pulse and asked for glitter to go entirely. Sensor tilt now
// drives the pulse phase (see sensorPulseModifier above).

// ────────────────────── Sensor-driven gradient pulse ──────────────────────

@Composable
private fun sensorPulseModifier(): Modifier {
    val tiltState = rememberDeviceTilt()
    val transition = rememberInfiniteTransition(label = "pulse_sweep")
    // Slow time-based phase so the sweep continues to drift even when the
    // phone is held perfectly still. Tilt offsets this phase, so a deliberate
    // tilt visibly accelerates / shifts the highlight position.
    val tPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pulse_phase",
    )
    return Modifier.drawWithCache {
        onDrawWithContent {
            val (rollRad, pitchRad) = tiltState.value
            // Combine time + tilt into a single 0..1 phase. Roll has the
            // larger coefficient so left/right tilt dominates the sweep
            // direction (matches the user's mental model: "tilting moves
            // the shine"). Pitch contributes a smaller cross-axis component.
            val phase = (tPhase + rollRad * 0.18f + pitchRad * 0.07f).rem(1f).let {
                if (it < 0f) it + 1f else it
            }
            drawRect(brush = sweepBrush(phase, size.width, size.height), size = size)
            drawContent()
        }
    }
}

private fun sweepBrush(phase: Float, w: Float, h: Float): Brush {
    val angle = phase * 2f * Math.PI.toFloat()
    val maxDim = maxOf(w, h)
    val cx = w / 2f + maxDim * 0.6f * cos(angle)
    val cy = h / 2f + maxDim * 0.6f * sin(angle)
    val band = maxDim * 0.6f
    return Brush.linearGradient(
        colors = listOf(
            Color.Transparent,
            Color(0x66FFC6FF), // soft pink
            Color(0x99FFFFFF), // white centre
            Color(0x66C6FFFF), // soft cyan
            Color.Transparent,
        ),
        start = Offset(cx - band, cy - band),
        end = Offset(cx + band, cy + band),
    )
}
