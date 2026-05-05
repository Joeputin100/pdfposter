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
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
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

/** Subjects that have synthesized fallback outputs for given models.
 *  RC20.2: empty — every cell now has a real upscale. The remaining
 *  Gristmill→Recraft asset was generated via a one-shot FAL job
 *  (downscale source to 1024w → fal-ai/recraft/upscale/crisp → 4× output
 *  back to JPEG quality 88) on 2026-05-05. The downscaling sidesteps
 *  Recraft's 5 MB output cap; output is 4096×2732, matching the source
 *  aspect ratio so the slider lines up cleanly. */
private val SYNTHESIZED_FALLBACKS: Set<Pair<CompareSubject, CompareModel>> = emptySet()

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
                        // RC20: use theme primary so dark mode picks the
                        // dark-variant blue (high contrast on dark surface).
                        // BlueprintBlue700 is the LIGHT-mode brand color and
                        // disappears against M3's dark surface (#1A1A1A).
                        color = MaterialTheme.colorScheme.primary,
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
            Text(stringResource(R.string.compare_subject_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CompareSubject.entries.forEach { s ->
                    // RC4: 24dp thumbnail of the source image as the chip\'s
                    // leading icon. Loaded lazily off the main thread per chip.
                    val thumbBitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
                        initialValue = null,
                        key1 = s.key,
                    ) {
                        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val resId = context.resources.getIdentifier(
                                "${s.key}_source", "raw", context.packageName,
                            )
                            if (resId == 0) return@withContext null
                            context.resources.openRawResource(resId).use { stream ->
                                val full = android.graphics.BitmapFactory.decodeStream(stream)
                                    ?: return@withContext null
                                android.graphics.Bitmap.createScaledBitmap(full, 64, 64, true)
                                    .asImageBitmap()
                            }
                        }
                    }
                    FilterChip(
                        selected = subject == s,
                        onClick = { subject = s },
                        leadingIcon = thumbBitmap?.let { bmp ->
                            {
                                Image(
                                    bitmap = bmp,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(RoundedCornerShape(50)),
                                )
                            }
                        },
                        label = { Text(s.label, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BlueprintBlue700,
                            selectedLabelColor = Color.White,
                        ),
                    )
                }
            }

            // Model picker chip row
            Text(stringResource(R.string.compare_model_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
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
    // RC4: shrunk from 24dp → 10dp. Old radius made the entire center column
    // of the viewport "near handle" — every center tap captured the
    // before/after slider instead of letting the user drag-pan the image.
    // 10dp is still wider than the actual handle stem and easy to grab.
    val handleHitRadiusPx = with(density) { 10.dp.toPx() }
    val knobRadiusPx = with(density) { 14.dp.toPx() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black, RoundedCornerShape(16.dp))
            .clipToBounds(),
    ) {
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        // RC20: dropped the var viewportW + reassign-during-composition pattern
        // — re-keying pointerInput on a state that flickered during gesture
        // updates was restarting the gesture coroutine mid-drag, which the
        // user perceived as "sticky" handle behavior. wPx comes straight from
        // BoxWithConstraints and only changes when the layout reflows, which
        // doesn't happen during a touch sequence.
        if (handleX < 0f) onHandleXChange(wPx / 2f)

        // Clamp helpers — keep image visible inside viewport at any zoom.
        fun clampOffsets() {
            val maxX = (wPx * (scale - 1f) / 2f).coerceAtLeast(0f)
            val maxY = (hPx * (scale - 1f) / 2f).coerceAtLeast(0f)
            onOffsetXChange(offsetX.coerceIn(-maxX, maxX))
            onOffsetYChange(offsetY.coerceIn(-maxY, maxY))
        }

        // RC15 — restored "near handle vs elsewhere" arbiter:
        //   • Down within ±60px (~24dp) of handle X → glide the handle as
        //     the finger moves. Pre-RC15 the slider only responded to a
        //     full press-release cycle (tap-to-snap), so a drag along the
        //     handle did nothing — user had to "double tap" to advance it.
        //   • Down elsewhere → pinch-zoom + pan, same as before.
        //   • A press near the handle that lifts WITHOUT moving past slop
        //     still snaps the handle to that x (covers tap-to-jump cases).
        //
        // RC8 noted that the prior "near-handle = drag" version had a bug
        // where reaching an edge made the handle unreachable; that was the
        // arbiter sometimes selecting pan-mode for handle-area presses. We
        // fix that here by ALWAYS using pixel proximity at the down event.
        // The handle never sticks at an edge because the user can drag
        // back away from it from any nearby position.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(wPx) {
                    val slop = viewConfiguration.touchSlop
                    val handleHitPx = 60f
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downPos = down.position
                        val nearHandle = kotlin.math.abs(downPos.x - handleX) <= handleHitPx
                        var movedPastSlop = false

                        if (nearHandle) {
                            // Glide the handle as the finger moves.
                            // Snap on first frame too so a tap immediately
                            // jumps the handle to the press location.
                            onHandleXChange(downPos.x.coerceIn(0f, wPx))
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || !change.pressed) break
                                onHandleXChange(change.position.x.coerceIn(0f, wPx))
                                change.consume()
                            }
                        } else {
                            // Pinch + pan elsewhere.
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (!movedPastSlop && change != null) {
                                    val dx = change.position.x - downPos.x
                                    val dy = change.position.y - downPos.y
                                    if (kotlin.math.hypot(dx, dy) > slop) movedPastSlop = true
                                }
                                if (movedPastSlop) {
                                    val zoomChange = event.calculateZoom()
                                    val panChange = event.calculatePan()
                                    if (zoomChange != 1f || panChange != Offset.Zero) {
                                        onScaleChange((scale * zoomChange).coerceIn(1f, 8f))
                                        onOffsetXChange(offsetX + panChange.x)
                                        onOffsetYChange(offsetY + panChange.y)
                                        clampOffsets()
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                                if (change == null || !change.pressed) break
                            }
                        }
                    }
                },
        ) {
            // RC20: clip outside the graphicsLayer transform so the split
            // edge stays pixel-aligned with the handle line at any zoom.
            // Previously drawWithContent was chained AFTER graphicsZoomPan,
            // so the clipRect at `right = handleX` ran in the Image's local
            // pre-transform coordinates while the handle line drew in
            // screen space. At scale > 1 the two diverged and the visible
            // before/after split trailed the handle by (scale-1)·offset.
            // Wrapping each Image in a Box and putting drawWithContent on
            // the Box puts the clip back in screen space.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        clipRect(left = 0f, top = 0f, right = handleX, bottom = size.height) {
                            this@drawWithContent.drawContent()
                        }
                    },
            ) {
                Image(
                    bitmap = source,
                    contentDescription = "Source image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsZoomPan(scale, offsetX, offsetY),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        clipRect(left = handleX, top = 0f, right = size.width, bottom = size.height) {
                            this@drawWithContent.drawContent()
                        }
                    },
            ) {
                Image(
                    bitmap = upscaled,
                    contentDescription = "Upscaled image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsZoomPan(scale, offsetX, offsetY),
                )
            }

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
