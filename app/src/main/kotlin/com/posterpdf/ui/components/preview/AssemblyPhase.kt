package com.posterpdf.ui.components.preview

/**
 * 8-phase looping construction-preview arc.
 *
 * RC7: slowed to 33% of the original speed (each phase 3× longer) per user
 * feedback that the original 14s cycle was "much too fast." Pinning now
 * holds an extra 5s on the fully-assembled state at the end of its window
 * — the final pinned poster sits visible for 5s before Reset begins, so
 * the user can study it. Total cycle: 47 seconds.
 *
 *  Printing    0.0  -  7.5 s  Dot-matrix printer body draws; pages slide out of
 *                             the paper-out slot with mechanical jitter, stagger
 *                             per pane. Margin visible, no overlap tint.
 *  Panning     7.5  - 12.0 s  Camera pans down the table — the viewport scrolls
 *                             vertically so the printer slides up/off-screen.
 *                             Wood-grain origin scrolls in sync.
 *  Arranging  12.0  - 18.0 s  Hand 👌 enters from off-frame, picks up panes one
 *                             at a time and arranges them in the poster grid
 *                             (with white borders still visible — pre-cut).
 *  Cutting    18.0  - 27.0 s  Scissors ✂️ trim the white BORDERS. Border alpha
 *                             → 0 progressively as scissors pass each edge,
 *                             and the leftover trailing-edge paper bands tear
 *                             and fall off (RC6 TearingBand).
 *  Tightening 27.0  - 31.5 s  Hand 👌 moves panes closer together, eliminating
 *                             the gaps where borders used to be.
 *  Taping     31.5  - 36.0 s  Hand applies tape strips on borders BETWEEN panes.
 *  Pinning    36.0  - 45.5 s  Hand pins thumb tacks to the 4 corners. The first
 *                             4.5s drives the tacks (3× the old 1.5s); the
 *                             remaining 5s holds the final assembled state so
 *                             the user can study the result before the cycle
 *                             restarts. phaseT clamps at 1.0 during the hold,
 *                             so all derived animations naturally freeze.
 *  Reset      45.5 - 47.0 s   Quick fade-out, cycle restarts.
 */
sealed class AssemblyPhase(val tStart: Float, val tEnd: Float) {
    data object Printing   : AssemblyPhase(0f,     7.5f)
    data object Panning    : AssemblyPhase(7.5f,  12f)
    data object Arranging  : AssemblyPhase(12f,   18f)
    data object Cutting    : AssemblyPhase(18f,   27f)
    data object Tightening : AssemblyPhase(27f,   31.5f)
    data object Taping     : AssemblyPhase(31.5f, 36f)
    data object Pinning    : AssemblyPhase(36f,   45.5f)
    data object Reset      : AssemblyPhase(45.5f, 47f)

    /**
     * Local 0..1 progress within this phase. RC7: Pinning is intentionally
     * 9.5s wide so the assembled poster sits visible for 5s at the end, but
     * the tack-landing animation should finish in the first 4.5s (3× the
     * pre-RC7 1.5s budget) — clamped to 1.0 for the remaining 5s hold so
     * every derived animation naturally freezes on the final state.
     *
     * RC58: when the poster is a single page, [singlePane]=true and
     * cycleSeconds runs over a 38-second cycle (Tightening + Taping skipped).
     * Effective time remaps so input ≥ Tightening.tStart (27f) lands in the
     * Pinning window directly. Without the remap, callers in single-pane
     * mode would index into Pinning's tStart=36f using a smaller cycle
     * value and read negative progress.
     */
    fun localProgress(cycleSeconds: Float, singlePane: Boolean = false): Float {
        val t = remapCycle(cycleSeconds, singlePane)
        if (this is Pinning) {
            return ((t - tStart) / 4.5f).coerceIn(0f, 1f)
        }
        return ((t - tStart) / (tEnd - tStart)).coerceIn(0f, 1f)
    }

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
        const val CYCLE_SECONDS = 47f

        // RC58: when the entire poster fits on a single page (rows=cols=1),
        // there are no gaps to close and no seams to tape. Tightening +
        // Taping (27.0..36.0 = 9.0s) become pure no-op visuals that just
        // freeze the cycle — so we elide them. The cycle ticker mods by
        // [CYCLE_SECONDS_SINGLE_PANE] (38s = 47-9), and effective time
        // values ≥ [SKIP_START] are remapped forward by [SKIP_DURATION]
        // before phase lookup so 27..38 lands inside Pinning..Reset.
        const val CYCLE_SECONDS_SINGLE_PANE = 38f
        private const val SKIP_START = 27f       // == Tightening.tStart
        private const val SKIP_DURATION = 9f     // == Tightening.+Taping. duration

        /** Effective cycle length — shorter when there are no seams. */
        fun cycleLength(singlePane: Boolean): Float =
            if (singlePane) CYCLE_SECONDS_SINGLE_PANE else CYCLE_SECONDS

        /** Internal: shift cycleSeconds past the skipped window when
         *  [singlePane] is true. Used by [phaseAt] and [localProgress]. */
        internal fun remapCycle(cycleSeconds: Float, singlePane: Boolean): Float =
            if (singlePane && cycleSeconds >= SKIP_START) cycleSeconds + SKIP_DURATION
            else cycleSeconds

        fun phaseAt(cycleSeconds: Float, singlePane: Boolean = false): AssemblyPhase {
            val t = remapCycle(cycleSeconds, singlePane)
            return when {
                t < Printing.tEnd   -> Printing
                t < Panning.tEnd    -> Panning
                t < Arranging.tEnd  -> Arranging
                t < Cutting.tEnd    -> Cutting
                t < Tightening.tEnd -> Tightening
                t < Taping.tEnd     -> Taping
                t < Pinning.tEnd    -> Pinning
                else                -> Reset
            }
        }
    }
}
