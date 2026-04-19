package com.pdfposter.ui.components

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
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfposter.MainViewModel

@Composable
fun PosterPreview(viewModel: MainViewModel) {
    val infiniteTransition = rememberInfiniteTransition(label = "wood_grain")
    val woodColor = Color(0xFF3E2723)
    val woodHighlight = Color(0xFF4E342E)
    
    // Animation state
    var animationPhase by remember { mutableStateOf(0) } // 0: Original, 1: Borders, 2: Melt, 3: Separate
    val transitionProgress = animateFloatAsState(
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "preview_anim"
    )

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
                .height(260.dp)
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
                
                // Draw Wood Grain (Simple lines)
                for (i in 0..10) {
                    drawLine(
                        color = Color.Black.copy(alpha = 0.1f),
                        start = Offset(0f, i * canvasH / 10),
                        end = Offset(canvasW, i * canvasH / 10 + 20f),
                        strokeWidth = 2f
                    )
                }

                if (paneInfo != null) {
                    val (_, rows, cols) = paneInfo
                    
                    // Scale poster to fit canvas (with padding)
                    val padding = 40f
                    val availableW = canvasW - 2 * padding
                    val availableH = canvasH - 2 * padding
                    
                    val pw = viewModel.posterWidth.toDoubleOrNull() ?: 1.0
                    val ph = viewModel.posterHeight.toDoubleOrNull() ?: 1.0
                    val scale = kotlin.math.min(availableW / pw, availableH / ph).toFloat()
                    
                    val posterDrawW = (pw * scale).toFloat()
                    val posterDrawH = (ph * scale).toFloat()
                    
                    val startX = (canvasW - posterDrawW) / 2
                    val startY = (canvasH - posterDrawH) / 2

                    // Draw Background Shadow for the whole poster
                    drawRoundRect(
                        color = Color.Black.copy(alpha = 0.3f),
                        topLeft = Offset(startX + 10f, startY + 10f),
                        size = Size(posterDrawW, posterDrawH),
                        cornerRadius = CornerRadius(4f, 4f)
                    )

                    // Draw the "Base" image (White sheet)
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(startX, startY),
                        size = Size(posterDrawW, posterDrawH)
                    )

                    // Animate Layers based on time
                    val t = transitionProgress.value
                    
                    // Phase: Drawing Borders (t: 0.0 -> 0.3)
                    if (t > 0.1) {
                        val borderAlpha = kotlin.math.min(1f, (t - 0.1f) * 5f)
                        for (r in 0 until rows) {
                            for (c in 0 until cols) {
                                val tileW = posterDrawW / cols
                                val tileH = posterDrawH / rows
                                
                                // Draw Grid Line
                                drawRect(
                                    color = Color.LightGray.copy(alpha = borderAlpha * 0.5f),
                                    topLeft = Offset(startX + c * tileW, startY + r * tileH),
                                    size = Size(tileW, tileH),
                                    style = Stroke(width = 1f)
                                )
                            }
                        }
                    }

                    // Phase: Melting Margins (t: 0.4 -> 0.7)
                    val m = (viewModel.margin.toDoubleOrNull() ?: 0.0) * scale
                    if (t > 0.4) {
                        val meltProgress = kotlin.math.min(1f, (t - 0.4f) * 3f)
                        val meltAlpha = meltProgress * 0.4f
                        
                        // Draw simulated margins on the edges with "melting" effect (growing slightly)
                        drawRect(
                            color = Color.Blue.copy(alpha = meltAlpha),
                            topLeft = Offset(startX, startY),
                            size = Size(posterDrawW, m.toFloat() * meltProgress)
                        )
                        drawRect(
                            color = Color.Blue.copy(alpha = meltAlpha),
                            topLeft = Offset(startX, (startY + posterDrawH - m * meltProgress).toFloat()),
                            size = Size(posterDrawW, m.toFloat() * meltProgress)
                        )
                    }

                    // Phase: Pages separating (t: 0.7 -> 1.0)
                    if (t > 0.7) {
                        val sepProgress = (t - 0.7f) * 3.33f
                        val sep = sepProgress * 20f
                        
                        for (r in 0 until rows) {
                            for (c in 0 until cols) {
                                val tileW = posterDrawW / cols
                                val tileH = posterDrawH / rows
                                
                                val tileStartX = startX + c * tileW + (c - (cols-1)/2f) * sep
                                val tileStartY = startY + r * tileH + (r - (rows-1)/2f) * sep
                                
                                drawRect(
                                    color = Color.White,
                                    topLeft = Offset(tileStartX, tileStartY),
                                    size = Size(tileW, tileH),
                                    style = Fill
                                )
                                drawRect(
                                    color = Color.Black.copy(alpha = 0.2f),
                                    topLeft = Offset(tileStartX, tileStartY),
                                    size = Size(tileW, tileH),
                                    style = Stroke(width = 1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
