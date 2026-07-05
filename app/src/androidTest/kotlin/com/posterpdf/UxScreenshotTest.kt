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

    /**
     * rc83: store-listing captures — the with_image page top plus a burst of
     * the Live Assembly Preview at the page bottom, feeding the product
     * video's animation segment. Runs on FTL because the GH-runner emulator
     * proved unable to survive this state reliably (6 crashed runs on
     * 2026-07-05). Ten full-screen swipes bottom the page out regardless of
     * the Advanced Styling card's expansion state.
     */
    @Test
    fun captureStoreAssemblyBurst() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val outDir = File(ctx.getExternalFilesDir(null), "ux-shots").apply { mkdirs() }
        // FTL zeroes the animation scales for instrumentation runs, which
        // freezes the assembly cycle's animator-driven camera while its
        // clock-driven captions keep advancing (run 28734179277: 24 frames,
        // 9 caption phases, one camera pose). Re-enable them — this burst
        // exists precisely to photograph motion.
        device.executeShellCommand("settings put global animator_duration_scale 1")
        device.executeShellCommand("settings put global transition_animation_scale 1")
        device.executeShellCommand("settings put global window_animation_scale 1")
        ctx.startActivity(
            ctx.packageManager.getLaunchIntentForPackage("com.posterpdf")!!
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("screenshot", "with_image"),
        )
        device.waitForIdle()
        Thread.sleep(8_000L) // splash + seeded-image decode + first preview frame
        val top = File(outDir, "store-with-image-top.png")
        device.takeScreenshot(top)
        check(top.length() > 0) { "empty with_image top screenshot" }
        // Scroll to the bottom, where the Live Assembly Preview lives.
        // NOT UiScrollable(scrollable(true)) — that matched the horizontal
        // paper-size carousel (first scrollable in the tree) and flung THAT
        // (FTL run 28733394914). Ten raw full-screen swipes bottom out the
        // vertical page deterministically; over-swiping is harmless.
        repeat(10) {
            device.swipe(
                device.displayWidth / 2, (device.displayHeight * 0.85).toInt(),
                device.displayWidth / 2, (device.displayHeight * 0.15).toInt(),
                40,
            )
            Thread.sleep(400L)
        }
        Thread.sleep(1_000L)
        // Sample past a full ~38s assembly cycle. 24 frames x 2s = 48s: the
        // 16-frame run (28733841132) spanned only 32s and missed the top-down
        // tile-grid phase entirely — a full cycle plus margin can't.
        for (i in 1..24) {
            val f = File(outDir, "store-assy-%02d.png".format(i))
            device.takeScreenshot(f)
            check(f.length() > 0) { "empty assembly burst frame $i" }
            Thread.sleep(2_000L)
        }
    }
}
