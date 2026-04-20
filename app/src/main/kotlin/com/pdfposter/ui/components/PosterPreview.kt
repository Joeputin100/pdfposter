package com.pdfposter.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfposter.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PosterPreview(viewModel: MainViewModel) {
    val context = LocalContext.current
    val woodColor = Color(0xFF3E2723)
    val woodHighlight = Color(0xFF4E342E)
    
    // Animation state
    val infiniteTransition = rememberInfiniteTransition(label = "preview_loop")
    val t by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "time"
    )

    // Load bitmap for preview
    var previewBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(viewModel.selectedImageUri) {
        val uri = viewModel.selectedImageUri ?: return@LaunchedEffect
        val bitmap = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { 
                    BitmapFactory.decodeStream(it)
                }
            } catch (e: Exception) { null }
        }
        previewBitmap = bitmap?.asImageBitmap()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Live Assembly Preview", 
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(woodColor, woodHighlight, woodColor)
                    )
                )
                .shadow(8.dp),
            contentAlignment = Alignment.Center
        ) {
            val paneInfo = viewModel.getPaneCount()
            
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasW = size.width
                val canvasH = size.height
                
                // Draw Wood Grain
                for (i in 0..15) {
                    drawLine(
                        color = Color.Black.copy(alpha = 0.2f),
                        start = Offset(0f, i * canvasH / 15f),
                        end = Offset(canvasW, i * canvasH / 15f + 30f),
                        strokeWidth = 3f
                    )
                }

                if (paneInfo != null) {
                    val (_, rows, cols) = paneInfo
                    
                    val padding = 60f
                    val availableW = canvasW - 2 * padding
                    val availableH = canvasH - 2 * padding
                    
                    val pw = viewModel.posterWidth.toDoubleOrNull() ?: 1.0
                    val ph = viewModel.posterHeight.toDoubleOrNull() ?: 1.0
                    val scale = kotlin.math.min(availableW.toDouble() / pw, availableH.toDouble() / ph).toFloat()
                    
                    val posterDrawW = (pw * scale).toFloat()
                    val posterDrawH = (ph * scale).toFloat()
                    
                    val startX = (canvasW - posterDrawW) / 2
                    val startY = (canvasH - posterDrawH) / 2

                    if (t < 0.6) {
                        // Draw shadow
                        drawRoundRect(
                            color = Color.Black.copy(alpha = 0.3f),
                            topLeft = Offset(startX + 10f, startY + 10f),
                            size = Size(posterDrawW, posterDrawH),
                            cornerRadius = CornerRadius(4f, 4f)
                        )

                        // Draw Image
                        previewBitmap?.let {
                            drawImage(
                                image = it,
                                dstOffset = IntOffset(startX.toInt(), startY.toInt()),
                                dstSize = IntSize(posterDrawW.toInt(), posterDrawH.toInt())
                            )
                        } ?: drawRect(Color.White, Offset(startX, startY), Size(posterDrawW, posterDrawH))

                        // Borders (Phase 2)
                        if (t > 0.2) {
                            val alpha = kotlin.math.min(1f, (t - 0.2f) * 5f)
                            for (r in 0 until rows) {
                                for (c in 0 until cols) {
                                    val tw = posterDrawW / cols
                                    val th = posterDrawH / rows
                                    drawRect(
                                        color = Color.White.copy(alpha = alpha * 0.7f),
                                        topLeft = Offset(startX + c * tw, startY + r * th),
                                        size = Size(tw, th),
                                        style = Stroke(width = 2f)
                                    )
                                }
                            }
                        }

                        // Margins (Phase 3)
                        if (t > 0.4) {
                            val alpha = kotlin.math.min(0.5f, (t - 0.4f) * 5f)
                            val m = (viewModel.margin.toDoubleOrNull() ?: 0.0).toFloat() * scale
                            drawRect(
                                color = Color.Blue.copy(alpha = alpha),
                                topLeft = Offset(startX, startY),
                                size = Size(posterDrawW, m)
                            )
                            drawRect(
                                color = Color.Blue.copy(alpha = alpha),
                                topLeft = Offset(startX, startY + posterDrawH - m),
                                size = Size(posterDrawW, m)
                            )
                        }
                    } else {
                        // Separate Pages (Phase 4)
                        val sepProgress = (t - 0.6f) * 2.5f
                        val gap = sepProgress * 40f
                        
                        for (r in 0 until rows) {
                            for (c in 0 until cols) {
                                val tw = posterDrawW / cols
                                val th = posterDrawH / rows
                                
                                val dx = startX + c * tw + (c - (cols-1)/2f) * gap
                                val dy = startY + r * th + (r - (rows-1)/2f) * gap
                                
                                // Each tile shadow
                                drawRect(
                                    color = Color.Black.copy(alpha = 0.2f),
                                    topLeft = Offset(dx + 5f, dy + 5f),
                                    size = Size(tw, th)
                                )

                                // Tile content
                                drawRect(Color.White, Offset(dx, dy), Size(tw, th), style = Fill)
                                // Draw thin outline
                                drawRect(Color.Black.copy(alpha = 0.1f), Offset(dx, dy), Size(tw, th), style = Stroke(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}
