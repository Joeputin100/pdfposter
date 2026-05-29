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
import androidx.compose.ui.graphics.asImageBitmap
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

    // RC69: PosterPreview publishes its decoded source bitmap here so the
    // low-DPI upgrade drawer can render from the Scaffold body (it used to
    // be a ModalBottomSheet inside PosterPreview).
    var sourcePreviewBitmap: androidx.compose.ui.graphics.ImageBitmap? by mutableStateOf(null)
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

    // RC69: debug-only helper for the CI screenshot pipeline. Seeds a small
    // bundled bitmap and a deliberately LOW source resolution so the low-DPI
    // upgrade picker is the relevant state when launched with
    // `--es screenshot model_picker`.
    fun seedScreenshotImage(context: android.content.Context) {
        val bmp = BitmapFactory.decodeResource(context.resources, R.drawable.dogcow)
        if (bmp != null) {
            sourcePreviewBitmap = bmp.asImageBitmap()
            sourcePixelDimensions = 400 to 300
        }
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
        get() = authSession.email?.lowercase() in ADMIN_EMAILS

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

    /** RC73: FTL upscale→PDF device-test marker; "" until done. Driven by the
     *  DEBUG-only `--es screenshot upscale_test` intent hook and surfaced as an
     *  on-screen Text + logcat line so the FTL instrumentation test can read a
     *  per-device duration. Never set outside the debug device-test driver. */
    var deviceTestStatus: String by mutableStateOf("")

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
    /** RC28: same idea for minScale — Retry replays at the same headroom. */
    private var lastAiUpscaleMinScale: Int? = null
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

    /** RC76: when true, MainActivity shows the ">10 min, are you sure?"
     *  confirmation before a long on-device upscale starts. The actual start
     *  is stashed in [pendingUpscaleStart] and fired by [confirmLongJob]. */
    var showLongJobConfirm by mutableStateOf(false)
        private set
    /** RC76: predicted on-device ETA in whole minutes, shown in the modal. */
    var pendingUpscaleEtaMin by mutableStateOf(0)
        private set
    private var pendingUpscaleStart: (() -> Unit)? = null

    /** RC76: gate run before a free upscale starts. Returns true to proceed
     *  now; false if it either (a) set [errorMessage] steering a too-big job
     *  to cloud, or (b) stashed [start] and popped the long-job confirm modal
     *  (proceed then happens via [confirmLongJob]). [etaMs] is the predicted
     *  on-device run time; pass 0 when unknown (no cached benchmark) so the
     *  time check is skipped — the memory net still applies. */
    private fun gateLongJob(
        context: Context,
        outWidthPx: Int,
        etaMs: Long,
        start: () -> Unit,
    ): Boolean {
        if (!com.posterpdf.ml.oneBandFits(context, outWidthPx)) {
            errorMessage = context.getString(R.string.upscale_too_large_use_cloud)
            return false
        }
        if (etaMs > 10 * 60 * 1000L) {
            pendingUpscaleEtaMin = (etaMs / 60000L).toInt()
            pendingUpscaleStart = start
            showLongJobConfirm = true
            return false
        }
        return true
    }

    /** RC76: user tapped "Continue on device" — dismiss the modal and start. */
    fun confirmLongJob() {
        showLongJobConfirm = false
        val start = pendingUpscaleStart
        pendingUpscaleStart = null
        start?.invoke()
    }

    /** RC76: user dismissed the long-job modal — drop the pending start. */
    fun dismissLongJob() {
        showLongJobConfirm = false
        pendingUpscaleStart = null
    }

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
    fun runFreeUpscale(context: Context, bypassGate: Boolean = false) {
        val uri = selectedImageUri ?: return
        freeUpscaleJob?.cancel()
        freeUpscaleJob = viewModelScope.launch {
            // RC76: gate BEFORE any progress UI shows. Decode bounds (cheap,
            // bounds-only), predict the on-device ETA from the cached benchmark,
            // then either steer a genuinely-too-big job to cloud, pop the
            // >10-min "are you sure?" modal, or fall through and run. The
            // bypassGate=true path is what confirmLongJob() re-enters after the
            // user taps "Continue on device", so the gate never loops. Running
            // the gate before isFreeUpscaling=true avoids flashing the progress
            // dialog while we steer/confirm.
            if (!bypassGate) {
                val gateBounds = withContext(Dispatchers.IO) {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use {
                            BitmapFactory.decodeStream(it, null, o)
                        }
                    }
                    o
                }
                val gw = gateBounds.outWidth
                val gh = gateBounds.outHeight
                // Only gate when we could read the bounds; a failed/0x0 decode
                // falls through so the main flow surfaces the proper error.
                if (gw > 0 && gh > 0) {
                    val outWidthPx = gw * 4
                    // ETA from the same cached-benchmark mechanism the low-DPI
                    // modal uses (UpscaleEta.etaForLocal — a range in seconds).
                    // Output MP = output pixels / 1e6, matching
                    // UpscalerOnDevice.benchmarkAndCache's ms-per-MP definition.
                    // Collapse the range to its midpoint (as formatEta does) and
                    // convert s→ms. Unknown (no cached benchmark) ⇒ etaMs=0 ⇒ the
                    // time check is skipped; the memory net still runs.
                    val outputMp = (outWidthPx.toLong() * (gh * 4) / 1_000_000L).coerceAtLeast(1L)
                    val msPerMp = withContext(Dispatchers.IO) {
                        com.posterpdf.ml.cachedMsPerMegapixel(context)
                    }
                    val etaRange = com.posterpdf.ml.etaForLocal(outputMp, msPerMp)
                    val etaMs = etaRange?.let { ((it.first + it.last) / 2).toLong() * 1000L } ?: 0L
                    val proceed = gateLongJob(context, outWidthPx, etaMs) {
                        runFreeUpscale(context, bypassGate = true)
                    }
                    if (!proceed) {
                        logEvent(context, "free_upscale: gated", "outW=$outWidthPx etaMs=$etaMs (steered/confirm)")
                        return@launch
                    }
                }
            }
            isFreeUpscaling = true
            freeUpscaleStartMs = System.currentTimeMillis()
            freeUpscaleTilesDone = 0
            freeUpscaleTotalTiles = 0
            logEvent(context, "free_upscale: start", "uri=$uri")
            try {
                logEvent(context, "free_upscale: init UpscalerOnDevice")
                com.posterpdf.ml.UpscalerOnDevice.init(context)
                logEvent(context, "free_upscale: decode source bounds")
                // RC76: band-streaming upscale — we no longer decode the whole
                // source bitmap (or hold the whole 4x output) in RAM. Decode
                // BOUNDS only to size the progress denominator; the upscaler
                // region-reads the source and streams the output to a PNG.
                val srcBounds = withContext(Dispatchers.IO) {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, o)
                    }
                    o
                }
                val srcW = srcBounds.outWidth
                val srcH = srcBounds.outHeight
                if (srcW <= 0 || srcH <= 0) {
                    errorMessage = context.getString(R.string.vm_error_couldnt_open_image)
                    logEvent(context, "free_upscale: ABORT — source bounds decode returned ${srcW}x$srcH")
                    return@launch
                }
                logEvent(context, "free_upscale: source bounds", "${srcW}x$srcH")

                // RC11: pre-compute total tiles so we can start the foreground
                // service with the right notification denominator.
                val totalTiles = (
                    ((srcW + 49) / 50).coerceAtLeast(1) *
                    ((srcH + 49) / 50).coerceAtLeast(1)
                )

                // RC76: band-keyed resume. The old per-tile partial-bitmap
                // snapshot is replaced by a band index (see UpscaleStateStore /
                // UpscalerOnDevice.upscaleToFile). A streamed PNG can't be
                // appended mid-stream, so resume re-encodes from band 0; the
                // saved band is read for diagnostics + forward-compat.
                val resumeFromBand = withContext(Dispatchers.IO) {
                    com.posterpdf.ml.UpscaleStateStore.lastBand(context, uri.toString())
                }
                if (resumeFromBand > 0) {
                    logEvent(context, "free_upscale: prior run reached band $resumeFromBand (re-encoding from start)")
                }

                com.posterpdf.ml.UpscaleForegroundService.start(context, totalTiles)
                logEvent(context, "free_upscale: foreground service started", "totalTiles=$totalTiles")

                logEvent(context, "free_upscale: invoking ESRGAN upscale (4x, streaming)")
                val outFile = File(
                    context.cacheDir,
                    "upscaled_${System.currentTimeMillis()}.png",
                )
                kotlinx.coroutines.withTimeout(15 * 60 * 1000L) {
                    com.posterpdf.ml.UpscalerOnDevice.upscaleToFile(
                        context = context,
                        sourceUri = uri,
                        dest = outFile,
                        resumeFromBand = resumeFromBand,
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
                    )
                }

                // RC76: read the final dimensions from the streamed PNG's bounds
                // (we no longer hold the upscaled bitmap in memory).
                val outBounds = withContext(Dispatchers.IO) {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(Uri.fromFile(outFile))?.use {
                        BitmapFactory.decodeStream(it, null, o)
                    }
                    o
                }
                // Fall back to the known 4x dimensions if the bounds re-decode
                // hiccups (the PNG was just written, so this is belt-and-braces
                // against a 0x0 → NaN aspect ratio).
                val outW = outBounds.outWidth.takeIf { it > 0 } ?: (srcW * 4)
                val outH = outBounds.outHeight.takeIf { it > 0 } ?: (srcH * 4)
                logEvent(context, "free_upscale: upscale returned", "${outW}x$outH")

                selectedImageUri = Uri.fromFile(outFile)
                // RC54: persist the upscale output URI so the user keeps
                // their upscaled image after process death.
                viewModelScope.launch {
                    repository.saveSetting(
                        SettingsRepository.SELECTED_IMAGE_URI,
                        selectedImageUri.toString(),
                    )
                }
                sourcePixelDimensions = outW to outH
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
                val arUp = outW.toDouble() / outH.toDouble()
                imageMetadata = ImageMetadata(
                    width = outW,
                    height = outH,
                    aspectRatioString = String.format(Locale.US, "%.1f:1.0", arUp),
                    aspectRatio = arUp,
                    resolution = "${outW}×$outH",
                )
                wasUpscaled = true
                successMessage = context.getString(R.string.vm_success_upscaled_inline, outW, outH)
                pendingUpscaleModelLabel = null
                logEvent(context, "free_upscale: SUCCESS", "wrote ${outFile.name}")
                // RC11: success — clear the resume state so the next run
                // starts fresh, and stop the foreground service.
                com.posterpdf.ml.UpscaleStateStore.clear(context)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                errorMessage = context.getString(R.string.vm_error_sharpening_timed_out)
                logEvent(context, "free_upscale: TIMEOUT — exceeded 15 min budget")
                // Keep state on disk so the user can resume next launch.
            } catch (e: kotlinx.coroutines.CancellationException) {
                logEvent(context, "free_upscale: cancelled by user")
                // RC11: explicit cancel clears state — user said "stop", don't
                // offer to resume. Process kill leaves state untouched.
                com.posterpdf.ml.UpscaleStateStore.clear(context)
            } catch (e: Throwable) {
                errorMessage = context.getString(R.string.vm_error_upscale_failed_inline, e.message ?: context.getString(R.string.support_unknown_error))
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
        // RC53: stop the foreground service explicitly so its notification
        // disappears. Cancelling the coroutine alone leaves the service
        // running until its observer eventually triggers stop, but the
        // notification stays visible in the meantime — user-perceivable
        // as a "stuck" tray entry.
        com.posterpdf.ml.UpscaleForegroundService.stop(appContext)
    }

    /**
     * RC73: DEBUG-only Firebase Test Lab driver. Seeds the bundled dogcow test
     * image at a low-DPI 18" poster (400px / 18in ≈ 22 DPI → triggers a small,
     * fast 4× free upscale → ~1600px ≈ 89 DPI), runs the on-device ESRGAN
     * upscale to completion, generates the PDF, and writes an
     * `UPSCALE_TEST_DONE` marker into [deviceTestStatus] (read by the FTL
     * UiAutomator test) plus a logcat line carrying the per-device upscale +
     * total durations. On any failure it sets `UPSCALE_TEST_FAILED` so the test
     * fails loudly instead of hanging.
     *
     * Completion of [runFreeUpscale] is awaited via its `isFreeUpscaling` flag:
     * that function sets `isFreeUpscaling = true` at coroutine start (line ~248)
     * and clears it in its `finally` block (`isFreeUpscaling = false`, line
     * ~421). It exposes no dedicated success flag, so we (1) wait for the flag
     * to flip true (start), then (2) wait for it to flip false (finish). Success
     * is then inferred from `wasUpscaled` being set true on the success path
     * (line ~392); a non-null `errorMessage` after the flag clears means it
     * failed.
     */
    fun runUpscaleAndPdfDeviceTest(context: Context) {
        // RC73: emit to REAL logcat (tag UPSCALE_TEST). logEvent writes only to
        // the in-app debug-log file AND is gated by debugLoggingEnabled (off by
        // default), so it never reaches FTL's logcat — Log.i is what makes the
        // per-device timing + diagnosis visible in the captured logcat.
        android.util.Log.i("UPSCALE_TEST", "driver start")
        val t0 = System.currentTimeMillis()
        // Seed the bundled dogcow preview + source dimensions, then materialize
        // a real file URI for it: both runFreeUpscale and generatePoster bail
        // early on a null selectedImageUri, and seedScreenshotImage only sets
        // the preview bitmap, not the URI.
        seedScreenshotImage(context)
        posterWidth = "18"
        viewModelScope.launch {
            try {
                val seedBmp = BitmapFactory.decodeResource(context.resources, R.drawable.dogcow)
                    ?: throw IllegalStateException("dogcow test image missing")
                val seedFile = File(context.cacheDir, "device_test_seed_${System.currentTimeMillis()}.png")
                withContext(Dispatchers.IO) {
                    FileOutputStream(seedFile).use { fos ->
                        seedBmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                }
                selectedImageUri = Uri.fromFile(seedFile)
                sourcePixelDimensions = seedBmp.width to seedBmp.height
                android.util.Log.i("UPSCALE_TEST", "seeded ${seedBmp.width}x${seedBmp.height} uri=$selectedImageUri posterWidth=$posterWidth")
                logEvent(context, "upscale_test", "seeded ${seedBmp.width}x${seedBmp.height}, posterWidth=$posterWidth")

                // Kick off the on-device free upscale and await completion via
                // the isFreeUpscaling flag (true at start, false in finally).
                wasUpscaled = false
                errorMessage = null
                android.util.Log.i("UPSCALE_TEST", "calling runFreeUpscale")
                runFreeUpscale(context)

                // Wait for it to START (guard ~5s) then to FINISH.
                val startGuard = System.currentTimeMillis() + 5_000L
                while (!isFreeUpscaling && System.currentTimeMillis() < startGuard) {
                    kotlinx.coroutines.delay(100)
                }
                android.util.Log.i("UPSCALE_TEST", "upscale loop entered=${isFreeUpscaling}")
                while (isFreeUpscaling) {
                    kotlinx.coroutines.delay(250)
                }
                val upscaleMs = System.currentTimeMillis() - t0
                android.util.Log.i("UPSCALE_TEST", "upscale finished wasUpscaled=$wasUpscaled upscaleMs=$upscaleMs err=${errorMessage ?: "-"}")
                if (!wasUpscaled) {
                    throw IllegalStateException("upscale did not succeed: ${errorMessage ?: "unknown"}")
                }
                logEvent(context, "upscale_test", "upscale complete upscaleMs=$upscaleMs")

                android.util.Log.i("UPSCALE_TEST", "calling generatePoster")
                generatePoster(context) {
                    deviceTestStatus = "UPSCALE_TEST_DONE upscaleMs=$upscaleMs " +
                        "totalMs=${System.currentTimeMillis() - t0} " +
                        "pdf=${lastGeneratedFile?.name ?: "?"}"
                    android.util.Log.i("UPSCALE_TEST", deviceTestStatus)
                    logEvent(context, "upscale_test", deviceTestStatus)
                }
            } catch (e: Throwable) {
                deviceTestStatus = "UPSCALE_TEST_FAILED: ${e.message}"
                android.util.Log.e("UPSCALE_TEST", "FAILED", e)
                logEvent(context, "upscale_test", deviceTestStatus)
            }
        }
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
    fun runAiUpscale(context: Context, modelId: String, minScale: Int? = null) {
        val uri = selectedImageUri ?: return
        val (srcW, srcH) = sourcePixelDimensions ?: return
        // RC24: capture for the failure dialog's Retry button.
        lastAiUpscaleModelId = modelId
        // RC28: also capture minScale so Retry replays the same headroom.
        lastAiUpscaleMinScale = minScale
        aiUpscaleFailure = null
        // RC26: clear the snackbar banners from any prior attempt so a
        // successful retry doesn't leave the previous "AI upscale failed: …"
        // text visible alongside the new success message.
        errorMessage = null
        successMessage = null
        val displayName = when (modelId) {
            "topaz" -> context.getString(R.string.upscale_option_topaz_gigapixel)
            "recraft" -> context.getString(R.string.upscale_option_recraft_crisp)
            "aurasr" -> context.getString(R.string.upscale_option_aurasr)
            "esrgan" -> context.getString(R.string.upscale_option_esrgan)
            "ccsr" -> context.getString(R.string.upscale_option_ccsr)
            else -> modelId
        }
        aiUpscaleJob?.cancel()
        aiUpscaleJob = viewModelScope.launch {
            isAiUpscaling = true
            aiUpscalePhase = context.getString(R.string.vm_phase_starting)
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
                    minScale = minScale,  // RC28
                ) { phase, frac, detail ->
                    aiUpscalePhase = when (phase) {
                        com.posterpdf.data.backend.AiUpscaleRepository.Phase.UPLOADING -> context.getString(R.string.vm_phase_uploading_source)
                        com.posterpdf.data.backend.AiUpscaleRepository.Phase.IN_QUEUE -> context.getString(R.string.vm_phase_in_queue)
                        com.posterpdf.data.backend.AiUpscaleRepository.Phase.IN_PROGRESS -> context.getString(R.string.vm_phase_in_progress, displayName)
                        com.posterpdf.data.backend.AiUpscaleRepository.Phase.DOWNLOADING -> context.getString(R.string.vm_phase_downloading)
                        com.posterpdf.data.backend.AiUpscaleRepository.Phase.SAVING -> context.getString(R.string.vm_phase_saving)
                        com.posterpdf.data.backend.AiUpscaleRepository.Phase.SUCCEEDED -> context.getString(R.string.vm_phase_done)
                        com.posterpdf.data.backend.AiUpscaleRepository.Phase.FAILED -> context.getString(R.string.vm_phase_failed)
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
                        // RC54: persist the AI upscale output so it survives
                        // process death (same rationale as the free upscale
                        // path above).
                        viewModelScope.launch {
                            repository.saveSetting(
                                SettingsRepository.SELECTED_IMAGE_URI,
                                selectedImageUri.toString(),
                            )
                        }
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
                        successMessage = context.getString(R.string.vm_success_upscaled_via, bmp.width, bmp.height, displayName)
                        logEvent(context, "ai_upscale: SUCCESS", "${bmp.width}x${bmp.height}")
                        bmp.recycle()
                    } else {
                        errorMessage = context.getString(R.string.vm_error_decode_failed)
                        aiUpscaleFailure = context.getString(R.string.vm_error_decode_failed)
                    }
                }.onFailure { t ->
                    logEvent(context, "ai_upscale: FAIL", t.message)
                    val msg = context.getString(R.string.vm_error_ai_failed_prefix, t.message ?: t.javaClass.simpleName)
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
                val msg = context.getString(R.string.vm_error_ai_error_prefix, t.message ?: t.javaClass.simpleName)
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
        // RC53: stop the foreground service so the notification dismisses
        // immediately (see cancelFreeUpscale comment).
        com.posterpdf.ml.UpscaleForegroundService.stop(appContext)
    }

    /**
     * RC24: re-run the AI upscale that just failed, using the same model id
     * captured at the previous attempt. Called from the failure dialog's
     * Retry button. No-op if no prior attempt was made.
     */
    fun retryAiUpscale(context: Context) {
        val modelId = lastAiUpscaleModelId ?: return
        aiUpscaleFailure = null
        runAiUpscale(context, modelId, minScale = lastAiUpscaleMinScale)
    }

    // RC27: Are-you-sure guard for upscales whose best achievable DPI is
    // below the user's target. Three of the four FAL models (Recraft, AuraSR,
    // ESRGAN) are locked at 4×; Recraft additionally caps the long edge at
    // ~4096 px. For low-res sources on large posters, even the model's max
    // scale won't hit target. We project the realistic output, and if it falls
    // short, surface a confirm dialog instead of submitting blindly.

    data class UpscaleProjection(
        val effectiveDpi: Float,
        val targetDpi: Float,
        val outputW: Int,
        val outputH: Int,
    )

    data class AiUpscaleConfirmState(
        val modelId: String,
        val displayName: String,
        val effectiveDpi: Int,
        val targetDpi: Int,
        val outputW: Int,
        val outputH: Int,
        val minScale: Int? = null,  // RC28: forwarded through confirm flow
    )

    var aiUpscaleConfirm by mutableStateOf<AiUpscaleConfirmState?>(null)

    // Per-model (max scale, max long-edge px). Caps observed empirically on
    // FAL responses (Recraft: 4096) or set conservatively high so we don't
    // over-warn (Topaz/AuraSR/ESRGAN). Bump if a model's real cap is lower.
    private val modelLimits: Map<String, Pair<Int, Int>> = mapOf(
        "topaz" to (8 to 16384),
        "recraft" to (4 to 4096),
        "aurasr" to (4 to 16384),
        "esrgan" to (4 to 16384),
        // RC29: CCSR max 4×, no observed dimensional cap — set conservatively
        // high so we don't over-warn.
        "ccsr" to (4 to 16384),
    )

    fun projectUpscale(modelId: String): UpscaleProjection? {
        val (srcW, srcH) = sourcePixelDimensions ?: return null
        val pwRaw = posterWidth.toDoubleOrNull() ?: return null
        val phRaw = posterHeight.toDoubleOrNull() ?: return null
        val pwIn = if (units == "Metric") pwRaw / 2.54 else pwRaw
        val phIn = if (units == "Metric") phRaw / 2.54 else phRaw
        if (pwIn <= 0 || phIn <= 0) return null

        val (maxScale, maxLongEdge) = modelLimits[modelId] ?: (4 to 16384)
        var outW = (srcW * maxScale).toDouble()
        var outH = (srcH * maxScale).toDouble()
        val longEdge = kotlin.math.max(outW, outH)
        if (longEdge > maxLongEdge) {
            val factor = maxLongEdge / longEdge
            outW *= factor
            outH *= factor
        }
        val dpiW = outW / pwIn
        val dpiH = outH / phIn
        return UpscaleProjection(
            effectiveDpi = kotlin.math.min(dpiW, dpiH).toFloat(),
            targetDpi = targetDpi.toFloat(),
            outputW = outW.toInt(),
            outputH = outH.toInt(),
        )
    }

    /** Entry point for any UI surface that wants to start an AI upscale.
     *  If projected output meets target DPI, kicks off runAiUpscale directly.
     *  Otherwise sets aiUpscaleConfirm so MainActivity can show the modal.
     *  RC28: minScale forwards through the confirm flow if a Topaz "headroom"
     *  override was selected on the detail card. */
    fun requestAiUpscale(context: Context, modelId: String, minScale: Int? = null) {
        val proj = projectUpscale(modelId)
        if (proj == null || proj.effectiveDpi >= proj.targetDpi) {
            runAiUpscale(context, modelId, minScale = minScale)
            return
        }
        val displayName = when (modelId) {
            "topaz" -> "Topaz Gigapixel"
            "recraft" -> "Recraft Crisp"
            "aurasr" -> "AuraSR"
            "esrgan" -> "ESRGAN"
            "ccsr" -> "CCSR"
            else -> modelId
        }
        aiUpscaleConfirm = AiUpscaleConfirmState(
            modelId = modelId,
            displayName = displayName,
            effectiveDpi = proj.effectiveDpi.toInt(),
            targetDpi = proj.targetDpi.toInt(),
            outputW = proj.outputW,
            outputH = proj.outputH,
            minScale = minScale,
        )
    }

    fun confirmAiUpscaleAfterWarning(context: Context) {
        val s = aiUpscaleConfirm ?: return
        aiUpscaleConfirm = null
        runAiUpscale(context, s.modelId, minScale = s.minScale)
    }

    fun cancelAiUpscaleConfirm() {
        aiUpscaleConfirm = null
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

    // RC33: visual override for the credit badge so the user can preview
    // the badge at arbitrary balances (e.g. four-digit values, or to
    // trigger the digiflip on a known delta). null = use real balance;
    // any Int = override the visible balance everywhere CreditBadge reads
    // through the override-aware accessor in MainActivity.
    var debugCreditOverride by mutableStateOf<Int?>(null)
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
    private val geminiQaRepo by lazy { com.posterpdf.data.backend.GeminiQaRepository() }

    // RC65: client-side tool-call router. When Gemini returns a toolCall
    // (e.g. quoteUpscaleCost), we invoke the local agent-function so the
    // numbers come from the same pricing module the modal uses, not from
    // Gemini's prose.
    private val agentFunctions by lazy {
        com.posterpdf.agentfunctions.PosterPdfAgentFunctions(
            appContext = appContext,
            allOptions = com.posterpdf.ui.components.ALL_OPTIONS,
        )
    }

    // RC65: Gemini Q&A sheet state. Driven by askGemini() / resetGeminiQaState().
    var geminiQaState: com.posterpdf.ui.components.GeminiQaState by mutableStateOf(
        com.posterpdf.ui.components.GeminiQaState.Idle,
    )
        private set

    var authSession by mutableStateOf(AuthSession())
        private set

    /** RC69 (replaces the G12 placeholder): live credit balance, observed
     *  from Firestore users/{uid}.credits via a snapshot listener. 0 while
     *  signed-out / anonymous. */
    var creditBalance: Int by mutableStateOf(0)
        private set

    private var creditListener: com.google.firebase.firestore.ListenerRegistration? = null

    private fun observeCreditBalance(uid: String?, isAnonymous: Boolean) {
        creditListener?.remove()
        creditListener = null
        if (uid == null || isAnonymous) { creditBalance = 0; return }
        creditListener = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .addSnapshotListener { snap, _ ->
                creditBalance = (snap?.getLong("credits") ?: 0L).toInt()
            }
    }
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

    /** RC35: Credits history screen — per-event ledger of credit
     *  purchases, upscale debits, signup bonus, refunds. Reached from
     *  the top-bar account menu. Read from Firestore /upscaleTransactions
     *  + /creditPurchases for the signed-in user. */
    var showCreditsHistory by mutableStateOf(false)
    /** RC35: Manage Account modal — credit balance, last activity,
     *  Google profile, upgrade link, DANGER ZONE (erase account). Modal,
     *  not a screen — closed by dismiss or by the action that triggers
     *  the type-CANCEL confirm sub-modal. */
    var showManageAccount by mutableStateOf(false)

    /** RC44: in-app community board. showCommunity drives the feed
     *  screen; selectedCommunityPostId drills into a post; composing
     *  toggles the new-post composer. The three are mutually exclusive
     *  layers stacked above the main screen — the route is computed in
     *  MainActivity from the combination. */
    var showCommunity by mutableStateOf(false)
    var selectedCommunityPostId by mutableStateOf<String?>(null)
    var composingCommunityPost by mutableStateOf(false)

    private var ignoreFlowUpdates = false

    init {
        loadSettings()
        viewModelScope.launch {
            auth.session.collectLatest { s ->
                authSession = s
                observeCreditBalance(s.uid, s.isAnonymous)
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
                // RC75c: skip the launch-time ETA benchmark in test-hook builds
                // (debug + benchmark variant). It shares the singleton TFLite
                // Interpreter with the FTL upscale device test; the RC75 mutex
                // makes that safe, but the benchmark's inference still contends
                // for the (now-serialized) interpreter and inflates the device
                // test's measured upscaleMs. Release builds (ENABLE_TEST_HOOKS
                // = false) still warm the ETA cache normally.
                if (!com.posterpdf.BuildConfig.ENABLE_TEST_HOOKS &&
                    com.posterpdf.ml.benchmarkNeedsRefresh(appContext)) {
                    com.posterpdf.ml.UpscalerOnDevice.init(appContext)
                    com.posterpdf.ml.UpscalerOnDevice.benchmarkAndCache(appContext)
                    logEvent(appContext, "upscale_benchmark: completed")
                }
            } catch (t: Throwable) {
                logEvent(appContext, "upscale_benchmark: failed", t.message)
            }
        }
    }

    override fun onCleared() {
        creditListener?.remove()
        super.onCleared()
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
                // RC54: restore the imported source image after process death
                // by re-decoding the persisted file URI. Only fires once per
                // settings emit, and only when selectedImageUri is currently
                // null (so we don't clobber an in-session change).
                if (selectedImageUri == null) {
                    (settings[SettingsRepository.SELECTED_IMAGE_URI] as? String)?.let { uriStr ->
                        val uri = runCatching { Uri.parse(uriStr) }.getOrNull()
                        if (uri != null) {
                            val file = uri.path?.let { java.io.File(it) }
                            if (file != null && file.exists()) {
                                // Use the in-class restore path so the bitmap +
                                // metadata caches all repopulate.
                                updateImage(appContext, uri)
                            } else {
                                // The file is gone (rare — would mean user cleared
                                // app data without clearing settings, or filesDir
                                // got wiped). Drop the stale pointer.
                                viewModelScope.launch {
                                    repository.saveSetting(
                                        SettingsRepository.SELECTED_IMAGE_URI, "",
                                    )
                                }
                            }
                        }
                    }
                }
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
        // RC54: copy the picked content into app-private storage so the URI
        // we keep in state can be reopened after process death. The URI we
        // get from ActivityResultContracts.GetContent() is only readable
        // for the lifetime of the calling Activity — Android killing the
        // backgrounded process invalidates it, and the user's image was
        // gone on relaunch. Copying once to filesDir means the URI is
        // durable across kills.
        //
        // RC57: use a timestamp-suffixed filename instead of a fixed
        // "imported_image.bin". Two reasons:
        //   1. mutableStateOf<Uri> only triggers recomposition when the URI
        //      *value* changes. With a fixed filename, picking a new image
        //      overwrites the bytes but leaves the URI string identical, so
        //      Compose treats it as a no-op and the viewport keeps showing
        //      the old (cached) bitmap.
        //   2. UpscaleStateStore keys resume snapshots by sourceUri.toString().
        //      A stable URI means an in-progress upscale's resume state
        //      (e.g. "tile 72 of 2508" from a 1080x5667 image) gets matched
        //      against a freshly-imported image with only 140 tiles, leading
        //      to impossible state and a crash mid-upscale (RC56 bug report,
        //      pdfposter_debug.log 2026-05-09 12:24).
        // Old imported files are deleted in the same pass so the filesDir
        // doesn't accumulate stale copies.
        val resolvedUri: Uri = try {
            if (uri.scheme == "file") {
                // Already a file URI (e.g. our own upscale output) — no copy.
                uri
            } else {
                val filesDir = context.filesDir
                val dest = java.io.File(filesDir, "imported_${System.currentTimeMillis()}.bin")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                // Best-effort cleanup of older imported_*.bin files (and the
                // legacy "imported_image.bin" name from RC54) so we don't
                // leak disk on every import. Skip the file we just wrote.
                filesDir.listFiles { f ->
                    f.isFile &&
                        (f.name.startsWith("imported_") || f.name == "imported_image.bin") &&
                        f.name != dest.name
                }?.forEach { runCatching { it.delete() } }
                Uri.fromFile(dest)
            }
        } catch (t: Throwable) {
            android.util.Log.w("MainViewModel", "image-copy failed: ${t.message}")
            uri  // Fall back to the original URI; will work for this session.
        }
        // RC57: any prior upscale resume state belongs to the previous
        // image — wipe it so runFreeUpscale doesn't try to resume a
        // 2508-tile job on top of a 140-tile image.
        runCatching {
            com.posterpdf.ml.UpscaleStateStore.clear(context)
        }
        selectedImageUri = resolvedUri
        // Persist URI string so a relaunch after process death can restore.
        viewModelScope.launch {
            repository.saveSetting(SettingsRepository.SELECTED_IMAGE_URI, resolvedUri.toString())
        }
        // RC16: clear the wasUpscaled flag whenever the user picks a fresh
        // image (this fn is only called for picker results; the post-upscale
        // selectedImageUri = Uri.fromFile(outFile) write skips it).
        wasUpscaled = false
        try {
            // Compute image content hash for per-image counter
            val imageBytes = context.contentResolver.openInputStream(resolvedUri)?.use { it.readBytes() }
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
            errorMessage = appContext.getString(R.string.vm_error_load_image_info, e.message ?: "")
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
                    } ?: throw Exception(appContext.getString(R.string.vm_error_could_not_load_image))

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
                                ?: throw Exception(appContext.getString(R.string.vm_error_could_not_reopen_svg))

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
                        context = appContext,
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
                        // RC34: also suppress for SVG sources — the PDF
                        // emitter renders SVG via the per-tile vector path,
                        // so DPI is meaningless for vector input.
                        suppressLowDpiWarning =
                            wasUpscaled ||
                            pendingUpscaleModelLabel != null ||
                            isFreeUpscaling ||
                            sourceIsSvg,
                        units = units,
                    )
                    
                     withContext(Dispatchers.Main) {
                         lastGeneratedFile = outputFile
                         successMessage = appContext.getString(R.string.vm_success_poster_generated, outputFile.name)
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
                // RC56: clear any prior errorMessage so a successful retry
                // after a cancellation doesn't render two stacked messages
                // ("Sign-in failed: 12501:" + "Signed in" — the cancel set
                // errorMessage and the success only set successMessage,
                // leaving both visible together).
                errorMessage = null
                successMessage = appContext.getString(R.string.vm_success_signed_in)
                refreshHistory()
            } else {
                // RC56: status code 12501 is SIGN_IN_CANCELLED — the user
                // dismissed Google's account picker. That isn't a failure,
                // so we silently ignore it instead of displaying a scary
                // "Sign-in failed: 12501:" message that confuses the user
                // when they sign in successfully on a follow-up tap.
                val ex = result.exceptionOrNull()
                val isCancellation = ex is com.google.android.gms.common.api.ApiException &&
                    ex.statusCode == com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes.SIGN_IN_CANCELLED
                if (!isCancellation) {
                    errorMessage = appContext.getString(R.string.vm_error_signin_failed, ex?.message ?: "")
                }
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

    fun resetGeminiQaState() {
        geminiQaState = com.posterpdf.ui.components.GeminiQaState.Idle
    }

    /**
     * RC65: dispatch a user prompt to the askGemini Cloud Function. Captures
     * current ViewModel state (image MP, target dimensions, paper size) as
     * context so Gemini's reply is specific to THIS poster, not generic
     * advice.
     */
    fun askGemini(prompt: String, currentCreditBalance: Int) {
        geminiQaState = com.posterpdf.ui.components.GeminiQaState.Loading
        viewModelScope.launch {
            val settings = buildMap<String, Any?> {
                sourcePixelDimensions?.let { (w, h) ->
                    put("selectedImageMp", (w.toLong() * h.toLong()) / 1_000_000.0)
                }
                posterWidth.toDoubleOrNull()?.let { put("targetWidthInches", it) }
                posterHeight.toDoubleOrNull()?.let { put("targetHeightInches", it) }
                put("paperSize", paperSize)
            }
            val result = geminiQaRepo.askGemini(
                prompt = prompt,
                imageGsUri = null,  // Image not uploaded to GCS by default; text-only context.
                currentSettings = settings,
            )
            geminiQaState = when {
                result.isFailure -> com.posterpdf.ui.components.GeminiQaState.Error(
                    appContext.getString(
                        com.posterpdf.R.string.gemini_qa_error,
                        result.exceptionOrNull()?.message ?: "",
                    ),
                )
                else -> {
                    val resp = result.getOrThrow()
                    val toolCall = resp.toolCall
                    if (toolCall == null) {
                        // No tool: show Gemini's reply (or the action-taken
                        // string), with the turn-1 remaining count.
                        com.posterpdf.ui.components.GeminiQaState.Reply(
                            text = resp.text.ifBlank {
                                appContext.getString(com.posterpdf.R.string.gemini_qa_action_taken)
                            },
                            remainingQueries = resp.remainingQueries,
                        )
                    } else {
                        // RC69: tool round-trip. Compute the tool result
                        // locally, then call askGemini again so Gemini
                        // composes a natural sentence. The continuation
                        // returns remainingQueries = -1 (sentinel); we keep
                        // the turn-1 count and never surface -1.
                        val firstTurnRemaining = resp.remainingQueries
                        val route = runCatching { routeToolCall(toolCall, currentCreditBalance) }
                            .getOrNull()
                        if (route == null) {
                            com.posterpdf.ui.components.GeminiQaState.Error(
                                appContext.getString(
                                    com.posterpdf.R.string.gemini_qa_error,
                                    "tool call failed",
                                ),
                            )
                        } else {
                            val cont = geminiQaRepo.askGemini(
                                prompt = prompt,
                                imageGsUri = null,
                                currentSettings = settings,
                                toolResult = mapOf(
                                    "name" to toolCall.name,
                                    "args" to toolCall.args,
                                    "response" to route.responseForGemini,
                                    "originalPrompt" to prompt,
                                ),
                            )
                            val replyText = cont.fold(
                                onSuccess = { c -> c.text.ifBlank { route.fallbackText } },
                                onFailure = { route.fallbackText },
                            )
                            com.posterpdf.ui.components.GeminiQaState.Reply(
                                text = replyText,
                                remainingQueries = firstTurnRemaining,
                            )
                        }
                    }
                }
            }
        }
    }

    /** RC69: result of dispatching a Gemini tool call locally. [responseForGemini]
     *  is the function-response payload sent back to Gemini for a natural-language
     *  reply; [fallbackText] is the local explanation shown if the continuation
     *  call fails. */
    private data class ToolRouteResult(
        val responseForGemini: Map<String, Any?>,
        val fallbackText: String,
    )

    private fun routeToolCall(
        toolCall: com.posterpdf.data.backend.ToolCall,
        currentCreditBalance: Int,
    ): ToolRouteResult = when (toolCall.name) {
        "quoteUpscaleCost" -> {
            val args = toolCall.args
            val modelStr = (args["upscaleModel"] as? String)?.lowercase()
                ?: error("missing upscaleModel arg")
            val model = com.posterpdf.ui.components.UpscaleModel.values()
                .firstOrNull { it.name.equals(modelStr, ignoreCase = true) }
                ?: error("unknown upscaleModel: $modelStr")
            val widthIn = (args["targetWidthInches"] as? Number)?.toDouble()
                ?: error("missing targetWidthInches")
            val heightIn = (args["targetHeightInches"] as? Number)?.toDouble()
                ?: error("missing targetHeightInches")
            // Input MP from current source image; fall back to a 1MP estimate
            // if no image is selected (Gemini may ask cost questions before
            // image pick).
            val dims = sourcePixelDimensions
            val inputMp = if (dims != null) (dims.first * dims.second) / 1_000_000.0
                          else 1.0
            val quote = agentFunctions.quoteUpscaleCost(
                upscaleModel = model,
                targetWidthInches = widthIn,
                targetHeightInches = heightIn,
                inputMp = inputMp,
                currentCreditBalance = currentCreditBalance,
                targetDpi = 150,
            )
            ToolRouteResult(
                responseForGemini = mapOf(
                    "costCredits" to quote.estimatedCredits,
                    "currentBalance" to currentCreditBalance,
                    "balanceAfter" to (currentCreditBalance - quote.estimatedCredits),
                    "balanceSufficient" to quote.canAfford,
                    "model" to model.name,
                ),
                fallbackText = quote.explanation,
            )
        }
        else -> ToolRouteResult(emptyMap(), "Unknown tool: ${toolCall.name}")
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
        // RC48: client-side mirror of backend ADMIN_EMAILS (upscale.ts).
        // Drives the ∞ symbol on the credit badge purely as UI feedback;
        // the authoritative credit-bypass check still runs server-side.
        // Keep in sync with backend/functions/src/upscale.ts.
        val ADMIN_EMAILS = setOf(
            "joeputin100@gmail.com",
            "mojo.xanadu.2@gmail.com",
        )

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
