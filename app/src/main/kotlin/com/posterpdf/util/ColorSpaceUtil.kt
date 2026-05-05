package com.posterpdf.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.os.Build

/**
 * RC21 / Phase J — sRGB normalization for bitmaps headed to upload or PDF.
 *
 * Modern Android phones (Pixel 4+, recent Samsung Galaxy) capture in
 * Display P3, which is wider than sRGB. When we send the raw bytes to
 * FAL the model reads them as sRGB and the wide-gamut values get
 * misinterpreted — produces ~10% hue/saturation drift on vivid colors
 * (most visible on logos, product photos, neon signage). The PDF embed
 * path has the same issue: PdfBox-Android writes DeviceRGB content
 * streams that PDF readers interpret as sRGB by default.
 *
 * Fix is to convert any non-sRGB bitmap to sRGB before it crosses the
 * upload boundary or gets drawn into a PDF. This module is the single
 * place that knows how to do that conversion across the SDK 23–36 range
 * we support.
 *
 * Behavior by API level:
 *   • API 26+ (Android 8 Oreo): full conversion via
 *     Bitmap.createBitmap(..., colorSpace) + Canvas.drawBitmap. The
 *     drawBitmap call respects source/dest colorspaces and emits
 *     correctly-mapped sRGB pixels.
 *   • API 23-25 (Android 6/7): bitmaps are implicitly sRGB on these
 *     versions — there's no colorspace metadata to convert, so we
 *     return the source unchanged. (BitmapFactory cannot decode P3 on
 *     pre-O anyway; the pixel data is already what sRGB would interpret.)
 */
object ColorSpaceUtil {

    /**
     * Returns a bitmap whose colorspace is sRGB. Cheap when the source is
     * already sRGB or when we're on pre-O — both paths return [src]
     * directly without allocation.
     */
    fun ensureSRGB(src: Bitmap): Bitmap {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return src
        val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
        if (src.colorSpace == srgb) return src
        val config = src.config ?: Bitmap.Config.ARGB_8888
        val out = Bitmap.createBitmap(src.width, src.height, config, src.hasAlpha(), srgb)
        Canvas(out).drawBitmap(src, 0f, 0f, null)
        return out
    }
}
