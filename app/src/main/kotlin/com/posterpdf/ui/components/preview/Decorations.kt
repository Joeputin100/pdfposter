package com.posterpdf.ui.components.preview

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlin.math.sin

/**
 * Translucent scotch-tape strip across a seam.
 * (centerX, centerY) is the seam center; (length, height) sets the strip size.
 * Slight rotation jitters in/out of perpendicular for organic feel.
 *
 * appearT 0..1 — how "stuck" the tape is (0 = invisible, 1 = fully landed).
 */
fun DrawScope.drawScotchTape(
    centerX: Float,
    centerY: Float,
    length: Float,
    height: Float,
    rotationDegrees: Float,
    appearT: Float,
) {
    if (appearT <= 0f) return
    val a = appearT.coerceIn(0f, 1f)

    rotate(degrees = rotationDegrees, pivot = Offset(centerX, centerY)) {
        // Tape body — translucent yellowish cream
        val body = Color(0xFFFFEFB5).copy(alpha = 0.55f * a)
        drawRoundRect(
            color = body,
            topLeft = Offset(centerX - length / 2f, centerY - height / 2f),
            size = Size(length, height),
            cornerRadius = CornerRadius(2f, 2f),
        )
        // Subtle adhesive shine — top highlight
        val shine = Color.White.copy(alpha = 0.22f * a)
        drawRect(
            color = shine,
            topLeft = Offset(centerX - length / 2f, centerY - height / 2f),
            size = Size(length, height * 0.30f),
        )
        // Edge shadow — short outer rim under the tape
        val edge = Color.Black.copy(alpha = 0.18f * a)
        drawRoundRect(
            color = edge,
            topLeft = Offset(centerX - length / 2f, centerY + height / 2f - 1f),
            size = Size(length, 2f),
            cornerRadius = CornerRadius(1f, 1f),
        )
    }
}

/**
 * Single thumb tack with a metallic dome.
 * (cx, cy) is the tack center; tackRadius is the head radius.
 * dropT is 0..1: 0 = tack hovers above, 1 = tack settled on workbench.
 * A small bounce overshoots near dropT≈0.85.
 */
fun DrawScope.drawThumbTack(
    cx: Float,
    cy: Float,
    tackRadius: Float,
    dropT: Float,
) {
    if (dropT <= 0f) return
    val t = dropT.coerceIn(0f, 1f)

    val hoverY = (1f - t) * (-32f) // start 32px above
    val bounce = if (t > 0.7f) {
        val b = (t - 0.7f) / 0.3f
        // damped sine for one-bounce overshoot
        kotlin.math.sin(b * Math.PI).toFloat() * 4f * (1f - b)
    } else 0f
    val drawCx = cx
    val drawCy = cy + hoverY + bounce

    // Cast shadow under the tack (only fully visible at dropT == 1)
    val shadowAlpha = (t * t) * 0.42f
    drawCircle(
        color = Color.Black.copy(alpha = shadowAlpha),
        radius = tackRadius * 1.3f,
        center = Offset(drawCx + 2f, drawCy + tackRadius + 4f),
    )

    // Outer rim — dark crimson
    drawCircle(
        color = Color(0xFFA71F22),
        radius = tackRadius,
        center = Offset(drawCx, drawCy),
    )
    // Inner dome — bright crimson
    drawCircle(
        color = Color(0xFFD9322A),
        radius = tackRadius * 0.78f,
        center = Offset(drawCx - tackRadius * 0.08f, drawCy - tackRadius * 0.08f),
    )
    // Specular highlight — top-left
    drawCircle(
        color = Color.White.copy(alpha = 0.78f),
        radius = tackRadius * 0.22f,
        center = Offset(drawCx - tackRadius * 0.30f, drawCy - tackRadius * 0.32f),
    )
    // Pin shadow line on the workbench (faint, suggests the pin behind)
    drawLine(
        color = Color.Black.copy(alpha = 0.22f * t),
        start = Offset(drawCx, drawCy + tackRadius * 0.6f),
        end = Offset(drawCx + 1f, drawCy + tackRadius * 1.6f),
        strokeWidth = 1.4f,
    )
}

/* ────────────────────────────────────────────────────────────────────────── *
 *  H-P1.8 props: printer, scissors, dust puff.
 * ────────────────────────────────────────────────────────────────────────── */

