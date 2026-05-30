package com.posterpdf.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
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
import com.posterpdf.upscale.creditsForOption
import com.posterpdf.upscale.pickScale
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────────────────────
// Per-model COGS lookup — mirrors backend/functions/src/upscale.ts MODELS map.
// 1 credit = $0.00425 USD (cost-per-credit budget).
// ─────────────────────────────────────────────────────────────────────────────

// RC63: shared card height. UpscaleOptionCard reads this directly so every
// card is exactly this tall (no wrap-content), and the LazyVerticalGrid's
// .height(gridHeight) calc multiplies this value by the row count. Was
// inlined as `cardHeightDp = 340` at the grid call site in RC20–RC62;
// promoted to a top-level const in RC63 so the Card composable can apply
// the same value via Modifier.height(CARD_HEIGHT_DP.dp). Without this,
// .fillMaxHeight() on the Card had nothing to fill (LazyVerticalGrid
// cells don't bound maxHeight), so each card sized to its content and
// the grid's fixed height left an empty trailing band (the user-reported
// "still an inch gap" + inconsistent heights in RC61/RC62).
private const val CARD_HEIGHT_DP = 340

// RC79: at large accessibility font the fixed 340dp cards were too short, so
// the pros/cons marketing copy clipped at the card bottom (rc77 edge audit,
// finding #2). Scale the shared card height by the font scale so the cards
// grow enough to fit the (also-larger) text. At fontScale 1.0 this returns
// exactly CARD_HEIGHT_DP, so RC63's uniform, no-gap look is preserved byte-
// for-byte at normal font — the ONLY behavior change is at large font.
//
// Returned as a rounded Int dp value (not a Float) so BOTH call sites agree:
// the per-card Modifier.height() and the grid's row-count multiply use the
// same integer height, so rowCount * height stays exact and no trailing gap
// can creep back in (the RC63 regression we must not reintroduce). Floored at
// CARD_HEIGHT_DP so a sub-1.0 fontScale (rare, but possible) can't shrink the
// cards below the RC63 baseline.
private fun scaledCardHeightDp(fontScale: Float): Int =
    (CARD_HEIGHT_DP * fontScale).toInt().coerceAtLeast(CARD_HEIGHT_DP)

enum class UpscaleModel { NONE, FREE_LOCAL, TOPAZ, RECRAFT, AURASR, ESRGAN, CCSR, IMAGEN }

