package com.posterpdf

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
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
 * DEBUG-only intent hook (`--es screenshot upscale_test`). MainActivity hands
 * that off to [MainViewModel.runUpscaleAndPdfDeviceTest], which seeds a small
 * low-DPI poster, runs the on-device ESRGAN free upscale, generates the PDF,
 * and surfaces an `UPSCALE_TEST_DONE upscaleMs=… totalMs=… pdf=…` marker as an
 * on-screen Text (and a matching logcat line via `logEvent("upscale_test", …)`).
 *
 * This test simply launches that flow and waits for the marker, asserting it
 * succeeded. The per-device upscale + total durations are read off the marker
 * text / device logcat, giving a low-end vs high-end comparison across FTL
 * device models.
 */
@RunWith(AndroidJUnit4::class)
class UpscalePdfDeviceTest {

    @Test
    fun upscaleTo18inThenGeneratePdf() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = ctx.packageManager.getLaunchIntentForPackage("com.posterpdf")!!
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("screenshot", "upscale_test")
        ctx.startActivity(intent)

        // The on-device upscale (small tile job at ~18" / low DPI) + PDF emit
        // should finish well under 8 minutes even on a low-end device.
        val appeared = device.wait(
            Until.hasObject(By.textContains("UPSCALE_TEST_DONE")),
            480_000L,
        )
        assertTrue(
            "UPSCALE_TEST_DONE marker never appeared within 8 min — pipeline hung or failed",
            appeared,
        )

        val marker = device.findObject(By.textContains("UPSCALE_TEST_DONE"))
        assertNotNull("UPSCALE_TEST_DONE marker object not found after wait", marker)
        val text = marker.text
        assertTrue("Marker text missing UPSCALE_TEST_DONE: $text", text.contains("UPSCALE_TEST_DONE"))
        assertFalse("Device test reported a failure: $text", text.contains("FAILED"))
    }
}
