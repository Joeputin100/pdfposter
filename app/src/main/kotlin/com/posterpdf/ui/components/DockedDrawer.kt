package com.posterpdf.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * RC69: a bottom-docked drawer that lives INSIDE the Scaffold body (below
 * the top bar) instead of as a window-level ModalBottomSheet. The scrim
 * only covers the body region this composable is given, so the two-row
 * top bar above stays visible and fully tappable. Caller places this as
 * the last child of the padded body Box so it overlays the scrolling
 * content.
 *
 * RC71: the drag-handle is now functional — grab it and drag down to
 * dismiss. The panel follows the finger; releasing past the threshold
 * closes it, otherwise it snaps back. (A real ModalBottomSheet would
 * give this for free, but we hand-roll it to keep the top bar tappable.)
 *
 * @param visible       whether the drawer is shown
 * @param onScrimTap    dismiss callback (scrim tap OR drag-down past threshold)
 * @param heightFraction fraction of the body height the panel occupies
 */
@Composable
fun DockedDrawer(
    visible: Boolean,
    onScrimTap: () -> Unit,
    heightFraction: Float = 0.97f,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Body-only scrim. No-ripple clickable so a tap on the dim
            // area dismisses without a visual ripple.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onScrimTap,
                    ),
            )
            // The panel itself slides up from the bottom and is capped.
            AnimatedVisibility(
                visible = visible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                // RC71: live drag offset. Downward-only (coerceAtLeast 0).
                var dragOffsetPx by remember { mutableFloatStateOf(0f) }
                val dismissThresholdPx = with(LocalDensity.current) { 120.dp.toPx() }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(heightFraction)
                        .offset { IntOffset(0, dragOffsetPx.roundToInt()) }
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // RC70/71: drag-handle. Generous touch target around
                        // the visual pill; vertical drag moves the panel and
                        // dismisses past the threshold.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(Unit) {
                                    detectVerticalDragGestures(
                                        onDragEnd = {
                                            if (dragOffsetPx > dismissThresholdPx) {
                                                onScrimTap()
                                            }
                                            dragOffsetPx = 0f
                                        },
                                        onVerticalDrag = { _, dy ->
                                            dragOffsetPx = (dragOffsetPx + dy).coerceAtLeast(0f)
                                        },
                                    )
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 36.dp, height = 4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                            .copy(alpha = 0.4f),
                                    ),
                            )
                        }
                        // Content scrolls within the remaining space.
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            content()
                        }
                    }
                }
            }
        }
    }
}
