package com.posterpdf

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.caverock.androidsvg.SVG
import com.posterpdf.data.SettingsRepository
import com.posterpdf.data.backend.AuthRepository
import com.posterpdf.data.backend.AuthSession
import com.posterpdf.data.backend.BackendClient
import com.posterpdf.data.backend.HistoryItem
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


data class ImageMetadata(
    val width: Int,
    val height: Int,
    val aspectRatioString: String,
    val aspectRatio: Double,
    val resolution: String
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    var isGenerating by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var successMessage by mutableStateOf<String?>(null)

    // Phase H-P3.2: 'paid' | 'auto-delete'. Default 'paid' = keep storing
    // posters in cloud after the 30-day free window, billed 1¢/month/file.
    var storageRetentionMode by mutableStateOf("paid")
        private set

    /** Validates + applies a new retention mode. The function name avoids
     *  the Kotlin-generated `setStorageRetentionMode` JVM signature clash. */
    fun chooseStorageRetention(mode: String) {
        if (mode != "paid" && mode != "auto-delete") return
        storageRetentionMode = mode
        // TODO(H-P3): persist to users/{uid}.storageRetentionMode in Firestore.
        // For now this is process-local; the next sign-in will read it from
        // Firestore when AuthRepository wires through.
    }

    // Phase H-P1.9: source bitmap pixel dimensions, populated by PosterPreview
    // after BitmapFactory.decodeStream succeeds. Used by MainActivity to gate
    // View/Save/Share at < 150 DPI.
    var sourcePixelDimensions by mutableStateOf<Pair<Int, Int>?>(null)
    /** RC16: true when the current source image is an upscale of the
     *  original (free or AI). Drives the "Upscaled X DPI ✓" label and
     *  suppresses the embedded low-DPI warning in the generated PDF. */
    var wasUpscaled by mutableStateOf(false)
        private set

    // Phase H-P1.13: true when the active source URI is an SVG (vector). The
    // preview rasterizes it for display, but the PDF generation path renders
    // each tile fresh via androidsvg at high DPI for vector-quality output.
    // The upscale modal also uses this flag to gray out raster-upscale options.
    var sourceIsSvg by mutableStateOf(false)

    /**
     * Current effective print resolution: source pixels per *unit* of poster
     * width, where the unit is whatever the user has selected. Inches → DPI
     * (dots-per-inch); Metric → DPCM (dots-per-centimeter). Returns 0f when
     * unknown. Use [currentResolutionUnitLabel] to render the right label.
     */
    fun computeCurrentDpi(): Float {
        val (w, _) = sourcePixelDimensions ?: return 0f
        val rawWidth = posterWidth.toDoubleOrNull() ?: return 0f
        if (rawWidth <= 0.0) return 0f
        return (w.toDouble() / rawWidth).toFloat()
    }

    /** "DPI" or "DPCM" depending on [units]. */
    val currentResolutionUnitLabel: String
        get() = if (units == "Metric") "DPCM" else "DPI"

    /** Industry-standard "good poster print" threshold expressed in the
     *  CURRENT unit. 150 DPI = 59.055 DPCM. Used by the under-preview
     *  warning gate so the threshold stays meaningful regardless of unit. */
    val lowResolutionThreshold: Float
        get() = if (units == "Metric") 150f / 2.54f else 150f

    /** RC20: targetDpi rendered in the current unit. Internally targetDpi is
     *  always stored in DPI (the canonical "150" the slider snaps to). When
     *  the user is in Metric mode, the chip + Settings label need to show
     *  the DPCM equivalent so "Target 150 DPI" doesn't render as the wrong
     *  number "150 DPCM" (≈381 DPI worth of pixels — 2.54× too high). */
    val targetDpiDisplay: Int
        get() = if (units == "Metric") (targetDpi / 2.54f).toInt() else targetDpi

    /**
     * RC3+ — show ∞ in the credit badge for admin accounts. The Firestore
     * custom-claim path is set by the admin script; for v1 we additionally
     * recognize the project owner's email so the badge feels right
     * immediately after sign-in (the claim takes ~1 hour to refresh in the
     * client's ID-token cache otherwise).
     */
    val isAdmin: Boolean
        get() = authSession.email == "joeputin100@gmail.com"

    /**
     * RC3+ target print DPI. Default 150 (industry-standard poster quality).
     * Users with high-DPI printers (600 / 1200) can bump this to drive a
     * higher-resolution upscale. Backend uses this to pick the smallest scale
     * factor that meets the target — saves real money vs. always 4× / 8×.
     */
    var targetDpi by mutableStateOf(150)
        private set

    /** Renamed from `setTargetDpi` to avoid JVM signature clash with the
     *  auto-generated setter on `var targetDpi`. Same pattern as
     *  `chooseStorageRetention`. */
    fun chooseTargetDpi(dpi: Int) {
        targetDpi = dpi.coerceIn(75, 1200)
    }

    /** RC4: separate from `isGenerating` (which covers PDF emit) so the UI
     *  can show a free-upscale-specific progress dialog with cancel button. */
    var isFreeUpscaling by mutableStateOf(false)
        private set
    private var freeUpscaleJob: kotlinx.coroutines.Job? = null

    /** RC19: AI upscale (FAL) progress state. Mirrors freeUpscaleTilesDone /
     *  freeUpscaleTotalTiles for the on-device flow but with named phases
     *  matching the user's request: "for AI models, progress card should
     *  show uploading, in_queue, in_progress, completed/failed processing,
     *  downloading as separate steps." Phase strings come from
     *  AiUpscaleRepository.Phase. progressFraction is a [0,1] estimate the
     *  UI uses to drive the LinearProgressIndicator. */
    var isAiUpscaling by mutableStateOf(false)
        private set
    var aiUpscalePhase by mutableStateOf("")
        private set
    var aiUpscaleProgress by mutableStateOf(0f)
        private set
    /** RC21: extra detail line for the AI upscale modal. Surfaces queue
     *  position ("Queue position 3") while IN_QUEUE and a generic
     *  "Processing image" while IN_PROGRESS, both populated by the
     *  AiUpscaleRepository's polling path from FAL's status response.
     *  Null during setup/teardown phases — UI hides the row in that case. */
    var aiUpscaleDetail by mutableStateOf<String?>(null)
        private set
    /** RC24: when non-null, the AI-upscale failure dialog is shown with this
     *  message + Retry/Close buttons. Set in the `onFailure`/catch paths
     *  of [runAiUpscale] alongside (not instead of) the existing
     *  errorMessage so the dialog is the primary surface and the snackbar
     *  is retained as a fallback. Cleared by the dialog's onDismiss. */
    var aiUpscaleFailure by mutableStateOf<String?>(null)
    /** RC24: the last attempted model id, captured at runAiUpscale entry
     *  so the failure dialog's Retry button can re-invoke the same upscale
     *  without making the caller re-pass parameters. */
    private var lastAiUpscaleModelId: String? = null
    private var aiUpscaleJob: kotlinx.coroutines.Job? = null

    /** RC13: tile-level upscale progress, exposed so the in-app modal can
     *  use the same ground-truth source as the foreground-service notification.
     *  Without this the modal ran a benchmark-based ETA estimate that
     *  diverged wildly from actual tile completion (user reported pill
     *  showing 11% / 16-of-1474, modal showing 63%). Updated from the
     *  onProgress callback in runFreeUpscale. */
    var freeUpscaleTilesDone by mutableStateOf(0)
        private set
    var freeUpscaleTotalTiles by mutableStateOf(0)
        private set
    var freeUpscaleStartMs by mutableStateOf(0L)
        private set

    /** RC4: app-level toggle for the low-DPI upscale modal. PosterPreview
     *  (the under-preview tappable card) and MainActivity (the new
     *  Sharpen-for-print CTA between Poster Size and Paper & Layout) both
     *  drive this flag, and PosterPreview\'s modal opens whenever it goes
     *  true. Hoisted to ViewModel to keep both call sites in sync. */
    var showLowDpiModal by mutableStateOf(false)

    /** RC7: tracks the user\'s upscale-model selection so the under-preview
     *  warning Card can swap from "Low resolution: NN DPI" to
     *  "Upscaling with <model> to <NN> DPI" once a model is queued. Cleared
     *  back to null when the upscale completes or the user picks a new
     *  source image. Display label only — not the wire id. */
    var pendingUpscaleModelLabel by mutableStateOf<String?>(null)

    /** RC12: storage-billing aggregate read from `users/{uid}.storageBilling`.
     *  Drives the drawer Account section\'s "Storage: N credits this month
     *  for M posters [X.X GB used]" line. Null when the user has no cloud-
     *  stored PDFs past the free 30-day window. */
    data class StorageBillingAggregate(
        val bytes: Long,
        val posters: Int,
        val lastBilledCredits: Int,
        val nextBillDueMs: Long?,
        val gracePeriodStartedMs: Long?,
    )
    var storageBilling by mutableStateOf<StorageBillingAggregate?>(null)
        private set

    /**
     * RC3 fix: actually run the on-device ESRGAN upscale, save it to cache,
     * point selectedImageUri at the result so the next preview redraw + DPI
     * calc see the 4× larger image. RC4: now exposes an isFreeUpscaling flag
     * + a cancellable Job so MainActivity can render a progress dialog.
     */
    fun runFreeUpscale(context: Context) {
        val uri = selectedImageUri ?: return
        freeUpscaleJob?.cancel()
        freeUpscaleJob = viewModelScope.launch {
            isFreeUpscaling = true
            freeUpscaleStartMs = System.currentTimeMillis()
            freeUpscaleTilesDone = 0
            freeUpscaleTotalTiles = 0
            logEvent(context, "free_upscale: start", "uri=$uri")
            try {
                logEvent(context, "free_upscale: init UpscalerOnDevice")
                com.posterpdf.ml.UpscalerOnDevice.init(context)
                logEvent(context, "free_upscale: decode source bitmap")
                val src = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                } ?: run {
                    errorMessage = "Couldn't open the source image"
                    logEvent(context, "free_upscale: ABORT — source decode returned null")
                    return@launch
                }
                logEvent(
                    context,
                    "free_upscale: source decoded",
                    "${src.width}x${src.height}, ${src.byteCount / 1024} KB",
                )

                // RC11: check for resume state matching this URI; if present,
                // pick up where the previous run left off.
                val resumeSnapshot = withContext(Dispatchers.IO) {
                    com.posterpdf.ml.UpscaleStateStore.load(context, uri.toString())
                }
                val resumeBitmap = resumeSnapshot?.let {
                    withContext(Dispatchers.IO) {
                        BitmapFactory.decodeFile(it.partialBitmapPath)
                    }
                }
                val resumeFrom = resumeSnapshot?.lastCompletedTile ?: 0
                if (resumeFrom > 0 && resumeBitmap != null) {
                    logEvent(
                        context,
                        "free_upscale: resuming from tile $resumeFrom",
                        "of ${resumeSnapshot.totalTiles}",
                    )
                }

                // RC11: pre-compute total tiles so we can start the foreground
                // service with the right notification denominator.
                val totalTiles = (
                    ((src.width + 49) / 50).coerceAtLeast(1) *
                    ((src.height + 49) / 50).coerceAtLeast(1)
                )
                com.posterpdf.ml.UpscaleForegroundService.start(context, totalTiles)
                logEvent(context, "free_upscale: foreground service started", "totalTiles=$totalTiles")

                logEvent(context, "free_upscale: invoking ESRGAN upscale (4x)")
                val upscaled = kotlinx.coroutines.withTimeout(15 * 60 * 1000L) {
                    com.posterpdf.ml.UpscalerOnDevice.upscale(
                        input = src,
                        resumeFromTile = resumeFrom,
                        partialOutput = resumeBitmap,
                        onProgress = { done, total ->
                            // RC13: surface ground-truth tile progress to the
                            // ViewModel so the in-app modal reads from the
                            // same source as the notification pill.
                            freeUpscaleTilesDone = done
                            freeUpscaleTotalTiles = total
                            com.posterpdf.ml.UpscaleForegroundService.updateProgress(
                                context, done, total,
                            )
                            if (done == 1 || done == total ||
                                (total > 20 && done % (total / 20).coerceAtLeast(1) == 0)) {
                                logEvent(context, "free_upscale: tile $done/$total")
                            }
                        },
                        onPartialSave = { lastDone, out ->
                            com.posterpdf.ml.UpscaleStateStore.save(
                                context,
                                sourceUri = uri.toString(),
                                totalTiles = totalTiles,
                                lastCompletedTile = lastDone,
                                partial = out,
                            )
                            logEvent(context, "free_upscale: partial saved", "tile=$lastDone")
                        },
                    )
                }
                resumeBitmap?.recycle()
                logEvent(
                    context,
                    "free_upscale: upscale returned",
                    "${upscaled.width}x${upscaled.height}",
                )
                val outFile = File(
                    context.cacheDir,
                    "upscaled_${System.currentTimeMillis()}.png",
                )
                withContext(Dispatchers.IO) {
                    FileOutputStream(outFile).use { fos ->
                        upscaled.compress(Bitmap.CompressFormat.PNG, 95, fos)
                    }
                }
                selectedImageUri = Uri.fromFile(outFile)
                sourcePixelDimensions = upscaled.width to upscaled.height
                // RC16: also refresh imageMetadata so the PDF generator's
                // sourcePixelW/H reflect the upscaled dimensions instead of
                // the stale original. Without this, the PDF embeds the
                // pre-upscale "Source: WxH" + low-DPI warning even though
                // the actual rendered image is high-res.
                // RC21: aspectRatioString uses the SAME "%.1f:1.0" format as the
                // initial-load path (line ~725) so the chip doesn't appear to
                // change format from "0.6:1.0" to a raw pixel ratio "768:1376"
                // after upscale. Underlying aspectRatio Double is identical
                // because ESRGAN-TF2's 4× upscale preserves dimensions linearly.
                val arUp = upscaled.width.toDouble() / upscaled.height.toDouble()
                imageMetadata = ImageMetadata(
                    width = upscaled.width,
                    height = upscaled.height,
                    aspectRatioString = String.format(Locale.US, "%.1f:1.0", arUp),
                    aspectRatio = arUp,
                    resolution = "${upscaled.width}×${upscaled.height}",
                )
                wasUpscaled = true
                successMessage = "Upscaled to ${upscaled.width}×${upscaled.height}"
                pendingUpscaleModelLabel = null
                logEvent(context, "free_upscale: SUCCESS", "wrote ${outFile.name}")
                // RC11: success — clear the resume state so the next run
                // starts fresh, and stop the foreground service.
                com.posterpdf.ml.UpscaleStateStore.clear(context)
                if (src !== upscaled) src.recycle()
                upscaled.recycle()
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                errorMessage = "Sharpening timed out after 15 minutes — try a smaller poster size or use an AI option instead."
                logEvent(context, "free_upscale: TIMEOUT — exceeded 15 min budget")
                // Keep state on disk so the user can resume next launch.
            } catch (e: kotlinx.coroutines.CancellationException) {
                logEvent(context, "free_upscale: cancelled by user")
                // RC11: explicit cancel clears state — user said "stop", don't
                // offer to resume. Process kill leaves state untouched.
                com.posterpdf.ml.UpscaleStateStore.clear(context)
            } catch (e: Throwable) {
                errorMessage = "Upscale failed: ${e.message ?: "unknown error"}"
                logEvent(
                    context,
                    "free_upscale: FAILED",
                    "${e.javaClass.simpleName}: ${e.message}",
                )
                // Failed runs clear state — the source is presumably corrupt
                // or the model is broken; resume would just hit the same error.
                com.posterpdf.ml.UpscaleStateStore.clear(context)
            } finally {
                isFreeUpscaling = false
                pendingUpscaleModelLabel = null
                com.posterpdf.ml.UpscaleForegroundService.stop(context)
            }
        }
    }

    fun cancelFreeUpscale() {
        freeUpscaleJob?.cancel()
        // Job's finally block clears isFreeUpscaling; redundant set is safe.
        isFreeUpscaling = false
        pendingUpscaleModelLabel = null
    }

    /**
     * RC19: kick off an AI upscale via FAL. Uploads the source bitmap to
     * Firebase Storage, calls the requestUpscale callable, polls for
     * completion, downloads the result, and swaps it in as the active
     * source image (same as the free-upscale flow's success path).
     *
     * Backend handles credit staging + commit + refund-on-failure
     * internally inside requestUpscale, so the client just needs to
     * surface progress and react to the final outcome.
     */
    fun runAiUpscale(context: Context, modelId: String) {
        val uri = selectedImageUri ?: return
        val (srcW, srcH) = sourcePixelDimensions ?: return
        // RC24: capture for the failure dialog's Retry button.
        lastAiUpscaleModelId = modelId
        aiUpscaleFailure = null
        val displayName = when (modelId) {
            "topaz" -> "Topaz Gigapixel"
            "recraft" -> "Recraft Crisp"
            "aurasr" -> "AuraSR"
            "esrgan" -> "ESRGAN"
            else -> modelId
        }
        aiUpscaleJob?.cancel()
        aiUpscaleJob = viewModelScope.launch {
            isAiUpscaling = true
            aiUpscalePhase = "Starting…"
            aiUpscaleProgress = 0f
            pendingUpscaleModelLabel = displayName
            logEvent(context, "ai_upscale: start", "model=$modelId src=${srcW}x$srcH")
            try {
                // Convert poster dims to inches for the backend's pickScale.
                val rawW = posterWidth.toDoubleOrNull() ?: 24.0
                val rawH = posterHeight.toDoubleOrNull() ?: 36.0
                val posterWIn = if (units == "Metric") rawW / 2.54 else rawW
                val posterHIn = if (units == "Metric") rawH / 2.54 else rawH
                val inputMp = (srcW.toDouble() * srcH) / 1_000_000.0

                val result = aiUpscaleRepo.runUpscale(
                    context = context,
                    sourceUri = uri,
                    modelId = modelId,
                    inputMp = inputMp,
                    posterWidthInches = posterWIn,
                    posterHeightInches = posterHIn,
                    targetDpi = targetDpi,
                ) { phase, frac, detail ->
                    aiUpscalePhase = when (phase) {
                        com.posterpdf.data.backend.AiUpscaleRepository.Phase.UPLOADING -> "Uploading source…"
                        com.posterpdf.data.backend.AiUpscaleRepository.Phase.IN_QUEUE -> "Waiting in queue…"
                        com.posterpdf.data.backend.AiUpscaleRepository.Phase.IN_PROGRESS -> "Sharpening with $displayName…"
                        com.posterpdf.data.backend.AiUpscaleRepository.Phase.DOWNLOADING -> "Downloading result…"
                        com.posterpdf.data.backend.AiUpscaleRepository.Phase.SAVING -> "Saving…"
                        com.posterpdf.data.backend.AiUpscaleRepository.Phase.SUCCEEDED -> "Done"
                        com.posterpdf.data.backend.AiUpscaleRepository.Phase.FAILED -> "Failed"
                    }
                    aiUpscaleProgress = frac
                    // RC21: detail is "Queue position 3", "Processing image",
                    // etc. when the backend has populated queuePosition; null
                    // for setup/teardown phases.
                    aiUpscaleDetail = detail
                }
                result.onSuccess { outFile ->
                    val bmp = android.graphics.BitmapFactory.decodeFile(outFile.absolutePath)
                    if (bmp != null) {
                        selectedImageUri = Uri.fromFile(outFile)
                        sourcePixelDimensions = bmp.width to bmp.height
                        // RC21: same "%.1f:1.0" format as the initial-load path
                        // and the FREE_LOCAL upscale path so the chip reads
                        // consistently regardless of how the image arrived.
                        val arAi = bmp.width.toDouble() / bmp.height.toDouble()
                        imageMetadata = ImageMetadata(
                            width = bmp.width,
                            height = bmp.height,
                            aspectRatioString = String.format(Locale.US, "%.1f:1.0", arAi),
                            aspectRatio = arAi,
                            resolution = "${bmp.width}×${bmp.height}",
                        )
                        wasUpscaled = true
                        successMessage = "Upscaled to ${bmp.width}×${bmp.height} via $displayName"
                        logEvent(context, "ai_upscale: SUCCESS", "${bmp.width}x${bmp.height}")
                        bmp.recycle()
                    } else {
                        errorMessage = "Upscale completed but result image could not be decoded"
                        aiUpscaleFailure = "Upscale completed but result image could not be decoded"
                    }
                }.onFailure { t ->
                    logEvent(context, "ai_upscale: FAIL", t.message)
                    val msg = "AI upscale failed: ${t.message ?: t.javaClass.simpleName}"
                    errorMessage = msg
                    // RC24: surface the failure as a dismissable modal with
                    // Retry/Close buttons + a refund reassurance line.
                    // Backend's refundAndFail already credited the user back
                    // when the FAL job errored, so the dialog can promise
                    // the refund truthfully.
                    aiUpscaleFailure = msg
                }
            } catch (t: Throwable) {
                logEvent(context, "ai_upscale: exception", t.message)
                val msg = "AI upscale error: ${t.message ?: t.javaClass.simpleName}"
                errorMessage = msg
                aiUpscaleFailure = msg
            } finally {
                isAiUpscaling = false
                pendingUpscaleModelLabel = null
            }
        }
    }

    fun cancelAiUpscale() {
        aiUpscaleJob?.cancel()
        isAiUpscaling = false
        pendingUpscaleModelLabel = null
    }

    /**
     * RC24: re-run the AI upscale that just failed, using the same model id
     * captured at the previous attempt. Called from the failure dialog's
     * Retry button. No-op if no prior attempt was made.
     */
    fun retryAiUpscale(context: Context) {
        val modelId = lastAiUpscaleModelId ?: return
        aiUpscaleFailure = null
        runAiUpscale(context, modelId)
    }


    // Reactive inputs
    var selectedImageUri by mutableStateOf<Uri?>(null)
    var posterWidth by mutableStateOf("24")
    var posterHeight by mutableStateOf("36")
    var paperSize by mutableStateOf("Letter (8.5x11)")
    var customPaperWidth by mutableStateOf("8.5")
    var customPaperHeight by mutableStateOf("11")
    var orientation by mutableStateOf("Best Fit") // Best Fit, Portrait, Landscape
    var margin by mutableStateOf("0.5")
    var overlap by mutableStateOf("0.25")
    
    // Advanced options
    // outlineSelection: "None" | "Solid Thin" | ... | "Crop Marks"
    var outlineSelection by mutableStateOf("Solid Medium")
    val showOutlines: Boolean get() = outlineSelection != "None"
    val outlineStyle: String get() = when {
        outlineSelection.startsWith("Crop Marks") -> "CropMarks"
        outlineSelection.startsWith("Solid") -> "Solid"
        outlineSelection.startsWith("Dashed") -> "Dashed"
        outlineSelection.startsWith("Dotted") -> "Dotted"
        else -> "Solid"
    }
    val outlineThickness: String get() = when {
        outlineSelection.endsWith("Thin") -> "Thin"
        outlineSelection.endsWith("Heavy") -> "Heavy"
        else -> "Medium"
    }
    var labelPanes by mutableStateOf(true)
    var includeInstructions by mutableStateOf(true)

    // Source image tracking (per-image counter)
    var currentImageHash by mutableStateOf<String?>(null)
    var lastCountedHash by mutableStateOf<String?>(null)
    
    // Debug & telemetry
    var debugLoggingEnabled by mutableStateOf(false)
    /** RC13b: A/B toggle — when true, model cards swap the AGSL holofoil
     *  glitter for a simpler light-pulse sweep (the same animated gradient
     *  the API 26-32 fallback uses). Visible only in debug builds via the
     *  drawer chips next to the FCM test buttons. Not persisted: a session-
     *  scoped flag is enough for an A/B comparison. */
    var usePulseEffect by mutableStateOf(false)
    var postersMadeCount by mutableStateOf(0)
    var showNagwareModal by mutableStateOf(false)
    var nagwareCountdown by mutableStateOf(5) // seconds
    var nagwareDismissed by mutableStateOf(false)
    
    var isAspectRatioLocked by mutableStateOf(true)
    var imageMetadata by mutableStateOf<ImageMetadata?>(null)
    
    var isFirstRun by mutableStateOf(false)
    var units by mutableStateOf("Inches")
    var lastGeneratedFile by mutableStateOf<File?>(null)

    private val posterLogic = PosterLogic()
    private val repository = SettingsRepository(application)
    private val appContext = application.applicationContext

    private val auth = AuthRepository.get(appContext)
    private val backend = BackendClient.create(auth)
    private val supportRepo = com.posterpdf.data.backend.SupportRepository(auth)
    private val aiUpscaleRepo = com.posterpdf.data.backend.AiUpscaleRepository(auth)

    var authSession by mutableStateOf(AuthSession())
        private set
    var historyItems by mutableStateOf<List<HistoryItem>>(emptyList())
        private set
    var isHistoryLoading by mutableStateOf(false)
        private set
    var showHistoryScreen by mutableStateOf(false)
    var showUpscaleComparison by mutableStateOf(false)

    // H-P2: content screens reachable from the hamburger drawer.
    var showGettingStarted by mutableStateOf(false)
    var showHelp by mutableStateOf(false)
    var showFaq by mutableStateOf(false)
    var showPrivacy by mutableStateOf(false)
    /** RC17: Support / feedback form. Submits a Firestore /support
     *  document via SupportRepository when the user taps Send. */
    var showSupport by mutableStateOf(false)

    private var ignoreFlowUpdates = false

    init {
        loadSettings()
        viewModelScope.launch {
            auth.session.collectLatest { s ->
                authSession = s
                // RC16: mirror the photoUrl into the debug log so the
                // user's next saved log tells us whether the URL is
                // actually null vs. set-but-failing-to-load.
                logEvent(
                    appContext,
                    "auth_session",
                    "signedIn=${s.signedIn} anon=${s.isAnonymous} photoUrl=${com.posterpdf.data.backend.AuthRepository.lastPhotoUrl ?: "<null>"}",
                )
            }
        }
        viewModelScope.launch {
            auth.ensureSignedIn()
            refreshHistory()
        }
        // RC16: kick off the on-device upscale benchmark on app start (if
        // missing or stale) so the LowDpiUpgradeModal's "Free upscale"
        // model card can render a real ETA instead of "estimating…"
        // forever. Runs on Dispatchers.Default; a few seconds on a mid-
        // tier phone, results cached for 30 days.
        viewModelScope.launch(Dispatchers.Default) {
            try {
                if (com.posterpdf.ml.benchmarkNeedsRefresh(appContext)) {
                    com.posterpdf.ml.UpscalerOnDevice.init(appContext)
                    com.posterpdf.ml.UpscalerOnDevice.benchmarkAndCache(appContext)
                    logEvent(appContext, "upscale_benchmark: completed")
                }
            } catch (t: Throwable) {
                logEvent(appContext, "upscale_benchmark: failed", t.message)
            }
        }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            repository.settingsFlow.collectLatest { settings ->
                if (ignoreFlowUpdates) return@collectLatest
                settings[SettingsRepository.POSTER_WIDTH]?.let { posterWidth = it as String }
                settings[SettingsRepository.POSTER_HEIGHT]?.let { posterHeight = it as String }
                settings[SettingsRepository.PAPER_SIZE]?.let { paperSize = it as String }
                settings[SettingsRepository.MARGIN]?.let { margin = it as String }
                settings[SettingsRepository.OVERLAP]?.let { overlap = it as String }
                // Migrate legacy outline settings to new combined selection
                val legacyShow = settings[SettingsRepository.SHOW_OUTLINES] as? Boolean
                val legacyStyle = settings[SettingsRepository.OUTLINE_STYLE] as? String
                val legacyThickness = settings[SettingsRepository.OUTLINE_THICKNESS] as? String
                settings[SettingsRepository.OUTLINE_SELECTION]?.let {
                    outlineSelection = it as String
                } ?: run {
                    if (legacyShow == false) outlineSelection = "None"
                    else if (legacyStyle != null && legacyThickness != null) {
                        outlineSelection = "$legacyStyle $legacyThickness"
                    }
                }
                settings[SettingsRepository.LAST_COUNTED_HASH]?.let { lastCountedHash = it as String }
                settings[SettingsRepository.LABEL_PANES]?.let { labelPanes = it as Boolean }
                settings[SettingsRepository.INCLUDE_INSTRUCTIONS]?.let { includeInstructions = it as Boolean }
                settings[SettingsRepository.UNITS]?.let { units = it as String }
                settings[SettingsRepository.IS_FIRST_RUN]?.let { isFirstRun = it as Boolean } ?: run { isFirstRun = true }
                settings[SettingsRepository.DEBUG_LOGGING_ENABLED]?.let { debugLoggingEnabled = it as Boolean }
                settings[SettingsRepository.POSTERS_MADE_COUNT]?.let { postersMadeCount = it as Int }
            }
        }
    }

    fun saveAllSettings() {
        viewModelScope.launch {
            ignoreFlowUpdates = true
            try {
                repository.saveSetting(SettingsRepository.POSTER_WIDTH, posterWidth)
                repository.saveSetting(SettingsRepository.POSTER_HEIGHT, posterHeight)
                repository.saveSetting(SettingsRepository.PAPER_SIZE, paperSize)
                repository.saveSetting(SettingsRepository.MARGIN, margin)
                repository.saveSetting(SettingsRepository.OVERLAP, overlap)
                repository.saveSetting(SettingsRepository.OUTLINE_SELECTION, outlineSelection)
                lastCountedHash?.let { repository.saveSetting(SettingsRepository.LAST_COUNTED_HASH, it) }
                repository.saveSetting(SettingsRepository.LABEL_PANES, labelPanes)
                repository.saveSetting(SettingsRepository.INCLUDE_INSTRUCTIONS, includeInstructions)
                repository.saveSetting(SettingsRepository.UNITS, units)
                repository.saveSetting(SettingsRepository.IS_FIRST_RUN, false)
                repository.saveSetting(SettingsRepository.DEBUG_LOGGING_ENABLED, debugLoggingEnabled)
                repository.saveSetting(SettingsRepository.POSTERS_MADE_COUNT, postersMadeCount)
                isFirstRun = false
            } finally {
                ignoreFlowUpdates = false
            }
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            repository.resetSettings()
            posterWidth = if (units == "Metric") "60.96" else "24"
            posterHeight = if (units == "Metric") "91.44" else "36"
            paperSize = "Letter (8.5x11)"
            customPaperWidth = if (units == "Metric") "21.59" else "8.5"
            customPaperHeight = if (units == "Metric") "27.94" else "11"
            orientation = "Best Fit"
            margin = if (units == "Metric") "1.27" else "0.5"
            overlap = if (units == "Metric") "0.63" else "0.25"
            outlineSelection = "Solid Medium"
            labelPanes = true
            includeInstructions = true
            saveAllSettings()
        }
    }

    fun toggleUnits(toMetric: Boolean) {
        logEvent(appContext, "Units toggle attempted", "toMetric=$toMetric, current=$units")
        val factor = if (toMetric) 2.54 else 1 / 2.54
        posterWidth = convertValue(posterWidth, factor)
        posterHeight = convertValue(posterHeight, factor)
        customPaperWidth = convertValue(customPaperWidth, factor)
        customPaperHeight = convertValue(customPaperHeight, factor)
        margin = convertValue(margin, factor)
        overlap = convertValue(overlap, factor)
        units = if (toMetric) "Metric" else "Inches"
        saveAllSettings()
        logEvent(appContext, "Units toggled", "new=$units")
    }

    private fun convertValue(value: String, factor: Double): String {
        val parsed = value.toDoubleOrNull() ?: return value
        return String.format(Locale.US, "%.2f", parsed * factor)
    }

    private fun formatWithSamePrecision(value: Double, source: String): String {
        val decimalPlaces = if (source.contains('.')) {
            val decimalPart = source.substringAfter('.')
            // Limit to reasonable precision, max 4 decimal places
            kotlin.math.min(decimalPart.length, 4)
        } else {
            // If source has no decimal point, check if it parses as integer
            if (source.toDoubleOrNull()?.rem(1) == 0.0) 0 else 2
        }
        return String.format(Locale.US, "%.${decimalPlaces}f", value)
    }

    private fun computeSha256(bytes: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun updateImage(context: Context, uri: Uri) {
        selectedImageUri = uri
        // RC16: clear the wasUpscaled flag whenever the user picks a fresh
        // image (this fn is only called for picker results; the post-upscale
        // selectedImageUri = Uri.fromFile(outFile) write skips it).
        wasUpscaled = false
        try {
            // Compute image content hash for per-image counter
            val imageBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            currentImageHash = imageBytes?.let { computeSha256(it) }

            // Phase H-P1.13: robust SVG detection — MIME first, extension second,
            // magic-byte sniff third. Cache the result on the ViewModel so the
            // preview / PDF / modal paths all agree.
            val isSvg = detectIsSvg(context, uri, imageBytes)
            sourceIsSvg = isSvg

            context.contentResolver.openInputStream(uri)?.use { input ->
                val bitmap = if (isSvg) {
                    val svg = SVG.getFromInputStream(input)
                    val width = svg.documentWidth.toInt().takeIf { it > 0 } ?: 1024
                    val height = svg.documentHeight.toInt().takeIf { it > 0 } ?: 1024
                    val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(b)
                    svg.renderToCanvas(canvas)
                    b
                } else {
                    BitmapFactory.decodeStream(input)
                }

                bitmap?.let { b ->
                    val w = b.width
                    val h = b.height
                    val ar = w.toDouble() / h.toDouble()
                    imageMetadata = ImageMetadata(
                        width = w,
                        height = h,
                        aspectRatio = ar,
                        aspectRatioString = String.format(Locale.US, "%.1f:1.0", ar),
                        resolution = "${w}x${h}px"
                    )

                    if (isAspectRatioLocked) {
                        val currentW = posterWidth.toDoubleOrNull() ?: (if (units == "Metric") 60.96 else 24.0)
                        posterHeight = formatWithSamePrecision(currentW / ar, posterWidth)
                        saveAllSettings()
                    }
                }
            }
        } catch (e: Exception) {
            errorMessage = "Failed to load image info: ${e.message}"
        }
    }

    /**
     * Phase H-P1.13: Robust SVG detection. ContentResolver.getType() is the
     * authoritative answer when the provider sets it, but content:// URIs from
     * SAF / Downloads sometimes return application/octet-stream. So:
     *   1) MIME starts with "image/svg" → SVG
     *   2) URI path ends with .svg / .svgz → SVG
     *   3) First non-whitespace bytes look like XML or "<svg" → SVG
     * The byte sniff handles SVGs that are gzipped (.svgz) too — the
     * androidsvg library auto-detects gzip via its own magic-byte check.
     */
    private fun detectIsSvg(context: Context, uri: Uri, headBytes: ByteArray?): Boolean {
        val mime = context.contentResolver.getType(uri)?.lowercase(Locale.US)
        if (mime != null && mime.startsWith("image/svg")) return true
        val path = uri.toString().lowercase(Locale.US)
        if (path.endsWith(".svg") || path.endsWith(".svgz")) return true
        if (headBytes == null || headBytes.isEmpty()) return false
        // Inspect up to first 256 bytes for XML/SVG markers.
        val sniff = headBytes.copyOfRange(0, kotlin.math.min(headBytes.size, 256))
        val asString = try {
            String(sniff, Charsets.UTF_8).trimStart()
        } catch (_: Exception) {
            return false
        }
        return asString.startsWith("<?xml") ||
            asString.startsWith("<svg") ||
            asString.startsWith("<!DOCTYPE svg")
    }

    fun updatePosterWidth(width: String) {
        posterWidth = width
        val w = width.toDoubleOrNull()
        val metadata = imageMetadata
        if (isAspectRatioLocked && w != null && metadata != null) {
            posterHeight = formatWithSamePrecision(w / metadata.aspectRatio, width)
            logEvent(appContext, "Aspect ratio locked height update", "width=$width, height=$posterHeight, aspectRatio=${metadata.aspectRatio}")
        }
        logEvent(appContext, "Poster width changed", "width=$width, locked=$isAspectRatioLocked")
    }

    fun updatePosterHeight(height: String) {
        posterHeight = height
        val h = height.toDoubleOrNull()
        val metadata = imageMetadata
        if (isAspectRatioLocked && h != null && metadata != null) {
            posterWidth = formatWithSamePrecision(h * metadata.aspectRatio, height)
            logEvent(appContext, "Aspect ratio locked width update", "height=$height, width=$posterWidth, aspectRatio=${metadata.aspectRatio}")
        }
        logEvent(appContext, "Poster height changed", "height=$height, locked=$isAspectRatioLocked")
    }

    private fun getPaperDimensionsForOrientation(orient: String): Pair<Double, Double> {
        var paperW: Double
        var paperH: Double
        if (paperSize == "Custom") {
            paperW = customPaperWidth.toDoubleOrNull() ?: if (units == "Metric") 21.59 else 8.5
            paperH = customPaperHeight.toDoubleOrNull() ?: if (units == "Metric") 27.94 else 11.0
        } else {
            val parts = paperSize.replace(")", "").split("(").last().split("x", "X")
            if (parts.size < 2) {
                paperW = if (units == "Metric") 21.59 else 8.5
                paperH = if (units == "Metric") 27.94 else 11.0
            } else {
                val pwInches = parts[0].trim().toDoubleOrNull() ?: 8.5
                val phInches = parts[1].trim().toDoubleOrNull() ?: 11.0
                paperW = if (units == "Metric") pwInches * 2.54 else pwInches
                paperH = if (units == "Metric") phInches * 2.54 else phInches
            }
        }
        return when (orient) {
            "Portrait" -> Pair(kotlin.math.min(paperW, paperH), kotlin.math.max(paperW, paperH))
            "Landscape" -> Pair(kotlin.math.max(paperW, paperH), kotlin.math.min(paperW, paperH))
            else -> Pair(paperW, paperH)
        }
    }

    fun getPaperDimensions(): Pair<Double, Double> {
        if (orientation != "Best Fit") {
            return getPaperDimensionsForOrientation(orientation)
        }
        // Best Fit: calculate pane counts for both orientations, choose the one with fewer panes
        val pw = posterWidth.toDoubleOrNull() ?: return getPaperDimensionsForOrientation("Portrait")
        val ph = posterHeight.toDoubleOrNull() ?: return getPaperDimensionsForOrientation("Portrait")
        val m = margin.toDoubleOrNull() ?: 0.0
        val o = overlap.toDoubleOrNull() ?: 0.0
        val unitScale = if (units == "Metric") 72.0 / 2.54 else 72.0

        val portraitDims = getPaperDimensionsForOrientation("Portrait")
        val landscapeDims = getPaperDimensionsForOrientation("Landscape")

        val portraitPW = portraitDims.first - 2 * m
        val portraitPH = portraitDims.second - 2 * m
        val landscapePW = landscapeDims.first - 2 * m
        val landscapePH = landscapeDims.second - 2 * m

        if (portraitPW <= 0 || portraitPH <= 0 || landscapePW <= 0 || landscapePH <= 0) {
            return portraitDims
        }

        val (portraitTotal, _, _) = posterLogic.calculateSheetCount(
            pw * unitScale, ph * unitScale,
            portraitPW * unitScale, portraitPH * unitScale,
            o * unitScale
        )
        val (landscapeTotal, _, _) = posterLogic.calculateSheetCount(
            pw * unitScale, ph * unitScale,
            landscapePW * unitScale, landscapePH * unitScale,
            o * unitScale
        )

        return if (portraitTotal <= landscapeTotal) portraitDims else landscapeDims
    }

    /**
     * Current paper width in the user's active units (inches if Imperial, cm if Metric),
     * already orientation-aware. Mirrors `getPaperDimensions().first`. Used by the
     * construction preview to compute pane geometry in the same unit space as
     * `posterWidth`, `margin`, and `overlap`.
     */
    fun currentPaperWidthInches(): Double = getPaperDimensions().first

    /**
     * Current paper height in the user's active units (inches if Imperial, cm if Metric).
     * See [currentPaperWidthInches].
     */
    fun currentPaperHeightInches(): Double = getPaperDimensions().second

    fun getDpiWarning(): String? {
        val metadata = imageMetadata ?: return null
        val w = posterWidth.toDoubleOrNull() ?: return null
        val h = posterHeight.toDoubleOrNull() ?: return null
        
        val widthInInches = if (units == "Metric") w / 2.54 else w
        val heightInInches = if (units == "Metric") h / 2.54 else h
        
        if (widthInInches <= 0 || heightInInches <= 0) return null

        val dpiW = metadata.width / widthInInches
        val dpiH = metadata.height / heightInInches
        val minDpi = kotlin.math.min(dpiW, dpiH)
        
        return if (minDpi < 150) {
            "Low Print Resolution: ~${minDpi.toInt()} DPI. Try AI upscaling (e.g., OpenArt Ultimate Upscale) or use a higher resolution image."
        } else null
    }

    fun getPaneCount(): Triple<Int, Int, Int>? {
        val pw = posterWidth.toDoubleOrNull() ?: return null
        val ph = posterHeight.toDoubleOrNull() ?: return null
        val m = margin.toDoubleOrNull() ?: 0.0
        val o = overlap.toDoubleOrNull() ?: 0.0
        
        val (paperW, paperH) = getPaperDimensions()
        
        val printableW = paperW - 2 * m
        val printableH = paperH - 2 * m
        
        if (printableW <= 0 || printableH <= 0) return null

        val unitScale = if (units == "Metric") 72.0 / 2.54 else 72.0

        return posterLogic.calculateSheetCount(pw * unitScale, ph * unitScale, printableW * unitScale, printableH * unitScale, o * unitScale)
    }

    fun getGridLabel(row: Int, col: Int): String {
        return posterLogic.getGridLabel(row, col)
    }

    fun generatePoster(context: Context, onSuccess: () -> Unit = {}) {
        val uri = selectedImageUri ?: return
        viewModelScope.launch {
            isGenerating = true
            errorMessage = null
            successMessage = null
            logEvent(appContext, "Poster generation started", "imageUri=$uri")

            try {
                withContext(Dispatchers.IO) {
                    PDFBoxResourceLoader.init(context)

                    // Phase H-P1.13: re-detect SVG defensively (also set in
                    // updateImage). Same bytes feed both detection and decode.
                    val headBytesForDetect = try {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    } catch (_: Exception) {
                        null
                    }
                    val isSvgSource = detectIsSvg(context, uri, headBytesForDetect)
                    sourceIsSvg = isSvgSource

                    val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                        if (isSvgSource) {
                            val svg = SVG.getFromInputStream(input)
                            val renderWidth = (svg.documentWidth.takeIf { it > 0 } ?: 2048f).toInt()
                            val renderHeight = (svg.documentHeight.takeIf { it > 0 } ?: 2048f).toInt()
                            val b = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(b)
                            svg.renderToCanvas(canvas)
                            b
                        } else {
                            BitmapFactory.decodeStream(input)
                        }
                    } ?: throw Exception("Could not load image")

                    val pw = posterWidth.toDoubleOrNull() ?: if (units == "Metric") 60.96 else 24.0
                    val ph = posterHeight.toDoubleOrNull() ?: if (units == "Metric") 91.44 else 36.0
                    val m = margin.toDoubleOrNull() ?: if (units == "Metric") 1.27 else 0.5
                    val o = overlap.toDoubleOrNull() ?: if (units == "Metric") 0.63 else 0.25

                    val (paperW, paperH) = getPaperDimensions()

                    val outputDir = context.getExternalFilesDir(null) ?: context.filesDir
                    val outputFile = File(outputDir, "poster_${System.currentTimeMillis()}.pdf")

                    val unitScale = if (units == "Metric") 72.0 / 2.54 else 72.0
                    val logoBitmap = try {
                        val res = appContext.resources
                        val pkg = appContext.packageName
                        val customLogoId = res.getIdentifier("pdf_logo", "drawable", pkg)
                        when {
                            customLogoId != 0 -> BitmapFactory.decodeResource(res, customLogoId)
                            else -> BitmapFactory.decodeResource(res, com.posterpdf.R.drawable.dogcow)
                        }
                    } catch (_: Exception) {
                        null
                    }

                    // Phase H-P1.13: per-tile SVG renderer for vector-quality
                    // PDF output. PosterLogic invokes this once per tile with
                    // (tilePxW, tilePxH, srcLeft01, srcTop01, srcRight01, srcBottom01)
                    // — the last four are the slice of the poster (in 0..1
                    // poster-fraction coords) that this tile should show. The
                    // callback returns a Bitmap of (tilePxW × tilePxH) showing
                    // exactly that slice rendered straight from the SVG (no
                    // intermediate full-poster raster). Returns null for raster
                    // sources.
                    val svgTileRenderer: ((Int, Int, Float, Float, Float, Float) -> Bitmap)? =
                        if (isSvgSource) { tilePxW: Int, tilePxH: Int,
                                           srcLeft01: Float, srcTop01: Float,
                                           srcRight01: Float, srcBottom01: Float ->
                            // Re-open the URI per tile; SVG.getFromInputStream
                            // consumes the stream, and re-parsing the XML is
                            // cheap compared to rendering.
                            val svg = context.contentResolver.openInputStream(uri)
                                ?.use { SVG.getFromInputStream(it) }
                                ?: throw Exception("Could not reopen SVG for tile")

                            // Force the doc to render at the full poster's
                            // pixel size, then we render with an offset Canvas
                            // so only the tile slice lands in the bitmap.
                            // We pick "full poster pixel size" as
                            // tilePx / sliceFraction → keeps SVG geometry
                            // proportional regardless of intrinsic dims.
                            val sliceW = (srcRight01 - srcLeft01).coerceAtLeast(1e-4f)
                            val sliceH = (srcBottom01 - srcTop01).coerceAtLeast(1e-4f)
                            val fullPosterPxW = tilePxW / sliceW
                            val fullPosterPxH = tilePxH / sliceH
                            svg.setDocumentWidth(fullPosterPxW)
                            svg.setDocumentHeight(fullPosterPxH)

                            val tileBmp = Bitmap.createBitmap(
                                tilePxW.coerceAtLeast(1),
                                tilePxH.coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888,
                            )
                            val canvas = Canvas(tileBmp)
                            // Translate so that the tile-slice origin lands at (0,0).
                            canvas.translate(-srcLeft01 * fullPosterPxW, -srcTop01 * fullPosterPxH)
                            svg.renderToCanvas(canvas)
                            tileBmp
                        } else null

                    posterLogic.createTiledPoster(
                        bitmap = bitmap,
                        posterW = pw * unitScale,
                        posterH = ph * unitScale,
                        pageW = paperW * unitScale,
                        pageH = paperH * unitScale,
                        margin = m * unitScale,
                        overlap = o * unitScale,
                        outputPath = outputFile.absolutePath,
                        showOutlines = showOutlines,
                        outlineStyle = outlineStyle,
                        outlineThickness = outlineThickness,
                        labelPanes = labelPanes,
                        includeInstructions = includeInstructions,
                        logoBitmap = logoBitmap,
                        sourcePixelW = imageMetadata?.width ?: bitmap.width,
                        sourcePixelH = imageMetadata?.height ?: bitmap.height,
                        svgTileRenderer = svgTileRenderer,
                        // RC16: also suppress when the source IS already an
                        // upscaled image (wasUpscaled). RC15 only suppressed
                        // while an upscale was queued/running; after success
                        // both flags clear, but the bitmap is now high-res
                        // so the warning was wrong-but-firing in user logs.
                        suppressLowDpiWarning =
                            wasUpscaled ||
                            pendingUpscaleModelLabel != null ||
                            isFreeUpscaling,
                        units = units,
                    )
                    
                     withContext(Dispatchers.Main) {
                         lastGeneratedFile = outputFile
                         successMessage = "Poster generated: ${outputFile.name}"
                         // Only count a new poster if this image hasn't been counted yet
                         val hash = currentImageHash
                         if (hash != null && hash != lastCountedHash) {
                             postersMadeCount++
                             lastCountedHash = hash
                             logEvent(appContext, "Poster count incremented", "file=${outputFile.name}, count=$postersMadeCount, hash=${hash.take(8)}")
                         } else {
                             logEvent(appContext, "Poster regenerated (not counted)", "file=${outputFile.name}, count=$postersMadeCount")
                         }
                         saveAllSettings()
                         recordPdfHistory(outputFile, paperW, paperH)
                         onSuccess()
                     }
                }
             } catch (e: Exception) {
                errorMessage = "Failed: ${e.message}"
                logEvent(appContext, "Poster generation failed", "error=${e.message}")
            } finally {
                isGenerating = false
            }
        }
    }

    fun dismissNagware() {
        showNagwareModal = false
        nagwareDismissed = true
    }

    private fun recordPdfHistory(file: File, paperW: Double, paperH: Double) {
        val hash = currentImageHash ?: return
        val pane = getPaneCount()
        val metadata = mapOf(
            "fileName" to file.name,
            "posterWidth" to posterWidth,
            "posterHeight" to posterHeight,
            "paperSize" to paperSize,
            "paperW" to paperW,
            "paperH" to paperH,
            "units" to units,
            "rows" to (pane?.second ?: 0),
            "cols" to (pane?.third ?: 0),
            "pages" to (pane?.first ?: 0),
        )
        viewModelScope.launch {
            val ok = backend.addHistory(
                type = "pdf_local",
                sourceHash = hash,
                localUri = file.absolutePath,
                metadata = metadata,
            )
            if (ok) refreshHistory()
        }
    }

    fun refreshHistory() {
        viewModelScope.launch {
            isHistoryLoading = true
            try {
                historyItems = backend.listHistory(limit = 50)
            } finally {
                isHistoryLoading = false
            }
        }
    }

    /** Pull a sign-in intent the Activity can launch. */
    fun googleSignInIntent(activity: android.app.Activity): android.content.Intent =
        auth.googleSignInIntent(activity, com.posterpdf.data.backend.BackendConfig.WEB_CLIENT_ID)

    fun handleGoogleSignInResult(data: android.content.Intent?) {
        viewModelScope.launch {
            val result = auth.handleGoogleSignInResult(data)
            if (result.isSuccess) {
                successMessage = "Signed in"
                refreshHistory()
            } else {
                errorMessage = "Sign-in failed: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun signOut() {
        auth.signOut()
        viewModelScope.launch {
            auth.ensureSignedIn() // immediately go back to anonymous
            refreshHistory()
        }
    }

    /**
     * RC12c — fires the backend's debug fixture so we can test FCM end-to-end
     * without waiting for the daily storage-billing cron.
     *
     * RC14: previous Toast attempt produced zero feedback per user testing.
     * Restructured for visibility:
     *   1. Immediate Toast on tap so the user sees ANY feedback right away
     *      (proves the chip click reached the ViewModel);
     *   2. Dispatchers.Main explicit for the show() calls (Toast.show throws
     *      silently from non-main threads on some OEMs);
     *   3. 15-second withTimeout on the backend call so a hung Ktor request
     *      doesn't leave the user staring at "Sending…" forever;
     *   4. Result Toast in a finally-style outer try/catch so any surprise
     *      exception still produces visible feedback;
     *   5. Also writes to debug log via logEvent so the failure mode is
     *      forensically inspectable from the user's saved log file.
     */
    fun runTestStorageEvent(type: String) {
        viewModelScope.launch(Dispatchers.Main) {
            android.widget.Toast.makeText(
                appContext, "Sending test push ($type)…", android.widget.Toast.LENGTH_SHORT,
            ).show()
            logEvent(appContext, "test_push: tap", "type=$type")
            val msg = try {
                val r = kotlinx.coroutines.withTimeout(15_000L) {
                    backend.triggerTestStorageEvent(type)
                }
                if (r != null) {
                    logEvent(appContext, "test_push: ok", "delivered=${r.delivered} title=${r.title}")
                    "Test push: ${r.title} (${r.delivered} delivered)"
                } else {
                    logEvent(appContext, "test_push: backend returned null", "type=$type")
                    "Test push: backend returned null (route 404? not signed in?)"
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                logEvent(appContext, "test_push: timeout", "type=$type")
                "Test push timed out after 15 s"
            } catch (t: Throwable) {
                logEvent(appContext, "test_push: exception", "${t.javaClass.simpleName}: ${t.message}")
                "Test push error: ${t.javaClass.simpleName}"
            }
            android.widget.Toast.makeText(appContext, msg, android.widget.Toast.LENGTH_LONG).show()
            successMessage = msg // fallback for when drawer is closed
        }
    }

    /**
     * RC17: submit a support ticket through SupportRepository. Result
     * callback fires on the main thread with the new doc id on success
     * or the failure throwable so the SupportScreen can show success
     * panel or error toast. The function also writes to debug log so
     * a failed submit is recoverable.
     */
    fun submitSupport(
        context: Context,
        subject: String,
        category: String,
        description: String,
        includeDiagnostics: Boolean,
        onResult: (Result<String>) -> Unit,
    ) {
        viewModelScope.launch {
            logEvent(context, "support_submit", "category=$category diag=$includeDiagnostics")
            val r = supportRepo.submit(
                context = context,
                subject = subject,
                category = category,
                description = description,
                includeDiagnostics = includeDiagnostics,
            )
            r.onSuccess { id ->
                logEvent(context, "support_submit: ok", "ticket=$id")
                successMessage = "Feedback sent — thanks"
            }.onFailure { t ->
                logEvent(context, "support_submit: fail", t.message)
                errorMessage = "Couldn't send: ${t.javaClass.simpleName}"
            }
            onResult(r)
        }
    }

    fun logEvent(context: Context, event: String, details: String? = null) {
        if (!debugLoggingEnabled) return
        viewModelScope.launch(Dispatchers.IO) {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val line = "$timestamp - $event${details?.let { ": $it" } ?: ""}\n"
            writeLogLineSync(context, line)
        }
    }

    companion object {
        /**
         * RC8: synchronous log-line write. Used by logEvent (already off the
         * main thread via Dispatchers.IO coroutine) AND by the global
         * UncaughtExceptionHandler installed in MainActivity, which has no
         * coroutine context — the JVM is dying and any async write would lose
         * the line. Writes go to the app's external-files Download dir,
         * accessible via FileProvider for the "Share debug log" drawer item.
         *
         * Pre-RC8: this code wrote to context.getExternalFilesDir() with a
         * fallback to Environment.getExternalStoragePublicDirectory() — the
         * fallback path silently fails on Android 11+ scoped storage, so the
         * "no log written" symptom user reported was the catch-all swallowing
         * the FileNotFoundException.
         */
        fun writeLogLineSync(context: Context, line: String) {
            try {
                val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: return
                if (!dir.exists()) dir.mkdirs()
                val logFile = File(dir, "pdfposter_debug.log")
                FileOutputStream(logFile, true).use { it.write(line.toByteArray()) }
            } catch (_: Throwable) {
                // Best-effort. If the FS is unavailable, drop the line silently.
            }
        }

        /**
         * Path to the log file the user can grab via FileProvider. Returns
         * null when there is no log file (debug logging never enabled or
         * cleared).
         */
        fun debugLogFile(context: Context): File? {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
            val f = File(dir, "pdfposter_debug.log")
            return if (f.exists()) f else null
        }
    }
}
