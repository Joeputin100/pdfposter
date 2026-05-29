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
 * RC73 — Firebase Test Lab functional/timing test for the on-device
 * upscale → PDF pipeline.
 *
 * Rather than fragile multi-step UiAutomator tapping, this launches the
 * test-only intent hook (`--es screenshot upscale_test`). MainActivity hands
 * that off to [MainViewModel.runUpscaleAndPdfDeviceTest], which seeds a small
 * low-DPI poster, runs the on-device ESRGAN free upscale, generates the PDF,
 * and emits an `UPSCALE_TEST_DONE upscaleMs=… totalMs=… pdf=…` marker BOTH as
 * a logcat line (`Log.i("UPSCALE_TEST", …)`) and as an on-screen Text.
 *
 * RC75b: we poll LOGCAT for the marker as the primary, occlusion-proof signal
 * (the on-screen Text lived behind a fillMaxSize scroll container, so
 * By.textContains alone never saw it once the pipeline actually completed),
 * and also check the on-screen marker each iteration as a fallback. Whichever
 * surfaces first wins. The per-device upscale + total durations are read off
 * the marker text, giving a low-end vs high-end comparison across FTL models.
 */
@RunWith(AndroidJUnit4::class)
class UpscalePdfDeviceTest {

    @Test
    fun upscaleTo18inThenGeneratePdf() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // Start from a clean logcat so we never match a stale marker from a
        // prior install/run on the same (reused) device image.
        runCatching { device.executeShellCommand("logcat -c") }

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = ctx.packageManager.getLaunchIntentForPackage("com.posterpdf")!!
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("screenshot", "upscale_test")
        ctx.startActivity(intent)

        // The on-device upscale (small tile job at ~18" / low DPI) + PDF emit
        // should finish well under 8 minutes even on a low-end device.
        val deadlineMs = System.currentTimeMillis() + 480_000L
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
            "Neither UPSCALE_TEST_DONE nor UPSCALE_TEST_FAILED appeared within 8 min — " +
                "pipeline hung or crashed (a native crash kills the app before either marker)",
            marker,
        )
        assertFalse("Device test reported a failure: $marker", marker!!.contains("UPSCALE_TEST_FAILED"))
        assertTrue("Marker missing UPSCALE_TEST_DONE: $marker", marker.contains("UPSCALE_TEST_DONE"))
    }
}
