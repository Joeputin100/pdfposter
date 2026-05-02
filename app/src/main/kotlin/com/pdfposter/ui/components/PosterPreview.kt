package com.pdfposter.ui.components

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RuntimeShader
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pdfposter.MainViewModel
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

    var tapAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(16)
        }
    }
    val tapCurl = if (tapAt == 0L) 0f else {
        val elapsed = ((now - tapAt).coerceAtLeast(0L)).toFloat()
        val p = (elapsed / 1400f).coerceIn(0f, 1f)
        if (p >= 1f) 0f else sin(p * Math.PI).toFloat().coerceIn(0f, 1f)
    }
    val autoCurl = sin((t * 2f * Math.PI).toFloat()).coerceAtLeast(0f)
    val globalCurl = max(tapCurl, autoCurl)

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

    val woodShader = remember { RuntimeShader(WOOD_AGSL) }

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
                    woodShader.setFloatUniform("iResolution", size.width, size.height)
                    woodShader.setFloatUniform("iTime", 0f)
                    val brush = ShaderBrush(woodShader)
                    onDrawBehind { drawRect(brush = brush, size = size) }
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
                            tapAt = System.currentTimeMillis()
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
                val (_, rows, cols) = paneInfo
                paneBounds.clear()

                val padding = 28f
                val gap = 18f
                val availableW = size.width - 2 * padding
                val availableH = size.height - 2 * padding
                val pw = viewModel.posterWidth.toDoubleOrNull() ?: 1.0
                val ph = viewModel.posterHeight.toDoubleOrNull() ?: 1.0
                val scale = min(
                    (availableW - (cols - 1) * gap).toDouble() / pw,
                    (availableH - (rows - 1) * gap).toDouble() / ph
                ).toFloat()

                val posterDrawW = (pw * scale).toFloat()
                val posterDrawH = (ph * scale).toFloat()
                val startX = (size.width - (posterDrawW + (cols - 1) * gap)) / 2f
                val startY = (size.height - (posterDrawH + (rows - 1) * gap)) / 2f

                val unitScale = scale.toDouble()
                val marginPx = ((viewModel.margin.toDoubleOrNull() ?: 0.0) * unitScale).toFloat()
                val overlapPx = ((viewModel.overlap.toDoubleOrNull() ?: 0.0) * unitScale).toFloat()
                val printableW = if (cols > 1) (posterDrawW + (cols - 1) * overlapPx) / cols else posterDrawW
                val printableH = if (rows > 1) (posterDrawH + (rows - 1) * overlapPx) / rows else posterDrawH

                // Layout each page as separate sheets with guaranteed visual gap.
                // Source sampling still uses overlap-aware coordinates so content remains accurate.
                val layoutTotalW = cols * printableW + (cols - 1) * gap
                val layoutTotalH = rows * printableH + (rows - 1) * gap
                val sheetStartX = (size.width - layoutTotalW) / 2f
                val sheetStartY = (size.height - layoutTotalH) / 2f

                val src = previewBitmap
                val srcW = src?.width ?: 0
                val srcH = src?.height ?: 0
                val srcPerCanvasX = if (src != null) srcW / posterDrawW else 1f
                val srcPerCanvasY = if (src != null) srcH / posterDrawH else 1f

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

                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val tilePosterX = c * (printableW - overlapPx)
                        val tilePosterY = r * (printableH - overlapPx)
                        val dx = sheetStartX + c * (printableW + gap)
                        val dy = sheetStartY + r * (printableH + gap)

                        paneBounds.add(PaneBounds(r, c, dx, dy, printableW, printableH))

                        val isJiggled = jiggledPane?.let { it.first == r && it.second == c } == true
                        val paneJiggleAngle = if (isJiggled) jiggleSwing * 4.5f else 0f
                        val paneJiggleDx = if (isJiggled) jiggleSwing * 2.5f else 0f
                        val paneJiggleDy = if (isJiggled) -jiggleAmp * 1.8f else 0f
                        val paneCenter = Offset(dx + printableW / 2f, dy + printableH / 2f)

                        // individual pane curl wave
                        val panePos = (c + r).toFloat() / max(1f, (rows + cols - 2).toFloat())
                        val paneCurl = (globalCurl * 1.6f - panePos * 0.35f).coerceIn(0f, 1f)
                        val cornerCurlSize = min(printableW, printableH) * (0.08f + 0.40f * paneCurl)

                        withTransform({
                            if (isJiggled) {
                                rotate(paneJiggleAngle, pivot = paneCenter)
                                translate(paneJiggleDx, paneJiggleDy)
                            }
                        }) {
                            drawPaperFill(dx, dy, printableW, printableH)
                            if (src != null) {
                                val paneSrcX = (tilePosterX * srcPerCanvasX).toInt().coerceIn(0, srcW)
                                val paneSrcY = (tilePosterY * srcPerCanvasY).toInt().coerceIn(0, srcH)
                                val paneSrcTileW = (printableW * srcPerCanvasX).toInt().coerceIn(1, srcW - paneSrcX)
                                val paneSrcTileH = (printableH * srcPerCanvasY).toInt().coerceIn(1, srcH - paneSrcY)
                                drawPaneImage(
                                    src = src,
                                    imageDstLeft = dx, imageDstTop = dy,
                                    imageDstWidth = printableW, imageDstHeight = printableH,
                                    srcX = paneSrcX, srcY = paneSrcY,
                                    srcTileW = paneSrcTileW, srcTileH = paneSrcTileH,
                                )
                            }
                            drawPaneOverlapZones(
                                pageLeft = dx, pageTop = dy,
                                pageWidth = printableW, pageHeight = printableH,
                                overlapPx = overlapPx,
                                row = r, col = c, rows = rows, cols = cols,
                            )
                            drawPaneMarginGuide(dx, dy, printableW, printableH, marginPx)
                            drawCutLineOrOutline(viewModel, dx, dy, printableW, printableH, overlapPx, outlinePx, outlineEffect)
                            drawPaneLabel(viewModel, dx, dy, printableW, printableH, r, c)

                            if (paneCurl > 0.02f) {
                                val br = Offset(dx + printableW, dy + printableH)
                                val corner = cornerCurlSize.coerceAtMost(min(printableW, printableH) * 0.65f)
                                val flapPath = Path().apply {
                                    // Curved corner flap (not triangular fold)
                                    moveTo(br.x, br.y)
                                    lineTo(br.x - corner, br.y)
                                    quadraticBezierTo(
                                        br.x - corner * 0.30f,
                                        br.y - corner * 0.30f,
                                        br.x,
                                        br.y - corner
                                    )
                                    close()
                                }

                                drawPath(flapPath, Color.Black.copy(alpha = 0.18f + paneCurl * 0.24f))

                                clipPath(flapPath) {
                                    withTransform({
                                        rotate(-34f * paneCurl, pivot = br)
                                        scale(
                                            scaleX = 0.56f + (1f - paneCurl) * 0.24f,
                                            scaleY = 1f,
                                            pivot = br
                                        )
                                        translate(-corner * 0.08f * paneCurl, -corner * 0.30f * paneCurl)
                                    }) {
                                        // Backside of paper for curled corner
                                        drawRect(
                                            color = Color(0xFFF6F6F2),
                                            topLeft = Offset(br.x - corner, br.y - corner),
                                            size = Size(corner, corner)
                                        )
                                        drawRect(
                                            color = Color.Black.copy(alpha = 0.16f + paneCurl * 0.20f),
                                            topLeft = Offset(br.x - corner, br.y - corner),
                                            size = Size(corner, corner)
                                        )
                                        drawLine(
                                            color = Color.White.copy(alpha = 0.55f),
                                            start = Offset(br.x - corner * 0.95f, br.y - 1f),
                                            end = Offset(br.x - 1f, br.y - corner * 0.95f),
                                            strokeWidth = max(1.2f, 2.8f * paneCurl)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendSwatch(Color(0xFF1976D2).copy(alpha = 0.55f), "Margin")
            LegendSwatch(Color(0xFFFF6F00).copy(alpha = 0.55f), "Overlap")
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
) {
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.32f),
        topLeft = Offset(pageLeft + 4f, pageTop + 6f),
        size = Size(pageWidth, pageHeight),
        cornerRadius = CornerRadius(2f, 2f),
    )
    drawRect(Color(0xFFFAFAF7), Offset(pageLeft, pageTop), Size(pageWidth, pageHeight))
}

/**
 * Sample [src] at the pre-computed source-rect [srcX,srcY,srcTileW,srcTileH] and paint
 * it into the dst rect at [imageDstLeft,imageDstTop] with size [imageDstWidth,imageDstHeight].
 * Caller is responsible for null-checking [src] and clamping the source-rect ints.
 */
private fun DrawScope.drawPaneImage(
    src: ImageBitmap,
    imageDstLeft: Float, imageDstTop: Float,
    imageDstWidth: Float, imageDstHeight: Float,
    srcX: Int, srcY: Int,
    srcTileW: Int, srcTileH: Int,
) {
    drawImage(
        image = src,
        srcOffset = IntOffset(srcX, srcY),
        srcSize = IntSize(srcTileW, srcTileH),
        dstOffset = IntOffset(imageDstLeft.toInt(), imageDstTop.toInt()),
        dstSize = IntSize(imageDstWidth.toInt(), imageDstHeight.toInt()),
    )
}

/**
 * Orange-tinted overlap zones drawn on the seam edges of each pane. Edges that touch
 * a neighbor get a tint; outer edges (no neighbor) are left clean.
 */
private fun DrawScope.drawPaneOverlapZones(
    pageLeft: Float, pageTop: Float,
    pageWidth: Float, pageHeight: Float,
    overlapPx: Float,
    row: Int, col: Int, rows: Int, cols: Int,
) {
    if (overlapPx <= 0.5f) return
    val overlapColor = Color(0xFFFF6F00).copy(alpha = 0.28f)
    if (col < cols - 1) drawRect(overlapColor, Offset(pageLeft + pageWidth - overlapPx, pageTop), Size(overlapPx, pageHeight))
    if (row < rows - 1) drawRect(overlapColor, Offset(pageLeft, pageTop + pageHeight - overlapPx), Size(pageWidth, overlapPx))
    if (col > 0) drawRect(overlapColor, Offset(pageLeft, pageTop), Size(overlapPx, pageHeight))
    if (row > 0) drawRect(overlapColor, Offset(pageLeft, pageTop), Size(pageWidth, overlapPx))
}

/**
 * White margin overlay (covers image bleed at the page edges) plus a faint blue
 * boundary line at the printable-area edge. Behavior-preserving — colors and
 * geometry exactly match the pre-refactor inline lambda.
 */
private fun DrawScope.drawPaneMarginGuide(
    pageLeft: Float, pageTop: Float, pageWidth: Float, pageHeight: Float,
    marginPx: Float,
) {
    if (marginPx <= 0.5f) return
    drawRect(Color.White, Offset(pageLeft, pageTop), Size(pageWidth, marginPx))
    drawRect(Color.White, Offset(pageLeft, pageTop + pageHeight - marginPx), Size(pageWidth, marginPx))
    drawRect(Color.White, Offset(pageLeft, pageTop), Size(marginPx, pageHeight))
    drawRect(Color.White, Offset(pageLeft + pageWidth - marginPx, pageTop), Size(marginPx, pageHeight))
    val borderColor = Color(0xFF1976D2).copy(alpha = 0.6f)
    drawLine(borderColor, Offset(pageLeft, pageTop + marginPx), Offset(pageLeft + pageWidth, pageTop + marginPx), 1.2f)
    drawLine(borderColor, Offset(pageLeft, pageTop + pageHeight - marginPx), Offset(pageLeft + pageWidth, pageTop + pageHeight - marginPx), 1.2f)
    drawLine(borderColor, Offset(pageLeft + marginPx, pageTop), Offset(pageLeft + marginPx, pageTop + pageHeight), 1.2f)
    drawLine(borderColor, Offset(pageLeft + pageWidth - marginPx, pageTop), Offset(pageLeft + pageWidth - marginPx, pageTop + pageHeight), 1.2f)
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
