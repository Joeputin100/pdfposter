package com.posterpdf

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RC21 — basic smoke tests that don't touch the network or FAL.
 *
 * These run on every API level in the test-battery matrix (23 / 28 / 33).
 * They verify the app boots without crashing and the launch activity reaches
 * a stable state. Anything beyond boot would require a real Firebase project
 * + photo picker + FAL key, all of which are intentionally out of scope here.
 *
 * Tests honoring the `excludeFAL` instrumentation arg are gated with
 * [assumeFalse] so the runner reports them as skipped (not failed) when CI
 * passes `-Pandroid.testInstrumentationRunnerArguments.excludeFAL=true`.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    @Test
    fun appPackage_matchesManifest() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        org.junit.Assert.assertEquals("com.posterpdf", ctx.packageName)
    }

    @Test
    fun mainActivity_launchesAndReachesResumed() {
        // Launches the activity, waits for it to reach the RESUMED state, then
        // closes cleanly. If the app crashes during onCreate / onResume (e.g.
        // a missing dependency, a NullPointerException in initialization), the
        // scenario builder throws and the test fails.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                org.junit.Assert.assertNotNull("Activity must not be null after launch", activity)
            }
        }
    }

    @Test
    fun aiUpscale_isFalGated() {
        // Marker test that documents the FAL-skip protocol. Real AI upscale
        // tests would assumeFalse(excludeFAL) and then exercise the full
        // upload → callable → poll → download pipeline. Until those land,
        // this test just verifies the gate works.
        val args = InstrumentationRegistry.getArguments()
        val excludeFal = args.getString("excludeFAL")?.toBoolean() == true
        assumeFalse("FAL paths skipped on CI", excludeFal)
        // If we got past the assume, FAL tests are allowed. No-op for now.
    }
}