internal data class UpscaleOption(
    val model: UpscaleModel,
    @StringRes val displayNameRes: Int,
    @StringRes val prosRes: Int,
    @StringRes val consRes: Int,
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
        displayNameRes = R.string.upscale_option_now_pixelated,
        prosRes = R.string.upscale_option_now_pros,
        consRes = R.string.upscale_option_now_cons,
        scale = 1,
        supportedScales = listOf(1),
        perOutputMp = 0.0,
    ),
    UpscaleOption(
        model = UpscaleModel.FREE_LOCAL,
        displayNameRes = R.string.upscale_option_free_upscale,
        prosRes = R.string.upscale_option_free_pros,
        consRes = R.string.upscale_option_free_cons,
        scale = 4,
        supportedScales = listOf(4),
        perOutputMp = 0.0,
    ),
    UpscaleOption(
        model = UpscaleModel.RECRAFT,
        displayNameRes = R.string.upscale_option_recraft_crisp,
        prosRes = R.string.upscale_option_recraft_pros,
        consRes = R.string.upscale_option_recraft_cons,
        scale = 4,
        supportedScales = listOf(4),
        perOutputMp = 0.0,
        flatUsd = 0.004,
    ),
    UpscaleOption(
        model = UpscaleModel.ESRGAN,
        displayNameRes = R.string.upscale_option_esrgan,
        prosRes = R.string.upscale_option_esrgan_pros,
        consRes = R.string.upscale_option_esrgan_cons,
        scale = 4,
        supportedScales = listOf(4),
        perOutputMp = 0.00111,
    ),
    UpscaleOption(
        model = UpscaleModel.AURASR,
        displayNameRes = R.string.upscale_option_aurasr,
        prosRes = R.string.upscale_option_aurasr_pros,
        consRes = R.string.upscale_option_aurasr_cons,
        scale = 4,
        supportedScales = listOf(4),
        perOutputMp = 0.00125,
    ),
    // RC60: Google Imagen 4 — Pure-Google mid-tier cloud upscale. Sits
    // between AuraSR and CCSR in the grid order. Per-call flat pricing
    // (~$0.03/call). Supports x2/x3/x4. Backend routes via Vertex AI
    // (not FAL); UI shape is identical to the FAL-routed models. Output
    // capped at 17 MP by the API — assertModelCapacity rejects larger
    // jobs before debiting credits.
    UpscaleOption(
        model = UpscaleModel.IMAGEN,
        displayNameRes = R.string.upscale_option_imagen_name,
        prosRes = R.string.upscale_option_imagen_pros,
        consRes = R.string.upscale_option_imagen_cons,
        scale = 4,
        supportedScales = listOf(2, 3, 4),
        // Flat per-call cost — set perOutputMp=0 and use flatUsd so the
        // card's credit-math doesn't multiply by output MP.
        perOutputMp = 0.0,
        flatUsd = 0.03,
    ),
    // RC29: CCSR — second photo-faithful adjustable model. Sits between
    // ESRGAN (cheap, predictable) and Topaz (premium edges) on price, with
    // configurable scale (2/3/4×) so the user can dial detail vs. cost.
    // RC33: positioned before Topaz so the grid reads in ascending-price
    // order (NONE → FREE → RECRAFT → ESRGAN → AURASR → CCSR → TOPAZ).
    // RC60: Imagen inserted between AuraSR and CCSR (see comment above).
    UpscaleOption(
        model = UpscaleModel.CCSR,
        displayNameRes = R.string.upscale_option_ccsr,
        prosRes = R.string.upscale_option_ccsr_pros,
        consRes = R.string.upscale_option_ccsr_cons,
        scale = 4,
        supportedScales = listOf(2, 3, 4),
        perOutputMp = 0.00125,
    ),
    UpscaleOption(
        model = UpscaleModel.TOPAZ,
        displayNameRes = R.string.upscale_option_topaz_gigapixel,
        prosRes = R.string.upscale_option_topaz_pros,
        consRes = R.string.upscale_option_topaz_cons,
        scale = 4,
        supportedScales = listOf(2, 4, 6, 8),
        perOutputMp = 0.01,
    ),
)

// RC65: the long KDoc that used to live here documented pickScale + the
// RC8/RC17 pricing accuracy history. pickScale has moved to
// com.posterpdf.upscale.PricingMath — git blame on PricingMath.kt's
// pickScale recovers the rationale.

private fun usdEquivalent(credits: Int, usdPerCredit: Double): String {
    if (usdPerCredit <= 0.0 || credits == 0) return "—"
    return "%.2f".format(credits * usdPerCredit)
}

/**
 * RC36: pretty-print a credit count as USD under the 1¢=1-credit rule.
 * Sub-dollar prices read as "89¢" (cleaner than "$0.89" in
 * microtransaction contexts), ≥$1 prices read as "$X.XX" / "$XX.XX".
 * Used by the small model cards and the headroom rows so the format
 * stays consistent across the modal.
 */
internal fun formatCredits(credits: Int): String =
    if (credits >= 100) "$${"%.2f".format(credits / 100.0)}" else "${credits}¢"

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
    // RC32: CCSR was added to ALL_OPTIONS in RC29 but the picker filters
    // through this set, so without this entry the card never rendered.
    UpscaleModel.CCSR,
    // RC60: Google Imagen — same gotcha as CCSR; the card won't render
    // unless its model is listed here.
    UpscaleModel.IMAGEN,
)

