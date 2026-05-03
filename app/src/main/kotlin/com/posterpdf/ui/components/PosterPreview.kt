package com.posterpdf.ui.components

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.posterpdf.MainViewModel
import com.posterpdf.ui.components.preview.AssemblyPhase
import com.posterpdf.ui.components.preview.drawScotchTape
import com.posterpdf.ui.components.preview.drawThumbTack
import com.posterpdf.ui.util.Hapt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val WOOD_AGSL = """
uniform float2 iResolution;
uniform float iTime;

float hash1(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}
float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash1(i);
    float b = hash1(i + float2(1.0, 0.0));
    float c = hash1(i + float2(0.0, 1.0));
    float d = hash1(i + float2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
float fbm(float2 p) {
    float v = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 5; i++) {
        v += amp * noise(p);
        p *= 2.02;
        amp *= 0.5;
    }
    return v;
}
half4 main(float2 fragCoord) {
    float2 uv = fragCoord / iResolution.xy;
    float2 grainUV = float2(uv.x * 6.0, uv.y * 2.2);
    float distort = fbm(grainUV * 3.0) * 0.15;
    grainUV.y += distort;
    float ring = fbm(float2(grainUV.x * 0.6, grainUV.y * 18.0));
    ring = fract(ring * 8.0);
    float rings = smoothstep(0.35, 0.45, ring) * smoothstep(0.65, 0.55, ring);
    half3 dark = half3(0.28, 0.16, 0.09);
    half3 mid = half3(0.46, 0.28, 0.16);
    half3 light = half3(0.62, 0.42, 0.24);
    float grain = fbm(grainUV * float2(12.0, 90.0)) * 0.35;
    half3 color = mix(mid, dark, half(rings));
    color = mix(color, light, half(grain * 0.5));
    float2 center = uv - 0.5;
    float vig = 1.0 - dot(center, center) * 0.6;
    color *= half(vig);
    return half4(color, 1.0);
}
"""

