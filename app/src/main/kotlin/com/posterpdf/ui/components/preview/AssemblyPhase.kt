package com.posterpdf.ui.components.preview

/**
 * 5-phase looping animation cycle for the construction preview.
 * Total cycle: 12 seconds.
 *
 *  Print     0.0 - 3.0 s  Pages slide in from below (stagger 200 ms),
 *                         margin visible, no overlap highlight yet.
 *  Trim      3.0 - 6.0 s  Travelling-stitch cut line animates around each
 *                         page's printable rect; margin tints toward gray.
 *  Assemble  6.0 - 9.0 s  Margins fade out completely; panes spring inward
 *                         to their assembled positions with overshoot.
 *                         Scotch tape strips appear at the seams.
 *  Reveal    9.0 - 11.0 s Assembled poster sits on the workbench. Thumb
 *                         tacks drop in at the corners with a tiny bounce.
 *  Reset     11.0 - 12.0 s Tacks/tape fade; panes spring back outward to
 *                         the Print state.
 */
sealed class AssemblyPhase(val tStart: Float, val tEnd: Float) {
    data object Print    : AssemblyPhase(0f,  3f)
    data object Trim     : AssemblyPhase(3f,  6f)
    data object Assemble : AssemblyPhase(6f,  9f)
    data object Reveal   : AssemblyPhase(9f, 11f)
    data object Reset    : AssemblyPhase(11f, 12f)

    /** Local 0..1 progress within this phase. */
    fun localProgress(cycleSeconds: Float): Float =
        ((cycleSeconds - tStart) / (tEnd - tStart)).coerceIn(0f, 1f)

    companion object {
        const val CYCLE_SECONDS = 12f

        fun phaseAt(cycleSeconds: Float): AssemblyPhase = when {
            cycleSeconds < Print.tEnd    -> Print
            cycleSeconds < Trim.tEnd     -> Trim
            cycleSeconds < Assemble.tEnd -> Assemble
            cycleSeconds < Reveal.tEnd   -> Reveal
            else                         -> Reset
        }
    }
}
