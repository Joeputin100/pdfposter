package com.posterpdf.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Credit balance display: the FlippingCoin icon followed by an inline
 * digit cascade that animates with a per-digit split-flap flip and a
 * 2× scale pulse to draw attention on every change.
 *
 * RC35: wholesale rewrite for the OpenArt-style top bar. The pre-RC35
 * `CreditBadge` was a `BadgedBox` whose `Badge` clipped at ~3 chars and
 * showed admin (∞) and zero-balance affordances; that role moves to the
 * containing top-bar chip. This composable now just renders coin + digits
 * inline so the chip can place it next to an Upgrade button without the
 * MD3 Badge container width limit.
 */
@Composable
fun CreditBadgeInline(balance: Int, isAdmin: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FlippingCoin(sizeDp = 24.dp, contentDescription = "AI credits")
        if (isAdmin) {
            Text("∞", fontWeight = FontWeight.Bold)
        } else {
            CascadingDigits(value = balance)
        }
    }
}

/**
 * Pre-RC35 wrapper kept for backwards compatibility with call sites that
 * still place the badge inside an IconButton (the legacy top bar). The
 * RC35 top bar should use [CreditBadgeInline] directly.
 *
 * @param balance current credit total
 * @param isAdmin show ∞ instead of a number when true
 * @param onClick tap → typically opens the purchase sheet
 */
@Composable
fun CreditBadge(balance: Int, isAdmin: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        CreditBadgeInline(balance = balance, isAdmin = isAdmin)
    }
}

/**
 * Renders [value] as a row of digits where each digit cascades through
 * every intermediate value (0 → 1 → 2 → … → target) on its own X-axis
 * flip, with a left-to-right stagger so it reads like a split-flap
 * display refreshing rank-by-rank. The whole row pulses to 2× scale
 * during the cascade and snaps back to 1× when the slowest digit lands,
 * so even small changes (e.g. 99 → 100) draw the eye.
 *
 * Why per-digit-step instead of per-digit-target: a real split-flap
 * doesn't teleport from "9" to "0+carry-1" in one flap — it ticks
 * through 0→1→2…9→0. We mirror that so the motion has texture and
 * matches the "digital flip clock" the user described.
 */
@Composable
private fun CascadingDigits(value: Int) {
    var displayedValue by remember { mutableStateOf(value) }
    val scope = rememberCoroutineScope()
    val pulse = remember { Animatable(1f) }

    // When the bound value changes, kick off the pulse + cascade. Each
    // digit position handles its own per-step flip in [FlippingDigit];
    // we just ride the pulse (whole-number 1×→2×→1×) here and update
    // the displayedValue once the cascade finishes so digits don't lag
    // behind the source of truth.
    LaunchedEffect(value) {
        if (value == displayedValue) return@LaunchedEffect
        val from = displayedValue
        val to = value
        val maxSteps = countSteps(from, to)
        // Total cascade duration: 140ms per step + 40ms stagger per digit
        // position. Pulse fades up over the first 30% of the duration,
        // holds at 2×, then eases back to 1× over the last 30%.
        val totalMs = (maxSteps * 140 + 80).coerceAtLeast(280)
        scope.launch {
            pulse.animateTo(2f, tween(durationMillis = (totalMs * 0.3f).toInt(), easing = FastOutSlowInEasing))
            // hold at 2× while the slowest digit finishes flipping
            delay((totalMs * 0.4f).toLong())
            pulse.animateTo(1f, tween(durationMillis = (totalMs * 0.3f).toInt(), easing = FastOutSlowInEasing))
        }
        displayedValue = to
    }

    // Render padded so the digit count matches between `from` and `to`
    // (e.g. 99 → 100 needs 3 columns from the start). Width grows on
    // the next render after [displayedValue] increases past a power of
    // ten; that re-layout happens within the same pulse cycle.
    val digitCount = maxOf(value.toString().length, displayedValue.toString().length)
    val targetStr = value.toString().padStart(digitCount, '0')
    val fromStr = displayedValue.toString().padStart(digitCount, '0')

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.graphicsLayer {
            val s = pulse.value
            scaleX = s
            scaleY = s
            // Left-anchor the pulse so the digits don't drift over the coin
            // when scaling up.
            transformOrigin = TransformOrigin(0f, 0.5f)
        },
    ) {
        for (i in 0 until digitCount) {
            val targetDigit = targetStr[i].digitToInt()
            val fromDigit = fromStr[i].digitToInt()
            // Stagger: leftmost digit starts last so the highest place
            // ticks over after the lower places carry — matches a
            // mechanical odometer rolling forward.
            val staggerMs = (digitCount - 1 - i) * 40
            FlippingDigit(
                fromDigit = fromDigit,
                toDigit = targetDigit,
                stepDelayMs = staggerMs,
            )
        }
    }
}

/**
 * One digit column. When [toDigit] differs from [fromDigit], the digit
 * counts up step-by-step (with wrap 9→0) using a 90°-flip-snap-(-90°)
 * X-rotation per step; each step is ~140ms.
 *
 * @param stepDelayMs delay before the cascade starts — used by the
 *   parent to stagger digit positions.
 */
@Composable
private fun FlippingDigit(fromDigit: Int, toDigit: Int, stepDelayMs: Int) {
    var displayed by remember { mutableStateOf(fromDigit) }
    val rotation = remember { Animatable(0f) }
    val density = LocalDensity.current

    LaunchedEffect(toDigit, fromDigit) {
        if (toDigit == displayed && fromDigit == displayed) return@LaunchedEffect
        if (stepDelayMs > 0) delay(stepDelayMs.toLong())
        // Walk forward through every digit between current and target,
        // wrapping at 9 → 0. So 7→2 walks 7,8,9,0,1,2 (5 steps).
        var current = displayed
        while (current != toDigit) {
            val next = (current + 1) % 10
            rotation.animateTo(90f, tween(durationMillis = 70))
            displayed = next
            rotation.snapTo(-90f)
            rotation.animateTo(0f, tween(durationMillis = 70))
            current = next
        }
    }

    Text(
        text = displayed.toString(),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.graphicsLayer {
            rotationX = rotation.value
            // 12dp camera distance gives a card-like flip; closer warps
            // the digit at 90°, farther flattens to a scale animation.
            cameraDistance = 12f * density.density
        },
    )
}

/**
 * Step count from [from] to [to] under the cascade rule (each digit
 * walks forward modulo 10). Returns the longest of the per-digit walks,
 * which determines the total animation duration.
 */
private fun countSteps(from: Int, to: Int): Int {
    val len = maxOf(from.toString().length, to.toString().length)
    val fStr = from.toString().padStart(len, '0')
    val tStr = to.toString().padStart(len, '0')
    var maxSteps = 0
    for (i in 0 until len) {
        val f = fStr[i].digitToInt()
        val t = tStr[i].digitToInt()
        val steps = if (t >= f) t - f else (10 - f) + t
        if (steps > maxSteps) maxSteps = steps
    }
    return maxOf(maxSteps, 1)
}
