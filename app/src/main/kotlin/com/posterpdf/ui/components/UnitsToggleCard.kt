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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.posterpdf.R
import com.posterpdf.ui.util.Hapt

/**
 * Two-card units toggle: "Inches" (with 12-inch ruler infographic) vs
 * "Centimeters" (with 30-cm ruler). Replaces the legacy RadioButton row.
 *
 * State plumbing: the parent owns a [String] that is either "Inches" or
 * "Metric" (kept for backwards compatibility with MainViewModel.units, which
 * accepts "Inches" / "Metric" as legal values; "Centimeters" displays as the
 * label but stores as "Metric" so existing settings, persistence, and the
 * MainViewModel.toggleUnits() call continue to work without migration).
 */
@Composable
fun UnitsToggleCard(
    selectedUnits: String, // "Inches" or "Metric"
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        UnitsCard(
            label = "Inches",
            drawableRes = R.drawable.ruler_inches,
            isSelected = selectedUnits == "Inches",
            onClick = { onSelect("Inches") },
            tickLabels = listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"),
            modifier = Modifier.weight(1f),
        )
        UnitsCard(
            label = "Centimeters",
            drawableRes = R.drawable.ruler_centimeters,
            // Backwards-compatible: stored as "Metric" inside the ViewModel.
            isSelected = selectedUnits == "Metric",
            onClick = { onSelect("Metric") },
            // 31 labels (0..30) is too dense at this size; show every 5cm so
            // the label row stays readable.
            tickLabels = listOf("0", "", "", "", "", "5", "", "", "", "", "10",
                                "", "", "", "", "15", "", "", "", "", "20",
                                "", "", "", "", "25", "", "", "", "", "30"),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun UnitsCard(
    label: String,
    drawableRes: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    tickLabels: List<String>,
    modifier: Modifier = Modifier,
) {
    val hapt = Hapt(LocalHapticFeedback.current)
    val borderColor by animateColorAsState(
        if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant,
        label = "unitsCardBorder"
    )
    val borderWidth by animateDpAsState(
        if (isSelected) 2.5.dp else 1.dp,
        label = "unitsCardBorderWidth"
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
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurface,
            )
            // Ruler graphic + numerical labels overlaid in Compose. The
            // drawable itself contains tick marks only (vector drawables
            // can't render text), and we overlay the numbers in a Row sized
            // to the same width as the ruler so each number sits above its
            // major tick.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Image(
                    painter = painterResource(id = drawableRes),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .padding(top = 12.dp),
                    contentScale = ContentScale.FillBounds,
                )
                // Numerical labels — one per major tick. Spread evenly with
                // SpaceBetween so they line up with the major-tick spacing
                // baked into the ruler drawable (margin 12 / inch 38 in a
                // 480 viewport => major ticks at 12, 50, 88, ..., 468 — i.e.
                // not flush with the card edges, but the small inset ratio
                // matches what a Row with horizontal padding produces).
                TickLabelRow(
                    labels = tickLabels,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 0.dp)
                )
            }
        }
    }
}

/**
 * Numerical tick labels above the ruler. Uses a manually-positioned row so
 * the labels align with the major ticks in the ruler drawable. Each ruler
 * drawable has margin 12 in a 480-unit viewport — i.e. the first tick is at
 * 2.5% from the left edge, last tick at 97.5%. We approximate that with
 * Arrangement.SpaceBetween + a tiny horizontal padding.
 */
@Composable
private fun TickLabelRow(
    labels: List<String>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        for (lbl in labels) {
            Text(
                text = lbl,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
