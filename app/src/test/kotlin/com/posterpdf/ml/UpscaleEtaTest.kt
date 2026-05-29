package com.posterpdf.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the cloud-upload speed-gate helpers added for the
 * "cloud connection & upload-speed gate" feature (2026-05-29). These are
 * dependency-free arithmetic — no Android, no coroutines — so they run on
 * the plain JUnit classpath the project already has.
 *
 * Covers: fast link (no warn), slow link (warn), the 60 s boundary, and the
 * `bytesPerSecond <= 0` / `inputBytes <= 0` guards.
 */
class UpscaleEtaTest {

    // --- uploadEtaSeconds --------------------------------------------------

    @Test fun `uploadEtaSeconds divides bytes by throughput`() {
        // 5 MB over 1 MB/s ⇒ 5 s.
        assertEquals(5.0, uploadEtaSeconds(5_000_000L, 1_000_000L)!!, 1e-6)
    }

    @Test fun `uploadEtaSeconds null when bytesPerSecond is zero`() {
        assertNull(uploadEtaSeconds(5_000_000L, 0L))
    }

    @Test fun `uploadEtaSeconds null when bytesPerSecond is negative`() {
        assertNull(uploadEtaSeconds(5_000_000L, -1L))
    }

    @Test fun `uploadEtaSeconds null when inputBytes is zero`() {
        assertNull(uploadEtaSeconds(0L, 1_000_000L))
    }

    @Test fun `uploadEtaSeconds null when inputBytes is negative`() {
        assertNull(uploadEtaSeconds(-10L, 1_000_000L))
    }

    // --- shouldWarnSlowUpload ----------------------------------------------

    @Test fun `fast link does not warn`() {
        // 10 MB over 5 MB/s ⇒ 2 s, well under the 60 s threshold.
        assertFalse(shouldWarnSlowUpload(10_000_000L, 5_000_000L))
    }

    @Test fun `slow link warns`() {
        // 10 MB over 50 KB/s ⇒ 200 s, far over threshold.
        assertTrue(shouldWarnSlowUpload(10_000_000L, 50_000L))
    }

    @Test fun `exactly at the 60s boundary does not warn`() {
        // 60 MB over 1 MB/s ⇒ exactly 60.0 s. The gate is strictly greater
        // than SLOW_UPLOAD_THRESHOLD_SEC, so 60 s is "fine".
        assertEquals(60.0, uploadEtaSeconds(60_000_000L, 1_000_000L)!!, 1e-6)
        assertFalse(shouldWarnSlowUpload(60_000_000L, 1_000_000L))
    }

    @Test fun `just over the 60s boundary warns`() {
        // 60 MB + 1 byte over 1 MB/s ⇒ 60.000001 s, strictly over threshold.
        assertTrue(shouldWarnSlowUpload(60_000_001L, 1_000_000L))
    }

    @Test fun `bytesPerSecond zero is treated as unknown and warns`() {
        // null ETA ⇒ Double.MAX_VALUE ⇒ > threshold ⇒ warn (conservative).
        assertTrue(shouldWarnSlowUpload(10_000_000L, 0L))
    }

    @Test fun `bytesPerSecond negative is treated as unknown and warns`() {
        assertTrue(shouldWarnSlowUpload(10_000_000L, -100L))
    }

    @Test fun `threshold constant is 60 seconds`() {
        assertEquals(60, SLOW_UPLOAD_THRESHOLD_SEC)
    }
}
