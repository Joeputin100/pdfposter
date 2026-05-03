package com.posterpdf.ui.components

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.posterpdf.R
import com.posterpdf.ml.Capability
import com.posterpdf.ml.CapabilityTier
import com.posterpdf.ml.DeviceCapability
import com.posterpdf.ml.UpscalerOnDevice
import com.posterpdf.ml.cachedMsPerMegapixel
import com.posterpdf.ml.etaForLocal
import com.posterpdf.ml.formatEta
import com.posterpdf.ui.theme.BlueprintBlue700
import com.posterpdf.ui.theme.TrimOrange500
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────────────────────
// Per-model COGS lookup — mirrors backend/functions/src/upscale.ts MODELS map.
// 1 credit = $0.00425 USD (cost-per-credit budget).
// ─────────────────────────────────────────────────────────────────────────────

private const val CREDIT_COST_BUDGET_USD = 0.00425

enum class UpscaleModel { NONE, FREE_LOCAL, TOPAZ_4X, TOPAZ_8X, RECRAFT, AURASR, ESRGAN }

private data class UpscaleOption(
    val model: UpscaleModel,
    val displayName: String,
    val pros: String,
    val cons: String,
    val scale: Int,
    val cogsUsd: (inputMp: Int) -> Double,
)

private val ALL_OPTIONS: List<UpscaleOption> = listOf(
    UpscaleOption(
        model = UpscaleModel.NONE,
        displayName = "Now (pixelated)",
        pros = "Fastest, free, works on any device",
        cons = "Visible pixelation at large prints",
        scale = 1,
        cogsUsd = { _ -> 0.0 },
    ),
    UpscaleOption(
        model = UpscaleModel.FREE_LOCAL,
        displayName = "Free upscale",
        pros = "Free, offline, works without internet",
        cons = "Slower on older phones; 4× max",
        scale = 4,
        cogsUsd = { _ -> 0.0 },
    ),
    UpscaleOption(
        model = UpscaleModel.TOPAZ_4X,
        displayName = "Topaz 4×",
        pros = "Cleanest edges, polished output",
        cons = "Highest cost",
        scale = 4,
        cogsUsd = { inputMp -> inputMp * 16.0 * 0.01 },
    ),
    UpscaleOption(
        model = UpscaleModel.TOPAZ_8X,
        displayName = "Topaz 8×",
        pros = "Same Topaz quality at 2× the print size",
        cons = "4× the cost of 4×",
        scale = 8,
        cogsUsd = { inputMp -> inputMp * 64.0 * 0.01 },
    ),
    UpscaleOption(
        model = UpscaleModel.RECRAFT,
        displayName = "Recraft Crisp",
        pros = "Photo-faithful, 40× cheaper than Topaz",
        cons = "Less crisp on text/UI than Topaz",
        scale = 4,
        cogsUsd = { _ -> 0.004 },
    ),
    UpscaleOption(
        model = UpscaleModel.AURASR,
        displayName = "AuraSR",
        pros = "Fast and cheap (~\$0.01/image)",
        cons = "Occasional artifacts on smooth gradients",
        scale = 4,
        cogsUsd = { inputMp -> inputMp * 16.0 * 0.00125 },
    ),
    UpscaleOption(
        model = UpscaleModel.ESRGAN,
        displayName = "ESRGAN",
        pros = "Open-source classic, cheapest AI option",
        cons = "Less crisp than Topaz",
        scale = 4,
        cogsUsd = { inputMp -> inputMp * 16.0 * 0.00111 },
    ),
)

/** Free options return 0; paid options return ceil(cogs / budget), min 1. */
private fun creditsForOption(option: UpscaleOption, inputMp: Int): Int {
    if (option.model == UpscaleModel.NONE || option.model == UpscaleModel.FREE_LOCAL) return 0
    val cogs = option.cogsUsd(inputMp)
    return ceil(cogs / CREDIT_COST_BUDGET_USD).toInt().coerceAtLeast(1)
}

