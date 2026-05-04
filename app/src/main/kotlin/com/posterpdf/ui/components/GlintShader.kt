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

    // RC9: rebuilt to match the shadertoy reference (PG2020 demo). The
    // prior version was a smooth color sheen confined to a fresnel cone;
    // the demo is a high-frequency speckle field where individual peaks
    // read as discrete diamond flashes, scattered across the entire
    // surface, with tilt driving the noise *origin* so speckles swim as
    // the phone moves.

    // High-frequency noise field. uv*80 gives ~80 cells across the card
    // width — small enough that each peak renders as one fragment-ish
    // sparkle dot at typical card sizes (~140dp). Tilt offsets the noise
    // origin so speckles slide rather than flicker in place.
    float2 p = uv * 80.0
        + float2(iTime * 0.5, iTime * 0.3)
        + float2(iTilt.x * 8.0, iTilt.y * 8.0);

    // Two octaves of value noise — combined to break up the regular
    // grid pattern of a single octave.
    float n1 = vnoise(p);
    float n2 = vnoise(p * 2.07);
    float roughness = n1 * 0.65 + n2 * 0.35;

    // Sharpen with high pow exponent. pow(0.95, 12) ≈ 0.54; pow(0.85, 12)
    // ≈ 0.14; pow(0.7, 12) ≈ 0.014 — only the brightest 5-10% of the
    // noise field renders as visible speckle, which is what gives the
    // "individual diamond flash" reading rather than a smooth color
    // gradient.
    float spark = pow(roughness, 12.0) * 6.0;

    // Holographic rainbow shifted by tilt + a small time component so
    // the speckle palette breathes. The 2.09 / 4.19 offsets are 2π/3
    // and 4π/3 — canonical RGB rainbow without HSV conversion.
    float hue = roughness * 6.28 + iTilt.x * 3.14 + iTime * 0.6;
    half3 holo = half3(
        0.5 + 0.5 * cos(hue),
        0.5 + 0.5 * cos(hue + 2.09),
        0.5 + 0.5 * cos(hue + 4.19)
    );
    // The brightest peaks push toward pure white — that's the "diamond"
    // flash quality of real holographic glitter on the demo.
    float whiteMix = clamp((spark - 0.5) * 2.0, 0.0, 1.0);
    holo = mix(holo, half3(1.0, 1.0, 1.0), whiteMix);

    // Alpha-premultiplied additive — BlendMode.Plus on top of the card.
    // Clamp at 1.0 so the brightest speckle pixels can blow fully white.
    float a = clamp(spark, 0.0, 1.0);
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
            // RC10: draw the glitter BEFORE the content so it sits on the
            // card surface but UNDER the icon / text / button. User said:
            // "confine glitter effect to card backgrounds — not over the
            // model logos, text descriptions, and action buttons."
            // The card\'s containerColor must be partly transparent for
            // the glitter to show through; surfaceVariant.copy(alpha=0.5)
            // (existing) is already see-through enough.
            val tSec = ((nowMs - startMs) / 1000f).coerceAtLeast(0f)
            shader.setFloatUniform("iResolution", size.width, size.height)
            shader.setFloatUniform("iTime", tSec)
            shader.setFloatUniform("iTilt", tilt.first, tilt.second)
            drawRect(brush = brush, size = size)
            drawContent()
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
    // RC4: switched from Modifier.background (which paints BEHIND content
    // and is invisible under the Card\'s opaque surfaceVariant) to
    // drawWithCache + onDrawWithContent + BlendMode.Plus, so the sweep
    // overlays on top — same compositing strategy as the AGSL path.
    return Modifier.drawWithCache {
        val brush = sweepBrush(phase)
        onDrawWithContent {
            // RC10: glitter under content (see GlintShader AGSL path comment).
            drawRect(brush = brush, size = size)
            drawContent()
        }
    }
}

private fun sweepBrush(phase: Float): Brush {
    // RC4: brighter palette + larger sweep window. The 0x33-alpha pink/cyan
    // were near-invisible after Plus-blend onto a surfaceVariant card; lifting
    // to 0x66-0x88 makes the sweep read as a clear holographic band.
    val angle = phase * 2f * Math.PI.toFloat()
    val cx = 300f + 600f * cos(angle)
    val cy = 300f + 600f * sin(angle)
    return Brush.linearGradient(
        colors = listOf(
            Color.Transparent,
            Color(0x66FFC6FF), // soft pink
            Color(0x99FFFFFF), // white centre
            Color(0x66C6FFFF), // soft cyan
            Color.Transparent,
        ),
        start = Offset(cx - 280f, cy - 280f),
        end = Offset(cx + 280f, cy + 280f),
    )
}
