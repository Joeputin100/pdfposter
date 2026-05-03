package com.posterpdf.ui.components.preview

/**
 * 8-phase looping construction-preview arc (RC3 redesign).
 * Total cycle: 14 seconds. Hand-driven narrative — a printer prints the pages,
 * the camera pans down the table away from the printer, then a hand 👌 arranges
 * the panes, scissors trim the white borders, the hand tightens the layout,
 * applies tape across seams, pins the four corners with thumb tacks, and the
 * cycle resets.
 *
 *  Printing    0.0 - 2.5 s   Dot-matrix printer body draws; pages slide out of
 *                            the paper-out slot with mechanical jitter, stagger
 *                            per pane (200 ms). Margin visible, no overlap tint.
 *  Panning     2.5 - 4.0 s   Camera pans down the table — the viewport scrolls
 *                            vertically so the printer slides up/off-screen.
 *                            Wood-grain origin scrolls in sync. End state:
 *                            printer is gone; just the stack on bare desk.
 *  Arranging   4.0 - 6.0 s   Hand 👌 enters from off-frame, picks up panes one
 *                            at a time and arranges them in the poster grid
 *                            (with white borders still visible — pre-cut).
 *                            Stagger the placement.
 *  Cutting     6.0 - 9.0 s   Scissors ✂️ trim the white BORDERS (not the panes
 *                            themselves). Border alpha → 0 progressively as
 *                            scissors pass over each border edge. ~600-800 ms
 *                            per cut so the user can read the action.
 *  Tightening  9.0 - 10.5 s  Hand 👌 moves panes closer together, eliminating
 *                            the gaps where borders used to be. Smooth ease.
 *  Taping      10.5 - 12.0 s Hand applies tape strips on borders BETWEEN panes.
 *                            One strip at a time, staggered.
 *  Pinning     12.0 - 13.5 s Hand pins thumb tacks to the 4 corners of the
 *                            assembled poster. One at a time with a bounce.
 *  Reset       13.5 - 14.0 s Brief hold then quick fade-out, cycle restarts.
 */
sealed class AssemblyPhase(val tStart: Float, val tEnd: Float) {
    data object Printing   : AssemblyPhase(0f,    2.5f)
    data object Panning    : AssemblyPhase(2.5f,  4f)
    data object Arranging  : AssemblyPhase(4f,    6f)
    data object Cutting    : AssemblyPhase(6f,    9f)
    data object Tightening : AssemblyPhase(9f,    10.5f)
    data object Taping     : AssemblyPhase(10.5f, 12f)
    data object Pinning    : AssemblyPhase(12f,   13.5f)
    data object Reset      : AssemblyPhase(13.5f, 14f)

    /** Local 0..1 progress within this phase. */
    fun localProgress(cycleSeconds: Float): Float =
        ((cycleSeconds - tStart) / (tEnd - tStart)).coerceIn(0f, 1f)

    /**
     * Phase ordering helper. Used to gate decorations like tape/tacks: they
     * should only render once we've reached the relevant assembly stage.
     */
    fun ordinal(): Int = when (this) {
        Printing   -> 0
        Panning    -> 1
        Arranging  -> 2
        Cutting    -> 3
        Tightening -> 4
        Taping     -> 5
        Pinning    -> 6
        Reset      -> 7
    }

    companion object {
        const val CYCLE_SECONDS = 14f

        fun phaseAt(cycleSeconds: Float): AssemblyPhase = when {
            cycleSeconds < Printing.tEnd   -> Printing
            cycleSeconds < Panning.tEnd    -> Panning
            cycleSeconds < Arranging.tEnd  -> Arranging
            cycleSeconds < Cutting.tEnd    -> Cutting
            cycleSeconds < Tightening.tEnd -> Tightening
            cycleSeconds < Taping.tEnd     -> Taping
            cycleSeconds < Pinning.tEnd    -> Pinning
            else                           -> Reset
        }
    }
}