@Composable
fun PosterPreview(viewModel: MainViewModel) {
    val context = LocalContext.current

    val infinite = rememberInfiniteTransition(label = "preview_loop")
    val t by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "time"
    )

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(16)
        }
    }

    // Phase D: 12-second Assembly Cycle clock + per-phase derived state.
    // The cycle (and the AGSL workbench shader, and the decoration draws) are gated
    // on API 33+ because RuntimeShader is API 33. On older devices the preview is
    // the static accurate Phase C output with a linear-gradient workbench fallback.
    val cycleEnabled = Build.VERSION.SDK_INT >= 33
    val cycleSeconds = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(cycleEnabled) {
        if (!cycleEnabled) return@LaunchedEffect
        val start = System.currentTimeMillis()
        while (true) {
            val elapsed = (System.currentTimeMillis() - start) / 1000f
            cycleSeconds.floatValue = elapsed % AssemblyPhase.CYCLE_SECONDS
            delay(16)
        }
    }
    val phase = AssemblyPhase.phaseAt(cycleSeconds.floatValue)
    val phaseT = phase.localProgress(cycleSeconds.floatValue)

    val hapt = Hapt(LocalHapticFeedback.current)

    // One-shot confirm haptic when the cycle enters the Reveal phase.
    val lastPhase = remember { mutableStateOf<AssemblyPhase?>(null) }
    LaunchedEffect(phase) {
        if (lastPhase.value != phase && phase == AssemblyPhase.Reveal) {
            hapt.confirm()
        }
        lastPhase.value = phase
    }

    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    // Per-pane jiggle: tap a specific page to jiggle just that page.
    // paneBounds is filled during Canvas draw and read by the tap handler;
    // using a plain MutableList (not state) to avoid recompositions on every frame.
    val paneBounds = remember { mutableListOf<PaneBounds>() }
    var jiggledPane by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var jiggleStartedAt by remember { mutableLongStateOf(0L) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val jiggleDurationMs = 600f
    val jigglePhase = if (jiggledPane == null) 0f else {
        val elapsed = (now - jiggleStartedAt).toFloat()
        (elapsed / jiggleDurationMs).coerceIn(0f, 1f)
    }
    val jiggleAmp = if (jigglePhase >= 1f) 0f else (1f - jigglePhase)
    val jiggleSwing = sin((jigglePhase * 4f * Math.PI).toFloat()) * jiggleAmp

    var previewBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(viewModel.selectedImageUri) {
        val uri = viewModel.selectedImageUri ?: return@LaunchedEffect
        val bitmap = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) {
                null
            }
        }
        previewBitmap = bitmap?.asImageBitmap()
    }

    // RuntimeShader is API 33+. On older devices we fall back to a static gradient.
    val woodShader = remember(cycleEnabled) {
        if (cycleEnabled) RuntimeShader(WOOD_AGSL) else null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Live Assembly Preview",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .clip(RoundedCornerShape(24.dp))
                .drawWithCache {
                    if (woodShader != null) {
                        woodShader.setFloatUniform("iResolution", size.width, size.height)
                        // Subtle ambient grain scroll (was hardcoded 0f); ties into the
                        // existing infinite transition `t`.
                        woodShader.setFloatUniform("iTime", t * 0.6f)
                        val brush = ShaderBrush(woodShader)
                        onDrawBehind { drawRect(brush = brush, size = size) }
                    } else {
                        // API <33 fallback: warm three-stop wood gradient (no AGSL).
                        val brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xFF6B4226),
                                Color(0xFF8B5A37),
                                Color(0xFF6B4226),
                            ),
                            start = Offset.Zero,
                            end = Offset(size.width, size.height),
                        )
                        onDrawBehind { drawRect(brush = brush, size = size) }
                    }
                }
                .shadow(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { boxSize = it }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, panChange, zoomChange, _ ->
                            zoom = (zoom * zoomChange).coerceIn(1f, 6f)
                            pan += panChange
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { rawOffset ->
                            hapt.tap()
                            // Invert the graphicsLayer pan/zoom to land in canvas coords.
                            val pivotX = boxSize.width / 2f
                            val pivotY = boxSize.height / 2f
                            val cx = pivotX + (rawOffset.x - pivotX - pan.x) / zoom
                            val cy = pivotY + (rawOffset.y - pivotY - pan.y) / zoom
                            val hit = paneBounds.firstOrNull { p ->
                                cx >= p.left && cx <= p.left + p.width &&
                                    cy >= p.top && cy <= p.top + p.height
                            }
                            if (hit != null) {
                                jiggledPane = hit.row to hit.col
                                jiggleStartedAt = System.currentTimeMillis()
                            }
                        })
                    }
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x
                        translationY = pan.y
                    }
            ) {
            val paneInfo = viewModel.getPaneCount()
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (paneInfo == null) {
                    paneBounds.clear()
                    return@Canvas
                }
                paneBounds.clear()

                val padding = 28f
                val gap = 18f
                val availableW = (size.width - 2 * padding).coerceAtLeast(1f)
                val availableH = (size.height - 2 * padding).coerceAtLeast(1f)
                val pw = viewModel.posterWidth.toDoubleOrNull() ?: 1.0
                val ph = viewModel.posterHeight.toDoubleOrNull() ?: 1.0
                val paperW = viewModel.currentPaperWidthInches()
                val paperH = viewModel.currentPaperHeightInches()
                val m = viewModel.margin.toDoubleOrNull() ?: 0.0
                val o = viewModel.overlap.toDoubleOrNull() ?: 0.0

                val layout = com.posterpdf.ui.components.preview.PaneGeometry.compute(
                    posterW = pw, posterH = ph,
                    paperW = paperW, paperH = paperH,
                    margin = m, overlap = o,
                    availableW = availableW, availableH = availableH,
                    interPaneGap = gap,
                )
                val rows = layout.rows
                val cols = layout.cols
                val marginPx = layout.marginPx
                val overlapPx = layout.overlapPx

                val src = previewBitmap

                val outlinePx = when (viewModel.outlineThickness) {
                    "Thin" -> 1.2f
                    "Heavy" -> 3.5f
                    else -> 2.2f
                }
                val outlineEffect = when (viewModel.outlineStyle) {
                    "Dashed" -> PathEffect.dashPathEffect(floatArrayOf(12f, 7f), 0f)
                    "Dotted" -> PathEffect.dashPathEffect(floatArrayOf(2f, 7f), 0f)
                    else -> null
                }

                for (pane in layout.panes) {
                    val r = pane.row
                    val c = pane.col
                    val dx = pane.pageLeft
                    val dy = pane.pageTop
                    val pageW = pane.pageWidth
                    val pageH = pane.pageHeight

                    paneBounds.add(PaneBounds(r, c, dx, dy, pageW, pageH))

                    val isJiggled = jiggledPane?.let { it.first == r && it.second == c } == true
                    val paneJiggleAngle = if (isJiggled) jiggleSwing * 4.5f else 0f
                    val paneJiggleDx = if (isJiggled) jiggleSwing * 2.5f else 0f
                    val paneJiggleDy = if (isJiggled) -jiggleAmp * 1.8f else 0f
                    val paneCenter = Offset(dx + pageW / 2f, dy + pageH / 2f)

                    // Phase D: per-pane animation values driven by the cycle clock.
                    // All zero when cycleEnabled=false (API <33), preserving the
                    // static accurate Phase C output on older devices.
                    val paneIndex = r * cols + c
                    val panePrintT = if (cycleEnabled && phase == AssemblyPhase.Print) {
                        val tInPhase = cycleSeconds.floatValue - paneIndex * 0.20f
                        (tInPhase / 1.8f).coerceIn(0f, 1f)
                    } else if (cycleEnabled && phase == AssemblyPhase.Reset) {
                        1f - phaseT
                    } else 1f
                    val printY = if (panePrintT < 1f) {
                        val tt = panePrintT
                        (1f - tt) * 220f - sin(tt * Math.PI * 2.0).toFloat() * 8f * (1f - tt)
                    } else 0f

                    val printableWpx = layout.printableW.toFloat() * layout.scale
                    val printableHpx = layout.printableH.toFloat() * layout.scale
                    val targetAssembledX = layout.layoutLeft + c * (printableWpx - overlapPx)
                    val targetAssembledY = layout.layoutTop + r * (printableHpx - overlapPx)
                    val targetDx = targetAssembledX - pane.imageDstLeft
                    val targetDy = targetAssembledY - pane.imageDstTop
                    val assembleT = when (phase) {
                        AssemblyPhase.Assemble -> if (cycleEnabled) phaseT else 0f
                        AssemblyPhase.Reveal -> if (cycleEnabled) 1f else 0f
                        AssemblyPhase.Reset -> if (cycleEnabled) 1f - phaseT else 0f
                        else -> 0f
                    }
                    // Cubic ease-out + small sine ripple = pseudo-spring with overshoot.
                    val springT = 1f - (1f - assembleT) * (1f - assembleT) * (1f - assembleT)
                    val overshoot = sin(assembleT * Math.PI * 1.4).toFloat() * 0.08f * (1f - assembleT)
                    val effT = (springT + overshoot).coerceIn(0f, 1.08f)
                    val assembleDx = targetDx * effT
                    val assembleDy = targetDy * effT

                    // Margin tint (Trim ramps in, Assemble ramps out) + alpha (Assemble fades).
                    val marginTintT = if (cycleEnabled) when (phase) {
                        AssemblyPhase.Trim -> phaseT
                        AssemblyPhase.Assemble -> 1f - phaseT
                        else -> 0f
                    } else 0f
                    val paperColor = lerp(
                        Color(0xFFFAFAF7),
                        Color(0xFFC9C2B0), // muted "this falls away" gray
                        marginTintT.coerceAtMost(0.7f),
                    )
                    val marginAlpha = if (cycleEnabled) when (phase) {
                        AssemblyPhase.Assemble -> (1f - phaseT).coerceIn(0f, 1f)
                        AssemblyPhase.Reveal -> 0f
                        AssemblyPhase.Reset -> phaseT.coerceIn(0f, 1f)
                        else -> 1f
                    } else 1f

                    withTransform({
                        if (isJiggled) {
                            rotate(paneJiggleAngle, pivot = paneCenter)
                            translate(paneJiggleDx, paneJiggleDy)
                        }
                        if (printY != 0f) translate(0f, printY)
                        if (assembleDx != 0f || assembleDy != 0f) translate(assembleDx, assembleDy)
                    }) {
                        drawPaperFill(dx, dy, pageW, pageH, paperColor = paperColor)
                        if (src != null) {
                            val srcW = src.width
                            val srcH = src.height
                            val paneSrcX = (pane.sourceFracLeft * srcW).toInt().coerceIn(0, srcW - 1)
                            val paneSrcY = (pane.sourceFracTop * srcH).toInt().coerceIn(0, srcH - 1)
                            val paneSrcTileW = (pane.sourceFracWidth * srcW).toInt().coerceAtLeast(1).coerceAtMost(srcW - paneSrcX)
                            val paneSrcTileH = (pane.sourceFracHeight * srcH).toInt().coerceAtLeast(1).coerceAtMost(srcH - paneSrcY)
                            drawPaneImage(
                                src = src,
                                imageDstLeft = pane.imageDstLeft, imageDstTop = pane.imageDstTop,
                                imageContentWidth = pane.imageContentWidth, imageContentHeight = pane.imageContentHeight,
                                srcX = paneSrcX, srcY = paneSrcY,
                                srcTileW = paneSrcTileW, srcTileH = paneSrcTileH,
                            )
                        }
                        // Overlap zones now sit INSIDE the printable image area, not at the page edge —
                        // matching where the seam will be after the user trims along the cut marks.
                        drawPaneOverlapZones(
                            rectLeft = pane.imageDstLeft, rectTop = pane.imageDstTop,
                            rectWidth = pane.imageDstWidth, rectHeight = pane.imageDstHeight,
                            overlapPx = overlapPx,
                            row = r, col = c, rows = rows, cols = cols,
                        )
                        // Margin guide draws faint blue lines at the printable-area boundary.
                        // The page (paperFill) is already the white "paper", so no overlay needed.
                        drawPaneMarginGuide(dx, dy, pageW, pageH, marginPx, alpha = marginAlpha)
                        // Cut marks / outline live inside the printable area (image dst rect).
                        drawCutLineOrOutline(
                            viewModel,
                            pane.imageDstLeft, pane.imageDstTop,
                            pane.imageDstWidth, pane.imageDstHeight,
                            overlapPx, outlinePx, outlineEffect,
                        )
                        // Label is positioned inside the printable area.
                        drawPaneLabel(
                            viewModel,
                            pane.imageDstLeft, pane.imageDstTop,
                            pane.imageDstWidth, pane.imageDstHeight,
                            r, c,
                        )
                    }
                }

                // Phase D: decoration draws — only on API 33+ (cycleEnabled), only
                // when there's something to assemble (multi-pane layouts).
                if (cycleEnabled) {
                    val printableWpx2 = layout.printableW.toFloat() * layout.scale
                    val printableHpx2 = layout.printableH.toFloat() * layout.scale

                    // Tape strips fade in late-Assemble, hold during Reveal, fade in Reset.
                    val tapeAppearT = when (phase) {
                        AssemblyPhase.Assemble -> ((phaseT - 0.55f) / 0.45f).coerceIn(0f, 1f)
                        AssemblyPhase.Reveal -> 1f
                        AssemblyPhase.Reset -> 1f - phaseT
                        else -> 0f
                    }
                    if (tapeAppearT > 0f && (cols > 1 || rows > 1)) {
                        val tapeLen = printableWpx2 * 0.45f
                        val tapeH = 14f
                        // Vertical seams (between cols)
                        for (rr in 0 until rows) {
                            for (cc in 0 until cols - 1) {
                                val seamX = layout.layoutLeft + (cc + 1) * (printableWpx2 - overlapPx) - overlapPx / 2f
                                val seamY = layout.layoutTop + rr * (printableHpx2 - overlapPx) + printableHpx2 / 2f
                                drawScotchTape(
                                    centerX = seamX, centerY = seamY,
                                    length = tapeLen, height = tapeH,
                                    rotationDegrees = 90f + ((rr * 13 + cc * 7) % 9 - 4).toFloat(),
                                    appearT = tapeAppearT,
                                )
                            }
                        }
                        // Horizontal seams (between rows)
                        for (rr in 0 until rows - 1) {
                            for (cc in 0 until cols) {
                                val seamX = layout.layoutLeft + cc * (printableWpx2 - overlapPx) + printableWpx2 / 2f
                                val seamY = layout.layoutTop + (rr + 1) * (printableHpx2 - overlapPx) - overlapPx / 2f
                                drawScotchTape(
                                    centerX = seamX, centerY = seamY,
                                    length = tapeLen, height = tapeH,
                                    rotationDegrees = ((rr * 11 + cc * 5) % 9 - 4).toFloat(),
                                    appearT = tapeAppearT,
                                )
                            }
                        }
                    }

                    // Four corner pins drop in during Reveal (staggered), fade in Reset.
                    val pinT = when (phase) {
                        AssemblyPhase.Reveal -> phaseT
                        AssemblyPhase.Reset -> 1f - phaseT
                        else -> 0f
                    }
                    if (pinT > 0f) {
                        val assembledLeft = layout.layoutLeft
                        val assembledTop = layout.layoutTop
                        val assembledRight = layout.layoutLeft + cols * (printableWpx2 - overlapPx) + overlapPx
                        val assembledBottom = layout.layoutTop + rows * (printableHpx2 - overlapPx) + overlapPx
                        val tackR = 9f
                        val inset = 6f
                        drawThumbTack(assembledLeft + inset, assembledTop + inset, tackR, pinT)
                        drawThumbTack(assembledRight - inset, assembledTop + inset, tackR, ((pinT - 0.10f) / 0.90f).coerceIn(0f, 1f))
                        drawThumbTack(assembledLeft + inset, assembledBottom - inset, tackR, ((pinT - 0.20f) / 0.80f).coerceIn(0f, 1f))
                        drawThumbTack(assembledRight - inset, assembledBottom - inset, tackR, ((pinT - 0.30f) / 0.70f).coerceIn(0f, 1f))
                    }
                }
            }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendSwatch(Color(0xFF0A3D62).copy(alpha = 0.55f), "Margin")
            LegendSwatch(Color(0xFFFF6F00).copy(alpha = 0.55f), "Overlap")
        }

        // Low-DPI surface (Plan G10 Step 1+2). Computes effective DPI from the
        // source bitmap's pixel width over the configured poster width; if it
        // falls in the warning band (1..149), shows a tappable card that opens
        // the three-Mona-Lisa upgrade modal.
        val sourcePixelW = previewBitmap?.width ?: 0
        val posterWInchesD = viewModel.posterWidth.toDoubleOrNull() ?: 0.0
        val posterHInchesD = viewModel.posterHeight.toDoubleOrNull() ?: 0.0
        val currentDpi = if (posterWInchesD > 0) (sourcePixelW / posterWInchesD).toFloat() else 0f
        val isLowDpi = currentDpi in 1f..149.99f
        if (isLowDpi && previewBitmap != null) {
            var showLowDpiModal by remember { mutableStateOf(false) }
            Spacer(Modifier.height(8.dp))
            Card(
                onClick = { showLowDpiModal = true },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    "Low resolution: ${currentDpi.toInt()} DPI · Tap to upscale ↑",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (showLowDpiModal) {
                val src = previewBitmap!!
                // inputMp / inputBytes are derived from the preview bitmap that's
                // already in memory. For the real pipeline this will come from
                // the source URI before any downsampling, but the preview
                // bitmap is a reasonable proxy for the modal's first paint.
                val srcAndroid = src.asAndroidBitmap()
                val inputMpInt = ((srcAndroid.width.toLong() * srcAndroid.height) / 1_000_000L)
                    .toInt()
                    .coerceAtLeast(1)
                val inputBytesL = srcAndroid.byteCount.toLong()
                LowDpiUpgradeModal(
                    sourceBitmap = src,
                    inputMp = inputMpInt,
                    inputBytes = inputBytesL,
                    currentDpi = currentDpi,
                    posterWInches = posterWInchesD,
                    posterHInches = posterHInchesD,
                    // TODO(G12): replace placeholder 0 with viewModel.creditBalance.collectAsState().value
                    creditBalance = 0,
                    // TODO(G12): replace 0.119 with usdPerCredit derived from
                    // pricing/current; 0.119 is the SKU-ladder default at 50% margin.
                    usdPerCredit = 0.119,
                    // TODO(G12): replace placeholder true with viewModel.isAnonymous.collectAsState().value
                    isAnonymous = true,
                    onDismiss = { showLowDpiModal = false },
                    // TODO(G12): wire to viewModel.runFreeUpscale()
                    onFreeUpscale = { showLowDpiModal = false },
                    // TODO(G12): wire to viewModel.runAiUpscale(modelId, inputMpInt)
                    onAiUpscale = { _ -> showLowDpiModal = false },
                    // TODO(G12): wire to viewModel.pickAlreadyUpscaledImage()
                    onPickAlreadyUpscaled = { showLowDpiModal = false },
                    // TODO(G12): wire to viewModel.signInWithGoogle()
                    onSignIn = { showLowDpiModal = false },
                    // TODO(G12): wire to viewModel.openPurchaseSheet()
                    onBuyCredits = { showLowDpiModal = false },
                )
            }
        }
    }
}

