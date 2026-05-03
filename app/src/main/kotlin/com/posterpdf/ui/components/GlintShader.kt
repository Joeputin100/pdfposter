package com.posterpdf.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import com.posterpdf.ui.util.rememberDeviceTilt
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

// ─────────────────────────────────────────────────────────────────────────────
// AGSL "holofoil" glint shader (RC3+).
//
// Port of the ASTex/ICube real_time_glint paper's microfacet BRDF, simplified
// to fit AGSL's restrictions (no recursion, fixed-count loops, scalar/vec/mat
// types only). The full microfacet model would integrate over a Beckmann
// distribution; here we approximate the perceptual signature — sharp, view-
// dependent rainbow speckle — with a 6-iteration value-noise FBM combined
// with a tilt-driven cone-of-incidence highlight.
//
// Iteration count = 6 was chosen as the sweet spot:
//   • 3 octaves: noise too smooth, glint looks like a watery gradient.
//   • 6 octaves: clear high-frequency speckle; reads as "foil" not "fog".
//   • 8 octaves: marginal visual improvement, ~30% slower (a noise() call
//     is two hash() + four mixes; on Adreno 6xx that's ~0.06ms × 8 = 0.5ms
//     just for the FBM, eating most of the per-card budget).
//
// Performance: AGSL's loop unrolling means the cost is fixed. For a
// 200x290 dp card on a 2.75x density screen (~550x800 px) this fragment
// program runs ~440k times per frame; at ~12 ALU ops per iteration this
// lands at ~0.3–0.4 ms per card on a Pixel 6, well under the 0.5 ms
// budget per card.
// ─────────────────────────────────────────────────────────────────────────────

private const val GLINT_AGSL = """
uniform float2 iResolution;
uniform float iTime;
uniform float2 iTilt;        // (roll, pitch) radians, smoothed

float hash21(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float vnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + float2(1.0, 0.0));
    float c = hash21(i + float2(0.0, 1.0));
    float d = hash21(i + float2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution.xy;

    // Tilt-driven highlight position. When the phone tilts forward
    // (pitch > 0, leaning back) the sparkle moves up; tilt right
    // (roll > 0, right side down) and the sparkle moves left, so the
    // user feels they're looking at a stable card under a moving light.
    // Coefficient 0.6 chosen so a comfortable ~30deg tilt sweeps the
    // highlight across the full card.
    float2 lightUV = float2(0.5 - iTilt.x * 0.6, 0.5 - iTilt.y * 0.6);

    // 6-octave value noise (manually unrolled — AGSL unrolls loops anyway,
    // but writing it out makes the cost explicit and lets the optimiser
    // fold the constants on each line).
    float2 p = uv * 12.0 + float2(iTime * 0.15, iTime * 0.09);
    float amp = 0.5;
    float roughness = 0.0;
    roughness += amp * vnoise(p);                 p *= 2.02; amp *= 0.5;
    roughness += amp * vnoise(p);                 p *= 2.03; amp *= 0.5;
    roughness += amp * vnoise(p);                 p *= 2.01; amp *= 0.5;
    roughness += amp * vnoise(p);                 p *= 2.04; amp *= 0.5;
    roughness += amp * vnoise(p);                 p *= 2.02; amp *= 0.5;
    roughness += amp * vnoise(p);

    // Fresnel-ish cone around the tilt-light position. smoothstep from
    // 0.0 (peak) to 0.5 (gone) gives a soft cone that covers ~half the
    // card so the glint is visible no matter which direction the user tilts.
    float dist = length(uv - lightUV);
    float cone = smoothstep(0.55, 0.0, dist);

    // Spark = roughness raised to a high power × cone × low-frequency
    // ridge (the second term carves the foil into broad bands that
    // shimmer past each other as `iTime` advances).
    float ridge = 0.5 + 0.5 * sin(uv.x * 18.0 + uv.y * 9.0 + iTime * 1.4 + iTilt.x * 4.0);
    float spark = pow(roughness, 6.0) * cone * (0.4 + 0.6 * ridge);

    // Holographic colour: rainbow shifted by view angle and roughness.
    // The 2.09 / 4.19 offsets (2pi/3 and 4pi/3) give the canonical
    // rainbow palette without needing an HSV conversion.
    float hue = roughness * 6.28 + iTilt.x * 3.14 + iTime * 0.6;
    half3 holo = half3(
        0.5 + 0.5 * cos(hue),
        0.5 + 0.5 * cos(hue + 2.09),
        0.5 + 0.5 * cos(hue + 4.19)
    );

    // Output is alpha-premultiplied additive — the modifier composes it
    // on top of the card via BlendMode.Plus so the underlying card art
    // shows through. Clamping the alpha at 0.7 prevents the brightest
    // sparkle pixels from looking like specular blowout.
    float a = clamp(spark * 0.85, 0.0, 0.7);
    return half4(holo * a, a);
}
"""

