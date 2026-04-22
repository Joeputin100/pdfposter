package com.pdfposter.ui.components

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

import androidx.compose.material3.MaterialTheme

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier) {
        // Background layer with blur
        Box(
            modifier = Modifier
                .matchParentSize()
                .then(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Modifier.blur(16.dp)
                    } else {
                        Modifier
                    }
                )
                .glassmorphism(
                    shape = RoundedCornerShape(24.dp),
                    backgroundColor = backgroundColor
                )
        )
        
        // Foreground content (unblurred)
        Box(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}