/**
 * AGSL shader that draws an animated dot-matrix ink/print-head streak across
 * the printer's paper-out slot. iTime drives a horizontal scan with bright
 * leading edge + faint trailing dot trail, suggesting active printing.
 *
 * Used only on API 33+; callers fall back to a flat ink color on older devices.
 */
const val PRINTER_INK_AGSL = """
uniform float2 iResolution;
uniform float iTime;

float hash11(float n) { return fract(sin(n) * 43758.5453123); }

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution.xy;
    // Scanning print-head, sweeping left-to-right, wrapping.
    float head = fract(iTime * 0.9);
    float dx = uv.x - head;
    // Bright leading edge with exponential trail behind it.
    float trail = exp(-max(0.0, -dx) * 18.0);
    float lead = exp(-abs(dx) * 36.0);
    // Dot-matrix grain — small high-frequency dots at the head.
    float dot = step(0.55, hash11(floor(uv.x * 80.0) + floor(iTime * 6.0))) *
                step(0.55, hash11(floor(uv.y * 12.0) * 7.13));
    float ink = clamp(lead + trail * 0.55 + dot * trail * 0.6, 0.0, 1.0);
    half3 inkColor = half3(0.05, 0.04, 0.07);
    half3 paper = half3(0.97, 0.96, 0.92);
    half3 col = mix(paper, inkColor, half(ink));
    return half4(col, half(ink * 0.85 + 0.10));
}
"""

/**
 * AGSL shader that draws a quick dust puff — soft radial cloud with FBM noise,
 * fading out as iTime → 1. Used at the moment the paper stack lands on the
 * desk. iTime is the puff's local 0..1 progress.
 *
 * Caller must gate to API 33+.
 */
const val DUST_PUFF_AGSL = """
uniform float2 iResolution;
uniform float iTime;

float h2(float2 p) {
    p = fract(p * float2(127.1, 311.7));
    p += dot(p, p + 19.19);
    return fract(p.x * p.y);
}
float n2(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = h2(i);
    float b = h2(i + float2(1.0, 0.0));
    float c = h2(i + float2(0.0, 1.0));
    float d = h2(i + float2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution.xy - 0.5;
    float r = length(uv);
    float expand = 0.18 + iTime * 0.42;
    float radial = smoothstep(expand, expand * 0.55, r);
    float puff = n2(uv * 6.0 + float2(iTime * 1.4, -iTime * 0.7)) * 0.55 +
                 n2(uv * 14.0) * 0.30;
    float a = radial * puff * (1.0 - iTime);
    half3 dust = half3(0.74, 0.66, 0.55);
    return half4(dust, half(a * 0.60));
}
"""

/**
 * Dot-matrix-style printer body sitting at the top of the canvas. Pages emerge
 * from the front-facing slot during the Printing phase. The printer fades in
 * at Printing-start, holds, then fades out as Stacking begins.
 *
 * (cx, topY) is the centerline + top of the printer body.
 * width drives overall scale; appearT 0..1 is the body's alpha.
 * inkScanT 0..1 advances the AGSL ink streak when present (API 33+).
 */
