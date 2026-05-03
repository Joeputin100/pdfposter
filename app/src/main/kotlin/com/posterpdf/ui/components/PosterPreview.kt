package com.posterpdf.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import com.caverock.androidsvg.SVG
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
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
import com.posterpdf.ui.components.preview.PRINTER_INK_AGSL
import com.posterpdf.ui.components.preview.drawHand
import com.posterpdf.ui.components.preview.drawPrinter
import com.posterpdf.R
import com.posterpdf.ui.components.preview.drawScissors
import com.posterpdf.ui.components.preview.drawTearingBand
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
uniform float iOriginY;

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
    // RC3: iOriginY shifts the sampled wood-grain origin in pixels so that the
    // grain scrolls in sync with the camera pan. The viewport pans DOWN the
    // table during the Panning phase (printer slides up/off-screen); we add
    // (-cameraOffsetY) to fragCoord.y so wood texture appears to slide too.
    float2 sampleCoord = float2(fragCoord.x, fragCoord.y - iOriginY);
    float2 uv = sampleCoord / iResolution.xy;
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
    // Vignette uses screen-space uv (not the scrolled sampleCoord) so the
    // viewport edges stay subtly darker even as the grain scrolls.
    float2 screenUV = fragCoord / iResolution.xy;
    float2 center = screenUV - 0.5;
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

    // One-shot confirm haptic when the cycle enters the Pinning phase
    // (tacks land in the corners — the "it's done" beat). RC3 retiming.
    val lastPhase = remember { mutableStateOf<AssemblyPhase?>(null) }
    LaunchedEffect(phase) {
        if (lastPhase.value != phase && phase == AssemblyPhase.Pinning) {
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

    // RC3 camera-pan offset (in canvas pixels). 0 during Printing; lerps from
    // 0 → -panTargetPx during Panning; holds at -panTargetPx through the rest
    // of the cycle; eases back to 0 during Reset. Applied as a pure Y-translate
    // to every pane and prop drawn inside the inner Canvas, AND fed to the
    // wood-grain shader's iOriginY uniform so the table grain scrolls in sync.
    // RC7: Pan target dropped 0.38 → 0.15. The 0.38 value pushed the top
    // row of pages above the viewport on tall layouts (user feedback +
    // Screenshot_20260503_153224). 0.15 still moves the printer body
    // mostly off-screen but keeps every row visible.
    val cameraOffsetYpx = remember { mutableFloatStateOf(0f) }
    androidx.compose.runtime.SideEffect {
        val panTarget = boxSize.height * 0.15f
        val newValue = if (!cycleEnabled) 0f else when (phase) {
            AssemblyPhase.Printing -> 0f
            AssemblyPhase.Panning -> {
                val k = phaseT
                val eased = if (k < 0.5f) 2f * k * k
                    else 1f - (-2f * k + 2f) * (-2f * k + 2f) / 2f
                -panTarget * eased
            }
            AssemblyPhase.Reset -> -panTarget * (1f - phaseT)
            else -> -panTarget // Hold panned-down through Arranging..Pinning.
        }
        if (cameraOffsetYpx.floatValue != newValue) {
            cameraOffsetYpx.floatValue = newValue
        }
    }
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
                // Phase H-P1.13: branch on SVG. Three-pronged detection
                // (MIME → extension → magic-byte sniff) keeps us robust to
                // providers that mis-report content-type. SVG renders into
                // a 512px-target bitmap (preview-sized; PDF path renders
                // fresh per tile at 300 DPI).
                val resolver = context.contentResolver
                val mime = resolver.getType(uri)?.lowercase()
                val path = uri.toString().lowercase()
                val mimeSaysSvg = mime != null && mime.startsWith("image/svg")
                val pathSaysSvg = path.endsWith(".svg") || path.endsWith(".svgz")
                val isSvg = if (mimeSaysSvg || pathSaysSvg) {
                    true
                } else {
                    // Magic-byte sniff: read up to 256 bytes and look for
                    // the standard XML/SVG markers. Keeps us safe against
                    // octet-stream providers.
                    val sniff = try {
                        resolver.openInputStream(uri)?.use { input ->
                            val buf = ByteArray(256)
                            val n = input.read(buf)
                            if (n <= 0) null else String(buf, 0, n, Charsets.UTF_8).trimStart()
                        }
                    } catch (_: Exception) {
                        null
                    }
                    sniff != null && (
                        sniff.startsWith("<?xml") ||
                            sniff.startsWith("<svg") ||
                            sniff.startsWith("<!DOCTYPE svg")
                        )
                }
                viewModel.sourceIsSvg = isSvg
                if (isSvg) {
                    resolver.openInputStream(uri)?.use { input ->
                        val svg = SVG.getFromInputStream(input)
                        // Pick a render target: SVG intrinsic size if present,
                        // else 1024 — gives the preview enough resolution for
                        // the construction-preview Canvas. Cap to 1024 to keep
                        // memory bounded; the live-preview canvas itself is
                        // ~300dp tall, so we don't need more pixels than that.
                        val intrinsicW = svg.documentWidth
                        val intrinsicH = svg.documentHeight
                        val targetMax = 1024
                        val (w, h) = if (intrinsicW > 0f && intrinsicH > 0f) {
                            val s = (targetMax / kotlin.math.max(intrinsicW, intrinsicH))
                                .coerceAtMost(1f)
                            (intrinsicW * s).toInt().coerceAtLeast(1) to
                                (intrinsicH * s).toInt().coerceAtLeast(1)
                        } else {
                            // Edge case: SVG with no intrinsic dims (e.g. a
                            // viewBox-only document). Render square.
                            targetMax to targetMax
                        }
                        // Force the doc to render at our target size even if
                        // it had no intrinsic dims.
                        svg.setDocumentWidth(w.toFloat())
                        svg.setDocumentHeight(h.toFloat())
                        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        svg.renderToCanvas(AndroidCanvas(b))
                        b
                    }
                } else {
                    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }
            } catch (_: Exception) {
                null
            }
        }
        previewBitmap = bitmap?.asImageBitmap()
        // Phase H-P1.9: lift the source dims to the ViewModel so MainActivity
        // can gate View/Save/Share on currentDpi < 150 without re-decoding.
        bitmap?.let { viewModel.sourcePixelDimensions = it.width to it.height }
    }

    // RuntimeShader is API 33+. On older devices we fall back to a static gradient.
    val woodShader = remember(cycleEnabled) {
        if (cycleEnabled) RuntimeShader(WOOD_AGSL) else null
    }
    // H-P1.8: dot-matrix printer ink streak — AGSL, gated to API 33+; null on
    // older devices (drawPrinter handles the fallback path). The RC3 redesign
    // dropped the dust puff (no more "stack lands on desk" beat).
    val inkShader = remember(cycleEnabled) {
        if (cycleEnabled) RuntimeShader(PRINTER_INK_AGSL) else null
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

        // RC3: gesture coverage extends to the WHOLE preview — table + panes +
        // decorations + hand all pinch-zoom and pan together. The outermost Box
        // holds the gestures + graphicsLayer; the inner Box draws the wood
        // background INSIDE that transform so the table scales/pans with the
        // panes (a fix for the user's "drag and pinch zoom affects the papers
        // only" complaint).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
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
                .clip(RoundedCornerShape(24.dp))
                .drawWithCache {
                    // Camera-pan offset is supplied by the AssemblyPhase clock
                    // via the `cameraOffsetYpx` Compose state below. We can't
                    // read Compose state from inside drawWithCache directly —
                    // drawBehind closes over the live state lambda, so reads
                    // happen at draw time.
                    if (woodShader != null) {
                        val brush = ShaderBrush(woodShader)
                        onDrawBehind {
                            woodShader.setFloatUniform("iResolution", size.width, size.height)
                            // Subtle ambient grain scroll (was hardcoded 0f); ties into the
                            // existing infinite transition `t`.
                            woodShader.setFloatUniform("iTime", t * 0.6f)
                            // RC3: scroll the wood-grain origin in lock-step with
                            // the camera pan. cameraOffsetYpx is negative when
                            // panning DOWN the table (printer slides up). We
                            // negate so the grain appears to slide too.
                            woodShader.setFloatUniform("iOriginY", -cameraOffsetYpx.floatValue)
                            drawRect(brush = brush, size = size)
                        }
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
                modifier = Modifier.fillMaxSize()
            ) {
            val paneInfo = viewModel.getPaneCount()
            // RC5: clipToBounds prevents the camera-pan transform from
            // letting page content (top 2 rows) bleed above the viewport.
            // Without it, the cameraOff translate pushes content up and
            // Canvas itself doesn\'t auto-clip — only the parent Compose
            // layout would, but the way this nests, top overflow was
            // visible to the user.
            Canvas(modifier = Modifier.fillMaxSize().clipToBounds()) {
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

                // ─────────────────────────────────────────────────────────────
                // RC3 construction-arc geometry. Computed once per Canvas frame
                // and read by every pane below + the prop draws further down.
                //  stack center  = where pages collapse onto the desk during
                //                  Panning + Arranging-start.
                //  printer slot  = where pages emerge during Printing / fall back
                //                  during Reset.
                // API <33 devices skip every per-phase offset and render the static
                // assembled output (Phase-C source of truth).
                // ─────────────────────────────────────────────────────────────
                val printableWpx = layout.printableW.toFloat() * layout.scale
                val printableHpx = layout.printableH.toFloat() * layout.scale
                val assembledBlockW = cols * (printableWpx - overlapPx) + overlapPx
                val assembledBlockH = rows * (printableHpx - overlapPx) + overlapPx
                val printerWidth = (size.width * 0.55f).coerceAtMost(360f)
                val printerTopY = -printerWidth * 0.10f      // sits slightly above canvas
                val printerBodyH = printerWidth * 0.55f
                val printerSlotY = printerTopY + printerBodyH * 0.71f
                val printerSlotX = size.width / 2f
                val stackCenterX = layout.layoutLeft + assembledBlockW / 2f
                val stackCenterY = layout.layoutTop + assembledBlockH / 2f
                val cameraOff = cameraOffsetYpx.floatValue

                // RC3: scissors trace the assembled block's PERIMETER during
                // Cutting (top → right → bottom → left). Each edge gets ~25% of
                // the phase. As the scissors pass each edge, that edge's white
                // border alpha fades to 0.
                fun edgeAlpha(start: Float, end: Float): Float {
                    if (phase != AssemblyPhase.Cutting) {
                        return when {
                            phase.ordinal() <= AssemblyPhase.Arranging.ordinal() -> 1f
                            else -> 0f
                        }
                    }
                    return (1f - ((phaseT - start) / (end - start))).coerceIn(0f, 1f)
                }
                val borderTopAlpha    = edgeAlpha(0.00f, 0.25f)
                val borderRightAlpha  = edgeAlpha(0.25f, 0.50f)
                val borderBottomAlpha = edgeAlpha(0.50f, 0.75f)
                val borderLeftAlpha   = edgeAlpha(0.75f, 1.00f)

                // RC3: every pane + every prop is drawn inside ONE outer
                // translate(0, cameraOff). The wood-grain background (drawn
                // behind, in the parent Modifier) takes its iOriginY uniform
                // from the same cameraOffsetYpx state, so the table appears to
                // scroll in lock-step with what's drawn here.
                withTransform({
                    if (cameraOff != 0f) translate(0f, cameraOff)
                }) {

                for (pane in layout.panes) {
                    val r = pane.row
                    val c = pane.col
                    val dx = pane.pageLeft
                    val dy = pane.pageTop
                    val pageW = pane.pageWidth
                    val pageH = pane.pageHeight

                    // Tap-bounds (canvas coords) — include cameraOff so taps land
                    // on the visually-displayed pane regardless of camera-pan.
                    paneBounds.add(PaneBounds(r, c, dx, dy + cameraOff, pageW, pageH))

                    val isJiggled = jiggledPane?.let { it.first == r && it.second == c } == true
                    val paneJiggleAngle = if (isJiggled) jiggleSwing * 4.5f else 0f
                    val paneJiggleDx = if (isJiggled) jiggleSwing * 2.5f else 0f
                    val paneJiggleDy = if (isJiggled) -jiggleAmp * 1.8f else 0f
                    val paneCenter = Offset(dx + pageW / 2f, dy + pageH / 2f)

                    val paneIndex = r * cols + c
                    val paneCount = (rows * cols).coerceAtLeast(1)

                    // Pane home stays at (dx, dy); printer-emerge / arrange / tighten
                    // / etc are expressed as offsets from home.
                    val toPrinterDx = (printerSlotX - pageW / 2f) - dx
                    val toPrinterDy = (printerSlotY - pageH / 2f) - dy
                    // Gap-closing offset for Tightening: shift each pane toward
                    // layout center by the inter-pane gap so panes butt up.
                    val tightenDx = -((c - (cols - 1) / 2f) * gap)
                    val tightenDy = -((r - (rows - 1) / 2f) * gap)

                    // Per-phase pane offset relative to home.
                    var paneOffX: Float
                    var paneOffY: Float
                    var paneAlpha = 1f

                    if (!cycleEnabled) {
                        paneOffX = 0f
                        paneOffY = 0f
                    } else when (phase) {
                        AssemblyPhase.Printing -> {
                            // Stagger each pane by 200ms; per-pane local progress 0..1
                            // over a 1.8s window. Pages slide DOWN out of the slot.
                            val staggerStart = paneIndex * 0.20f
                            val tInPhase = cycleSeconds.floatValue - staggerStart
                            val emergeT = (tInPhase / 1.8f).coerceIn(0f, 1f)
                            val jitterX = sin(tInPhase.toDouble() * Math.PI * 8.0)
                                .toFloat() * 1.6f * (1f - emergeT)
                            val emergedY = toPrinterDy + (printerBodyH * 0.55f) +
                                paneIndex * 6f
                            val easeOut = 1f - (1f - emergeT) * (1f - emergeT)
                            paneOffX = toPrinterDx + jitterX
                            paneOffY = toPrinterDy + (emergedY - toPrinterDy) * easeOut
                            paneAlpha = if (emergeT <= 0f) 0f else 1f
                        }

                        AssemblyPhase.Panning -> {
                            // Panes hold at the printer-emerged position; the CAMERA
                            // moves around them (cameraOff handled by outer transform).
                            val emergedY = toPrinterDy + (printerBodyH * 0.55f) +
                                paneIndex * 6f
                            paneOffX = toPrinterDx
                            paneOffY = emergedY
                        }

                        AssemblyPhase.Arranging -> {
                            // Hand picks panes one at a time. Each pane is staggered
                            // across the phase; before its slice it stays at the
                            // print-stack pile, after its slice it sits at home (with
                            // white borders intact — pre-cut).
                            val emergedY = toPrinterDy + (printerBodyH * 0.55f) +
                                paneIndex * 6f
                            val slice = 1f / paneCount
                            val sliceStart = paneIndex * slice
                            val k = ((phaseT - sliceStart) / slice).coerceIn(0f, 1f)
                            // Smooth ease-in-out for the lift-and-place arc.
                            val eased = if (k < 0.5f) 2f * k * k
                                else 1f - (-2f * k + 2f) * (-2f * k + 2f) / 2f
                            paneOffX = toPrinterDx + (0f - toPrinterDx) * eased
                            paneOffY = emergedY + (0f - emergedY) * eased
                        }

                        AssemblyPhase.Cutting -> {
                            // Holds at home; scissors trace the perimeter and the
                            // page borders fade per edge (handled in drawPaperFill
                            // path below via per-edge alpha rects).
                            paneOffX = 0f
                            paneOffY = 0f
                        }

                        AssemblyPhase.Tightening -> {
                            // Lerp from home → tightened-home (gaps removed).
                            val springT = 1f - (1f - phaseT) * (1f - phaseT) * (1f - phaseT)
                            paneOffX = tightenDx * springT
                            paneOffY = tightenDy * springT
                        }

                        AssemblyPhase.Taping,
                        AssemblyPhase.Pinning -> {
                            // Hold at tightened-home through tape + pin.
                            paneOffX = tightenDx
                            paneOffY = tightenDy
                        }

                        AssemblyPhase.Reset -> {
                            // Brief hold then quick fade-out.
                            paneOffX = tightenDx
                            paneOffY = tightenDy
                            paneAlpha = (1f - phaseT).coerceIn(0f, 1f)
                        }
                    }

                    if (paneAlpha <= 0.01f) continue

                    // Margin-guide alpha — visible while panes have white borders,
                    // gone once the borders are cut.
                    val marginAlpha = if (!cycleEnabled) 1f else when (phase) {
                        AssemblyPhase.Cutting ->
                            (borderTopAlpha + borderRightAlpha +
                                borderBottomAlpha + borderLeftAlpha) / 4f
                        AssemblyPhase.Tightening,
                        AssemblyPhase.Taping,
                        AssemblyPhase.Pinning,
                        AssemblyPhase.Reset -> 0f
                        else -> 1f
                    }

                    withTransform({
                        if (isJiggled) {
                            rotate(paneJiggleAngle, pivot = paneCenter)
                            translate(paneJiggleDx, paneJiggleDy)
                        }
                        if (paneOffX != 0f || paneOffY != 0f) translate(paneOffX, paneOffY)
                    }) {
                        // RC3: paper "border" = the margin band around the printable
                        // area. Before Cutting, full page is paper. During Cutting,
                        // each edge fades independently. After Cutting, only the
                        // printable rect remains as paper.
                        // RC5: leftover paper from imperfect grid-fit. When the
                        // poster image doesn't divide evenly into the page grid,
                        // the rightmost column / bottom row tiles have unused
                        // printable area on their trailing edges. We draw that
                        // band separately so we can fade it with the same
                        // borderRightAlpha / borderBottomAlpha that the page
                        // margins use — i.e. the scissors visibly trim it
                        // during the Cutting phase, then it's gone afterward.
                        val leftoverRight = pane.imageDstWidth - pane.imageContentWidth
                        val leftoverBottom = pane.imageDstHeight - pane.imageContentHeight
                        val paperColor = Color(0xFFFAFAF7)
                        val showFullPaper = !cycleEnabled ||
                            phase.ordinal() <= AssemblyPhase.Arranging.ordinal()
                        if (showFullPaper) {
                            drawPaperFill(dx, dy, pageW, pageH, paperColor = paperColor)
                        } else if (phase == AssemblyPhase.Cutting) {
                            // Image-content rect always at full alpha.
                            drawPaperFill(
                                pageLeft = pane.imageDstLeft,
                                pageTop = pane.imageDstTop,
                                pageWidth = pane.imageContentWidth,
                                pageHeight = pane.imageContentHeight,
                                paperColor = paperColor,
                            )
                            // RC6 — torn paper effect. tearProgress is the
                            // *inverse* of edgeAlpha so the band tears as the
                            // scissors pass: 0=intact, 1=fully torn and fallen.
                            val rightTear = 1f - borderRightAlpha
                            val bottomTear = 1f - borderBottomAlpha
                            if (leftoverRight > 0f) {
                                drawTearingBand(
                                    bandLeft = pane.imageDstLeft + pane.imageContentWidth,
                                    bandTop = pane.imageDstTop,
                                    bandWidth = leftoverRight,
                                    bandHeight = pane.imageDstHeight,
                                    tearProgress = rightTear,
                                    isVertical = true,
                                    seed = r * cols + c,
                                )
                            }
                            if (leftoverBottom > 0f) {
                                drawTearingBand(
                                    bandLeft = pane.imageDstLeft,
                                    bandTop = pane.imageDstTop + pane.imageContentHeight,
                                    bandWidth = pane.imageContentWidth,
                                    bandHeight = leftoverBottom,
                                    tearProgress = bottomTear,
                                    isVertical = false,
                                    seed = (r * cols + c) * 31 + 7,
                                )
                            }
                            drawBorderBands(
                                pageLeft = dx, pageTop = dy,
                                pageWidth = pageW, pageHeight = pageH,
                                marginPx = marginPx,
                                topAlpha = borderTopAlpha,
                                rightAlpha = borderRightAlpha,
                                bottomAlpha = borderBottomAlpha,
                                leftAlpha = borderLeftAlpha,
                            )
                        } else {
                            // Tightening / Taping / Pinning / Reset: only the
                            // image-content rect remains as paper — leftover
                            // is gone (consistent with the cut having happened).
                            drawPaperFill(
                                pageLeft = pane.imageDstLeft,
                                pageTop = pane.imageDstTop,
                                pageWidth = pane.imageContentWidth,
                                pageHeight = pane.imageContentHeight,
                                paperColor = paperColor,
                            )
                        }

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
                        drawPaneMarginGuide(dx, dy, pageW, pageH, marginPx, alpha = marginAlpha)
                        drawCutLineOrOutline(
                            viewModel,
                            pane.imageDstLeft, pane.imageDstTop,
                            pane.imageDstWidth, pane.imageDstHeight,
                            overlapPx, outlinePx, outlineEffect,
                        )
                        drawPaneLabel(
                            viewModel,
                            pane.imageDstLeft, pane.imageDstTop,
                            pane.imageDstWidth, pane.imageDstHeight,
                            r, c,
                        )
                    }
                }

                // ─────────────────────────────────────────────────────────────
                // RC3 props — printer, scissors (perimeter trace), hand 👌,
                // tape, thumb tacks. All gated to API 33+ (cycleEnabled).
                // ─────────────────────────────────────────────────────────────
                if (cycleEnabled) {
                    // ── Printer body — visible during Printing, fades during
                    //    Panning (camera leaves it behind), hidden through the
                    //    rest, fades back in during Reset (ready for next loop).
                    val printerAppearT = when (phase) {
                        AssemblyPhase.Printing -> 1f
                        AssemblyPhase.Panning -> (1f - phaseT).coerceIn(0f, 1f)
                        AssemblyPhase.Reset -> phaseT.coerceIn(0f, 1f)
                        else -> 0f
                    }
                    val inkScanT = (cycleSeconds.floatValue / 1.6f) % 1f
                    if (printerAppearT > 0f) {
                        drawPrinter(
                            cx = printerSlotX,
                            topY = printerTopY,
                            width = printerWidth,
                            appearT = printerAppearT,
                            inkScanT = inkScanT,
                            inkShader = inkShader,
                        )
                    }

                    // Tightened assembled-rect (post-Tightening), reused by
                    // Taping/Pinning + the hand draws below.
                    val tCenterX = stackCenterX
                    val tCenterY = stackCenterY
                    val tightenedW = assembledBlockW - (cols - 1) * gap
                    val tightenedH = assembledBlockH - (rows - 1) * gap
                    val tLeft = tCenterX - tightenedW / 2f
                    val tTop = tCenterY - tightenedH / 2f
                    val tRight = tCenterX + tightenedW / 2f
                    val tBottom = tCenterY + tightenedH / 2f

                    // ── Hand 👌 — drives Arranging, Tightening, Taping, Pinning.
                    // RC5: bumped 0.45 → 0.65 of the smaller printable axis so
                    // the hand reads as a real human-scale instrument rather
                    // than a cursor. Still relative to paper size, so a bigger
                    // poster gets a proportionally bigger hand.
                    val handSize = (min(printableWpx, printableHpx)) * 0.65f
                    val handOffFrameY = size.height - cameraOff + handSize
                    val handOffFrameX = size.width + handSize

                    when (phase) {
                        AssemblyPhase.Arranging -> {
                            // Slide in from below-right, hover over the pane being
                            // placed at this moment, slide out once each pane is placed.
                            val outerPaneCount = layout.panes.size.coerceAtLeast(1)
                            val slice = 1f / outerPaneCount
                            val activeIndex = (phaseT / slice).toInt().coerceIn(0, outerPaneCount - 1)
                            val activePane = layout.panes[activeIndex]
                            val targetX = activePane.pageLeft + activePane.pageWidth / 2f +
                                handSize * 0.30f
                            val targetY = activePane.pageTop + activePane.pageHeight / 2f +
                                handSize * 0.30f
                            val sliceStart = activeIndex * slice
                            val sliceLocal = ((phaseT - sliceStart) / slice).coerceIn(0f, 1f)
                            val approachK = (sliceLocal / 0.3f).coerceIn(0f, 1f)
                            val retractK = ((sliceLocal - 0.7f) / 0.3f).coerceIn(0f, 1f)
                            val hx = handOffFrameX +
                                (targetX - handOffFrameX) * approachK -
                                (targetX - handOffFrameX) * retractK
                            val hy = handOffFrameY +
                                (targetY - handOffFrameY) * approachK -
                                (targetY - handOffFrameY) * retractK
                            drawHand(hx, hy, handSize, alpha = 1f - retractK * 0.85f)
                        }

                        AssemblyPhase.Tightening -> {
                            // Hand sweeps over the assembly center, "pushing" panes in.
                            val approachK = (phaseT / 0.3f).coerceIn(0f, 1f)
                            val retractK = ((phaseT - 0.7f) / 0.3f).coerceIn(0f, 1f)
                            val cx0 = tCenterX + handSize * 0.30f
                            val cy0 = tCenterY + handSize * 0.30f
                            val hx = handOffFrameX +
                                (cx0 - handOffFrameX) * approachK -
                                (cx0 - handOffFrameX) * retractK
                            val hy = handOffFrameY +
                                (cy0 - handOffFrameY) * approachK -
                                (cy0 - handOffFrameY) * retractK
                            drawHand(hx, hy, handSize, alpha = 1f - retractK * 0.85f)
                        }

                        AssemblyPhase.Taping -> {
                            // Hand orbits the assembly center as the strips fall in.
                            val angle = (phaseT * Math.PI * 2.0).toFloat()
                            val orbitR = (min(tightenedW, tightenedH)) * 0.30f
                            val hx = tCenterX + kotlin.math.cos(angle) * orbitR +
                                handSize * 0.20f
                            val hy = tCenterY + kotlin.math.sin(angle) * orbitR +
                                handSize * 0.20f
                            drawHand(hx, hy, handSize, alpha = 0.95f)
                        }

                        AssemblyPhase.Pinning -> {
                            // Hand visits each of the 4 corners in turn (TL, TR, BL, BR).
                            val corners = listOf(
                                tLeft to tTop, tRight to tTop,
                                tLeft to tBottom, tRight to tBottom,
                            )
                            val cornerSlice = 1f / 4f
                            val cornerIdx = (phaseT / cornerSlice).toInt().coerceIn(0, 3)
                            val (tx, ty) = corners[cornerIdx]
                            val sliceLocal = ((phaseT - cornerIdx * cornerSlice) / cornerSlice)
                                .coerceIn(0f, 1f)
                            val approachK = (sliceLocal / 0.5f).coerceIn(0f, 1f)
                            val targetX = tx + handSize * 0.20f
                            val targetY = ty + handSize * 0.20f
                            val hx = handOffFrameX + (targetX - handOffFrameX) * approachK
                            val hy = handOffFrameY + (targetY - handOffFrameY) * approachK
                            drawHand(hx, hy, handSize, alpha = 0.95f)
                        }

                        else -> { /* no hand */ }
                    }

                    // ── Scissors prop — RC3: trace the perimeter of the assembled
                    //    block (NOT slice through panes). Top L→R, right T→B,
                    //    bottom R→L, left B→T. Each edge gets ~25% of the phase.
                    if (phase == AssemblyPhase.Cutting) {
                        val scissorsSize = (min(assembledBlockW, assembledBlockH)) * 0.30f
                        val left = layout.layoutLeft
                        val top = layout.layoutTop
                        val right = layout.layoutLeft + assembledBlockW
                        val bottom = layout.layoutTop + assembledBlockH
                        val sx: Float
                        val sy: Float
                        val rotDeg: Float
                        when {
                            phaseT < 0.25f -> {
                                val k = phaseT / 0.25f
                                sx = left + (right - left) * k
                                sy = top
                                rotDeg = 0f
                            }
                            phaseT < 0.50f -> {
                                val k = (phaseT - 0.25f) / 0.25f
                                sx = right
                                sy = top + (bottom - top) * k
                                rotDeg = 90f
                            }
                            phaseT < 0.75f -> {
                                val k = (phaseT - 0.50f) / 0.25f
                                sx = right - (right - left) * k
                                sy = bottom
                                rotDeg = 180f
                            }
                            else -> {
                                val k = (phaseT - 0.75f) / 0.25f
                                sx = left
                                sy = bottom - (bottom - top) * k
                                rotDeg = 270f
                            }
                        }
                        drawScissors(
                            cx = sx, cy = sy,
                            sizePx = scissorsSize,
                            rotationDegrees = rotDeg,
                            alpha = 1f,
                        )
                    }

                    // ── Tape strips — RC3: applied during Taping phase, staggered
                    //    one strip at a time. NEVER visible before Taping. Strip
                    //    coords use the TIGHTENED layout (post-gap-close), so the
                    //    tape sits exactly on the seams between adjacent printable
                    //    rects.
                    val tapeAppearT = when (phase) {
                        AssemblyPhase.Taping -> phaseT.coerceIn(0f, 1f)
                        AssemblyPhase.Pinning -> 1f
                        AssemblyPhase.Reset -> (1f - phaseT).coerceIn(0f, 1f)
                        else -> 0f
                    }
                    if (tapeAppearT > 0f && (cols > 1 || rows > 1)) {
                        val seamStepX = printableWpx - overlapPx
                        val seamStepY = printableHpx - overlapPx
                        val tapeLen = printableWpx * 0.45f
                        val tapeH = 14f
                        val verticalSeamCount = rows * (cols - 1)
                        val horizontalSeamCount = (rows - 1) * cols
                        val totalStrips = (verticalSeamCount + horizontalSeamCount).coerceAtLeast(1)
                        var stripIdx = 0
                        for (rr in 0 until rows) {
                            for (cc in 0 until cols - 1) {
                                val seamX = tLeft + (cc + 1) * seamStepX - overlapPx / 2f
                                val seamY = tTop + rr * seamStepY + printableHpx / 2f
                                val stripT = stripStaggerT(tapeAppearT, stripIdx, totalStrips)
                                drawScotchTape(
                                    centerX = seamX, centerY = seamY,
                                    length = tapeLen, height = tapeH,
                                    rotationDegrees = 90f + ((rr * 13 + cc * 7) % 9 - 4).toFloat(),
                                    appearT = stripT,
                                )
                                stripIdx++
                            }
                        }
                        for (rr in 0 until rows - 1) {
                            for (cc in 0 until cols) {
                                val seamX = tLeft + cc * seamStepX + printableWpx / 2f
                                val seamY = tTop + (rr + 1) * seamStepY - overlapPx / 2f
                                val stripT = stripStaggerT(tapeAppearT, stripIdx, totalStrips)
                                drawScotchTape(
                                    centerX = seamX, centerY = seamY,
                                    length = tapeLen, height = tapeH,
                                    rotationDegrees = ((rr * 11 + cc * 5) % 9 - 4).toFloat(),
                                    appearT = stripT,
                                )
                                stripIdx++
                            }
                        }
                    }

                    // ── Thumb tacks — RC3: drop during Pinning phase (each corner
                    //    on its own 25% slice), fade out during Reset.
                    val pinT = when (phase) {
                        AssemblyPhase.Pinning -> phaseT.coerceIn(0f, 1f)
                        AssemblyPhase.Reset -> (1f - phaseT).coerceIn(0f, 1f)
                        else -> 0f
                    }
                    if (pinT > 0f) {
                        val tackR = 9f
                        val inset = 6f
                        val tlT = (pinT / 0.25f).coerceIn(0f, 1f)
                        val trT = ((pinT - 0.25f) / 0.25f).coerceIn(0f, 1f)
                        val blT = ((pinT - 0.50f) / 0.25f).coerceIn(0f, 1f)
                        val brT = ((pinT - 0.75f) / 0.25f).coerceIn(0f, 1f)
                        drawThumbTack(tLeft + inset, tTop + inset, tackR, tlT)
                        drawThumbTack(tRight - inset, tTop + inset, tackR, trT)
                        drawThumbTack(tLeft + inset, tBottom - inset, tackR, blT)
                        drawThumbTack(tRight - inset, tBottom - inset, tackR, brT)
                    }
                }

                } // end withTransform(camera-pan)
            }
            // RC7: phase caption at the bottom of the construction preview
            // viewport. Narrates each step in plain English (already wired
            // to stringResource so the locale-switch on RC7 also localizes
            // the captions). Hidden during Reset.
            if (cycleEnabled && phase != AssemblyPhase.Reset) {
                val captionRes = when (phase) {
                    AssemblyPhase.Printing -> R.string.preview_caption_printing
                    AssemblyPhase.Panning -> R.string.preview_caption_panning
                    AssemblyPhase.Arranging -> R.string.preview_caption_arranging
                    AssemblyPhase.Cutting -> R.string.preview_caption_cutting
                    AssemblyPhase.Tightening -> R.string.preview_caption_tightening
                    AssemblyPhase.Taping -> R.string.preview_caption_taping
                    AssemblyPhase.Pinning -> R.string.preview_caption_pinning
                    AssemblyPhase.Reset -> null
                }
                if (captionRes != null) {
                    androidx.compose.material3.Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Black.copy(alpha = 0.55f),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp, start = 16.dp, end = 16.dp),
                    ) {
                        androidx.compose.material3.Text(
                            text = androidx.compose.ui.res.stringResource(captionRes),
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
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
        val pendingLabel = viewModel.pendingUpscaleModelLabel
        if ((isLowDpi || pendingLabel != null) && previewBitmap != null) {
            // RC4: showLowDpiModal lives on viewModel so the new MainActivity
            // "Sharpen for print" CTA can also drive the modal. We read/write
            // it directly here — no local alias.
            var showBringYourOwnHelp by remember { mutableStateOf(false) }
            Spacer(Modifier.height(8.dp))
            // RC7: when an upscale model is queued, swap the warning Card
            // for an "Upscaling with X to Y DPI" Card on tertiaryContainer
            // so it no longer reads as an error. Tap still opens the modal.
            val cardContainer = if (pendingLabel != null)
                MaterialTheme.colorScheme.tertiaryContainer
            else
                MaterialTheme.colorScheme.errorContainer
            val cardOnContainer = if (pendingLabel != null)
                MaterialTheme.colorScheme.onTertiaryContainer
            else
                MaterialTheme.colorScheme.onErrorContainer
            Card(
                onClick = { viewModel.showLowDpiModal = true },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = cardContainer),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    text = if (pendingLabel != null) {
                        val targetDpi = (currentDpi * 4f).toInt()
                        androidx.compose.ui.res.stringResource(
                            R.string.preview_upscaling_card, pendingLabel, targetDpi,
                        )
                    } else {
                        "Low resolution: ${currentDpi.toInt()} DPI · Tap to upscale ↑"
                    },
                    modifier = Modifier.padding(12.dp),
                    color = cardOnContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (viewModel.showLowDpiModal) {
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
                    // Phase H-P1.13: when the source is SVG, the modal swaps
                    // the upscale grid for a vector-explainer banner and keeps
                    // only the BringYourOwn card enabled.
                    sourceIsSvg = viewModel.sourceIsSvg,
                    // TODO(G12): replace placeholder 0 with viewModel.creditBalance.collectAsState().value
                    creditBalance = 0,
                    // TODO(G12): replace 0.119 with usdPerCredit derived from
                    // pricing/current; 0.119 is the SKU-ladder default at 50% margin.
                    usdPerCredit = 0.119,
                    // RC3 fix: derive from actual auth session, not placeholder
                    isAnonymous = viewModel.authSession.isAnonymous || !viewModel.authSession.signedIn,
                    isAdmin = viewModel.isAdmin,
                    onDismiss = { viewModel.showLowDpiModal = false },
                    onFreeUpscale = {
                        viewModel.showLowDpiModal = false
                        viewModel.pendingUpscaleModelLabel = "Free upscale"
                        viewModel.runFreeUpscale(context)
                    },
                    // TODO(G12): wire to viewModel.runAiUpscale(modelId, inputMpInt)
                    onAiUpscale = { modelId ->
                        viewModel.showLowDpiModal = false
                        viewModel.pendingUpscaleModelLabel = when (modelId) {
                            "topaz" -> "Topaz Gigapixel"
                            "recraft" -> "Recraft Crisp"
                            "aurasr" -> "AuraSR"
                            "esrgan" -> "ESRGAN"
                            else -> modelId
                        }
                    },
                    // TODO(G12): wire to viewModel.pickAlreadyUpscaledImage()
                    onPickAlreadyUpscaled = { viewModel.showLowDpiModal = false },
                    // H-P2.6: BringYourOwn card now opens the walkthrough dialog
                    // first; the dialog's "Choose file" button calls
                    // onPickAlreadyUpscaled itself.
                    onShowBringYourOwnHelp = {
                        viewModel.showLowDpiModal = false
                        showBringYourOwnHelp = true
                    },
                    // TODO(G12): wire to viewModel.signInWithGoogle()
                    onSignIn = { viewModel.showLowDpiModal = false },
                    // TODO(G12): wire to viewModel.openPurchaseSheet()
                    onBuyCredits = { viewModel.showLowDpiModal = false },
                    onCompareModels = {
                        viewModel.showLowDpiModal = false
                        viewModel.showUpscaleComparison = true
                    },
                )
            }
            if (showBringYourOwnHelp) {
                com.posterpdf.ui.components.BringYourOwnHelpDialog(
                    onDismiss = { showBringYourOwnHelp = false },
                    onPickAlreadyUpscaled = {
                        showBringYourOwnHelp = false
                        // TODO(G12): wire to viewModel.pickAlreadyUpscaledImage().
                        // The button currently dismisses; the actual file-picker
                        // launch is staged for the same VM hookup as the modal.
                    },
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

/**
 * RC3: per-edge fading "white border" bands around a page during the Cutting
 * phase. Each band is the margin region on one side of the page; alpha 1 =
 * fully visible (uncut), 0 = invisible (scissors have just passed). When all
 * four hit zero, only the printable rect remains as paper.
 *
 * Layout:
 *  ┌──────────────────────────────┐
 *  │            top               │
 *  ├───┬──────────────────────┬───┤
 *  │ l │  printable (handled  │ r │
 *  │ e │  by the caller's     │ i │
 *  │ f │  drawPaperFill on    │ g │
 *  │ t │  the inset rect)     │ h │
 *  │   │                      │ t │
 *  ├───┴──────────────────────┴───┤
 *  │           bottom             │
 *  └──────────────────────────────┘
 */
private fun DrawScope.drawBorderBands(
    pageLeft: Float, pageTop: Float, pageWidth: Float, pageHeight: Float,
    marginPx: Float,
    topAlpha: Float,
    rightAlpha: Float,
    bottomAlpha: Float,
    leftAlpha: Float,
) {
    if (marginPx <= 0.5f) return
    val paper = Color(0xFFFAFAF7)
    if (topAlpha > 0.01f) {
        drawRect(
            color = paper.copy(alpha = topAlpha),
            topLeft = Offset(pageLeft, pageTop),
            size = Size(pageWidth, marginPx),
        )
    }
    if (bottomAlpha > 0.01f) {
        drawRect(
            color = paper.copy(alpha = bottomAlpha),
            topLeft = Offset(pageLeft, pageTop + pageHeight - marginPx),
            size = Size(pageWidth, marginPx),
        )
    }
    if (leftAlpha > 0.01f) {
        drawRect(
            color = paper.copy(alpha = leftAlpha),
            topLeft = Offset(pageLeft, pageTop + marginPx),
            size = Size(marginPx, pageHeight - 2f * marginPx),
        )
    }
    if (rightAlpha > 0.01f) {
        drawRect(
            color = paper.copy(alpha = rightAlpha),
            topLeft = Offset(pageLeft + pageWidth - marginPx, pageTop + marginPx),
            size = Size(marginPx, pageHeight - 2f * marginPx),
        )
    }
}

/**
 * Stagger helper for tape-strip reveals. Given the phase's overall progress
 * [tapeAppearT] and an integer [stripIdx] of [totalStrips], returns the
 * individual strip's 0..1 appearT. Each strip lights up at idx/total with a
 * 30% ramp window — produces a clean one-after-another reveal.
 */
private fun stripStaggerT(
    tapeAppearT: Float,
    stripIdx: Int,
    totalStrips: Int,
): Float {
    if (totalStrips <= 0) return tapeAppearT
    val start = stripIdx.toFloat() / totalStrips.toFloat()
    val window = (1f / totalStrips.toFloat()).coerceAtLeast(0.05f)
    return ((tapeAppearT - start) / window).coerceIn(0f, 1f)
}