private fun usdEquivalent(credits: Int, usdPerCredit: Double): String {
    if (usdPerCredit <= 0.0 || credits == 0) return "—"
    return "%.2f".format(credits * usdPerCredit)
}

// Default options shown before "See other AI options" is expanded.
private val DEFAULT_MODELS = setOf(
    UpscaleModel.NONE,
    UpscaleModel.FREE_LOCAL,
    UpscaleModel.TOPAZ_4X,
    UpscaleModel.RECRAFT,
    // BringYourOwn is appended separately as a special card
)

// Extra options shown when expanded.
private val EXTRA_MODELS = setOf(
    UpscaleModel.TOPAZ_8X,
    UpscaleModel.AURASR,
    UpscaleModel.ESRGAN,
)

private const val DEFAULT_BYTES_PER_SECOND = 500_000L

/**
 * Per-model upscale options modal (Phase H-P1.10).
 *
 * Shows a 2-column LazyVerticalGrid with 5 default cards
 * (NONE / FREE_LOCAL / TOPAZ_4X / RECRAFT / BringYourOwn) plus an
 * expandable section for TOPAZ_8X / AURASR / ESRGAN.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LowDpiUpgradeModal(
    sourceBitmap: ImageBitmap,
    inputMp: Int,
    inputBytes: Long,
    currentDpi: Float,
    posterWInches: Double,
    posterHInches: Double,
    creditBalance: Int,
    /** Effective USD per credit at the current SKU ladder; 0.0 hides the price hint. */
    usdPerCredit: Double,
    isAnonymous: Boolean,
    onDismiss: () -> Unit,
    onFreeUpscale: () -> Unit,
    onAiUpscale: (modelId: String) -> Unit,
    onPickAlreadyUpscaled: () -> Unit,
    onSignIn: () -> Unit,
    onBuyCredits: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val severityColor = when {
        currentDpi < 100f -> MaterialTheme.colorScheme.error
        else -> Color(0xFFB58900)
    }

    val context = LocalContext.current

    // Device capability for on-device upscale (gates FREE_LOCAL card button).
    val freeCapability = remember(inputMp, context) {
        Capability.assessLocalUpscale(inputMp, scale = 4, ctx = context)
    }
    val freeEnabled = freeCapability.tier != CapabilityTier.RED

    // On-device ETA from cached benchmark.
    var msPerMp by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(context) { msPerMp = cachedMsPerMegapixel(context) }
    val localOutputMp = remember(inputMp) { inputMp.toLong() * 4 * 4 }
    val localEtaText = remember(localOutputMp, msPerMp) {
        etaForLocal(localOutputMp, msPerMp)?.let(::formatEta) ?: "estimating…"
    }

    // Thumbnail prep — preserve aspect ratio.
    val sourceThumb: Bitmap = remember(sourceBitmap) {
        val src = sourceBitmap.asAndroidBitmap()
        val target = 384
        val ratio = target.toFloat() / maxOf(src.width, src.height)
        val newW = (src.width * ratio).toInt().coerceAtLeast(1)
        val newH = (src.height * ratio).toInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(src, newW, newH, true)
    }

    val pixelatedThumb: ImageBitmap = remember(sourceThumb) {
        val small = Bitmap.createScaledBitmap(sourceThumb, 128, 128, false)
        Bitmap.createScaledBitmap(small, 256, 256, false).asImageBitmap()
    }

    var onDeviceThumb by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(sourceThumb) {
        val result = withContext(Dispatchers.Default) {
            try {
                UpscalerOnDevice.init(context)
                UpscalerOnDevice.upscale(sourceThumb)
            } catch (_: Throwable) {
                Bitmap.createScaledBitmap(sourceThumb, 1024, 1024, true)
            }
        }
        onDeviceThumb = Bitmap.createScaledBitmap(result, 256, 256, true).asImageBitmap()
    }

    // Expand/collapse extra AI options.
    var showExtra by remember { mutableStateOf(false) }

    // Build the visible option list for the grid (BringYourOwn sentinel appended last).
    val visibleOptions: List<UpscaleModel?> = remember(showExtra) {
        val list = mutableListOf<UpscaleModel?>()
        list += ALL_OPTIONS.filter { it.model in DEFAULT_MODELS }.map { it.model }
        if (showExtra) list += ALL_OPTIONS.filter { it.model in EXTRA_MODELS }.map { it.model }
        list += null // sentinel for BringYourOwn
        list
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = severityColor,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "This poster will print at low resolution",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = BlueprintBlue700,
                )
            }
            Text(
                "Current: ${currentDpi.toInt()} DPI  ·  ${"%.0f".format(posterWInches)}\" × ${"%.0f".format(posterHInches)}\"",
                style = MaterialTheme.typography.bodyMedium,
                color = severityColor,
                fontWeight = FontWeight.SemiBold,
            )

            HorizontalDivider()

            // 2-column option grid — fixed height to avoid unbounded scroll conflict.
            val rowCount = (visibleOptions.size + 1) / 2
            val cardHeightDp = 290
            val gridHeight = (rowCount * cardHeightDp + (rowCount - 1) * 12).dp

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(gridHeight),
                userScrollEnabled = false,
            ) {
                items(visibleOptions) { modelOrNull ->
                    if (modelOrNull == null) {
                        BringYourOwnCard(onPick = onPickAlreadyUpscaled)
                    } else {
                        val option = ALL_OPTIONS.first { it.model == modelOrNull }
                        val credits = remember(inputMp) { creditsForOption(option, inputMp) }
                        val usdStr = usdEquivalent(credits, usdPerCredit)
                        val outputDpi = currentDpi * option.scale
                        val hasEnough = creditBalance >= credits

                        UpscaleOptionCard(
                            option = option,
                            outputDpi = outputDpi,
                            credits = credits,
                            usdStr = usdStr,
                            isAnonymous = isAnonymous,
                            hasEnoughCredits = hasEnough,
                            freeCapability = if (option.model == UpscaleModel.FREE_LOCAL) freeCapability else null,
                            freeEnabled = if (option.model == UpscaleModel.FREE_LOCAL) freeEnabled else true,
                            localEtaText = if (option.model == UpscaleModel.FREE_LOCAL) localEtaText else null,
                            pixelatedThumb = if (option.model == UpscaleModel.NONE) pixelatedThumb else null,
                            onDeviceThumb = if (option.model == UpscaleModel.FREE_LOCAL) onDeviceThumb else null,
                            onFreeUpscale = onFreeUpscale,
                            onAiUpscale = { onAiUpscale(option.model.name.lowercase()) },
                            onSignIn = onSignIn,
                            onBuyCredits = onBuyCredits,
                        )
                    }
                }
            }

            // Expand / help row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { showExtra = !showExtra }) {
                    Text(
                        if (showExtra) "Hide other AI options ▴" else "See other AI options ▾",
                        style = MaterialTheme.typography.labelMedium,
                        color = BlueprintBlue700,
                    )
                }
                TextButton(onClick = {
                    Toast.makeText(context, "Comparison demo coming in next update", Toast.LENGTH_SHORT).show()
                }) {
                    Text(
                        "Help me decide…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            HorizontalDivider()

            // Footer levers
            Text(
                "Aim for at least 150 DPI. You have a few ways to get there:",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            LeverRow(
                title = "Reduce poster size",
                body = "Same source image, smaller print → higher DPI. Fastest fix, no upload needed.",
            )
            LeverRow(
                title = "Upgrade source",
                body = "Upscale on-device (free, gated by RAM) or via AI (credits charged per model).",
            )
            LeverRow(
                title = "Bring your own",
                body = "Already upscaled with Canva, OpenArt, Topaz Photo AI, or Magnific? Load the upscaled file directly. PosterPDF is free with any image source — AI credits only cover the inference cost.",
            )

            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text("Reduce poster size instead")
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Option card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun UpscaleOptionCard(
    option: UpscaleOption,
    outputDpi: Float,
    credits: Int,
    usdStr: String,
    isAnonymous: Boolean,
    hasEnoughCredits: Boolean,
    freeCapability: DeviceCapability?,
    freeEnabled: Boolean,
    localEtaText: String?,
    pixelatedThumb: ImageBitmap?,
    onDeviceThumb: ImageBitmap?,
    onFreeUpscale: () -> Unit,
    onAiUpscale: () -> Unit,
    onSignIn: () -> Unit,
    onBuyCredits: () -> Unit,
) {
    val isAi = credits > 0
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Title row
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isAi) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = TrimOrange500,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    option.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = BlueprintBlue700,
                    maxLines = 1,
                )
            }

            // Thumbnail
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                when (option.model) {
                    UpscaleModel.NONE -> {
                        if (pixelatedThumb != null) {
                            Image(
                                bitmap = pixelatedThumb,
                                contentDescription = "Pixelated preview",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(100.dp),
                            )
                        }
                    }
                    UpscaleModel.FREE_LOCAL -> {
                        if (onDeviceThumb != null) {
                            Image(
                                bitmap = onDeviceThumb,
                                contentDescription = "On-device upscale preview",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(100.dp),
                            )
                        } else {
                            CircularProgressIndicator(color = TrimOrange500, modifier = Modifier.size(32.dp))
                        }
                    }
                    else -> {
                        Image(
                            painter = painterResource(id = R.drawable.ai_upscale_demo),
                            contentDescription = "AI upscale demo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(100.dp),
                        )
                    }
                }
            }

            // DPI + cost
            Text(
                "≈ ${outputDpi.toInt()} DPI",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (credits > 0) {
                Text(
                    if (usdStr == "—") "$credits credits" else "$credits credits · ~\$$usdStr",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (option.model == UpscaleModel.FREE_LOCAL && localEtaText != null) {
                Text(
                    if (freeEnabled) "ETA $localEtaText" else (freeCapability?.reason ?: "Unavailable"),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (freeEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
            }

            // Pros (green-tinted)
            Text(
                option.pros,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF2E7D32),
                maxLines = 2,
            )
            // Cons (amber-tinted)
            Text(
                option.cons,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF795548),
                maxLines = 2,
            )

            // Action button
            when {
                option.model == UpscaleModel.NONE -> {
                    // No button — "now" is the status quo
                }
                option.model == UpscaleModel.FREE_LOCAL -> {
                    Button(
                        onClick = onFreeUpscale,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        enabled = freeEnabled && onDeviceThumb != null,
                        colors = ButtonDefaults.buttonColors(containerColor = BlueprintBlue700),
                    ) {
                        Text("Upscale free", style = MaterialTheme.typography.labelSmall)
                    }
                }
                isAnonymous -> {
                    Button(
                        onClick = onSignIn,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BlueprintBlue700),
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_google_g),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Sign in", style = MaterialTheme.typography.labelSmall)
                    }
                }
                !hasEnoughCredits -> {
                    Button(
                        onClick = onBuyCredits,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TrimOrange500),
                    ) {
                        Text("Get more credits", style = MaterialTheme.typography.labelSmall)
                    }
                }
                else -> {
                    Button(
                        onClick = onAiUpscale,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TrimOrange500),
                    ) {
                        Icon(
                            Icons.Default.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Upscale · $credits cr", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BringYourOwn special card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BringYourOwnCard(onPick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.FileUpload,
                    contentDescription = null,
                    tint = BlueprintBlue700,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "Bring your own",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = BlueprintBlue700,
                    maxLines = 1,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.FileUpload,
                    contentDescription = null,
                    tint = BlueprintBlue700,
                    modifier = Modifier.size(40.dp),
                )
            }

            Text(
                "Free with any source",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Canva · OpenArt · Topaz · Magnific",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )

            // Pros/cons placeholders keep visual rhythm consistent
            Text(
                "Works with any upscale tool you already have",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF2E7D32),
                maxLines = 2,
            )
            Text(
                "You handle upscaling before loading the file",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF795548),
                maxLines = 2,
            )

            Button(
                onClick = onPick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BlueprintBlue700),
            ) {
                Text("Show me how…", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared lever row (footer explanation)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LeverRow(title: String, body: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = BlueprintBlue700,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
