package com.posterpdf.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun Modifier.glassmorphism(
    shape: Shape,
    backgroundColor: Color = Color.White.copy(alpha = 0.2f),
    borderColor: Color = Color.White.copy(alpha = 0.3f)
) = this
    .background(
        brush = Brush.verticalGradient(
            colors = listOf(
                backgroundColor,
                backgroundColor.copy(alpha = 0.1f)
            )
        ),
        shape = shape
    )
    .border(
        width = 1.dp,
        brush = Brush.verticalGradient(
            colors = listOf(
                borderColor,
                Color.Transparent
            )
        ),
        shape = shape
    )
    .clip(shape)
