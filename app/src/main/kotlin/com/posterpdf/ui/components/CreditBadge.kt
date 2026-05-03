package com.posterpdf.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity

/**
 * TopAppBar action: sparkles icon with an MD3 Badge showing the AI-credit
 * balance. Tapping opens the purchase sheet (caller's `onClick`).
 *
 * The badge is suppressed when balance == 0 so the icon reads as a plain
 * "buy credits" affordance instead of "you have zero".
 *
 * RC5: when the balance changes, the digit flips on its X-axis like a
 * pqina/flip split-flap display — the badge rotates edge-on (rotationX=90°,
 * invisible from the user's POV), swaps to the new value, then rotates back
 * from -90° to 0°. Reads as a single physical card flipping over to reveal
 * the next number.
 */
@Composable
fun CreditBadge(balance: Int, isAdmin: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        BadgedBox(badge = {
            when {
                isAdmin -> Badge { Text("∞") }
                balance > 0 -> Badge { FlipNumber(balance) }
            }
        }) {
            Icon(Icons.Default.AutoAwesome, contentDescription = "AI credits")
        }
    }
}

/**
 * Animates a 3D X-axis flip when [value] changes. The animation runs in two
 * legs: rotate to 90° (edge-on / invisible), snap value to new, rotate from
 * -90° back to 0°. Total duration ~280ms. Uses `cameraDistance` so the
 * perspective looks like a physical card and not a flat scale.
 */
@Composable
private fun FlipNumber(value: Int) {
    var displayed by remember { mutableStateOf(value) }
    val rotation = remember { Animatable(0f) }
    val density = LocalDensity.current

    LaunchedEffect(value) {
        if (value != displayed) {
            rotation.animateTo(90f, tween(durationMillis = 140))
            displayed = value
            rotation.snapTo(-90f)
            rotation.animateTo(0f, tween(durationMillis = 140))
        }
    }

    Text(
        text = displayed.toString(),
        modifier = Modifier.graphicsLayer {
            rotationX = rotation.value
            // 12dp camera distance is empirically a good split-flap depth —
            // farther feels like a flat scale; closer warps too much at 90°.
            cameraDistance = 12f * density.density
        },
    )
}
