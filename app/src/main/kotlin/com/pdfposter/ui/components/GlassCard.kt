package com.pdfposter.ui.components

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Glassmorphism via BlurEffect on a graphicsLayer. The trick: this Composable
 * doesn't blur its OWN children (that would fight readability) — it blurs
 * what's *behind* it by drawing a separate translucent rounded surface, then
 * placing content unblurred on top.
 *
 * NOTE: real "blur the screen behind me" requires Android 12+ (API 31).
 * On older API levels we fall back to a translucent overlay (the gradient
 * + border in `glassmorphism()` does the heavy lifting in that case).
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(24.dp)
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    clip = true
                    this.shape = shape
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        this.renderEffect = RenderEffect.createBlurEffect(
                            18f, 18f, Shader.TileMode.DECAL
                        ).asComposeRenderEffect()
                    }
                }
                .glassmorphism(shape = shape, backgroundColor = backgroundColor),
        )
        Box(
            modifier = Modifier.padding(16.dp),
            content = content,
        )
    }
}
