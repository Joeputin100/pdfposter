package com.posterpdf.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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

enum class UpscaleModel { NONE, FREE_LOCAL, TOPAZ, RECRAFT, AURASR, ESRGAN }

internal data class UpscaleOption(
    val model: UpscaleModel,
    val displayName: String,
    val pros: String,
    val cons: String,
    /** Default scale for display (used by NONE/FREE_LOCAL only). For paid
     *  models the actual scale is picked dynamically by [pickScale] using
     *  the same logic as backend/functions/src/upscale.ts pickScale. */
    val scale: Int,
    /** Supported scale factors in ascending order — paid models pick the
     *  smallest that produces output_mp >= targetMp × 1.2. */
    val supportedScales: List<Int>,
    /** USD-per-output-MP. Recraft is flat-rate (set perOutputMp=0 and use
     *  flatUsd). */
    val perOutputMp: Double,
    val flatUsd: Double = 0.0,
)

// RC18: Cards sorted ascending by typical-cost (free → cheapest paid → premium):
//   NONE, FREE_LOCAL, RECRAFT ($0.004 flat), ESRGAN (~3cr), AURASR (~4cr), TOPAZ (~30cr).
// Copy framing distills each paid model into a "use-when" pros line + a
// "trade-off" cons line — a compressed 4Ps/6Ms read so the user can pick by
// fit (text vs photo vs art) and price together, instead of guessing.
internal val ALL_OPTIONS: List<UpscaleOption> = listOf(
    UpscaleOption(
        model = UpscaleModel.NONE,
        displayName = "Now (pixelated)",
        pros = "Fastest, free, works on any device",
        cons = "Visible pixelation at large prints",
        scale = 1,
        supportedScales = listOf(1),
        perOutputMp = 0.0,
    ),
    UpscaleOption(
        model = UpscaleModel.FREE_LOCAL,
        displayName = "Free upscale",
        pros = "Free, offline, works without internet",
        cons = "Slower on older phones; 4× max",
        scale = 4,
        supportedScales = listOf(4),
        perOutputMp = 0.0,
    ),
    UpscaleOption(
        model = UpscaleModel.RECRAFT,
        displayName = "Recraft Crisp",
        pros = "Best for photos & portraits — keeps the original look",
        cons = "Softer than Topaz on text and hard edges",
        scale = 4,
        supportedScales = listOf(4),
        perOutputMp = 0.0,
        flatUsd = 0.004,
    ),
    UpscaleOption(
        model = UpscaleModel.ESRGAN,
        displayName = "ESRGAN",
        pros = "Budget cleanup — predictable open-source baseline",
        cons = "Dated model; can over-smooth fine detail",
        scale = 4,
        supportedScales = listOf(4),
        perOutputMp = 0.00111,
    ),
    UpscaleOption(
        model = UpscaleModel.AURASR,
        displayName = "AuraSR",
        pros = "Best for art, anime & illustrations — fast GAN polish",
        cons = "Occasional artifacts on skies and skin",
        scale = 4,
        supportedScales = listOf(4),
        perOutputMp = 0.00125,
    ),
    UpscaleOption(
        model = UpscaleModel.TOPAZ,
        displayName = "Topaz Gigapixel",
        pros = "Best for text, line art & print-shop polish",
        cons = "30× the cost for ~10–20% sharper edges",
        scale = 4,
        supportedScales = listOf(2, 4, 6, 8),
        perOutputMp = 0.01,
    ),
)

/**
 * RC8 — DPI-aware scale picker, mirrors backend/functions/src/upscale.ts.
 * Pick the smallest supported scale that produces enough pixels to hit the
 * user's target DPI on the chosen poster size. Falls back to the largest
 * available scale if no scale meets the target. Pre-RC8 the client always
 * assumed 4× regardless of target DPI, so Topaz cost was displayed as $4.52
 * on an 8MP source × 16 scale-factor even when only 2× was needed to hit
 * 150 DPI.
 *
 * RC17 — two pricing-accuracy fixes:
 *  1. inputMp is now a Double (was Int with `coerceAtLeast(1)`). Sub-1 MP
 *     sources (e.g., 768×1024 = 0.79 MP) were rounding up to 1, which
 *     inflated the downstream outputMp from 12.58 → 16 MP and over-charged
 *     Topaz/AuraSR/ESRGAN by 25-67% on small sources.
 *  2. Dropped the 1.2× headroom on `required`. Topaz's
 *     `supportedScales = [2,4,6,8]` was forcing a jump from 4→6 whenever
 *     scale 4 met `targetMp` exactly but didn't clear `targetMp × 1.2` —
 *     billing the user for ~125% extra MP they never asked for. The 1.2×
 *     was justified as "20% crop headroom," but real bleed/crop is <5% of
 *     dimensions; targeting `targetMp` exactly is the honest behavior.
 */
