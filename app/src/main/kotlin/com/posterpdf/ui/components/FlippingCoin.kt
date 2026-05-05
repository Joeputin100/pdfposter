package com.posterpdf.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.posterpdf.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * RC22 — replaces the in-line "🪙" emoji with a flipping coin animation.
 *
 * Two faces, both photoreal copper-bronze coin renders generated via
 * Vertex Imagen 3 (`imagen-3.0-generate-002`):
 *   • obverse (`coin_obverse.png`) — engraved "1¢ CENT"
 *   • reverse (`coin_reverse.png`) — engraved "POSTER PDF" with laurel wreath
 *
 * Both faces are pre-cropped to a circular alpha mask so the coin appears
 * round on any background.
 *
 * Triggers:
 *   • Auto-flip at random 30–60 s intervals so the coin feels "alive"
 *     without being distracting.
 *   • Tap-flip: any click on the coin triggers an immediate flip. The
 *     auto-flip timer keeps running independently.
 *
 * Each flip animates rotationY from current → +360° over ~800ms with
 * FastOutSlowInEasing for the auto path (gentle settle) and ~600ms for the
 * tap path (snappier feedback). The face image swaps at 90°/270° crossings;
 * the back face is rotated 180° on its own axis so its engraving reads
 * correctly when shown (otherwise the text would mirror).
 */
@Composable
fun FlippingCoin(
    modifier: Modifier = Modifier,
    sizeDp: Dp = 32.dp,
    contentDescription: String? = "1¢ coin",
) {
    val rotation = remember { Animatable(0f) }
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // Auto-flip loop — random 30–60 s.
    LaunchedEffect(Unit) {
        while (true) {
            delay((30_000L..60_000L).random())
            rotation.animateTo(
                targetValue = rotation.value + 360f,
                animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            )
        }
    }

    val cameraDistancePx = with(density) { 12.dp.toPx() } * density.density

    Box(
        modifier = modifier
            .size(sizeDp)
            .graphicsLayer {
                rotationY = rotation.value
                cameraDistance = cameraDistancePx
            }
            .clickable {
                scope.launch {
                    rotation.animateTo(
                        targetValue = rotation.value + 360f,
                        animationSpec = tween(durationMillis = 600),
                    )
                }
            },
    ) {
        // Determine which face is currently facing the viewer. rotationY in
        // degrees; obverse visible when rotation mod 360 is in [-90, 90).
        val rNorm = (((rotation.value % 360f) + 360f) % 360f)
        val showObverse = rNorm < 90f || rNorm >= 270f
        Image(
            painter = painterResource(
                id = if (showObverse) R.drawable.coin_obverse else R.drawable.coin_reverse,
            ),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Mirror the back face so its engraving is right-reading
                    // when the coin's parent rotation is past 180°.
                    rotationY = if (showObverse) 0f else 180f
                },
        )
    }
}
