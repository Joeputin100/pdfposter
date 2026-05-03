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

    fun setStorageRetentionMode(mode: String) {
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

    /** Current effective DPI = sourceWidthPx / posterWidthInches.  0f when unknown. */
    fun computeCurrentDpi(): Float {
        val (w, _) = sourcePixelDimensions ?: return 0f
        val widthIn = posterWidth.toDoubleOrNull() ?: return 0f
        if (widthIn <= 0.0) return 0f
        return (w.toDouble() / widthIn).toFloat()
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

    var authSession by mutableStateOf(AuthSession())
        private set
    var historyItems by mutableStateOf<List<HistoryItem>>(emptyList())
        private set
    var isHistoryLoading by mutableStateOf(false)
        private set
    var showHistoryScreen by mutableStateOf(false)

    private var ignoreFlowUpdates = false

    init {
        loadSettings()
        viewModelScope.launch { auth.session.collectLatest { authSession = it } }
        viewModelScope.launch {
            auth.ensureSignedIn()
            refreshHistory()
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
        try {
            // Compute image content hash for per-image counter
            val imageBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            currentImageHash = imageBytes?.let { computeSha256(it) }

            context.contentResolver.openInputStream(uri)?.use { input ->
                val bitmap = if (uri.toString().lowercase(Locale.US).endsWith(".svg") || 
                    context.contentResolver.getType(uri)?.contains("svg") == true) {
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

                    val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                        if (uri.toString().lowercase(Locale.US).endsWith(".svg") || 
                            context.contentResolver.getType(uri)?.contains("svg") == true) {
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
                        sourcePixelH = imageMetadata?.height ?: bitmap.height
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

    fun logEvent(context: Context, event: String, details: String? = null) {
        if (!debugLoggingEnabled) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val logFile = File(downloadsDir, "pdfposter_debug.log")
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                val line = "$timestamp - $event${details?.let { ": $it" } ?: ""}\n"
                FileOutputStream(logFile, true).use { it.write(line.toByteArray()) }
            } catch (e: Exception) {
                // Ignore logging failures
            }
        }
    }
}
