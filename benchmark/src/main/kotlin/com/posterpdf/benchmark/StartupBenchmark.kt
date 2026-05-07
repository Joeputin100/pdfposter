package com.posterpdf.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RC41 — startup-time macrobenchmark.
 *
 * Three flavors per run:
 *   - COLD: app process killed + dropped from page cache between iterations.
 *           Measures the full Activity-creation + first-frame path. This
 *           is the number users feel when launching from a cold device.
 *   - WARM: app process killed but pages still cached. Measures the
 *           re-attach + first-frame path. Reflects "I closed it 5 minutes
 *           ago, opened it again."
 *   - HOT:  app process alive in background. Measures Activity-resume +
 *           first-frame. Reflects "I task-switched away and back."
 *
 * Each scenario runs 5 iterations to filter outliers. The CompilationMode
 * is `Partial` (default Play Store install: AOT-compile based on the
 * baseline profile if one exists, JIT otherwise). For now we don't have a
 * baseline profile so the numbers are JIT-only worst case — a future RC
 * can wire in `BaselineProfileGenerator` and re-run to see the speedup.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test fun startupCold() = startup(StartupMode.COLD)
    @Test fun startupWarm() = startup(StartupMode.WARM)
    @Test fun startupHot() = startup(StartupMode.HOT)

    private fun startup(mode: StartupMode) {
        benchmarkRule.measureRepeated(
            packageName = "com.posterpdf",
            metrics = listOf(StartupTimingMetric()),
            iterations = 5,
            startupMode = mode,
            compilationMode = CompilationMode.Partial(),
        ) {
            pressHome()
            startActivityAndWait()
        }
    }
}
