package com.pdfposter.ui.components.preview

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate

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