// rc80: promoted from private so MainViewModel can reuse the same fallback
// throughput when computing the cloud-upscale ETA (cloudEtaText) before any
// real probe measurement exists. Same 500 KB/s "unknown network" default.
internal const val DEFAULT_BYTES_PER_SECOND = 500_000L

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
    // RC28: minScale lets the Topaz "Headroom" picker tell the backend not
    // to pick a scale below the chosen step. null = "smallest scale that
    // meets target" (current behavior).
    onAiUpscale: (modelId: String, minScale: Int?) -> Unit,
    onPickAlreadyUpscaled: () -> Unit,
    onShowBringYourOwnHelp: () -> Unit,
    onSignIn: () -> Unit,
    onBuyCredits: () -> Unit,
    onCompareModels: () -> Unit,
) {
    // RC69: this composable used to wrap its content in a ModalBottomSheet.
    // It now renders as a plain content Column placed inside a DockedDrawer
    // (MainActivity body), so the two-row top bar stays visible. onDismiss is
    // still honored (the drawer scrim-tap and the in-content button both call
    // it); the drawer supplies the slide/scrim chrome.
    // RC45: source can also be ABOVE the target DPI (e.g. high-res photo
    // on a small print). The modal still opens via the "Sharpen for print"
    // CTA, but the warning framing is wrong — the user should see that
    // their image is already sharp enough, with sharpening as an opt-in
    // rather than a recommended fix.
    val alreadyHighRes = !sourceIsSvg && currentDpi >= targetDpi.toFloat()
    val severityColor = when {
        alreadyHighRes -> MaterialTheme.colorScheme.primary
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
    val estimatingText = stringResource(R.string.lowdpi_eta_estimating_inline)
    val localEtaText = remember(localOutputMp, msPerMp, estimatingText) {
        etaForLocal(localOutputMp, msPerMp)?.let(::formatEta) ?: estimatingText
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
    //
    // RC23: preserve the source's aspect ratio. Pre-RC23 the thumb was
    // forced to 32×32 → 256×256 (square), so a portrait 768×1376 source
    // got squished into a square and looked wrong next to the other
    // (correctly-aspected) thumbnails. Now we pick the smaller side as
    // 32 px and scale the other side proportionally.
    val pixelatedThumb: ImageBitmap = remember(sourceThumb) {
        val srcW = sourceThumb.width
        val srcH = sourceThumb.height
        val (smallW, smallH) = if (srcW >= srcH) {
            32 to (32 * srcH / srcW).coerceAtLeast(1)
        } else {
            (32 * srcW / srcH).coerceAtLeast(1) to 32
        }
        val (bigW, bigH) = if (srcW >= srcH) {
            256 to (256 * srcH / srcW).coerceAtLeast(1)
        } else {
            (256 * srcW / srcH).coerceAtLeast(1) to 256
        }
        val small = Bitmap.createScaledBitmap(sourceThumb, smallW, smallH, false)
        Bitmap.createScaledBitmap(small, bigW, bigH, false).asImageBitmap()
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

    // RC61: dynamic price sort. ALL_OPTIONS' static array order doesn't
    // reflect actual cost for a given input image + target poster + DPI —
    // e.g. Imagen at $0.03 flat is cheap on a 16 MP output but more
    // expensive than per-MP CCSR on a 2 MP output, so the card ordering
    // should re-rank per situation. Re-sort whenever the pricing inputs
    // change. NONE and FREE_LOCAL anchor at the top (both cost 0); paid
    // models sort by their computed credit cost using the SAME helpers
    // the cards themselves call to render the price.
    //
    // RC61 (b): the BringYourOwn card no longer rides inside the grid as
    // a null sentinel — that left a half-empty row when the model count
    // is odd (the user reported a large empty space between the last
    // model card and "Help me decide…"). It now renders as a full-width
    // row BELOW the grid; see further down.
    val visibleOptions: List<UpscaleModel> = remember(inputMp, posterWInches, posterHInches, targetDpi) {
        val withCost = ALL_OPTIONS
            .filter { it.model in ALL_MODELS }
            .map { opt ->
                val scale = pickScale(opt, inputMp, posterWInches, posterHInches, targetDpi)
                val credits = creditsForOption(opt, inputMp, scale)
                opt.model to credits
            }
        withCost
            .sortedBy { (_, credits) -> credits }
            .map { (model, _) -> model }
    }

    // RC69: plain content Column (no ModalBottomSheet wrapper) — the
    // DockedDrawer host supplies the panel chrome. Keeps verticalScroll so
    // the picker scrolls inside the capped drawer height.
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
                    if (alreadyHighRes) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = severityColor,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    when {
                        sourceIsSvg -> stringResource(R.string.lowdpi_header_svg_inline)
                        alreadyHighRes -> stringResource(R.string.lowdpi_header_already_sharp_inline)
                        else -> stringResource(R.string.lowdpi_header_warning_inline)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (!sourceIsSvg) {
                Text(
                    stringResource(
                        R.string.lowdpi_current_dpi_inline,
                        currentDpi.toInt(),
                        "%.0f".format(posterWInches),
                        "%.0f".format(posterHInches),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = severityColor,
                    fontWeight = FontWeight.SemiBold,
                )
                if (alreadyHighRes) {
                    Text(
                        stringResource(R.string.lowdpi_already_sharp_body_inline),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    stringResource(
                        R.string.lowdpi_poster_size_only_inline,
                        "%.0f".format(posterWInches),
                        "%.0f".format(posterHInches),
                    ),
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
                // RC63: cardHeightDp pulled to top-level CARD_HEIGHT_DP; spacing
                // calc now matches the actual verticalArrangement (10dp, was 12dp
                // — 6dp of accumulated drift over 3 inter-row gaps).
                val rowCount = (visibleOptions.size + 1) / 2
                // RC79: scale the per-card height by font scale, then build
                // the grid height from the SAME scaled value so the grid's
                // total exactly matches rowCount cards + inter-row spacing.
                // At fontScale 1.0 cardHeightDp == CARD_HEIGHT_DP, so this is
                // identical to the RC63 calc (no-gap look preserved).
                val gridFontScale = LocalConfiguration.current.fontScale
                val cardHeightDp = scaledCardHeightDp(gridFontScale)
                val gridHeight = (rowCount * cardHeightDp + (rowCount - 1) * 10).dp

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    // RC57: zIndex(1f) raises the grid above its sibling
                    // HorizontalDividers in draw order. Selected cards
                    // animate to scaleX/Y = 1.03 (UpscaleOptionCard, line
                    // ~742) and grow ~2dp past their grid-cell bounds. Top-
                    // row cards' overflow lands in the upper divider's
                    // strip, and without zIndex the divider — a later
                    // sibling in the parent Column — paints over those
                    // pixels, visually clipping the card's top edge.
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(gridHeight)
                        .zIndex(1f),
                    userScrollEnabled = false,
                ) {
                    itemsIndexed(visibleOptions) { index, modelEntry ->
                        val option = ALL_OPTIONS.first { it.model == modelEntry }
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
                            onAiUpscale = { onAiUpscale(option.model.name.lowercase(), null) },
                            onSignIn = onSignIn,
                            onBuyCredits = onBuyCredits,
                            onShowDetail = { detailModel = option.model },
                            // RC64: stagger each card's bouncy entrance by index
                            // (~50ms apart) so the grid cascades in on modal open.
                            cardIndex = index,
                        )
                    }
                }

                // RC61: BringYourOwn pulled out of the grid so we don't
                // leave a half-empty row when the model count is odd.
                // Renders as a full-width card directly below the grid.
                BringYourOwnCard(
                    onPick = onShowBringYourOwnHelp,
                    modifier = Modifier.fillMaxWidth(),
                )

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
                    title = stringResource(R.string.lowdpi_card_reduce_title),
                    body = stringResource(R.string.lowdpi_card_reduce_body),
                )
                LeverRow(
                    title = stringResource(R.string.lowdpi_card_upgrade_title),
                    body = stringResource(R.string.lowdpi_card_upgrade_body),
                )
                LeverRow(
                    title = stringResource(R.string.lowdpi_card_byo_title),
                    body = stringResource(R.string.lowdpi_card_byo_body),
                )
            }

            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(if (sourceIsSvg) stringResource(R.string.lowdpi_dismiss_svg_inline) else stringResource(R.string.lowdpi_dismiss_warning_inline))
            }

            Spacer(Modifier.height(8.dp))
        }
    // RC22-7: full-screen marketing detail dialog. Triggered by the lower-right
    // Info icon on each card, or a double-tap anywhere on the card. Surfaces
    // the long-form pitch (pickWhen / standsOut / worthThePrice) that doesn't
    // fit in the in-grid card body.
    val open = detailModel
    if (open != null) {
        val option = ALL_OPTIONS.first { it.model == open }
        val copy = detailFor(open)
        // RC28: Topaz exposes a "Headroom" picker — three choices that
        // override the backend's default smallest-scale-that-meets-target
        // pick. null = default; >0 = floor in pickScale's supported list.
        // Reset to null whenever the user opens a different model's dialog.
        var topazMinScale by remember(open) { mutableStateOf<Int?>(null) }
        val pickedDefault = pickScale(option, inputMp, posterWInches, posterHInches, targetDpi)
        val effectiveScale = (topazMinScale?.takeIf { it > pickedDefault }
            ?.let { ms -> option.supportedScales.firstOrNull { it >= ms } ?: option.supportedScales.last() }
            ?: pickedDefault)
        val optionName = stringResource(option.displayNameRes)
        val (actionLabel, action) = when {
            open == UpscaleModel.NONE -> "" to { }
            open == UpscaleModel.FREE_LOCAL -> stringResource(R.string.model_detail_action_use_free) to onFreeUpscale
            isAnonymous -> stringResource(R.string.model_detail_action_sign_in) to onSignIn
            !(isAdmin || creditBalance >= creditsForOption(option, inputMp, effectiveScale)) ->
                stringResource(R.string.model_detail_action_get_credits) to onBuyCredits
            else -> stringResource(R.string.model_detail_action_upscale_with, optionName) to {
                onAiUpscale(open.name.lowercase(), topazMinScale)
            }
        }
        ModelDetailDialog(
            displayName = optionName,
            bestFor = copy.bestFor,
            pickWhen = copy.pickWhen,
            standsOut = copy.standsOut,
            worthThePrice = copy.worthThePrice,
            primaryActionLabel = actionLabel,
            onPrimaryAction = action,
            showPrimaryAction = open != UpscaleModel.NONE,
            onDismiss = { detailModel = null },
            extraContent = {
                // RC28 (Topaz) → RC29 (any multi-scale model). CCSR also
                // qualifies, so generalise the visibility check rather than
                // hard-coding TOPAZ.
                if (option.supportedScales.size > 1) {
                    TopazHeadroomPicker(
                        option = option,
                        defaultScale = pickedDefault,
                        selected = topazMinScale,
                        onSelect = { topazMinScale = it },
                        inputMp = inputMp,
                        creditBalance = creditBalance,
                        isAdmin = isAdmin,
                    )
                }
            },
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
    // RC29.1: CCSR's official logo is the eastern bluebird from FAL's
    // model card (sourced from v3b.fal.media). 256×256 PNG.
    UpscaleModel.CCSR -> R.drawable.ic_model_ccsr
    // RC60: Google Imagen — explicit fallback to the generic AI upscale
    // demo icon for v1. Custom Google-branded icon is a Spec C polish item.
    UpscaleModel.IMAGEN -> R.drawable.ai_upscale_demo
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
    // RC64: position in the grid — drives the staggered entrance delay so
    // cards cascade in on modal open instead of all popping at once.
    cardIndex: Int = 0,
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

    // RC64: MD3E-flavored bouncy entrance. Each card stages after the
    // previous one so the grid cascades in on modal open. Spring with
    // DampingRatioMediumBouncy overshoots ~12% before settling — feels
    // expressive without being cartoonish. Effects (alpha) fade in on a
    // shorter linear timeline; spatial (scale) bounces.
    // RC65 (no-build tweak): timing slowed 20% — stagger 50→60ms, spring
    // stiffness 400→280 (perceived ~20% longer bounce), alpha 220→264ms.
    var hasEntered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(cardIndex * 60L)
        hasEntered = true
    }
    val entranceScale by animateFloatAsState(
        targetValue = if (hasEntered) 1f else 0.6f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = 280f,  // ~20% slower than Spring.StiffnessMediumLow (400f)
        ),
        label = "card_entrance_scale",
    )
    val entranceAlpha by animateFloatAsState(
        targetValue = if (hasEntered) 1f else 0f,
        animationSpec = tween(durationMillis = 264),
        label = "card_entrance_alpha",
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
    // RC79: same scaled height the grid uses (scaledCardHeightDp) so the
    // per-card height and the grid's gridHeight calc stay in lockstep at
    // every font scale. At fontScale 1.0 this is exactly CARD_HEIGHT_DP, so
    // the RC63 fixed-height / no-gap behavior is unchanged at normal font.
    val cardFontScale = LocalConfiguration.current.fontScale
    val cardHeightDp = scaledCardHeightDp(cardFontScale)
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = Modifier
            // RC63: explicit fixed height. RC62's .fillMaxHeight() was a
            // no-op because LazyVerticalGrid cells don't bound maxHeight
            // — there was nothing for fillMaxHeight to fill, so cards
            // sized to content and the user still saw inconsistent
            // heights + the ~1-inch gap below the last row. Setting an
            // explicit height forces every card to measure at the same
            // height, matching the grid's gridHeight calc and eliminating
            // the trailing gap. Shorter cards get internal bottom padding
            // (anchored top via Arrangement.spacedBy on the inner Column).
            // RC79: height now scales with font (scaledCardHeightDp) so
            // text fits at large font; == CARD_HEIGHT_DP at fontScale 1.0.
            .height(cardHeightDp.dp)
            // RC64: multiply selection-scale and entrance-scale, plus apply
            // entrance alpha. Selection-scale (1.0↔1.03) is the existing
            // "selected card glows + grows" effect; entrance-scale
            // (0.6→bouncy→1.0) is the new staggered cascade-in. Both
            // compose multiplicatively in graphicsLayer.
            .graphicsLayer {
                val s = scaleValue * entranceScale
                scaleX = s
                scaleY = s
                alpha = entranceAlpha
            }
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
                .fillMaxHeight()
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
                    stringResource(option.displayNameRes),
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
                                contentDescription = stringResource(R.string.lowdpi_more_about_model_cd, stringResource(option.displayNameRes)),
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
                                contentDescription = stringResource(R.string.lowdpi_pixelated_preview_cd),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth().height(100.dp),
                            )
                        }
                    }
                    UpscaleModel.FREE_LOCAL -> {
                        Image(
                            bitmap = onDeviceThumb,
                            contentDescription = stringResource(R.string.lowdpi_free_preview_cd),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().height(100.dp),
                        )
                    }
                    else -> {
                        // RC16: AI card thumbnail = original image with
                        // bottom-aligned brand stripe (model icon + 🪄).
                        Image(
                            bitmap = onDeviceThumb,
                            contentDescription = stringResource(R.string.lowdpi_model_preview_cd, stringResource(option.displayNameRes)),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().height(100.dp),
                        )
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.55f))
                                // RC23: 32 dp end-padding so the 🪄 doesn't
                                // overlap the lower-right Info icon
                                // (28 dp + 2 dp pad = 30 dp; 32 dp gives a
                                // small gap between them).
                                .padding(start = 8.dp, end = 32.dp, top = 4.dp, bottom = 4.dp),
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
                                stringResource(option.displayNameRes),
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
                stringResource(R.string.upscale_card_dpi_estimate, outputDpi.toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (credits > 0) {
                // RC36: show "N credits · XX¢" or "N credits · $X.XX" — the
                // RC35 fix dropped the misleading "$10.59" line entirely
                // (it came from a stale 0.119 multiplier), but the user
                // wants the price visible, just formatted correctly under
                // 1¢ = 1 credit. ¢ for sub-dollar, $ for ≥$1.
                Text(
                    stringResource(R.string.lowdpi_card_credits_inline, credits, formatCredits(credits)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (option.model == UpscaleModel.FREE_LOCAL && localEtaText != null) {
                Text(
                    if (freeEnabled) stringResource(R.string.lowdpi_card_eta_on_device, localEtaText) else (freeCapability?.reason ?: stringResource(R.string.lowdpi_card_eta_unavailable)),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (freeEnabled) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
            }

            // Pros (green-tinted). RC20: 2 → 3 lines (pairs with the
            // bumped cardHeightDp so longer marketing copy fits cleanly).
            Text(
                stringResource(option.prosRes),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF66BB6A),
                maxLines = 3,
            )
            // Cons (amber-tinted)
            Text(
                stringResource(option.consRes),
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
                    stringResource(R.string.lowdpi_card_byo_inline),
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

// ─────────────────────────────────────────────────────────────────────────────
// RC28: Topaz "Headroom" picker
// ─────────────────────────────────────────────────────────────────────────────
//
// Topaz is the only model with multiple supported scales (2/4/6/8). The
// backend's pickScale picks the smallest that meets target DPI, which
// minimises cost — but the user may want to *exceed* target for sharper
// results. This picker offers three choices:
//
//   • Just enough — null override (cheapest, current default behaviour)
//   • Above target — next supported scale above the default pick
//   • Maximum — clamp to the highest supported scale (8×)
//
// The card shows live credit cost for each option so the user sees the
// trade-off before tapping the action button.
@Composable
private fun TopazHeadroomPicker(
    option: UpscaleOption,
    defaultScale: Int,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    inputMp: Double,
    creditBalance: Int,
    isAdmin: Boolean,
) {
    val above = option.supportedScales.firstOrNull { it > defaultScale } ?: defaultScale
    val maxScale = option.supportedScales.last()
    val justEnoughCredits = creditsForOption(option, inputMp, defaultScale)
    val aboveCredits = creditsForOption(option, inputMp, above)
    val maxCredits = creditsForOption(option, inputMp, maxScale)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.headroom_section_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.headroom_section_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HeadroomRow(
            title = stringResource(R.string.headroom_just_enough_title),
            subtitle = stringResource(R.string.headroom_just_enough_subtitle, defaultScale),
            credits = justEnoughCredits,
            isSelected = selected == null,
            isAffordable = isAdmin || creditBalance >= justEnoughCredits,
            onClick = { onSelect(null) },
        )
        if (above > defaultScale) {
            HeadroomRow(
                title = stringResource(R.string.headroom_above_title),
                subtitle = stringResource(R.string.headroom_above_subtitle, above),
                credits = aboveCredits,
                isSelected = selected == above,
                isAffordable = isAdmin || creditBalance >= aboveCredits,
                onClick = { onSelect(above) },
            )
        }
        if (maxScale > above) {
            HeadroomRow(
                title = stringResource(R.string.headroom_max_title),
                subtitle = stringResource(R.string.headroom_max_subtitle, maxScale),
                credits = maxCredits,
                isSelected = selected == maxScale,
                isAffordable = isAdmin || creditBalance >= maxCredits,
                onClick = { onSelect(maxScale) },
            )
        }
    }
}

@Composable
private fun HeadroomRow(
    title: String,
    subtitle: String,
    credits: Int,
    isSelected: Boolean,
    isAffordable: Boolean,
    onClick: () -> Unit,
) {
    val container = if (isSelected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    val onContainer = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = container,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = onContainer)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = onContainer.copy(alpha = 0.8f))
            }
            Text(
                // RC35/36: shared formatCredits helper — ≥100¢ as "$X.XX",
                // sub-dollar as "XX¢".
                formatCredits(credits),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isAffordable) onContainer else MaterialTheme.colorScheme.error,
            )
        }
    }
}
