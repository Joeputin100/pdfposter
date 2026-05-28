package com.posterpdf.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * RC11 — persistence for in-progress on-device upscales so a process kill
 * (PPK / Doze / OOM / app-restart) doesn't lose all the tile work.
 *
 * State is written to two files in the app's private cache:
 *   • `upscale_state.txt`  — a 4-line text record: source URI, total tiles,
 *                            last completed tile, output dimensions.
 *   • `upscale_partial.png` — the partial output bitmap as lossless PNG.
 *
 * Both files are written together every ~60 seconds during a long upscale.
 * On the next call to [runFreeUpscale], if a saved state exists for the
 * currently-selected source URI, the user is offered "Resume from N%?".
 *
 * Plain-text format (rather than JSON) so we don't pull in another library
 * for one tiny record. Order is fixed; one value per line.
 */
object UpscaleStateStore {

    private const val TAG = "UpscaleStateStore"
    private const val STATE_FILE = "upscale_state.txt"
    private const val PARTIAL_FILE = "upscale_partial.png"

    data class Snapshot(
        val sourceUri: String,
        val totalTiles: Int,
        val lastCompletedTile: Int,
        val outputW: Int,
        val outputH: Int,
        val partialBitmapPath: String,
    )

    /**
     * Persist the current upscale state + partial output bitmap. Cheap-ish
     * (~1-2 s for a 4 MP PNG encode); call every 60 s during the loop, not
     * per-tile.
     */
    fun save(
        ctx: Context,
        sourceUri: String,
        totalTiles: Int,
        lastCompletedTile: Int,
        partial: Bitmap,
    ) {
        try {
            val dir = ctx.cacheDir
            val partialFile = File(dir, PARTIAL_FILE)
            FileOutputStream(partialFile).use { fos ->
                partial.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            val stateFile = File(dir, STATE_FILE)
            stateFile.writeText(
                buildString {
                    appendLine(sourceUri)
                    appendLine(totalTiles.toString())
                    appendLine(lastCompletedTile.toString())
                    appendLine(partial.width.toString())
                    appendLine(partial.height.toString())
                },
            )
        } catch (e: Throwable) {
            Log.w(TAG, "save failed: ${e.message}")
        }
    }

    /** Load the saved snapshot if present and matches [expectedSourceUri].
     *  Returns null on miss / corrupt / mismatch. Caller can decode the
     *  partialBitmapPath via BitmapFactory.decodeFile(). */
    fun load(ctx: Context, expectedSourceUri: String): Snapshot? {
        return try {
            val dir = ctx.cacheDir
            val stateFile = File(dir, STATE_FILE)
            val partialFile = File(dir, PARTIAL_FILE)
            if (!stateFile.exists() || !partialFile.exists()) return null
            val lines = stateFile.readLines()
            if (lines.size < 5) return null
            val savedUri = lines[0]
            if (savedUri != expectedSourceUri) return null
            Snapshot(
                sourceUri = savedUri,
                totalTiles = lines[1].toInt(),
                lastCompletedTile = lines[2].toInt(),
                outputW = lines[3].toInt(),
                outputH = lines[4].toInt(),
                partialBitmapPath = partialFile.absolutePath,
            )
        } catch (e: Throwable) {
            Log.w(TAG, "load failed: ${e.message}")
            null
        }
    }

    /** Probe for any in-progress upscale (regardless of URI). Used to show
     *  a "Resume?" prompt at app startup. */
    fun peek(ctx: Context): Snapshot? {
        return try {
            val dir = ctx.cacheDir
            val stateFile = File(dir, STATE_FILE)
            val partialFile = File(dir, PARTIAL_FILE)
            if (!stateFile.exists() || !partialFile.exists()) return null
            val lines = stateFile.readLines()
            if (lines.size < 5) return null
            Snapshot(
                sourceUri = lines[0],
                totalTiles = lines[1].toInt(),
                lastCompletedTile = lines[2].toInt(),
                outputW = lines[3].toInt(),
                outputH = lines[4].toInt(),
                partialBitmapPath = partialFile.absolutePath,
            )
        } catch (_: Throwable) {
            null
        }
    }

    /** Remove both files. Call after a successful upscale or an explicit
     *  cancel / fail. (Not on a kill — leaves state for next-launch
     *  resume.) */
    fun clear(ctx: Context) {
        try {
            File(ctx.cacheDir, STATE_FILE).delete()
            File(ctx.cacheDir, PARTIAL_FILE).delete()
        } catch (_: Throwable) {
            // best-effort
        }
    }
}
