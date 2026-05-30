package com.posterpdf.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.posterpdf.R

/**
 * RC65: top-bar sparkle button — Google's first-party convention for in-app
 * Gemini affordances (Photos, Keep, Maps, Gmail all use this glyph). Tap
 * fires [onTap] which the parent uses to open the Q&A modal sheet.
 *
 * Sized to match the existing IconButton-based top-bar widgets (48dp tap
 * target around a 24dp glyph). Tinted primary so the sparkle reads as
 * active/interactive, not a passive label.
 */
@Composable
fun GeminiSparkleButton(
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onTap,
        modifier = modifier,
    ) {
        Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = stringResource(R.string.top_bar_gemini_cd),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
    }
}
