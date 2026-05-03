package com.posterpdf.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.posterpdf.R
import com.posterpdf.ui.theme.BlueprintBlue700
import com.posterpdf.ui.theme.TrimOrange500
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────────────────────
// Subject + model definitions for the demo screen.
// Each cell maps to a baked res/raw/<subject>_<model>.jpg asset.
// gristmill_aurasr / gristmill_recraft are SYNTHESIZED FALLBACKS — they're a
// copy of gristmill_topaz so the layout still renders. The chip rendering
// surfaces a "(synthesized fallback)" tag for these slots.
// ─────────────────────────────────────────────────────────────────────────────

private enum class CompareSubject(val label: String, val key: String) {
    DiscoChicken("Disco chicken", "disco_chicken"),
    CatShimmer("Cat", "cat_shimmer"),
    Gristmill("Gristmill", "gristmill"),
    Earth("Earth poster", "earth"),
}

private enum class CompareModel(val label: String, val key: String) {
    Topaz("Topaz", "topaz"),
    Recraft("Recraft", "recraft"),
    AuraSr("AuraSR", "aurasr"),
    Esrgan("ESRGAN", "esrgan"),
}

/** Subjects that have synthesized fallback outputs for given models. */
private val SYNTHESIZED_FALLBACKS: Set<Pair<CompareSubject, CompareModel>> = setOf(
    CompareSubject.Gristmill to CompareModel.AuraSr,
    CompareSubject.Gristmill to CompareModel.Recraft,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpscaleComparisonScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var subject by remember { mutableStateOf(CompareSubject.DiscoChicken) }
    var model by remember { mutableStateOf(CompareModel.Topaz) }

    // Decode the source + upscaled bitmaps in IO. They're already sized
    // ≤1024px so allocation is small (~1-2 MB each); we still keep two slots
    // in state and overwrite as the user picks new subjects/models.
    var sourceBmp by remember { mutableStateOf<ImageBitmap?>(null) }
    var upscaledBmp by remember { mutableStateOf<ImageBitmap?>(null) }

    // RC3+: lifted viewport state so it persists across the brief null-gap
    // when bitmaps swap (model picker tap). Start zoomed in 4× so the user
    // sees the upscale-quality difference immediately.
    var viewScale by remember { mutableFloatStateOf(4f) }
    var viewOffsetX by remember { mutableFloatStateOf(0f) }
    var viewOffsetY by remember { mutableFloatStateOf(0f) }
    var viewHandleX by remember { mutableFloatStateOf(-1f) }

    LaunchedEffect(subject, model) {
        sourceBmp = null
        upscaledBmp = null
        val srcId = resIdFor(context, "${subject.key}_source")
        val upId = resIdFor(context, "${subject.key}_${model.key}")
        val (s, u) = withContext(Dispatchers.IO) {
            decodeRaw(context, srcId) to decodeRaw(context, upId)
        }
        sourceBmp = s?.asImageBitmap()
        upscaledBmp = u?.asImageBitmap()
    }

    val isFallback = (subject to model) in SYNTHESIZED_FALLBACKS

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(stringResource(R.string.screen_compare_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = BlueprintBlue700,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Subject picker chip row
            Text(stringResource(R.string.compare_subject_label), style = MaterialTheme.typography.labelMedium, color = BlueprintBlue700)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CompareSubject.entries.forEach { s ->
                    FilterChip(
                        selected = subject == s,
                        onClick = { subject = s },
                        label = { Text(s.label, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BlueprintBlue700,
                            selectedLabelColor = Color.White,
                        ),
                    )
                }
            }

            // Model picker chip row
            Text(stringResource(R.string.compare_model_label), style = MaterialTheme.typography.labelMedium, color = BlueprintBlue700)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CompareModel.entries.forEach { m ->
                    FilterChip(
                        selected = model == m,
                        onClick = { model = m },
                        label = { Text(m.label, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TrimOrange500,
                            selectedLabelColor = Color.White,
                        ),
                    )
                }
            }

            if (isFallback) {
                Text(stringResource(R.string.compare_synthesized_fallback),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Viewport
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (sourceBmp != null && upscaledBmp != null) {
                    SlideHandleViewer(
                        source = sourceBmp!!,
                        upscaled = upscaledBmp!!,
                        scale = viewScale,
                        offsetX = viewOffsetX,
                        offsetY = viewOffsetY,
                        handleX = viewHandleX,
                        onScaleChange = { viewScale = it },
                        onOffsetXChange = { viewOffsetX = it },
                        onOffsetYChange = { viewOffsetY = it },
                        onHandleXChange = { viewHandleX = it },
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // Footer attribution
            Text(
                "Demo images: cat_shimmer (CC BY 2.0), gristmill (CC BY-SA 4.0), " +
                    "earth poster (CC BY 2.0, ESO). disco_chicken generated by Gemini Nano Banana.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Stack source + upscaled bitmaps at the same on-screen size and reveal them
 * to the left/right of a draggable vertical handle.
 *
 * Pinch-zoom + offset apply to BOTH layers identically so the slide split
 * stays pixel-aligned. The handle drag uses a custom pointer-input gate that
 * captures pointers near the handle X (within 24.dp) BEFORE the transform
 * gesture sees them, so the handle wins the gesture race at the handle but
 * pinch/pan still works everywhere else.
 */
@Composable
private fun SlideHandleViewer(
    source: ImageBitmap,
    upscaled: ImageBitmap,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    handleX: Float,
    onScaleChange: (Float) -> Unit,
    onOffsetXChange: (Float) -> Unit,
    onOffsetYChange: (Float) -> Unit,
    onHandleXChange: (Float) -> Unit,
) {
    val density = LocalDensity.current
    val handleHitRadiusPx = with(density) { 24.dp.toPx() }
    val knobRadiusPx = with(density) { 14.dp.toPx() }

    var viewportW by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black, RoundedCornerShape(16.dp))
            .clipToBounds(),
    ) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        viewportW = wPx
        if (handleX < 0f) onHandleXChange(wPx / 2f)

        // Clamp helpers — keep image visible inside viewport at any zoom.
        fun clampOffsets() {
            val maxX = (wPx * (scale - 1f) / 2f).coerceAtLeast(0f)
            val maxY = (hPx * (scale - 1f) / 2f).coerceAtLeast(0f)
            onOffsetXChange(offsetX.coerceIn(-maxX, maxX))
            onOffsetYChange(offsetY.coerceIn(-maxY, maxY))
        }

        // Single pointerInput that ARBITRATES between handle drag and
        // pinch-zoom + pan based on the initial down position and pointer count.
        //
        // Decision rule on first-down:
        //   • If down lands within ±24.dp of the handle X AND only one pointer
        //     is down → claim the gesture for the handle, ignore additional
        //     pointers until release.
        //   • Otherwise → run a transform loop (pinch zoom + pan) until all
        //     pointers lift.
        //
        // This avoids the layered-pointerInput dispatch ambiguity: there is
        // exactly one decision point per gesture.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val firstDown = awaitFirstDown(requireUnconsumed = true)
                        val nearHandle = abs(firstDown.position.x - handleX) <= handleHitRadiusPx

                        if (nearHandle) {
                            firstDown.consume()
                            var pointer: PointerInputChange? = firstDown
                            while (pointer != null && pointer.pressed) {
                                onHandleXChange(pointer.position.x.coerceIn(0f, viewportW))
                                if (pointer.positionChange() != Offset.Zero) pointer.consume()
                                val event = awaitPointerEvent()
                                pointer = event.changes.firstOrNull { it.id == firstDown.id }
                            }
                        } else {
                            do {
                                val event = awaitPointerEvent()
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                if (zoomChange != 1f || panChange != Offset.Zero) {
                                    onScaleChange((scale * zoomChange).coerceIn(1f, 8f))
                                    onOffsetXChange(offsetX + panChange.x)
                                    onOffsetYChange(offsetY + panChange.y)
                                    clampOffsets()
                                    event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                },
        ) {
            // Source image — drawn on the LEFT half of handle.
            Image(
                bitmap = source,
                contentDescription = "Source image",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsZoomPan(scale, offsetX, offsetY)
                    .drawWithContent {
                        clipRect(left = 0f, top = 0f, right = handleX, bottom = size.height) {
                            this@drawWithContent.drawContent()
                        }
                    },
            )
            // Upscaled image — drawn on the RIGHT of handle, same transform.
            Image(
                bitmap = upscaled,
                contentDescription = "Upscaled image",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsZoomPan(scale, offsetX, offsetY)
                    .drawWithContent {
                        clipRect(left = handleX, top = 0f, right = size.width, bottom = size.height) {
                            this@drawWithContent.drawContent()
                        }
                    },
            )

            // Handle line + knob (drawn on top, NOT zoom-panned).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawLine(
                            color = Color.White.copy(alpha = 0.92f),
                            start = Offset(handleX, 0f),
                            end = Offset(handleX, size.height),
                            strokeWidth = 3f,
                        )
                        val knobCenter = Offset(handleX, size.height / 2f)
                        drawCircle(
                            color = Color.White,
                            radius = knobRadiusPx,
                            center = knobCenter,
                        )
                        drawCircle(
                            color = BlueprintBlue700,
                            radius = knobRadiusPx,
                            center = knobCenter,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
                        )
                    },
            )
            // RC3+: side labels — "Original" left of handle, "Upscaled" right.
            Text(stringResource(R.string.compare_label_original),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Text(stringResource(R.string.compare_label_upscaled),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** Apply a uniform scale + translation around the center to an Image. */
private fun Modifier.graphicsZoomPan(scale: Float, offsetX: Float, offsetY: Float): Modifier =
    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
        translationX = offsetX
        translationY = offsetY
    }

// ─────────────────────────────────────────────────────────────────────────────
// Asset loading helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun resIdFor(context: android.content.Context, name: String): Int =
    context.resources.getIdentifier(name, "raw", context.packageName)

private fun decodeRaw(context: android.content.Context, resId: Int): Bitmap? {
    if (resId == 0) return null
    return context.resources.openRawResource(resId).use { stream ->
        BitmapFactory.decodeStream(stream)
    }
}
