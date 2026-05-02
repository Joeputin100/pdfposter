package com.posterpdf.ui.util

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * Wraps Compose's HapticFeedback so call sites can write
 * `hapt.tap()`, `hapt.confirm()` etc. instead of the noisy
 * `LocalHapticFeedback.current.performHapticFeedback(...)`.
 */
class Hapt(private val raw: HapticFeedback) {
    fun tap() = raw.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    fun longPress() = raw.performHapticFeedback(HapticFeedbackType.LongPress)
    // For Reveal moment / "the tape stuck" / "generation complete" beats:
    fun confirm() = raw.performHapticFeedback(HapticFeedbackType.LongPress)
}
