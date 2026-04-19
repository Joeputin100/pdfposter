package com.pdfposter

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainViewModel : ViewModel() {
    var isGenerating by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var successMessage by mutableStateOf<String?>(null)

    private val posterLogic = PosterLogic()

    fun generatePoster(
        context: Context,
        imageUri: Uri,
        posterWidth: Double,
        posterHeight: Double,
        paperSize: String, // e.g., "8.5x11"
        margin: Double,
        overlap: Double
    ) {
        viewModelScope.launch {
            isGenerating = true
            errorMessage = null
            successMessage = null

            try {
                withContext(Dispatchers.IO) {
                    // 1. Copy URI to temp file
                    val tempImage = File(context.cacheDir, "temp_poster_image")
                    context.contentResolver.openInputStream(imageUri)?.use { input ->
                        FileOutputStream(tempImage).use { output ->
                            input.copyTo(output)
                        }
                    }

                    // 2. Parse paper size
                    val parts = paperSize.split("x", "X")
                    val paperW = parts[0].toDouble() * 72.0
                    val paperH = parts[1].toDouble() * 72.0

                    // 3. Output path
                    val outputFile = File(context.getExternalFilesDir(null), "poster_${System.currentTimeMillis()}.pdf")

                    // 4. Generate
                    posterLogic.createTiledPoster(
                        imagePath = tempImage.absolutePath,
                        posterW = posterWidth * 72.0,
                        posterH = posterHeight * 72.0,
                        pageW = paperW,
                        pageH = paperH,
                        margin = margin * 72.0,
                        overlap = overlap * 72.0,
                        outputPath = outputFile.absolutePath
                    )
                    
                    successMessage = "Poster generated: ${outputFile.name}"
                }
            } catch (e: Exception) {
                errorMessage = "Failed to generate poster: ${e.message}"
            } finally {
                isGenerating = false
            }
        }
    }
}
