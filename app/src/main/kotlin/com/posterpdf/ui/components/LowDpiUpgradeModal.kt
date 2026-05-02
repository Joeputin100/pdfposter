package com.posterpdf.ui.components

import android.graphics.Bitmap
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.posterpdf.ml.etaForFal
import com.posterpdf.ml.etaForLocal
import com.posterpdf.ml.formatEta
import com.posterpdf.ui.theme.BlueprintBlue700
import com.posterpdf.ui.theme.TrimOrange500
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 1 credit = 5 MP of FAL output capacity. Mirror of the constant in
// backend/functions/src/pricing.ts and upscale.ts. If either changes,
// change all three. See docs/superpowers/plans/2026-05-02-phase-g-economics-revision.md.
private const val MP_PER_CREDIT = 5

private fun creditsForUpscale(inputMp: Int, scale: Int): Int {
    val outputMp = inputMp.toDouble() * scale * scale
    return ceil(outputMp / MP_PER_CREDIT).toInt().coerceAtLeast(1)
}

private fun outputMegapixels(inputMp: Int, scale: Int): Long =
    inputMp.toLong() * scale * scale

// Default network bandwidth assumption for FAL upload/download estimates,
// in bytes/sec. ~500 KB/s is conservative-typical for mobile + wifi
// blended. Tighten with NetworkInfo / Connectivity API readings later.
private const val DEFAULT_BYTES_PER_SECOND = 500_000L

