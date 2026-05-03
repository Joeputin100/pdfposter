package com.posterpdf.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.posterpdf.R
import com.posterpdf.ui.util.Hapt
import kotlinx.coroutines.launch

/**
 * Paper-size descriptor for the infographic card. The [label] is the value
 * stored in MainViewModel.paperSize (matches one of the strings in
 * PaperSizeSelector's options list); [drawableRes] is the to-scale rectangle
 * for that size; [shortName] is the chip-style display name.
 */
data class PaperOption(
    val label: String,
    val shortName: String,
    val dimensions: String,
    val drawableRes: Int,
    val isRecommended: Boolean = false,
)

val BUILT_IN_PAPER_OPTIONS = listOf(
    PaperOption(
        label = "Letter (8.5x11)",
        shortName = "Letter",
        dimensions = "8.5 × 11 in",
        drawableRes = R.drawable.paper_letter,
        isRecommended = true,
    ),
    PaperOption(
        label = "A4 (8.27x11.69)",
        shortName = "A4",
        dimensions = "210 × 297 mm",
        drawableRes = R.drawable.paper_a4,
    ),
    PaperOption(
        label = "Legal (8.5x14)",
        shortName = "Legal",
        dimensions = "8.5 × 14 in",
        drawableRes = R.drawable.paper_legal,
    ),
    PaperOption(
        label = "Tabloid (11x17)",
        shortName = "Tabloid",
        dimensions = "11 × 17 in",
        drawableRes = R.drawable.paper_tabloid,
    ),
)

/**
 * Single paper-size selector card: the paper drawable above its short name.
 * Selected state inflates the border + tints the surface.
 *
 * For Letter (or any [PaperOption] with [tooltipText] set) a small star sits
 * at the top-right corner; long-pressing it shows the tooltip. The star is
 * also a passive cue ("hey, this is the recommended pick").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperSizeCard(
    option: PaperOption,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hapt = Hapt(LocalHapticFeedback.current)
    val borderColor by animateColorAsState(
        if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant,
        label = "paperCardBorder"
    )
    val borderWidth by animateDpAsState(
        if (isSelected) 2.5.dp else 1.dp,
        label = "paperCardBorderWidth"
    )
    val containerColor = if (isSelected)
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    else
        MaterialTheme.colorScheme.surface

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable {
                if (!isSelected) hapt.tap()
                onClick()
            }
            .border(borderWidth, borderColor, RoundedCornerShape(20.dp)),
        color = containerColor,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Image(
                painter = painterResource(id = option.drawableRes),
                contentDescription = option.shortName,
                modifier = Modifier.size(64.dp),
            )
            Text(
                option.shortName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                option.dimensions,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (option.isRecommended) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = Color(0xFFFFC107),
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.size(2.dp))
                    Text(
                        "Recommended",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFB58900),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * The horizontal row of paper size cards (Letter / A4 / Legal / Tabloid) plus
 * a "Custom" trailing chip-style card. Selection state is driven by
 * [selectedLabel] and [onSelect]. Mirrors the shape of the original
 * PaperSizeSelector so the ViewModel plumbing is unchanged.
 */
@Composable
fun PaperSizeCardRow(
    selectedLabel: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Switched from `Row { weight(1f) }` (which squeezes Tabloid + Custom on
    // narrow phones) to LazyRow with fixed-width cards. Users scroll if
    // 5 cards don't fit in the viewport.
    androidx.compose.foundation.lazy.LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
    ) {
        items(BUILT_IN_PAPER_OPTIONS, key = { it.label }) { option ->
            PaperSizeCard(
                option = option,
                isSelected = selectedLabel == option.label,
                onClick = { onSelect(option.label) },
                modifier = Modifier.width(96.dp),
            )
        }
        item(key = "custom") {
            CustomPaperCard(
                isSelected = selectedLabel == "Custom",
                onClick = { onSelect("Custom") },
                modifier = Modifier.width(96.dp),
            )
        }
    }
}

@Composable
private fun CustomPaperCard(
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hapt = Hapt(LocalHapticFeedback.current)
    val borderColor by animateColorAsState(
        if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant,
        label = "customCardBorder"
    )
    val borderWidth by animateDpAsState(
        if (isSelected) 2.5.dp else 1.dp,
        label = "customCardBorderWidth"
    )
    val containerColor = if (isSelected)
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    else
        MaterialTheme.colorScheme.surface

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable {
                if (!isSelected) hapt.tap()
                onClick()
            }
            .border(borderWidth, borderColor, RoundedCornerShape(20.dp)),
        color = containerColor,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp)
                .height(102.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(8.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "?",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.outline,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Custom",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
