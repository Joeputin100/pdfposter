package com.posterpdf.data.backend

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for the cloud-upscale gate decision logic. The probe itself
 * (Firebase + ConnectivityManager) is verified on-device, not here — but the
 * branch it feeds is dependency-free and is the part that actually decides
 * whether the user is blocked / warned / let through, so it gets real tests
 * (mirrors the DelegateSelectionTest fake-injection pattern).
 */
class CloudUploadGateTest {

    @Test fun `offline blocks regardless of size`() {
        assertEquals(
            CloudGateDecision.Block,
            decideCloudGate(ConnectionStatus.Offline, inputBytes = 1L),
        )
    }

    @Test fun `probe failed warns (fail-safe, never blocks)`() {
        assertEquals(
            CloudGateDecision.Warn,
            decideCloudGate(ConnectionStatus.ProbeFailed, inputBytes = 5_000_000L),
        )
    }

    @Test fun `fast measured link proceeds silently`() {
        // 5 MB over 5 MB/s ⇒ 1 s ⇒ proceed.
        assertEquals(
            CloudGateDecision.Proceed,
            decideCloudGate(ConnectionStatus.Measured(5_000_000L), inputBytes = 5_000_000L),
        )
    }

    @Test fun `slow measured link warns`() {
        // 10 MB over 50 KB/s ⇒ 200 s ⇒ warn.
        assertEquals(
            CloudGateDecision.Warn,
            decideCloudGate(ConnectionStatus.Measured(50_000L), inputBytes = 10_000_000L),
        )
    }

    @Test fun `measured zero throughput is unknown and warns`() {
        assertEquals(
            CloudGateDecision.Warn,
            decideCloudGate(ConnectionStatus.Measured(0L), inputBytes = 10_000_000L),
        )
    }

    @Test fun `at the 60s boundary proceeds`() {
        // 60 MB over 1 MB/s ⇒ exactly 60 s ⇒ not strictly over threshold ⇒ proceed.
        assertEquals(
            CloudGateDecision.Proceed,
            decideCloudGate(ConnectionStatus.Measured(1_000_000L), inputBytes = 60_000_000L),
        )
    }
}
