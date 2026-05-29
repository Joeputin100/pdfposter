package com.posterpdf.data.backend

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.firebase.storage.FirebaseStorage
import com.posterpdf.ml.shouldWarnSlowUpload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Cloud connection & upload-speed gate (2026-05-29).
 *
 * Measures the user's *real* upload throughput before a cloud upscale by
 * timing a tiny real upload to Firebase Storage, and reports connectivity.
 * The decision (block / warn / proceed) lives in [decideCloudGate] so it can
 * be unit-tested without Android or Firebase; the probe itself is verified
 * on-device.
 */

/** Result of a connectivity check + upload-speed probe. */
sealed interface ConnectionStatus {
    /** No validated internet connection. The gate blocks the cloud job. */
    object Offline : ConnectionStatus

    /** A real upload completed; [bytesPerSecondUp] is the measured throughput. */
    data class Measured(val bytesPerSecondUp: Long) : ConnectionStatus

    /** Connected, but the probe timed out / errored. The gate warns (fail-safe). */
    object ProbeFailed : ConnectionStatus
}

/** What the gate should do with a given [ConnectionStatus] + payload size. */
enum class CloudGateDecision { Block, Warn, Proceed }

/**
 * Pure decision: map a probe result + the source-image size to a gate action.
 *  - [ConnectionStatus.Offline]      → [CloudGateDecision.Block]
 *  - [ConnectionStatus.ProbeFailed]  → [CloudGateDecision.Warn]  (never block on a probe failure)
 *  - [ConnectionStatus.Measured]     → warn iff the upload would exceed the slow threshold,
 *                                      else proceed silently.
 */
fun decideCloudGate(status: ConnectionStatus, inputBytes: Long): CloudGateDecision = when (status) {
    is ConnectionStatus.Offline -> CloudGateDecision.Block
    is ConnectionStatus.ProbeFailed -> CloudGateDecision.Warn
    is ConnectionStatus.Measured ->
        if (shouldWarnSlowUpload(inputBytes, status.bytesPerSecondUp)) {
            CloudGateDecision.Warn
        } else {
            CloudGateDecision.Proceed
        }
}

/**
 * Injectable so the ViewModel can be exercised with a fake that returns each
 * [ConnectionStatus] without touching the network.
 */
interface UploadSpeedProbe {
    suspend fun probe(context: Context): ConnectionStatus
}

/**
 * Firebase-backed probe. Checks for a validated network, then uploads a
 * ~512 KB byte array to the same Storage prefix the AI upscale uses
 * (`users/{uid}/upscale-input/…`, permitted by the existing rules — see
 * [AiUpscaleRepository] line ~83/104), times it, deletes the probe object,
 * and returns the throughput. Wrapped in a ~10 s timeout; ANY failure maps to
 * [ConnectionStatus.ProbeFailed] so the upscale flow is never crashed. Keeps
 * the last [ConnectionStatus.Measured] in memory and reuses it within 45 s.
 */
class FirebaseUploadSpeedProbe(
    // Allow tests / callers to substitute the auth source; defaults to the
    // app-wide singleton like the rest of the backend layer.
    private val authProvider: (Context) -> AuthRepository = { AuthRepository.get(it) },
    private val now: () -> Long = { System.currentTimeMillis() },
) : UploadSpeedProbe {

    @Volatile private var cached: ConnectionStatus.Measured? = null
    @Volatile private var cachedAtMs: Long = 0L

    override suspend fun probe(context: Context): ConnectionStatus {
        // 1. Connectivity: require a validated internet-capable network.
        if (!hasValidatedInternet(context)) {
            // A drop in connectivity invalidates any cached speed.
            cached = null
            return ConnectionStatus.Offline
        }

        // 2. Reuse a fresh measurement so a quick retry doesn't re-probe.
        cached?.let { last ->
            if (now() - cachedAtMs <= CACHE_TTL_MS) return last
        }

        // 3. Time a real 512 KB upload, then delete it. Never throw out.
        return try {
            withTimeout(PROBE_TIMEOUT_MS) {
                val auth = authProvider(context)
                auth.ensureSignedIn()
                val uid = auth.session.value.uid ?: return@withTimeout ConnectionStatus.ProbeFailed
                // Same prefix as AiUpscaleRepository's `users/$uid/upscale-input/$hash.png`
                // upload, so the existing Storage rules already permit it (no new
                // rules — Storage rules deploy is currently blocked).
                val probePath = "users/$uid/upscale-input/.speedprobe-${now()}.bin"
                val ref = FirebaseStorage.getInstance().reference.child(probePath)
                val payload = ByteArray(PROBE_BYTES) // zero-filled; content is irrelevant

                val startNs = System.nanoTime()
                ref.putBytes(payload).await()
                val elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000.0

                // Best-effort cleanup; a failed delete must not flip a good
                // measurement into a failure.
                try {
                    ref.delete().await()
                } catch (t: Throwable) {
                    Log.w(TAG, "probe cleanup failed (non-fatal): ${t.message}")
                }

                if (elapsedSec <= 0.0) {
                    ConnectionStatus.ProbeFailed
                } else {
                    val bps = (PROBE_BYTES / elapsedSec).toLong()
                    val measured = ConnectionStatus.Measured(bps)
                    cached = measured
                    cachedAtMs = now()
                    Log.i(TAG, "upload probe: ${PROBE_BYTES}B in ${"%.2f".format(elapsedSec)}s -> ${bps}B/s")
                    measured
                }
            }
        } catch (t: TimeoutCancellationException) {
            // The probe's OWN timeout — expected, fail-safe to "warn".
            Log.w(TAG, "upload probe timed out after ${PROBE_TIMEOUT_MS}ms")
            ConnectionStatus.ProbeFailed
        } catch (c: CancellationException) {
            // The CALLER's coroutine was cancelled (e.g. ViewModel cleared).
            // Must propagate to honor structured concurrency — do NOT swallow.
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "upload probe failed: ${t.message}", t)
            ConnectionStatus.ProbeFailed
        }
    }

    private suspend fun hasValidatedInternet(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@withContext false
            val network = cm.activeNetwork ?: return@withContext false
            val caps = cm.getNetworkCapabilities(network) ?: return@withContext false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // A SecurityException (missing ACCESS_NETWORK_STATE) or any other
            // surprise must not crash the flow; treat as "can't tell" → not
            // offline, so the probe still runs and the fail-safe warn applies.
            Log.w(TAG, "connectivity check failed: ${t.message}")
            true
        }
    }

    companion object {
        private const val TAG = "NetSpeedProbe"

        /** 512 KB — balances accuracy (256 KB underestimates via TCP slow-start)
         *  against the cost of the probe upload. */
        const val PROBE_BYTES = 512 * 1024

        /** Reuse a fresh measurement within this window. */
        const val CACHE_TTL_MS = 45_000L

        /** Hard cap on the probe so a stalled upload can't hang the gate. */
        const val PROBE_TIMEOUT_MS = 10_000L
    }
}