private data class PaneBounds(
    val row: Int,
    val col: Int,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

@Composable
private fun LegendSwatch(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(12.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Drop shadow + page surface — what the user holds in their hand.
 * Behavior-preserving extraction from the previous inline drawPaneSurface lambda.
 */
private fun DrawScope.drawPaperFill(
    pageLeft: Float, pageTop: Float, pageWidth: Float, pageHeight: Float,
    paperColor: Color = Color(0xFFFAFAF7),
) {
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.32f),
        topLeft = Offset(pageLeft + 4f, pageTop + 6f),
        size = Size(pageWidth, pageHeight),
        cornerRadius = CornerRadius(2f, 2f),
    )
    drawRect(paperColor, Offset(pageLeft, pageTop), Size(pageWidth, pageHeight))
}

/**
 * Sample [src] at the pre-computed source-rect [srcX,srcY,srcTileW,srcTileH] and paint
 * it into the content rect at [imageDstLeft,imageDstTop] with size
 * [imageContentWidth,imageContentHeight]. The content rect is ≤ the printable rect
 * (imageDstWidth/Height); on edge tiles where the source slice is clamped, the content
 * rect is shorter, leaving blank paper on the trailing edge — mirroring PosterLogic's
 * clip()+drawImage(fullPoster, translated) flow. Caller is responsible for
 * null-checking [src] and clamping the source-rect ints.
 */
private fun DrawScope.drawPaneImage(
    src: ImageBitmap,
    imageDstLeft: Float, imageDstTop: Float,
    imageContentWidth: Float, imageContentHeight: Float,
    srcX: Int, srcY: Int,
    srcTileW: Int, srcTileH: Int,
) {
    drawImage(
        image = src,
        srcOffset = IntOffset(srcX, srcY),
        srcSize = IntSize(srcTileW, srcTileH),
        dstOffset = IntOffset(imageDstLeft.toInt(), imageDstTop.toInt()),
        dstSize = IntSize(imageContentWidth.toInt(), imageContentHeight.toInt()),
    )
}

/**
 * Orange-tinted overlap zones drawn on the seam edges of each pane. Edges that touch
 * a neighbor get a tint; outer edges (no neighbor) are left clean. Operates on
 * whatever rect the caller passes (rect-agnostic) — currently the printable rect,
 * since cut marks and overlap zones live inside the printable area.
 */
private fun DrawScope.drawPaneOverlapZones(
    rectLeft: Float, rectTop: Float,
    rectWidth: Float, rectHeight: Float,
    overlapPx: Float,
    row: Int, col: Int, rows: Int, cols: Int,
) {
    if (overlapPx <= 0.5f) return
    val overlapColor = Color(0xFFFF6F00).copy(alpha = 0.28f)
    if (col < cols - 1) drawRect(overlapColor, Offset(rectLeft + rectWidth - overlapPx, rectTop), Size(overlapPx, rectHeight))
    if (row < rows - 1) drawRect(overlapColor, Offset(rectLeft, rectTop + rectHeight - overlapPx), Size(rectWidth, overlapPx))
    if (col > 0) drawRect(overlapColor, Offset(rectLeft, rectTop), Size(overlapPx, rectHeight))
    if (row > 0) drawRect(overlapColor, Offset(rectLeft, rectTop), Size(rectWidth, overlapPx))
}

/**
 * Faint blue lines at the printable-area boundary (margin lines). The page surface
 * itself is already drawn as paper-cream by [drawPaperFill] and the image is inset
 * by margin on every side, so no white overlay is needed in the margin area —
 * the user sees the actual paper rather than an opaque overlay.
 */
private fun DrawScope.drawPaneMarginGuide(
    pageLeft: Float, pageTop: Float, pageWidth: Float, pageHeight: Float,
    marginPx: Float,
    alpha: Float = 1f,
) {
    if (marginPx <= 0.5f) return
    if (alpha <= 0.001f) return
    val borderColor = Color(0xFF0A3D62).copy(alpha = 0.45f * alpha.coerceIn(0f, 1f))
    drawLine(borderColor, Offset(pageLeft + marginPx, pageTop + marginPx),
        Offset(pageLeft + pageWidth - marginPx, pageTop + marginPx), 1.2f)
    drawLine(borderColor, Offset(pageLeft + marginPx, pageTop + pageHeight - marginPx),
        Offset(pageLeft + pageWidth - marginPx, pageTop + pageHeight - marginPx), 1.2f)
    drawLine(borderColor, Offset(pageLeft + marginPx, pageTop + marginPx),
        Offset(pageLeft + marginPx, pageTop + pageHeight - marginPx), 1.2f)
    drawLine(borderColor, Offset(pageLeft + pageWidth - marginPx, pageTop + marginPx),
        Offset(pageLeft + pageWidth - marginPx, pageTop + pageHeight - marginPx), 1.2f)
}

/**
 * Outline / cut-marks overlay. The rect sits inside the page by [overlapPx] (the
 * cut line is inside the overlap zone, mirroring how PosterLogic draws cut marks
 * on the actual PDF). Returns early if the user has outlines disabled.
 */
private fun DrawScope.drawCutLineOrOutline(
    viewModel: MainViewModel,
    pageLeft: Float, pageTop: Float, pageWidth: Float, pageHeight: Float,
    overlapPx: Float,
    outlinePx: Float,
    outlineEffect: PathEffect?,
) {
    if (!viewModel.showOutlines) return
    val rx = pageLeft + overlapPx
    val ry = pageTop + overlapPx
    val rw = (pageWidth - 2 * overlapPx).coerceAtLeast(4f)
    val rh = (pageHeight - 2 * overlapPx).coerceAtLeast(4f)
    if (viewModel.outlineStyle == "CropMarks") {
        val arm = min(rw, rh) * 0.10f
        val sw = max(1.2f, outlinePx)
        drawLine(Color.Black, Offset(rx, ry + arm), Offset(rx, ry), sw)
        drawLine(Color.Black, Offset(rx, ry), Offset(rx + arm, ry), sw)
        drawLine(Color.Black, Offset(rx + rw - arm, ry), Offset(rx + rw, ry), sw)
        drawLine(Color.Black, Offset(rx + rw, ry), Offset(rx + rw, ry + arm), sw)
        drawLine(Color.Black, Offset(rx, ry + rh - arm), Offset(rx, ry + rh), sw)
        drawLine(Color.Black, Offset(rx, ry + rh), Offset(rx + arm, ry + rh), sw)
        drawLine(Color.Black, Offset(rx + rw - arm, ry + rh), Offset(rx + rw, ry + rh), sw)
        drawLine(Color.Black, Offset(rx + rw, ry + rh - arm), Offset(rx + rw, ry + rh), sw)
    } else {
        drawRect(
            color = Color.Black.copy(alpha = 0.85f),
            topLeft = Offset(rx, ry),
            size = Size(rw, rh),
            style = Stroke(width = outlinePx, pathEffect = outlineEffect, cap = StrokeCap.Round),
        )
    }
}

/**
 * Grid label (e.g. "A1", "B3") at the bottom-left of the page, with a soft white
 * shadow so it stays legible over photos. Returns early if labels are disabled.
 */
private fun DrawScope.drawPaneLabel(
    viewModel: MainViewModel,
    pageLeft: Float, pageTop: Float, pageWidth: Float, pageHeight: Float,
    row: Int, col: Int,
) {
    if (!viewModel.labelPanes) return
    val label = viewModel.getGridLabel(row, col)
    val labelSize = min(pageWidth, pageHeight) * 0.22f
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawText(
            label,
            pageLeft + pageWidth * 0.08f,
            pageTop + pageHeight - pageHeight * 0.08f,
            Paint().apply {
                color = android.graphics.Color.argb(235, 0, 0, 0)
                textSize = labelSize
                isAntiAlias = true
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setShadowLayer(5f, 0f, 0f, android.graphics.Color.argb(220, 255, 255, 255))
            },
        )
    }
}
