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
 * decoding. [width]/[height] are the full source dimensions.
 *
 * RC76: the band-streaming input side of the OOM fix — the upscaler reads the
 * source 50x50 tile-by-tile through here, never holding the whole source.
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
        fun open(ctx: Context, uri: Uri): RegionSource {
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
            // Fallback: whole decode (inSampleSize keeps it bounded if very large).
            val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val whole = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                ?: error("RegionSource: could not decode $uri")
            return RegionSource(whole.width, whole.height, null, whole)
        }
    }
}
