package com.posterpdf.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal val LocalBackdropLayer = compositionLocalOf<GraphicsLayer?> { null }

// Captures content into a GraphicsLayer with BlurEffect applied, then exposes that
// layer to descendants via LocalBackdropLayer. Glass surfaces inside re-draw the
// layer translated to their own origin, producing a "frosted glass" view of what
// is actually behind them — without re-running the parent's composables.
//
// Real backdrop blur needs RenderEffect (API 31+); pre-S devices fall through to
// a plain Box and the glass surfaces use a translucent-gradient fallback.
@Composable
fun BackdropBlur(
    modifier: Modifier = Modifier,
    blurRadius: Dp = 28.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        Box(modifier = modifier, content = content)
        return
    }

    val density = LocalDensity.current
    val sharp = rememberGraphicsLayer()
    val blurred = rememberGraphicsLayer().also {
        it.renderEffect = with(density) {
            BlurEffect(blurRadius.toPx(), blurRadius.toPx(), TileMode.Decal)
        }
    }

    CompositionLocalProvider(LocalBackdropLayer provides blurred) {
        Box(
            modifier = modifier.drawWithContent {
                val intSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
                if (intSize.width > 0 && intSize.height > 0) {
                    sharp.record(this, layoutDirection, intSize) {
                        this@drawWithContent.drawContent()
                    }
                    blurred.record(this@drawWithContent, layoutDirection, intSize) {
                        drawLayer(sharp)
                    }
                    drawLayer(sharp)
                } else {
                    this@drawWithContent.drawContent()
                }
            },
            content = content,
        )
    }
}

// Apply a glass surface to this Modifier chain: when LocalBackdropLayer is set and
// the device is API 31+, draws the blurred backdrop translated to align with what
// is actually behind, then a tint on top. Older devices get a translucent gradient
// fallback. Caller does NOT need to add .clip() — this modifier handles clipping.
fun Modifier.glassBackdrop(
    shape: Shape,
    tint: Color = Color.White.copy(alpha = 0.18f),
): Modifier = composed {
    val backdrop = LocalBackdropLayer.current
    if (backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        var pos by remember { mutableStateOf(Offset.Zero) }
        this
            .onGloballyPositioned { pos = it.positionInRoot() }
            .clip(shape)
            .drawBehind {
                translate(left = -pos.x, top = -pos.y) {
                    drawLayer(backdrop)
                }
                drawRect(tint)
            }
    } else {
        this
            .clip(shape)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        tint,
                        tint.copy(alpha = tint.alpha * 0.4f),
                    ),
                ),
            )
    }
}