fun DrawScope.drawPrinter(
    cx: Float,
    topY: Float,
    width: Float,
    appearT: Float,
    inkScanT: Float,
    inkShader: RuntimeShader? = null,
) {
    if (appearT <= 0f) return
    val a = appearT.coerceIn(0f, 1f)
    val bodyH = width * 0.55f
    val left = cx - width / 2f

    // Soft drop-shadow under the printer.
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.28f * a),
        topLeft = Offset(left + 4f, topY + 6f),
        size = Size(width, bodyH),
        cornerRadius = CornerRadius(10f, 10f),
    )
    // Body — beige plastic.
    drawRoundRect(
        color = Color(0xFFD9D2C2).copy(alpha = a),
        topLeft = Offset(left, topY),
        size = Size(width, bodyH),
        cornerRadius = CornerRadius(10f, 10f),
    )
    // Top vent slats.
    val ventY = topY + bodyH * 0.18f
    for (i in 0 until 6) {
        val vx = left + width * 0.18f + i * (width * 0.10f)
        drawRoundRect(
            color = Color(0xFF8C8576).copy(alpha = 0.85f * a),
            topLeft = Offset(vx, ventY),
            size = Size(width * 0.07f, bodyH * 0.08f),
            cornerRadius = CornerRadius(2f, 2f),
        )
    }
    // Status LED — pulses while printing.
    val ledPulse = 0.55f + 0.45f * sin(inkScanT * Math.PI.toFloat() * 6f)
    drawCircle(
        color = Color(0xFF21C36D).copy(alpha = a * ledPulse),
        radius = width * 0.022f,
        center = Offset(left + width * 0.86f, topY + bodyH * 0.22f),
    )
    // Paper-out slot — wide horizontal opening near the bottom front.
    val slotY = topY + bodyH * 0.62f
    val slotH = bodyH * 0.18f
    val slotInset = width * 0.10f
    drawRoundRect(
        color = Color(0xFF1A1814).copy(alpha = a),
        topLeft = Offset(left + slotInset, slotY),
        size = Size(width - 2f * slotInset, slotH),
        cornerRadius = CornerRadius(3f, 3f),
    )
    // Animated ink-streak inside the slot (AGSL on 33+, flat fallback below).
    if (inkShader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        drawInkStreakAGSL(
            inkShader,
            left = left + slotInset + 4f,
            top = slotY + 2f,
            width = width - 2f * slotInset - 8f,
            height = slotH - 4f,
            scanT = inkScanT,
            alpha = a,
        )
    } else {
        // Fallback: tiny moving ink dot, no AGSL.
        val headX = left + slotInset + 6f +
            (width - 2f * slotInset - 12f) * inkScanT.coerceIn(0f, 1f)
        drawCircle(
            color = Color(0xFFEFE8D6).copy(alpha = a * 0.85f),
            radius = slotH * 0.20f,
            center = Offset(headX, slotY + slotH / 2f),
        )
    }
    // Front control-panel highlight strip.
    drawRoundRect(
        color = Color(0xFFB8B0A0).copy(alpha = 0.85f * a),
        topLeft = Offset(left + width * 0.06f, topY + bodyH * 0.40f),
        size = Size(width * 0.88f, bodyH * 0.08f),
        cornerRadius = CornerRadius(2f, 2f),
    )
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.drawInkStreakAGSL(
    shader: RuntimeShader,
    left: Float, top: Float,
    width: Float, height: Float,
    scanT: Float,
    alpha: Float,
) {
    if (width <= 0f || height <= 0f) return
    shader.setFloatUniform("iResolution", width, height)
    shader.setFloatUniform("iTime", scanT)
    val brush = ShaderBrush(shader)
    translate(left = left, top = top) {
        drawRect(
            brush = brush,
            topLeft = Offset.Zero,
            size = Size(width, height),
            alpha = alpha,
        )
    }
}

/**
 * Scissors emoji rendered via native canvas, positioned at (cx, cy) and
 * rotated. Used during Cutting phase as the sweeping prop. Native text draw
 * keeps it simple and emoji-color-aware on every device.
 */
fun DrawScope.drawScissors(
    cx: Float,
    cy: Float,
    sizePx: Float,
    rotationDegrees: Float,
    alpha: Float = 1f,
) {
    if (alpha <= 0f) return
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        val checkpoint = nc.save()
        nc.rotate(rotationDegrees, cx, cy)
        val paint = Paint().apply {
            this.textSize = sizePx
            this.isAntiAlias = true
            this.textAlign = Paint.Align.CENTER
            this.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        }
        // Vertical baseline correction so the emoji sits visually centered.
        val baseline = cy - (paint.descent() + paint.ascent()) / 2f
        nc.drawText("✂", cx, baseline, paint)
        nc.restoreToCount(checkpoint)
    }
}

/**
 * One-shot AGSL dust puff at (cx, cy). Renders into a square bbox of [size] px.
 * t is the puff's 0..1 lifetime (0 = just landed, 1 = fully dispersed).
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun DrawScope.drawDustPuff(
    shader: RuntimeShader,
    cx: Float,
    cy: Float,
    size: Float,
    t: Float,
) {
    if (t <= 0f || t >= 1f) return
    shader.setFloatUniform("iResolution", size, size)
    shader.setFloatUniform("iTime", t.coerceIn(0f, 1f))
    val brush = ShaderBrush(shader)
    translate(left = cx - size / 2f, top = cy - size / 2f) {
        drawRect(
            brush = brush,
            topLeft = Offset.Zero,
            size = Size(size, size),
        )
    }
}
