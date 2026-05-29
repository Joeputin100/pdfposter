package com.posterpdf

import android.content.Intent
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * RC77 — captures one screenshot per dense screen for odd-form-factor review
 * (run on a real flip via FTL: ftl-ux-screenshots.yml on `arcfox`). Saves to
 * /sdcard/ux-shots/, pulled by the workflow via --directories-to-pull. This is
 * NOT a pass/fail layout test (a human reviews the images); it asserts only
 * that each capture wrote a non-empty file, so a black/missing screen surfaces
 * as a failure rather than a silent no-op. Drives the same debug-only
 * `--es screenshot <state>` intent hook the emulator pipeline uses.
 */
@RunWith(AndroidJUnit4::class)
class UxScreenshotTest {
    private val states = listOf(
        "main", "compare", "model_picker", "settings",
        "low_dpi_modal", "gemini", "getting_started",
    )

    @Test
    fun captureDenseScreens() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        @Suppress("DEPRECATION")
        val outDir = File(Environment.getExternalStorageDirectory(), "ux-shots").apply { mkdirs() }
        for (state in states) {
            ctx.startActivity(
                ctx.packageManager.getLaunchIntentForPackage("com.posterpdf")!!
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra("screenshot", state),
            )
            device.waitForIdle()
            Thread.sleep(6_000L) // splash + first composition + drawer slide-in
            val f = File(outDir, "$state.png")
            device.takeScreenshot(f)
            check(f.length() > 0) { "empty screenshot for state=$state" }
        }
    }
}