private fun pickScale(
    option: UpscaleOption,
    inputMp: Double,
    posterWInches: Double,
    posterHInches: Double,
    targetDpi: Int,
): Int {
    val targetMp = (posterWInches * targetDpi) * (posterHInches * targetDpi) / 1_000_000.0
    for (s in option.supportedScales) {
        if (inputMp * s * s >= targetMp) return s
    }
    return option.supportedScales.last()
}

private fun cogsForOption(
    option: UpscaleOption,
    inputMp: Double,
    pickedScale: Int,
): Double {
    if (option.flatUsd > 0.0) return option.flatUsd
    val outputMp = inputMp * pickedScale * pickedScale
    return outputMp * option.perOutputMp
}

/** Free options return 0; paid options return ceil(cogs / budget), min 1. */
private fun creditsForOption(
    option: UpscaleOption,
    inputMp: Double,
    pickedScale: Int,
): Int {
    if (option.model == UpscaleModel.NONE || option.model == UpscaleModel.FREE_LOCAL) return 0
    val cogs = cogsForOption(option, inputMp, pickedScale)
    return ceil(cogs / CREDIT_COST_BUDGET_USD).toInt().coerceAtLeast(1)
}

private fun usdEquivalent(credits: Int, usdPerCredit: Double): String {
    if (usdPerCredit <= 0.0 || credits == 0) return "—"
    return "%.2f".format(credits * usdPerCredit)
}

