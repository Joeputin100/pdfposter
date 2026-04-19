package com.pdfposter

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pdfposter.data.SettingsRepository
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ImageMetadata(
    val width: Int,
    val height: Int,
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
    var margin by mutableStateOf("0.5")
    var overlap by mutableStateOf("0.25")
    
    // Advanced options
    var showOutlines by mutableStateOf(true)
    var outlineStyle by mutableStateOf("Solid") // Solid, Dotted, Dashed
    var outlineThickness by mutableStateOf("Medium") // Thin, Medium, Heavy
    var labelPanes by mutableStateOf(true)
    var includeInstructions by mutableStateOf(true)
    
    var isAspectRatioLocked by mutableStateOf(true)
    var imageMetadata by mutableStateOf<ImageMetadata?>(null)
    
    var isFirstRun by mutableStateOf(false)
    var units by mutableStateOf("Inches")
    var lastGeneratedFile by mutableStateOf<File?>(null)

    private val posterLogic = PosterLogic()
    private val repository = SettingsRepository(application)

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            repository.settingsFlow.collectLatest { settings ->
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
            }
        }
    }

    fun saveAllSettings() {
        viewModelScope.launch {
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
            isFirstRun = false
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            repository.resetSettings()
            // Values will be updated via loadSettings collectLatest
        }
    }

    fun updateImage(context: Context, uri: Uri) {
        selectedImageUri = uri
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, options)
                val w = options.outWidth
                val h = options.outHeight
                if (w > 0 && h > 0) {
                    val ar = w.toDouble() / h.toDouble()
                    imageMetadata = ImageMetadata(
                        width = w,
                        height = h,
                        aspectRatio = ar,
                        resolution = "${w}x${h}px"
                    )
                    
                    // Initial sizing based on current width and image aspect ratio
                    if (isAspectRatioLocked) {
                        val currentW = posterWidth.toDoubleOrNull() ?: 24.0
                        posterHeight = String.format("%.2f", currentW / ar)
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
            posterHeight = String.format("%.2f", w / metadata.aspectRatio)
        }
    }

    fun updatePosterHeight(height: String) {
        posterHeight = height
        val h = height.toDoubleOrNull()
        val metadata = imageMetadata
        if (isAspectRatioLocked && h != null && metadata != null) {
            posterWidth = String.format("%.2f", h * metadata.aspectRatio)
        }
    }

    fun getDpiWarning(): String? {
        val metadata = imageMetadata ?: return null
        val w = posterWidth.toDoubleOrNull() ?: return null
        val h = posterHeight.toDoubleOrNull() ?: return null
        
        val dpiW = metadata.width / w
        val dpiH = metadata.height / h
        val minDpi = kotlin.math.min(dpiW, dpiH)
        
        return if (minDpi < 150) {
            "Low DPI Warning: Your poster will be printed at approximately ${minDpi.toInt()} DPI. For best results, use a higher resolution image or a smaller poster size."
        } else null
    }

    fun getPaneCount(): Triple<Int, Int, Int>? {
        val pw = posterWidth.toDoubleOrNull() ?: return null
        val ph = posterHeight.toDoubleOrNull() ?: return null
        val m = margin.toDoubleOrNull() ?: 0.0
        val o = overlap.toDoubleOrNull() ?: 0.0
        
        // Parse paper size
        val paperW: Double
        val paperH: Double
        if (paperSize == "Custom") {
            paperW = customPaperWidth.toDoubleOrNull() ?: 8.5
            paperH = customPaperHeight.toDoubleOrNull() ?: 11.0
        } else {
            val parts = paperSize.replace(")", "").split("(").last().split("x", "X")
            if (parts.size < 2) return null
            paperW = parts[0].trim().toDouble()
            paperH = parts[1].trim().toDouble()
        }
        
        val printableW = paperW - 2 * m
        val printableH = paperH - 2 * m
        
        return posterLogic.calculateSheetCount(pw * 72.0, ph * 72.0, printableW * 72.0, printableH * 72.0, o * 72.0)
    }

    fun generatePoster(context: Context) {
        val uri = selectedImageUri ?: return
        viewModelScope.launch {
            isGenerating = true
            errorMessage = null
            successMessage = null

            try {
                withContext(Dispatchers.IO) {
                    PDFBoxResourceLoader.init(context)

                    val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                        BitmapFactory.decodeStream(input)
                    } ?: throw Exception("Could not load image")

                    val pw = posterWidth.toDoubleOrNull() ?: 24.0
                    val ph = posterHeight.toDoubleOrNull() ?: 36.0
                    val m = margin.toDoubleOrNull() ?: 0.5
                    val o = overlap.toDoubleOrNull() ?: 0.25

                    val paperW: Double
                    val paperH: Double
                    if (paperSize == "Custom") {
                        paperW = customPaperWidth.toDoubleOrNull() ?: 8.5
                        paperH = customPaperHeight.toDoubleOrNull() ?: 11.0
                    } else {
                        val parts = paperSize.replace(")", "").split("(").last().split("x", "X")
                        if (parts.size < 2) throw Exception("Invalid paper size")
                        paperW = parts[0].trim().toDouble()
                        paperH = parts[1].trim().toDouble()
                    }

                    val outputDir = context.getExternalFilesDir(null) ?: context.filesDir
                    val outputFile = File(outputDir, "poster_${System.currentTimeMillis()}.pdf")

                    posterLogic.createTiledPoster(
                        bitmap = bitmap,
                        posterW = pw * 72.0,
                        ph * 72.0,
                        pageW = paperW * 72.0,
                        pageH = paperH * 72.0,
                        margin = m * 72.0,
                        overlap = o * 72.0,
                        outputPath = outputFile.absolutePath,
                        showOutlines = showOutlines,
                        outlineStyle = outlineStyle,
                        outlineThickness = outlineThickness,
                        labelPanes = labelPanes,
                        includeInstructions = includeInstructions
                    )
                    
                    lastGeneratedFile = outputFile
                    successMessage = "Poster generated: ${outputFile.name}"
                }
            } catch (e: Exception) {
                errorMessage = "Failed: ${e.message}"
            } finally {
                isGenerating = false
            }
        }
    }
}
