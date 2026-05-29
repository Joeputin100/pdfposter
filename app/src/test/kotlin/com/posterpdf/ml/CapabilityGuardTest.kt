package com.posterpdf.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the band-memory guard helpers. These exercise only the
 * Android-free math (band-tile count + working-set bytes) — the device-facing
 * [oneBandFits] reads ActivityManager and is covered by the FTL device test,
 * not here, since neither Android framework classes nor javax.imageio are on
 * the unit-test classpath.
 */
class CapabilityGuardTest {
    // bandWorkingSetBytes: peak working set for one band at the chosen band height.
    @Test fun small_image_fits() {
        assertTrue(bandWorkingSetBytes(outWidthPx = 1808, bandTilesY = 2) < 64L * 1024 * 1024)
    }

    @Test fun band_count_scales_down_for_wide_images() {
        // Wider output ⇒ fewer tile-rows per band to stay under budget.
        val narrow = bandTilesForBudget(outWidthPx = 800, budgetBytes = 32L * 1024 * 1024)
        val wide = bandTilesForBudget(outWidthPx = 20000, budgetBytes = 32L * 1024 * 1024)
        assertTrue(wide <= narrow)
        assertTrue(wide >= 1)
    }

    @Test fun min_one_tile_row() {
        assertEquals(1, bandTilesForBudget(outWidthPx = 10_000_000, budgetBytes = 1L))
    }
}
