package com.posterpdf.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri

/**
 * Supplies source pixels by region. Prefers BitmapRegionDecoder (decodes only
 * the requested rect — bounded memory for huge sources); falls back to a single
 * inSampleSize-bounded whole decode for formats that don't support region
 * decoding. [width]/[height] are the (possibly downsampled, in the fallback
 * path) source dimensions that all [region] rects are expressed against.
 *
 * RC76: the band-streaming input side of the OOM fix — the upscaler reads the
 * source 50x50 tile-by-tile through here, never holding the whole source.
 *
 * RC80: the whole-decode fallback (used only when BitmapRegionDecoder is
 * unavailable for the source format) now honours an inSampleSize so it does NOT
 * decode a huge source at full resolution into RAM (the previous behaviour was
 * a guaranteed memory spike for large fallback sources). The power-of-two
 * inSampleSize is chosen from the source bounds vs a target dimension so the
 * decoded bitmap stays bounded; [width]/[height] then report the actual decoded
 * dimensions, so callers tile/scale consistently against the downsampled image.
 * The BitmapRegionDecoder fast-path is unchanged (full-resolution regions).
 */
internal class RegionSource private constructor(
    val width: Int,
    val height: Int,
    private val decoder: BitmapRegionDecoder?,
    private val whole: Bitmap?,
) {
    /** Read [rect] of the source into a Bitmap (caller recycles). */
    fun region(rect: Rect): Bitmap {
        decoder?.let {
            val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            return synchronized(it) { it.decodeRegion(rect, o) }
        }
        val b = whole!!
        return Bitmap.createBitmap(b, rect.left, rect.top, rect.width(), rect.height())
    }

    fun close() { decoder?.recycle(); whole?.recycle() }

    companion object {
        /**
         * Default cap on the larger dimension of the whole-decode FALLBACK
         * bitmap. Sources that can't be region-decoded get downsampled (via a
         * power-of-two inSampleSize) so their decoded dimension stays at or
         * below this. 4096 keeps the fallback bitmap to at most ~64 MB
         * (4096 * 4096 * 4 bytes) instead of an unbounded full-resolution
         * decode, while still leaving plenty of pixels for the x4 upscale.
         */
        const val DEFAULT_FALLBACK_MAX_DIM = 4096

        /**
         * Compute the largest power-of-two inSampleSize such that the decoded
         * dimensions (srcW/sample, srcH/sample) both fit within [maxDim].
         * Returns 1 when no downsampling is needed (or for invalid bounds).
         * BitmapFactory rounds inSampleSize down to the nearest power of two,
         * so we return a power of two directly.
         */
        internal fun computeInSampleSize(srcW: Int, srcH: Int, maxDim: Int): Int {
            if (srcW <= 0 || srcH <= 0 || maxDim <= 0) return 1
            var sample = 1
            // Double the sample until BOTH downsampled dimensions fit maxDim.
            while ((srcW / sample) > maxDim || (srcH / sample) > maxDim) {
                sample = sample shl 1
            }
            return sample
        }

        /**
         * @param fallbackMaxDim cap on the larger decoded dimension for the
         *   whole-decode fallback (ignored on the BitmapRegionDecoder path,
         *   which always decodes regions at full resolution).
         */
        fun open(
            ctx: Context,
            uri: Uri,
            fallbackMaxDim: Int = DEFAULT_FALLBACK_MAX_DIM,
        ): RegionSource {
            val cr = ctx.contentResolver
            // Bounds first (no allocation).
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val w = bounds.outWidth; val h = bounds.outHeight
            // Try region decoder.
            val decoder = try {
                cr.openInputStream(uri)?.use { ins ->
                    @Suppress("DEPRECATION")
                    BitmapRegionDecoder.newInstance(ins, false)
                }
            } catch (_: Throwable) { null }
            if (decoder != null) return RegionSource(w, h, decoder, null)
            // Fallback: whole decode, downsampled via a power-of-two
            // inSampleSize so a huge source doesn't spike memory. The decoded
            // bitmap reports its OWN (downsampled) dimensions as width/height,
            // so region() rects and any caller tiling stay self-consistent.
            val sample = computeInSampleSize(w, h, fallbackMaxDim)
            val o = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sample
            }
            val whole = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                ?: error("RegionSource: could not decode $uri")
            return RegionSource(whole.width, whole.height, null, whole)
        }
    }
}
