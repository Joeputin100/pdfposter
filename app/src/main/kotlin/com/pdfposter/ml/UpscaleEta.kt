package com.pdfposter.ml

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * ETA estimation helpers for both upscale paths. Both functions return a
 * range in seconds — the modal renders this as "~1–3 min" so users get
 * a window, not a single brittle number.
 */

// --- DataStore: benchmark cache --------------------------------------------

private const val UPSCALE_PREFS_NAME = "upscale_prefs"
internal val Context.upscalePrefs: DataStore<Preferences> by preferencesDataStore(UPSCALE_PREFS_NAME)

// Median ms it took the on-device ESRGAN to produce 1 MP of output during
// the most recent benchmark. Written by [UpscalerOnDevice.benchmarkAndCache].
internal val MS_PER_MP_KEY = longPreferencesKey("ms_per_megapixel")
// When the benchmark last ran, in epoch ms. Re-run after STALE_AFTER_MS.
internal val LAST_BENCHMARK_AT_KEY = longPreferencesKey("last_benchmark_at")

/** 30 days — re-benchmark to catch device updates / Android version bumps. */
internal const val STALE_AFTER_MS = 30L * 24L * 60L * 60L * 1000L

/** One-shot read of the cached ms-per-megapixel; null if never benchmarked. */
suspend fun cachedMsPerMegapixel(ctx: Context): Long? {
    val prefs = ctx.upscalePrefs.data.first()
    return prefs[MS_PER_MP_KEY]
}

/** Whether the cached benchmark is missing or older than [STALE_AFTER_MS]. */
suspend fun benchmarkNeedsRefresh(ctx: Context, now: Long = System.currentTimeMillis()): Boolean {
    val prefs = ctx.upscalePrefs.data.first()
    val last = prefs[LAST_BENCHMARK_AT_KEY] ?: return true
    if (prefs[MS_PER_MP_KEY] == null) return true
    return now - last > STALE_AFTER_MS
}

internal suspend fun writeBenchmark(ctx: Context, msPerMp: Long, now: Long = System.currentTimeMillis()) {
    ctx.upscalePrefs.edit { p ->
        p[MS_PER_MP_KEY] = msPerMp
        p[LAST_BENCHMARK_AT_KEY] = now
    }
}

/** Observe the cached value as a Flow for Compose UI. */
fun msPerMegapixelFlow(ctx: Context): Flow<Long?> =
    ctx.upscalePrefs.data.map { it[MS_PER_MP_KEY] }

// --- ETA estimators ---------------------------------------------------------

/** ±25% window around [point] in seconds, clamped to ≥ 1 s. */
private fun rangeAround(point: Double): IntRange {
    val low = (point * 0.75).toInt().coerceAtLeast(1)
    val high = (point * 1.25).toInt().coerceAtLeast(low + 1)
    return low..high
}

/**
 * On-device ETA in seconds for an upscale producing [outputMp] megapixels of
 * output, given the cached [msPerMp] from [UpscalerOnDevice.benchmarkAndCache].
 * Returns null if [msPerMp] is null (caller should show "estimating…").
 */
fun etaForLocal(outputMp: Long, msPerMp: Long?): IntRange? {
    if (msPerMp == null || msPerMp <= 0) return null
    val totalMs = outputMp * msPerMp
    return rangeAround(totalMs / 1000.0)
}

/**
 * FAL ETA in seconds. Empirical curve (no FAL-side telemetry available):
 *   upload_time    = inputBytes / bandwidth
 *   queue_constant = 30 s         # tighten with telemetry once we have it
 *   inference_time = 0.5 s/MP of output (Topaz Gigapixel observed)
 *   download_time  = outputBytes / bandwidth   (outputBytes ≈ inputBytes × scale²)
 *
 * [bytesPerSecond] is the caller's bandwidth estimate — pass a reasonable
 * default like 500 KB/s for unknown networks. Returns null only if math
 * underflows; callers should treat that as "unknown".
 */
fun etaForFal(
    inputBytes: Long,
    outputMp: Long,
    bytesPerSecond: Long,
): IntRange? {
    if (bytesPerSecond <= 0 || inputBytes <= 0 || outputMp <= 0) return null
    val uploadSec = inputBytes.toDouble() / bytesPerSecond
    val queueSec = 30.0
    val inferenceSec = outputMp * 0.5
    // Output bytes guesstimate: lossy compression ≈ 1 byte per pixel for JPEG/PNG.
    val outputBytesEstimate = outputMp * 1_000_000L
    val downloadSec = outputBytesEstimate.toDouble() / bytesPerSecond
    val total = uploadSec + queueSec + inferenceSec + downloadSec
    return rangeAround(total)
}

/**
 * Render a seconds [range] as a human-friendly string.
 * Examples: `2..4` → "2–4 s", `45..70` → "45–70 s", `90..150` → "1.5–2.5 min".
 */
fun formatEta(range: IntRange): String {
    val low = range.first
    val high = range.last
    return when {
        high < 60 -> "$low–$high s"
        high < 600 -> "%.1f–%.1f min".format(low / 60.0, high / 60.0)
        else -> "%d–%d min".format(low / 60, high / 60)
    }
}
