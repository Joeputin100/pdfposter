package com.posterpdf.ui.components.preview

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate

/**
 * RC6 — torn-paper "leftover" band.
 *
 * When the poster image doesn't fill the page grid evenly, the trailing-edge
 * pages have a leftover white band. RC5 simply faded those bands; RC6
 * replaces the fade with a tear-and-fall effect modeled on the photo-tear
 * Three.js demo (CodePen rNjOgYv) the user shared:
 *
 *   1. **Tear edge**: the band's inner edge (touching the image content) is
 *      a jagged path, not a clean rectangle. Built deterministically from a
 *      hash(seed) so the same band tears the same way every frame.
 *   2. **Lift**: as `tearProgress` grows from 0 → 0.5, the band rotates a
 *      few degrees away from the image content (pivoted at its outer
 *      corner) — like the corner curling.
 *   3. **Fall**: from 0.5 → 1.0, the band accelerates downward (squared
 *      easing for gravity), rotates further, and fades to alpha 0.
 *
 * AGSL was an option but is fragment-only — full 3D mesh distortion isn't
 * possible. Path clip + Compose translate/rotate gets us the same visual
 * read at a fraction of the GPU cost, and works on every Android API
 * level we support.
 *
 * @param tearProgress 0 = intact, 1 = fully torn and fallen off.
 * @param isVertical true for the right-side band (left edge tears),
 *                   false for the bottom band (top edge tears).
 * @param seed deterministic input for the jagged-edge noise. Use the
 *             pane's row*cols+col so each band has its own tear pattern.
 */
fun DrawScope.drawTearingBand(
    bandLeft: Float, bandTop: Float,
    bandWidth: Float, bandHeight: Float,
    tearProgress: Float,
    isVertical: Boolean,
    seed: Int,
    paperColor: Color = Color(0xFFFAFAF7),
) {
    if (tearProgress >= 1f) return

    // Lift over [0..0.5], fall over [0.5..1.0]. Two phases give the band a
    // moment to "peel" before it accelerates downward.
    val liftPhase = (tearProgress * 2f).coerceIn(0f, 1f)
    val fallPhaseRaw = ((tearProgress - 0.5f) * 2f).coerceIn(0f, 1f)
    val fallPhase = fallPhaseRaw * fallPhaseRaw // gravity ease

    val rotDeg = liftPhase * 6f + fallPhase * 28f
    val translateY = fallPhase * (bandHeight * 2.5f + 120f)
    val translateX = if (isVertical) fallPhase * 80f else fallPhase * 20f
    val alpha = (1f - fallPhase * 0.95f).coerceIn(0f, 1f)

    // Pivot for the rotate: opposite corner from the tear edge so the
    // tearing edge swings away while the outer corner stays put.
    //   isVertical=true  (right-side band, tear on its left edge)
    //     → pivot at top-right corner (bandLeft + bandWidth, bandTop).
    //   isVertical=false (bottom band, tear on its top edge)
    //     → pivot at bottom-right corner (bandLeft + bandWidth, bandTop + bandHeight).
    val pivotX = bandLeft + bandWidth
    val pivotY = if (isVertical) bandTop else bandTop + bandHeight

    translate(translateX, translateY) {
        rotate(degrees = rotDeg, pivot = Offset(pivotX, pivotY)) {
            val path = buildTornPath(
                bandLeft, bandTop, bandWidth, bandHeight, isVertical, seed,
            )
            clipPath(path) {
                drawRect(
                    color = paperColor.copy(alpha = alpha),
                    topLeft = Offset(bandLeft, bandTop),
                    size = Size(bandWidth, bandHeight),
                )
                // Subtle shadow along the tear edge so the curl reads as 3D.
                // Gradient runs from the tear edge inward ~25% of the band
                // dimension, darkest at the tear.
                val shadowDepth = (if (isVertical) bandWidth else bandHeight) * 0.25f
                if (isVertical) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.25f * alpha),
                                Color.Transparent,
                            ),
                            startX = bandLeft,
                            endX = bandLeft + shadowDepth,
                        ),
                        topLeft = Offset(bandLeft, bandTop),
                        size = Size(shadowDepth, bandHeight),
                    )
                } else {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.25f * alpha),
                                Color.Transparent,
                            ),
                            startY = bandTop,
                            endY = bandTop + shadowDepth,
                        ),
                        topLeft = Offset(bandLeft, bandTop),
                        size = Size(bandWidth, shadowDepth),
                    )
                }
            }
        }
    }
}

/**
 * Build a Path that traces the band rectangle but with a jagged tear edge
 * on the inner side. The other three sides stay straight.
 *
 * Tear noise: deterministic LCG-ish hash of (segment_index, seed) drives
 * a perpendicular jitter of ±~6dp on each segment vertex. ~14 segments
 * along the tear edge gives a clearly torn-paper look without overdrawing.
 */
private fun buildTornPath(
    bandLeft: Float, bandTop: Float,
    bandWidth: Float, bandHeight: Float,
    isVertical: Boolean,
    seed: Int,
): Path {
    val segments = 14
    val jitterPx = 7f
    val path = Path()

    if (isVertical) {
        // Tear edge runs along the LEFT side of the band, vertically.
        // Segments step from top to bottom along Y. Jitter perturbs X around
        // bandLeft. Other three sides straight.
        val seg0X = bandLeft + jitterAt(0, seed) * jitterPx
        path.moveTo(seg0X, bandTop)
        for (i in 1..segments) {
            val t = i.toFloat() / segments
            val y = bandTop + t * bandHeight
            val x = bandLeft + jitterAt(i, seed) * jitterPx
            path.lineTo(x, y)
        }
        path.lineTo(bandLeft + bandWidth, bandTop + bandHeight)
        path.lineTo(bandLeft + bandWidth, bandTop)
        path.close()
    } else {
        // Tear edge runs along the TOP side of the band, horizontally.
        val seg0Y = bandTop + jitterAt(0, seed) * jitterPx
        path.moveTo(bandLeft, seg0Y)
        for (i in 1..segments) {
            val t = i.toFloat() / segments
            val x = bandLeft + t * bandWidth
            val y = bandTop + jitterAt(i, seed) * jitterPx
            path.lineTo(x, y)
        }
        path.lineTo(bandLeft + bandWidth, bandTop + bandHeight)
        path.lineTo(bandLeft, bandTop + bandHeight)
        path.close()
    }
    return path
}

/**
 * Returns a deterministic value in [-1, 1] for segment [i] given [seed].
 * Cheap multiplicative hash; good enough for visual noise, not crypto.
 */
private fun jitterAt(i: Int, seed: Int): Float {
    val n = (i * 374761393 xor seed * 668265263).hashCode()
    val unit = ((n and 0x7FFFFFFF) % 1000) / 500f - 1f  // [-1, 1)
    return unit
}
