package com.posterpdf.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RC41 — scroll-jank macrobenchmark.
 *
 * Measures FrameTimingMetric while the user scrolls the main poster builder
 * column up and down. That column is the densest screen in the app —
 * image picker + paper size cards + advanced styling expanders + DPI chips
 * + sharpen-for-print CTA + construction preview + nag banner — so it's
 * the most likely place to see frame drops.
 *
 * Frame-timing metrics:
 *   - frameDurationCpuMs    : P50 / P95 / P99 — the headline number
 *   - frameOverrunMs        : how much each frame missed its 16.67ms budget
 *
 * If P95 frameDurationCpuMs > 16ms, scroll is dropping frames on the
 * benchmark device. Worth investigating which composable is doing too
 * much work per recomposition.
 */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun mainScreenScroll() {
        benchmarkRule.measureRepeated(
            packageName = "com.posterpdf",
            metrics = listOf(FrameTimingMetric()),
            iterations = 5,
            startupMode = StartupMode.WARM,
            compilationMode = CompilationMode.Partial(),
            setupBlock = {
                pressHome()
                startActivityAndWait()
                // RC73: a fresh install (every FTL device is fresh) lands on
                // the first-run wizard, which has no scrollable builder
                // column — so By.scrollable(true) was null, the scroll was a
                // no-op, and FrameTimingMetric aborted with "0 found for
                // frameDurationCpuMs". Dismiss the wizard first (tap its
                // "Get Started" primary button) so the real main screen +
                // its vertical-scroll Column is present. No-op if the wizard
                // isn't showing (already past first run).
                device.wait(Until.hasObject(By.text("Get Started")), 5_000)
                device.findObject(By.text("Get Started"))?.click()
                // Then wait for the scrollable builder column to exist.
                device.wait(Until.hasObject(By.scrollable(true)), 5_000)
            },
        ) {
            // RC73: coordinate-based swipes instead of By.scrollable(true).fling().
            // Compose's vertical-scroll column is unreliable to locate via the
            // isScrollable AccessibilityNodeInfo flag headlessly — By.scrollable
            // returned null, the scroll no-oped, and FrameTimingMetric crashed
            // with "0 found for frameDurationCpuMs". Swiping the screen centre
            // drives whatever is scrollable underneath and always produces scroll
            // frames, so the metric gets real samples.
            val cx = device.displayWidth / 2
            val yTop = (device.displayHeight * 0.30).toInt()
            val yBot = (device.displayHeight * 0.70).toInt()
            repeat(3) {
                device.swipe(cx, yBot, cx, yTop, 12)  // scroll down
                device.waitForIdle()
                device.swipe(cx, yTop, cx, yBot, 12)  // scroll up
                device.waitForIdle()
            }
        }
    }
}