// RC3+: all 5 model cards visible at once (NONE / FREE_LOCAL / TOPAZ /
// RECRAFT / AURASR / ESRGAN) plus a BringYourOwn sentinel — no more
// expansion link. Selected card gets a glow ring (see UpscaleOptionCard).
private val ALL_MODELS = setOf(
    UpscaleModel.NONE,
    UpscaleModel.FREE_LOCAL,
    UpscaleModel.TOPAZ,
    UpscaleModel.RECRAFT,
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
    /** RC17: source megapixels as a Double (was Int with `coerceAtLeast(1)`).
     *  Sub-1 MP sources rounded up to 1 inflated the COGS calc by up to 67%.
     *  Capability + ETA helpers still take Int and use ceil() locally. */
    inputMp: Double,
    inputBytes: Long,
    currentDpi: Float,
    posterWInches: Double,
    posterHInches: Double,
    creditBalance: Int,
    /** Effective USD per credit at the current SKU ladder; 0.0 hides the price hint. */
    usdPerCredit: Double,
    isAnonymous: Boolean,
    /** RC7: admin accounts have unlimited credits — short-circuits the
     *  hasEnoughCredits check so admin never sees "Get more credits". */
    isAdmin: Boolean = false,
    /** RC8: user\'s target print DPI (drawer slider). Used to pick the
     *  smallest model scale factor that hits the target — mirrors the
     *  backend\'s pickScale logic so the displayed cost matches what the
     *  user will actually be charged. */
    targetDpi: Int = 150,
    /**
     * Phase H-P1.13: when true the source is an SVG (vector) and upscaling
     * makes no sense — the modal hides the 4 raster-upscale cards (NONE,
     * FREE_LOCAL, TOPAZ_4X, RECRAFT, plus the EXTRA models when expanded)
     * and replaces them with a single explainer banner. The BringYourOwn
     * card stays visible (user might want to swap to a raster source).
     */
    sourceIsSvg: Boolean = false,
    /** RC13b: when true, swap AGSL glitter for animated-gradient pulse on AI cards. */
    usePulseEffect: Boolean = false,
    onDismiss: () -> Unit,
    onFreeUpscale: () -> Unit,
    onAiUpscale: (modelId: String) -> Unit,
    onPickAlreadyUpscaled: () -> Unit,
    onShowBringYourOwnHelp: () -> Unit,
    onSignIn: () -> Unit,
    onBuyCredits: () -> Unit,
    onCompareModels: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val severityColor = when {
        currentDpi < 100f -> MaterialTheme.colorScheme.error
        else -> Color(0xFFB58900)
    }

    val context = LocalContext.current

    // Capability + ETA helpers still operate in integer-MP space. Use
    // ceil() so a 0.79 MP source counts as "1 MP of work" for the RAM-
    // sufficiency check (slightly conservative is fine here; the exact
    // pricing path uses the Double inputMp directly).
    val inputMpInt = remember(inputMp) { ceil(inputMp).toInt().coerceAtLeast(1) }

    // Device capability for on-device upscale (gates FREE_LOCAL card button).
    val freeCapability = remember(inputMpInt, context) {
        Capability.assessLocalUpscale(inputMpInt, scale = 4, ctx = context)
    }
    val freeEnabled = freeCapability.tier != CapabilityTier.RED

    // On-device ETA from cached benchmark.
    var msPerMp by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(context) { msPerMp = cachedMsPerMegapixel(context) }
    val localOutputMp = remember(inputMpInt) { inputMpInt.toLong() * 4 * 4 }
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

    // RC16: chunky-pixel "Now" preview — heavily downscale then nearest-
    // neighbor upscale to make the contrast against the high-quality
    // alternatives visceral. 32px source → 256px display = ~8× pixel
    // size, instantly readable as "low res."
    val pixelatedThumb: ImageBitmap = remember(sourceThumb) {
        val small = Bitmap.createScaledBitmap(sourceThumb, 32, 32, false)
        Bitmap.createScaledBitmap(small, 256, 256, false).asImageBitmap()
    }

    // RC16: "Free upscale" preview is now just the source image (no actual
    // ESRGAN render). The pre-RC16 version ran on-device upscale for the
    // thumbnail which took ~30 s on the user's phone, blocking the modal
    // and making the card useless as a quick A/B. Showing the original
    // is fine for the comparison: "Now" is pixelated, "Free / AI" are
    // the original — the side-by-side itself sells the upgrade.
    val sourceThumbBitmap = remember(sourceThumb) { sourceThumb.asImageBitmap() }
    val onDeviceThumb: ImageBitmap = sourceThumbBitmap

    // RC3+: tracks user-selected model card for the glow effect. Defaults to
    // null (nothing selected); user can tap any card to highlight it.
    var selectedModel by remember { mutableStateOf<UpscaleModel?>(null) }
    // RC22-7: when non-null, the marketing-detail dialog opens for this
    // model. Set by either the lower-right Info icon on a card, or a
    // double-tap on the card body. Cleared by the dialog's onDismiss.
    var detailModel by remember { mutableStateOf<UpscaleModel?>(null) }

    // All 6 model options always visible + BringYourOwn sentinel last.
    val visibleOptions: List<UpscaleModel?> = remember {
        ALL_OPTIONS.filter { it.model in ALL_MODELS }.map { it.model } + listOf<UpscaleModel?>(null)
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
                    if (sourceIsSvg) "Vector source — no upscale needed"
                    else "This poster will print at low resolution",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (!sourceIsSvg) {
                Text(
                    "Current: ${currentDpi.toInt()} DPI  ·  ${"%.0f".format(posterWInches)}\" × ${"%.0f".format(posterHInches)}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = severityColor,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                Text(
                    "Poster size: ${"%.0f".format(posterWInches)}\" × ${"%.0f".format(posterHInches)}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            HorizontalDivider()

            if (sourceIsSvg) {
                // Phase H-P1.13: vector source — replace the upscale grid with
                // a single explainer banner. The BringYourOwn card stays
                // visible below (user might want to swap to a raster source).
                SvgVectorBanner()
                BringYourOwnCard(onPick = onShowBringYourOwnHelp, modifier = Modifier.fillMaxWidth())
            } else {
                // 2-column option grid — fixed height to avoid unbounded scroll conflict.
                // RC20: bumped 290 → 340 so the pros/cons rows can render 3 lines
                // each without ellipsis. User reported truncation on real devices
                // even with English copy because the marketing-framework strings
                // ("Use when … / Trade-off …") run long.
                val rowCount = (visibleOptions.size + 1) / 2
                val cardHeightDp = 340
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
                            BringYourOwnCard(onPick = onShowBringYourOwnHelp)
                        } else {
                            val option = ALL_OPTIONS.first { it.model == modelOrNull }
                            // RC8: pick scale dynamically based on target DPI
                            // (mirrors backend pickScale). Fixes user-reported
                            // "$4.52 Topaz cost on a poster that only needs 2×".
                            val pickedScale = remember(option.model, inputMp, posterWInches, posterHInches, targetDpi) {
                                pickScale(option, inputMp, posterWInches, posterHInches, targetDpi)
                            }
                            val credits = remember(option.model, inputMp, pickedScale) {
                                creditsForOption(option, inputMp, pickedScale)
                            }
                            val usdStr = usdEquivalent(credits, usdPerCredit)
                            val outputDpi = currentDpi * pickedScale
                            val hasEnough = isAdmin || creditBalance >= credits

                            UpscaleOptionCard(
                                option = option,
                                outputDpi = outputDpi,
                                credits = credits,
                                usdStr = usdStr,
                                isAnonymous = isAnonymous,
                                hasEnoughCredits = hasEnough,
                                isSelected = selectedModel == option.model,
                                freeCapability = if (option.model == UpscaleModel.FREE_LOCAL) freeCapability else null,
                                freeEnabled = if (option.model == UpscaleModel.FREE_LOCAL) freeEnabled else true,
                                localEtaText = if (option.model == UpscaleModel.FREE_LOCAL) localEtaText else null,
                                pixelatedThumb = if (option.model == UpscaleModel.NONE) pixelatedThumb else null,
                                // RC16: pass the source thumbnail to every
                                // non-NONE card (Free + AI) so the new
                                // instant-preview design works for all of
                                // them. The "AI" cards overlay a brand
                                // stripe inside the thumbnail box.
                                onDeviceThumb = onDeviceThumb,
                                usePulseEffect = usePulseEffect,
                                onCardClick = { selectedModel = option.model },
                                onFreeUpscale = onFreeUpscale,
                                onAiUpscale = { onAiUpscale(option.model.name.lowercase()) },
                                onSignIn = onSignIn,
                                onBuyCredits = onBuyCredits,
                                onShowDetail = { detailModel = option.model },
                            )
                        }
                    }
                }

                // RC3+: dropped expand/collapse — all cards visible above. Just
                // the "Help me decide…" link remains, anchored right.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onCompareModels) {
                        Text(stringResource(R.string.lowdpi_help_me_decide),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }

            HorizontalDivider()

            // Footer levers
            if (!sourceIsSvg) {
                Text(stringResource(R.string.lowdpi_aim_for_dpi),
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
            }

            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(if (sourceIsSvg) "Got it" else "Reduce poster size instead")
            }

            Spacer(Modifier.height(8.dp))
        }
    }
    // RC22-7: full-screen marketing detail dialog. Triggered by the lower-right
    // Info icon on each card, or a double-tap anywhere on the card. Surfaces
    // the long-form pitch (pickWhen / standsOut / worthThePrice) that doesn't
    // fit in the in-grid card body.
    val open = detailModel
    if (open != null) {
        val option = ALL_OPTIONS.first { it.model == open }
        val copy = detailFor(open)
        val (actionLabel, action) = when {
            open == UpscaleModel.NONE -> "" to { }
            open == UpscaleModel.FREE_LOCAL -> "Use free upscaler" to onFreeUpscale
            isAnonymous -> "Sign in to upscale" to onSignIn
            !(isAdmin || creditBalance >= creditsForOption(option, inputMp,
                pickScale(option, inputMp, posterWInches, posterHInches, targetDpi))) ->
                "Get more credits" to onBuyCredits
            else -> "Upscale with ${option.displayName}" to { onAiUpscale(open.name.lowercase()) }
        }
        ModelDetailDialog(
            displayName = option.displayName,
            bestFor = copy.bestFor,
            pickWhen = copy.pickWhen,
            standsOut = copy.standsOut,
            worthThePrice = copy.worthThePrice,
            primaryActionLabel = actionLabel,
            onPrimaryAction = action,
            showPrimaryAction = open != UpscaleModel.NONE,
            onDismiss = { detailModel = null },
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Per-model icon mapping (H-P1.11)
// ─────────────────────────────────────────────────────────────────────────────

/** Returns the drawable resource id for the model's abstract icon thumbnail. */
private fun iconForModel(model: UpscaleModel): Int = when (model) {
    UpscaleModel.TOPAZ -> R.drawable.ic_model_premium
    UpscaleModel.RECRAFT -> R.drawable.ic_model_clean
    UpscaleModel.AURASR -> R.drawable.ic_model_swirl
    UpscaleModel.ESRGAN -> R.drawable.ic_model_basic
    else -> R.drawable.ai_upscale_demo
}

// ─────────────────────────────────────────────────────────────────────────────
// Option card
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UpscaleOptionCard(
    option: UpscaleOption,
    outputDpi: Float,
    credits: Int,
    usdStr: String,
    isAnonymous: Boolean,
    hasEnoughCredits: Boolean,
    isSelected: Boolean,
    freeCapability: DeviceCapability?,
    freeEnabled: Boolean,
    localEtaText: String?,
    pixelatedThumb: ImageBitmap?,
    onDeviceThumb: ImageBitmap,
    usePulseEffect: Boolean,
    onCardClick: () -> Unit,
    onFreeUpscale: () -> Unit,
    onAiUpscale: () -> Unit,
    onSignIn: () -> Unit,
    onBuyCredits: () -> Unit,
    // RC22-7: show the detailed model dialog. Triggered by the
    // lower-right Info icon button OR a double-tap anywhere on the card.
    onShowDetail: () -> Unit = {},
) {
    val isAi = credits > 0
    val isAiModel = option.model in setOf(
        UpscaleModel.TOPAZ,
        UpscaleModel.RECRAFT,
        UpscaleModel.AURASR,
        UpscaleModel.ESRGAN,
    )
    // RC3+: selected card glows — primary border + shadow + slight scale up.
    val primary = MaterialTheme.colorScheme.primary
    val borderColor by animateColorAsState(
        if (isSelected) primary else Color.Transparent,
        label = "card_border",
    )
    val scaleValue by animateFloatAsState(
        if (isSelected) 1.03f else 1f,
        label = "card_scale",
    )
    // RC15: rewrote the card layering so glitter/pulse is actually visible.
    // RC14's setup was Card(containerColor=surfaceVariant) + .glintEffect
    // modifier on the Card's outer chain, but Card paints containerColor
    // INSIDE its surface, on top of any modifier-drawn pixels. So the
    // glitter (drawn before drawContent in glintEffect's drawWithCache
    // lambda) was completely covered by the opaque card. Fix: Card now
    // has containerColor = Transparent; an inner Box paints the paper
    // tone explicitly, glintEffect/pulseEffect overlays the glitter on
    // top of that paint, and the Column's children draw on top of both.
    val outlineColor = MaterialTheme.colorScheme.outline
    val paperFill = MaterialTheme.colorScheme.surfaceVariant
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = Modifier
            .graphicsLayer { scaleX = scaleValue; scaleY = scaleValue }
            .shadow(
                elevation = if (isSelected) 12.dp else 2.dp,
                shape = RoundedCornerShape(20.dp),
                spotColor = primary,
            )
            .border(
                width = if (isSelected) 2.5.dp else 1.dp,
                color = if (isSelected) borderColor else outlineColor,
                shape = RoundedCornerShape(20.dp),
            )
            // RC22-7: combinedClickable so the same Card surface responds to
            // single-tap (select-this-model) AND double-tap (open the
            // marketing-detail dialog). Single-tap goes to onCardClick like
            // before; double-tap routes to onShowDetail.
            .combinedClickable(
                onClick = onCardClick,
                onDoubleClick = onShowDetail,
            ),
    ) {
        Column(
            modifier = Modifier
                .background(paperFill)
                .let {
                    // Glitter / pulse drawn between the paper fill (Box bg
                    // above) and the column children (drawContent below).
                    if (usePulseEffect) it.pulseEffect(active = isAiModel)
                    else it.glintEffect(active = isAiModel)
                }
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Title row
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isAi) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    option.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }

            // Thumbnail. RC16 redesign: instantaneous previews. NONE shows
            // the chunky-pixel proxy; FREE_LOCAL and the AI cards both show
            // the original source image (the side-by-side against the
            // pixelated NOW card sells the upgrade), with AI cards adding
            // a model-icon + magic-wand brand stripe at the bottom so the
            // user can tell which model produced which result later.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                // RC22-7: lower-right info button. Single tap → onShowDetail
                // (full-screen marketing detail). Sits on top of the thumbnail
                // so the icon is always reachable; the surrounding circle gives
                // the icon a tap target on busy/dark thumbnails.
                IconButton(
                    onClick = onShowDetail,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp)
                        .size(28.dp),
                ) {
                    Surface(
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = Color.Black.copy(alpha = 0.55f),
                        modifier = Modifier.size(24.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = "More about ${option.displayName}",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                when (option.model) {
                    UpscaleModel.NONE -> {
                        if (pixelatedThumb != null) {
                            Image(
                                bitmap = pixelatedThumb,
                                contentDescription = "Pixelated preview",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth().height(100.dp),
                            )
                        }
                    }
                    UpscaleModel.FREE_LOCAL -> {
                        Image(
                            bitmap = onDeviceThumb,
                            contentDescription = "Free upscale preview (original image)",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().height(100.dp),
                        )
                    }
                    else -> {
                        // RC16: AI card thumbnail = original image with
                        // bottom-aligned brand stripe (model icon + 🪄).
                        Image(
                            bitmap = onDeviceThumb,
                            contentDescription = "${option.displayName} preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().height(100.dp),
                        )
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.55f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Image(
                                painter = painterResource(id = iconForModel(option.model)),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                option.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                            Spacer(Modifier.weight(1f))
                            Text("🪄", fontSize = 14.sp)
                        }
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
                    if (freeEnabled) "$localEtaText on your device" else (freeCapability?.reason ?: "Unavailable"),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (freeEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
            }

            // Pros (green-tinted). RC20: 2 → 3 lines (pairs with the
            // bumped cardHeightDp so longer marketing copy fits cleanly).
            Text(
                option.pros,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF66BB6A),
                maxLines = 3,
            )
            // Cons (amber-tinted)
            Text(
                option.cons,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
            )

            // Action button (Layer 1 i18n hardening: every label has maxLines=1 +
            // ellipsis + softWrap=false so DE/RU expansion can\'t reflow the card).
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
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Text(
                            stringResource(R.string.upscale_card_upscale_free),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                isAnonymous -> {
                    Button(
                        onClick = onSignIn,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_google_g),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.upscale_card_sign_in),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                !hasEnoughCredits -> {
                    Button(
                        onClick = onBuyCredits,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                    ) {
                        Text(
                            stringResource(R.string.upscale_card_get_more_credits),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                else -> {
                    Button(
                        onClick = onAiUpscale,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                    ) {
                        // RC13: dropped the leading Bolt icon (was eating
                        // ~18dp of width before the label) — `🪙` in the
                        // label already signals "credits cost," and users
                        // saw "Upscale 1…" truncation in label-small at
                        // ~150dp card widths. Also tightened horizontal
                        // contentPadding from the default 16dp → 8dp.
                        Text(
                            stringResource(R.string.upscale_card_upscale_with_credits, credits),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            softWrap = false,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Phase H-P1.13 — SVG vector explainer banner
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Replaces the upscale grid when the source image is an SVG. The banner uses
 * the user's exact words from the spec ("SVG is a **vector** image — it
 * prints sharp at any size. No upscale needed.") and visually mirrors the
 * Card style used by the upscale option cards so the layout doesn't shift.
 */
@Composable
private fun SvgVectorBanner() {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.svg_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // User's exact words from H-P1.13 spec.
            Text(stringResource(R.string.svg_banner_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(stringResource(R.string.svg_banner_secondary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BringYourOwn special card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun BringYourOwnCard(onPick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.FileUpload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "Bring your own",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
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
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
            }

            Text(stringResource(R.string.byo_card_free),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.byo_card_tools),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )

            // Pros/cons placeholders keep visual rhythm consistent
            Text(stringResource(R.string.byo_card_pros),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF66BB6A),
                maxLines = 2,
            )
            Text(stringResource(R.string.byo_card_cons),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )

            Button(
                onClick = onPick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text(stringResource(R.string.byo_card_button), style = MaterialTheme.typography.labelSmall)
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
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
