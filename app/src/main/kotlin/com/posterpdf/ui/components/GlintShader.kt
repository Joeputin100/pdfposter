package com.posterpdf.ui.components

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.posterpdf.ui.util.rememberDeviceTilt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.coroutines.delay

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

// ────────────────────── Discrete-event sensor + timed pulse ──────────────────────

/**
 * RC22 — replaces the previous always-on infinite-repeat gradient sweep with
 * discrete pulse EVENTS. A pulse event drives a 0 → 1 → 0 alpha envelope over
 * ~700ms; between events the modifier is fully transparent.
 *
 * Pulse triggers:
 *   • Time-based: every ~10–13 s (small jitter so a wall of cards doesn't
 *     sync up into one big synchronous flash).
 *   • Sensor-based: a tilt magnitude change exceeding ~0.15 rad (≈8.5°)
 *     since the last pulse fires an immediate event. Hand-tremor is below
 *     this threshold thanks to the IIR smoothing in rememberDeviceTilt.
 *
 * Sweep direction is still phase-modulated by the tilt vector so when an
 * event fires after a tilt, the highlight crosses the surface in the
 * direction of the tilt (visually intuitive — the "shine" follows where
 * the user moved the phone).
 */
@Composable
private fun sensorPulseModifier(): Modifier {
    val tiltState = rememberDeviceTilt()
    val pulseAmplitude = remember { Animatable(0f) }
    val phaseHolder = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        // Track the tilt magnitude at the time of the most recent pulse so
        // we can fire on a meaningful CHANGE, not absolute tilt level.
        var anchorRoll = 0f
        var anchorPitch = 0f
        val tiltDeltaThreshold = 0.15f

        // Timed pulses: 10–13s with a per-tick rerandomized interval so
        // multiple cards on screen don't collapse into one synchronous flash.
        var nextTimedPulseMs = System.currentTimeMillis() + 1500L  // first pulse soon

        snapshotFlow { tiltState.value }.collect { (roll, pitch) ->
            val now = System.currentTimeMillis()
            val tiltDelta = hypot(roll - anchorRoll, pitch - anchorPitch)
            val timeUp = now >= nextTimedPulseMs
            val tiltUp = tiltDelta > tiltDeltaThreshold
            if (timeUp || tiltUp) {
                anchorRoll = roll
                anchorPitch = pitch
                // Phase drives the sweep direction during this pulse;
                // derive from the tilt vector so the shine "follows" tilt.
                phaseHolder.floatValue = ((roll * 0.18f + pitch * 0.07f) + 0.25f).rem(1f).let {
                    if (it < 0f) it + 1f else it
                }
                nextTimedPulseMs = now + (10_000L..13_000L).random()
                // Animate 0 → 1 (fast in, ~200ms) → 0 (slow out, ~500ms).
                pulseAmplitude.snapTo(0f)
                pulseAmplitude.animateTo(1f, tween(200, easing = LinearEasing))
                pulseAmplitude.animateTo(0f, tween(500, easing = LinearEasing))
            }
        }
    }

    // Fallback: the snapshotFlow above only ticks when tilt updates.
    // On a perfectly stationary device with no rotation sensor, the timed
    // pulse would never fire. Run a separate slow loop that nudges
    // pulseAmplitude on the same 10–13s schedule.
    LaunchedEffect(Unit) {
        while (true) {
            delay((10_000L..13_000L).random())
            // Skip if we're already animating from the sensor path.
            if (pulseAmplitude.value < 0.01f) {
                pulseAmplitude.snapTo(0f)
                pulseAmplitude.animateTo(1f, tween(200, easing = LinearEasing))
                pulseAmplitude.animateTo(0f, tween(500, easing = LinearEasing))
            }
        }
    }

    return Modifier.drawWithCache {
        onDrawWithContent {
            drawContent()
            val amp = pulseAmplitude.value
            if (amp > 0.01f) {
                drawRect(
                    brush = sweepBrush(phaseHolder.floatValue, size.width, size.height, amp),
                    size = size,
                )
            }
        }
    }
}

private fun sweepBrush(phase: Float, w: Float, h: Float, amplitude: Float = 1f): Brush {
    val angle = phase * 2f * Math.PI.toFloat()
    val maxDim = maxOf(w, h)
    val cx = w / 2f + maxDim * 0.6f * cos(angle)
    val cy = h / 2f + maxDim * 0.6f * sin(angle)
    val band = maxDim * 0.6f
    // RC22: scale the gradient stops by the pulse amplitude (0..1) so the
    // overall envelope animates from invisible to peak and back. The colour
    // hex values keep their hue but their alpha multiplies by amplitude.
    val a = amplitude.coerceIn(0f, 1f)
    fun fade(c: Long, baseAlpha: Float): Color {
        val argb = c.toInt()
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = baseAlpha * a)
    }
    return Brush.linearGradient(
        colors = listOf(
            Color.Transparent,
            fade(0xFFC6FFL, 0.4f),  // soft pink
            fade(0xFFFFFFL, 0.6f),  // white centre
            fade(0xC6FFFFL, 0.4f),  // soft cyan
            Color.Transparent,
        ),
        start = Offset(cx - band, cy - band),
        end = Offset(cx + band, cy + band),
    )
}
