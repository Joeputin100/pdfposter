package com.posterpdf

import android.content.Intent
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
    // RC77: dense screens wired to the `--es screenshot <state>` hook. Dropped
    // low_dpi_modal (identical to model_picker's modal).
    // rc80: added `gemini` — the Q&A sheet now has a launch flag
    // (seedGeminiSheetForScreenshot populates a sample Reply).
    private val states = listOf(
        "main", "compare", "model_picker", "settings", "getting_started", "gemini",
    )

    @Test
    fun captureDenseScreens() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // RC77.1: write to the app-specific external files dir. The /sdcard
        // root is NOT writable under scoped storage on API 30+ — on arcfox
        // (API 34) that produced 0-byte screenshots ("empty screenshot for
        // state=main"). This dir needs no permission and is pulled by FTL via
        // --directories-to-pull=/sdcard/Android/data/com.posterpdf/files/ux-shots.
        val outDir = File(ctx.getExternalFilesDir(null), "ux-shots").apply { mkdirs() }
        android.util.Log.i("UX_SHOTS", "writing screenshots to ${outDir.absolutePath}")
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