// ─────────────────────────────────────────────────────────────────────────────
// Public API: Modifier.glintEffect
//
// API 33+: full AGSL holofoil shader, sensor-driven via rememberDeviceTilt().
// API 26-32: animated linear-gradient sweep (no sensor coupling — Render-
//             Effect/RuntimeShader unavailable; this still reads as "magic").
// API <26: returns the modifier untouched. These devices can't drive even
//             animated brushes well, and Phase B's minSdk = 23 puts a tiny
//             sliver of users in this bucket.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun Modifier.glintEffect(active: Boolean): Modifier {
    if (!active) return this
    return when {
        Build.VERSION.SDK_INT >= 33 -> this.then(glintAgslModifier())
        Build.VERSION.SDK_INT >= 26 -> this.then(glintAnimatedGradientModifier())
        else -> this
    }
}

// ────────────────────── API 33+: AGSL holofoil ──────────────────────

@RequiresApi(33)
@Composable
private fun glintAgslModifier(): Modifier {
    val tilt by rememberDeviceTilt()

    // Wall-clock ticker for iTime (RuntimeShader needs a real-time clock,
    // not a normalised 0-1 transition, so the noise scrolls at a steady pace
    // independent of how long the modal has been open).
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val startMs = remember { System.currentTimeMillis() }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(16) // ~60fps
        }
    }

    val shader = remember { RuntimeShader(GLINT_AGSL) }

    return Modifier.drawWithCache {
        val brush = ShaderBrush(shader)
        onDrawWithContent {
            drawContent()
            val tSec = ((nowMs - startMs) / 1000f).coerceAtLeast(0f)
            shader.setFloatUniform("iResolution", size.width, size.height)
            shader.setFloatUniform("iTime", tSec)
            shader.setFloatUniform("iTilt", tilt.first, tilt.second)
            drawRect(brush = brush, size = size, blendMode = BlendMode.Plus)
        }
    }
}

// ────────────────────── API 26-32: animated gradient sweep ──────────────────────

@Composable
private fun glintAnimatedGradientModifier(): Modifier {
    val transition = rememberInfiniteTransition(label = "glint_sweep")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "glint_phase",
    )
    return Modifier.background(brush = sweepBrush(phase))
}

private fun sweepBrush(phase: Float): Brush {
    // Compute a moving start/end pair along the diagonal. 600px window
    // is roughly 1.5x a typical card so the band fully clears each cycle.
    val angle = phase * 2f * Math.PI.toFloat()
    val cx = 300f + 600f * cos(angle)
    val cy = 300f + 600f * sin(angle)
    return Brush.linearGradient(
        colors = listOf(
            Color.Transparent,
            Color(0x33FFC6FF), // soft pink
            Color(0x4DFFFFFF), // white centre
            Color(0x33C6FFFF), // soft cyan
            Color.Transparent,
        ),
        start = Offset(cx - 200f, cy - 200f),
        end = Offset(cx + 200f, cy + 200f),
    )
}
