package com.posterpdf.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.posterpdf.R

/**
 * Parses "Letter (8.5x11)" style labels into (widthInches, heightInches).
 * Returns null for "Custom" or unparseable.
 */
fun parsePaperSize(label: String): Pair<Double, Double>? {
    if (label == "Custom") return null
    val inside = label.substringAfter('(', "").substringBefore(')', "")
    val parts = inside.split("x", "X")
    if (parts.size < 2) return null
    val w = parts[0].trim().toDoubleOrNull() ?: return null
    val h = parts[1].trim().toDoubleOrNull() ?: return null
    return w to h
}

/**
 * The largest supported paper dimension across the built-in list, used as the
 * "reference" size for relative scaling. A3 = 11.69 x 16.54 → longest side 16.54 in.
 */
const val REFERENCE_PAPER_LONG_SIDE = 16.54

/**
 * Draws a page rectangle inside a box of [boxSize], scaled RELATIVE to [REFERENCE_PAPER_LONG_SIDE]
 * so that smaller paper sizes look smaller and larger ones fill more of the box.
 * If [orientation] is "Portrait" page is taller than wide; "Landscape" is wider; null = natural.
 */
@Composable
fun PaperGraphic(
    widthInches: Double,
    heightInches: Double,
    boxSize: Dp = 48.dp,
    orientation: String? = null, // "Portrait" | "Landscape" | null
    showDogCow: Boolean = false,
    selected: Boolean = false,
    relativeScale: Boolean = true,
) {
    // Apply orientation override
    val (w, h) = when (orientation) {
        "Portrait" -> kotlin.math.min(widthInches, heightInches) to kotlin.math.max(widthInches, heightInches)
        "Landscape" -> kotlin.math.max(widthInches, heightInches) to kotlin.math.min(widthInches, heightInches)
        else -> widthInches to heightInches
    }

    Box(
        modifier = Modifier.size(boxSize),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(boxSize)) {
            val boxW = size.width
            val boxH = size.height
            val aspectRatio = w / h
            val longSide = kotlin.math.max(w, h)

            // Size relative to reference. Small papers (e.g. Letter 11") will render smaller
            // than large ones (e.g. A3 16.54"). Floor at 55% so smallest is still legible.
            val relativeFactor: Float = if (relativeScale) {
                (longSide / REFERENCE_PAPER_LONG_SIDE).coerceIn(0.55, 1.0).toFloat()
            } else {
                1f
            }
            // Available space is 90% of the box, further scaled by relativeFactor
            val maxW = boxW * 0.9f * relativeFactor
            val maxH = boxH * 0.9f * relativeFactor
            val drawW: Float
            val drawH: Float
            if (aspectRatio > maxW / maxH) {
                drawW = maxW
                drawH = (maxW / aspectRatio).toFloat()
            } else {
                drawH = maxH
                drawW = (maxH * aspectRatio).toFloat()
            }
            val x = (boxW - drawW) / 2f
            val y = (boxH - drawH) / 2f

            // Page fill
            drawRoundRect(
                color = if (selected) Color.White else Color.White.copy(alpha = 0.92f),
                topLeft = Offset(x, y),
                size = Size(drawW, drawH),
                cornerRadius = CornerRadius(2f, 2f)
            )
            // Page outline
            drawRoundRect(
                color = if (selected) Color(0xFF4A4A4A) else Color(0xFF8A8A8A),
                topLeft = Offset(x, y),
                size = Size(drawW, drawH),
                cornerRadius = CornerRadius(2f, 2f),
                style = Stroke(width = 1.5f)
            )
        }

        if (showDogCow) {
            Image(
                painter = painterResource(id = R.drawable.dogcow),
                contentDescription = "Orientation indicator (Clarus the DogCow)",
                modifier = Modifier.size(boxSize * 0.45f)
            )
        }
    }
}
