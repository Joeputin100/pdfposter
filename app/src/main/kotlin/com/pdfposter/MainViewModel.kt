package com.pdfposter

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
import com.pdfposter.data.SettingsRepository
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
    var showOutlines by mutableStateOf(true)
    var outlineStyle by mutableStateOf("Solid") // Solid, Dotted, Dashed
    var outlineThickness by mutableStateOf("Medium") // Thin, Medium, Heavy
    var labelPanes by mutableStateOf(true)
    var includeInstructions by mutableStateOf(true)
    
    // Debug & telemetry
    var debugLoggingEnabled by mutableStateOf(false)
    var postersMadeCount by mutableStateOf(0)
    var showNagwareModal by mutableStateOf(false)
    var nagwareCountdown by mutableStateOf(5) // seconds
    var pendingAction: (() -> Unit)? = null
    
    var isAspectRatioLocked by mutableStateOf(true)
    var imageMetadata by mutableStateOf<ImageMetadata?>(null)
    
    var isFirstRun by mutableStateOf(false)
    var units by mutableStateOf("Inches")
    var lastGeneratedFile by mutableStateOf<File?>(null)

    private val posterLogic = PosterLogic()
    private val repository = SettingsRepository(application)
    private val appContext = application.applicationContext

    private var ignoreFlowUpdates = false

    init {
        loadSettings()
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
                settings[SettingsRepository.SHOW_OUTLINES]?.let { showOutlines = it as Boolean }
                settings[SettingsRepository.OUTLINE_STYLE]?.let { outlineStyle = it as String }
                settings[SettingsRepository.OUTLINE_THICKNESS]?.let { outlineThickness = it as String }
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
                repository.saveSetting(SettingsRepository.SHOW_OUTLINES, showOutlines)
                repository.saveSetting(SettingsRepository.OUTLINE_STYLE, outlineStyle)
                repository.saveSetting(SettingsRepository.OUTLINE_THICKNESS, outlineThickness)
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
            showOutlines = true
            outlineStyle = "Solid"
            outlineThickness = "Medium"
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

    fun updateImage(context: Context, uri: Uri) {
        selectedImageUri = uri
        try {
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
                        posterHeight = String.format(Locale.US, "%.2f", currentW / ar)
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
            posterHeight = String.format(Locale.US, "%.2f", w / metadata.aspectRatio)
            logEvent(appContext, "Aspect ratio locked height update", "width=$width, height=$posterHeight, aspectRatio=${metadata.aspectRatio}")
        }
        logEvent(appContext, "Poster width changed", "width=$width, locked=$isAspectRatioLocked")
    }

    fun updatePosterHeight(height: String) {
        posterHeight = height
        val h = height.toDoubleOrNull()
        val metadata = imageMetadata
        if (isAspectRatioLocked && h != null && metadata != null) {
            posterWidth = String.format(Locale.US, "%.2f", h * metadata.aspectRatio)
            logEvent(appContext, "Aspect ratio locked width update", "height=$height, width=$posterWidth, aspectRatio=${metadata.aspectRatio}")
        }
        logEvent(appContext, "Poster height changed", "height=$height, locked=$isAspectRatioLocked")
    }

    fun getPaperDimensions(): Pair<Double, Double> {
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

        return when (orientation) {
            "Portrait" -> Pair(kotlin.math.min(paperW, paperH), kotlin.math.max(paperW, paperH))
            "Landscape" -> Pair(kotlin.math.max(paperW, paperH), kotlin.math.min(paperW, paperH))
            else -> {
                // Best Fit: Match aspect ratio of poster
                val pw = posterWidth.toDoubleOrNull() ?: 1.0
                val ph = posterHeight.toDoubleOrNull() ?: 1.0
                if (pw > ph) Pair(kotlin.math.max(paperW, paperH), kotlin.math.min(paperW, paperH))
                else Pair(kotlin.math.min(paperW, paperH), kotlin.math.max(paperW, paperH))
            }
        }
    }

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
            "Low Print Resolution: Your poster will print at ~${minDpi.toInt()} DPI. For sharp prints, use a higher resolution image or smaller poster size."
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
                        includeInstructions = includeInstructions
                    )
                    
                     withContext(Dispatchers.Main) {
                         lastGeneratedFile = outputFile
                         successMessage = "Poster generated: ${outputFile.name}"
                         logEvent(appContext, "Poster generation succeeded", "file=${outputFile.name}, count=$postersMadeCount")
                         postersMadeCount++
                         saveAllSettings()
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

    fun triggerNagwareModal(action: () -> Unit) {
        if (postersMadeCount >= 10) {
            pendingAction = action
            showNagwareModal = true
            nagwareCountdown = 5
            viewModelScope.launch {
                repeat(5) {
                    kotlinx.coroutines.delay(1000)
                    nagwareCountdown--
                }
                showNagwareModal = false
                pendingAction?.invoke()
                pendingAction = null
            }
        } else {
            action()
        }
    }

    fun dismissNagwareModal() {
        showNagwareModal = false
        pendingAction = null
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
