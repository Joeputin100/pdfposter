package com.posterpdf

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RC73/RC76 — Firebase Test Lab functional/timing test for the on-device
 * upscale → PDF pipeline.
 *
 * Rather than fragile multi-step UiAutomator tapping, this launches the
 * test-only intent hook (`--es screenshot upscale_test`). MainActivity hands
 * that off to [MainViewModel.runUpscaleAndPdfDeviceTest], which seeds a source
 * image, runs the on-device ESRGAN free upscale, generates the PDF, and emits
 * an `UPSCALE_TEST_DONE upscaleMs=… totalMs=… pdf=…` marker BOTH as a logcat
 * line (`Log.i("UPSCALE_TEST", …)`) and as an on-screen Text.
 *
 * RC75b: we poll LOGCAT for the marker as the primary, occlusion-proof signal
 * (the on-screen Text lived behind a fillMaxSize scroll container, so
 * By.textContains alone never saw it once the pipeline actually completed),
 * and also check the on-screen marker each iteration as a fallback. Whichever
 * surfaces first wins. The per-device upscale + total durations are read off
 * the marker text, giving a low-end vs high-end comparison across FTL models.
 *
 * RC76 — a single FTL fan-out now exercises three on-device scenarios via the
 * `--es upscale_mode …` extra, each a separate @Test sharing the [runMarker]
 * harness:
 *  - [upscaleTo18inThenGeneratePdf] (`small`) — the original tiny dogcow job.
 *  - [largeImage_streams_without_oom] (`large`) — a 1200×900 source (≈17 MP
 *    output spanning ≥2 streaming bands) that proves bounded memory; the
 *    controller also greps `peak_heap_mb=…` from logcat.
 *  - [forcedCpu_fallback_works] (`cpu`) — forces the validated CPU fallback so
 *    `tile engine path=CPU` is exercised even on GPU-capable hardware.
 */
@RunWith(AndroidJUnit4::class)
@FtlOnly  // real on-device ML upscale — FTL hardware only, excluded from the emulator battery
class UpscalePdfDeviceTest {

    @Test
    fun upscaleTo18inThenGeneratePdf() = runMarker("small")

    @Test
    fun largeImage_streams_without_oom() = runMarker("large")

    @Test
    fun forcedCpu_fallback_works() = runMarker("cpu")

    /**
     * Launches the device-test driver in [mode] (`small` | `large` | `cpu`) via
     * `--es screenshot upscale_test --es upscale_mode <mode>` and polls logcat
     * for the `UPSCALE_TEST_DONE` / `UPSCALE_TEST_FAILED` marker exactly as the
     * original single test did. The larger jobs (`large`/`cpu`) get a longer
     * poll deadline that stays safely under FTL's ~25-min infra timeout while
     * leaving room for the upscaler's own 15-min internal budget to either
     * finish or surface its `UPSCALE_TEST_FAILED`.
     */
    private fun runMarker(mode: String) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // Start from a clean logcat so we never match a stale marker from a
        // prior install/run on the same (reused) device image.
        runCatching { device.executeShellCommand("logcat -c") }

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = ctx.packageManager.getLaunchIntentForPackage("com.posterpdf")!!
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("screenshot", "upscale_test")
            .putExtra("upscale_mode", mode)
        ctx.startActivity(intent)

        // The small tile job at ~18" / low DPI + PDF emit finishes well under
        // 8 minutes even on a low-end device. The large/forced-CPU jobs (≈17 MP
        // output) can take longer on slow hardware, so allow ~16 min: the
        // upscaler's own 15-min internal withTimeout self-aborts a too-slow run
        // and the driver then emits UPSCALE_TEST_FAILED, so polling much past 15
        // min buys nothing. NOTE for the controller: all three @Test methods run
        // in ONE FTL invocation under a single --timeout (25m in
        // ftl-upscale-test.yml). 8 + 16 + 16 min of poll budget exceeds that, so
        // on slow devices the later modes may be cut off — raise --timeout or
        // split modes into separate --test-targets invocations when fanning out.
        val timeoutMs = if (mode == "small") 480_000L else 960_000L
        val deadlineMs = System.currentTimeMillis() + timeoutMs
        var marker: String? = null
        while (System.currentTimeMillis() < deadlineMs) {
            // Primary: scan logcat for the completion/failure marker. -d dumps
            // and exits; -s UPSCALE_TEST:I filters to our tag at Info+.
            marker = runCatching {
                device.executeShellCommand("logcat -d -s UPSCALE_TEST:I")
                    .lineSequence()
                    .lastOrNull {
                        it.contains("UPSCALE_TEST_DONE") || it.contains("UPSCALE_TEST_FAILED")
                    }
            }.getOrNull()
            if (marker != null) break

            // Fallback: the on-screen marker Text (now drawn above the scroll
            // container via zIndex), in case shell logcat is unavailable.
            val ui = device.findObject(By.textContains("UPSCALE_TEST_DONE"))
                ?: device.findObject(By.textContains("UPSCALE_TEST_FAILED"))
            if (ui != null) {
                marker = ui.text
                break
            }
            Thread.sleep(2_000L)
        }

        assertNotNull(
            "Neither UPSCALE_TEST_DONE nor UPSCALE_TEST_FAILED appeared in time (mode=$mode) — " +
                "pipeline hung or crashed (a native crash kills the app before either marker)",
            marker,
        )
        assertFalse("Device test reported a failure (mode=$mode): $marker", marker!!.contains("UPSCALE_TEST_FAILED"))
        assertTrue("Marker missing UPSCALE_TEST_DONE (mode=$mode): $marker", marker.contains("UPSCALE_TEST_DONE"))
    }
}