/**
 * Three-Mona-Lisa low-DPI upgrade modal (Plan G10), revised for Phase G
 * economics: credit cost now scales with `input_mp × scale²` and the modal
 * surfaces the math live. Four cards across:
 *
 *  1. **Now (pixelated)** — what the print would look like at the current DPI.
 *  2. **Free upscale (on-device)** — actual ESRGAN output. Capability-gated:
 *     RED tier devices show the card but the button is disabled with a
 *     "device too small" caption.
 *  3. **AI upscale** — Topaz via FAL. Button shows live credits + USD,
 *     subtitle shows the ETA range.
 *  4. **I'll bring my own** — opens the file picker directly so users with
 *     Canva / OpenArt / Topaz Photo AI / Magnific output can skip the
 *     credit charge entirely.
 *
 * The footer reminds users that PosterPDF is free with any image source —
 * the credits cover FAL inference cost, not the poster pipeline.
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
    onAiUpscale: (tier: Int) -> Unit, // tier in {4, 8}
    onPickAlreadyUpscaled: () -> Unit,
    onSignIn: () -> Unit,
    onBuyCredits: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val severityColor = when {
        currentDpi < 100f -> MaterialTheme.colorScheme.error
        else -> Color(0xFFB58900) // amber for 100..149
    }

    // Live credit math — recomputed when tier changes.
    var aiTier by remember { mutableStateOf(4) }
    val tier4Credits = remember(inputMp) { creditsForUpscale(inputMp, 4) }
    val tier8Credits = remember(inputMp) { creditsForUpscale(inputMp, 8) }
    val tierCost = if (aiTier == 4) tier4Credits else tier8Credits
    val hasEnoughCredits = creditBalance >= tierCost

    val context = LocalContext.current

    // Device capability for on-device upscale. Use the 4× tier as the
    // baseline check — if the device can't handle 4× of this image it
    // can't handle 8× either.
    val freeCapability = remember(inputMp, context) {
        Capability.assessLocalUpscale(inputMp, scale = 4, ctx = context)
    }
    val freeEnabled = freeCapability.tier != CapabilityTier.RED

    // ETAs — on-device uses the cached benchmark; FAL uses the empirical curve.
    var msPerMp by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(context) {
        msPerMp = cachedMsPerMegapixel(context)
    }
    val localOutputMp = remember(inputMp) { outputMegapixels(inputMp, 4) }
    val localEtaText = remember(localOutputMp, msPerMp) {
        etaForLocal(localOutputMp, msPerMp)?.let(::formatEta) ?: "estimating…"
    }
    val falEtaText = remember(inputMp, aiTier, inputBytes) {
        val outputMp = outputMegapixels(inputMp, aiTier)
        etaForFal(inputBytes, outputMp, DEFAULT_BYTES_PER_SECOND)?.let(::formatEta)
            ?: "—"
    }

    // Pre-build the source 256×256 thumbnail once; both the pixelated tile and
    // the on-device upscale tile derive from it.
    val sourceThumb: Bitmap = remember(sourceBitmap) {
        val src = sourceBitmap.asAndroidBitmap()
        Bitmap.createScaledBitmap(src, 256, 256, true)
    }

    // Pixelated tile: downsample 256→128 nearest-neighbor, then upsample 128→256
    // nearest-neighbor. `filter=false` is the magic flag for nearest-neighbor.
    val pixelatedThumb: ImageBitmap = remember(sourceThumb) {
        val small = Bitmap.createScaledBitmap(sourceThumb, 128, 128, false)
        val big = Bitmap.createScaledBitmap(small, 256, 256, false)
        big.asImageBitmap()
    }

    // On-device upscale tile: kick off coroutine on first composition; null
    // while computing → CircularProgressIndicator overlay.
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

    val tierUsd = usdEquivalent(tierCost, usdPerCredit)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
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

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                item {
                    NowCard(
                        thumb = pixelatedThumb,
                        currentDpi = currentDpi,
                    )
                }
                item {
                    FreeUpscaleCard(
                        thumb = onDeviceThumb,
                        previewDpi = currentDpi * 4f,
                        capability = freeCapability,
                        etaText = localEtaText,
                        enabled = freeEnabled,
                        onUpscale = onFreeUpscale,
                    )
                }
                item {
                    AiUpscaleCard(
                        previewDpi = currentDpi * if (aiTier == 4) 4f else 8f,
                        tier = aiTier,
                        creditBalance = creditBalance,
                        isAnonymous = isAnonymous,
                        hasEnoughCredits = hasEnoughCredits,
                        tierCost = tierCost,
                        tierUsd = tierUsd,
                        etaText = falEtaText,
                        onAiUpscale = { onAiUpscale(aiTier) },
                        onSignIn = onSignIn,
                        onBuyCredits = onBuyCredits,
                    )
                }
                item {
                    BringYourOwnCard(onPick = onPickAlreadyUpscaled)
                }
            }

            // AI tier radio toggle (4× / 8×) — labels carry the live credit
            // counts so the user sees how scale affects price before tapping.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TierRadio(
                    selected = aiTier == 4,
                    label = "4× · $tier4Credits credits${tierLabelUsd(tier4Credits, usdPerCredit)}",
                    onClick = { aiTier = 4 },
                )
                TierRadio(
                    selected = aiTier == 8,
                    label = "8× · $tier8Credits credits${tierLabelUsd(tier8Credits, usdPerCredit)}",
                    onClick = { aiTier = 8 },
                )
            }

            HorizontalDivider()

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
                body = "Upscale on-device (free, gated by RAM) or via AI ($tierCost credits / ~$$tierUsd).",
            )
            LeverRow(
                title = "Bring your own",
                body = "Already upscaled with Canva, OpenArt, Topaz Photo AI, or Magnific? Load the upscaled file directly. PosterPDF is free with any image source — AI credits only cover the FAL inference cost.",
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

private fun usdEquivalent(credits: Int, usdPerCredit: Double): String {
    if (usdPerCredit <= 0.0) return "—"
    return "%.2f".format(credits * usdPerCredit)
}

private fun tierLabelUsd(credits: Int, usdPerCredit: Double): String {
    if (usdPerCredit <= 0.0) return ""
    return " (~$%.2f)".format(credits * usdPerCredit)
}

@Composable
private fun NowCard(thumb: ImageBitmap, currentDpi: Float) {
    Card(
        modifier = Modifier.width(160.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Now",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = BlueprintBlue700,
            )
            Box(
                modifier = Modifier
                    .size(136.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
            ) {
                Image(
                    bitmap = thumb,
                    contentDescription = "Pixelated preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(136.dp),
                )
            }
            Text(
                "${currentDpi.toInt()} DPI",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FreeUpscaleCard(
    thumb: ImageBitmap?,
    previewDpi: Float,
    capability: DeviceCapability,
    etaText: String,
    enabled: Boolean,
    onUpscale: () -> Unit,
) {
    Card(
        modifier = Modifier.width(160.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Free upscale",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = BlueprintBlue700,
            )
            Box(
                modifier = Modifier
                    .size(136.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb,
                        contentDescription = "On-device upscale preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(136.dp),
                    )
                } else {
                    CircularProgressIndicator(color = TrimOrange500)
                }
            }
            Text(
                "≈ ${previewDpi.toInt()} DPI",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (enabled) "ETA $etaText" else (capability.reason ?: "Unavailable"),
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
            )
            Button(
                onClick = onUpscale,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = enabled && thumb != null,
                colors = ButtonDefaults.buttonColors(containerColor = BlueprintBlue700),
            ) {
                Text("Upscale free", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun AiUpscaleCard(
    previewDpi: Float,
    tier: Int,
    creditBalance: Int,
    isAnonymous: Boolean,
    hasEnoughCredits: Boolean,
    tierCost: Int,
    tierUsd: String,
    etaText: String,
    onAiUpscale: () -> Unit,
    onSignIn: () -> Unit,
    onBuyCredits: () -> Unit,
) {
    Card(
        modifier = Modifier.width(160.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = TrimOrange500,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "AI upscale",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = BlueprintBlue700,
                )
            }
            Box(
                modifier = Modifier
                    .size(136.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ai_upscale_demo),
                    contentDescription = "AI upscale demo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(136.dp),
                )
            }
            Text(
                "≈ ${previewDpi.toInt()} DPI",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "ETA $etaText",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                isAnonymous -> {
                    Button(
                        onClick = onSignIn,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BlueprintBlue700),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Login,
                            contentDescription = null,
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
                            .height(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TrimOrange500),
                    ) {
                        Text(
                            "Get more credits",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                else -> {
                    Button(
                        onClick = onAiUpscale,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TrimOrange500),
                    ) {
                        Icon(
                            Icons.Default.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (tierUsd == "—") "$tierCost credits · ${tier}×"
                            else "$tierCost credits · ~$$tierUsd",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BringYourOwnCard(onPick: () -> Unit) {
    Card(
        modifier = Modifier.width(160.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.FileUpload,
                    contentDescription = null,
                    tint = BlueprintBlue700,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "Bring your own",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = BlueprintBlue700,
                )
            }
            Box(
                modifier = Modifier
                    .size(136.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.FileUpload,
                    contentDescription = null,
                    tint = BlueprintBlue700,
                    modifier = Modifier.size(48.dp),
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
            )
            Button(
                onClick = onPick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BlueprintBlue700),
            ) {
                Text("Load file", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun TierRadio(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

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
