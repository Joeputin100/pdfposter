package com.posterpdf.ui.components.preview

/**
 * 6-phase looping construction-preview arc (H-P1.8).
 * Total cycle: 12 seconds. Narrative: dot-matrix printer outputs pages → stack
 * lands on desk → scissors slice the stack into panes → panes align → tape +
 * tacks secure them → cycle resets.
 *
 *  Printing  0.0 - 3.0 s   Dot-matrix printer body draws; pages slide out of
 *                          the paper-out slot with mechanical jitter, stagger
 *                          per pane (200 ms). Margin visible, no overlap tint.
 *  Stacking  3.0 - 4.5 s   Pages collapse into a single paper stack which
 *                          falls onto the desk with gravity easing and a
 *                          slight tumble. Optional AGSL dust puff on landing.
 *  Cutting   4.5 - 6.5 s   Scissors emoji sweeps across the stack along seam
 *                          lines; each pass reveals a cut, margin tints toward
 *                          the gray "this falls away" color.
 *  Aligning  6.5 - 9.0 s   Margins fade out completely; panes glide to their
 *                          assembled positions with smooth easing.
 *  Securing  9.0 - 10.5 s  Tape strips fade in across seams; thumb tacks drop
 *                          into the four corners (staggered, with a tiny
 *                          bounce). This is the H-P1.7 gating boundary —
 *                          tape/tacks NEVER appear before this phase.
 *  Reset     10.5 - 12.0 s Tacks/tape fade; panes spring back outward; the
 *                          printer body fades back in, ready for next loop.
 */
sealed class AssemblyPhase(val tStart: Float, val tEnd: Float) {
    data object Printing : AssemblyPhase(0f,    3f)
    data object Stacking : AssemblyPhase(3f,    4.5f)
    data object Cutting  : AssemblyPhase(4.5f,  6.5f)
    data object Aligning : AssemblyPhase(6.5f,  9f)
    data object Securing : AssemblyPhase(9f,    10.5f)
    data object Reset    : AssemblyPhase(10.5f, 12f)

    /** Local 0..1 progress within this phase. */
    fun localProgress(cycleSeconds: Float): Float =
        ((cycleSeconds - tStart) / (tEnd - tStart)).coerceIn(0f, 1f)

    /**
     * Phase ordering helper. Used to gate decorations like tape/tacks
     * (H-P1.7): they should only render once we've reached [Securing].
     */
    fun ordinal(): Int = when (this) {
        Printing -> 0
        Stacking -> 1
        Cutting  -> 2
        Aligning -> 3
        Securing -> 4
        Reset    -> 5
    }

    companion object {
        const val CYCLE_SECONDS = 12f

        fun phaseAt(cycleSeconds: Float): AssemblyPhase = when {
            cycleSeconds < Printing.tEnd -> Printing
            cycleSeconds < Stacking.tEnd -> Stacking
            cycleSeconds < Cutting.tEnd  -> Cutting
            cycleSeconds < Aligning.tEnd -> Aligning
            cycleSeconds < Securing.tEnd -> Securing
            else                         -> Reset
        }
    }
}
