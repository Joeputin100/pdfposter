package com.posterpdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import com.google.zxing.BarcodeFormat
import com.posterpdf.R
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import kotlin.math.ceil

// ─── RC42: PDF-text helpers ───────────────────────────────────────────────────
//
// PdfBox-Android ships only Type1 (Helvetica) fonts, which are encoded in
// WinAnsi (Latin-1ish). Calling showText() with characters outside that
// encoding throws IllegalArgumentException and the whole PDF generation
// fails — that's why CJK/Cyrillic/Devanagari/Arabic locales were producing
// no PDF at all in RC39+.
//
// True fix is to embed Noto Sans + Noto Sans CJK as PDType0 fonts. That's
// 5–25 MB of font assets and a separate ramp; deferred to a follow-up RC.
// Interim fix: detect whether the user's locale produces Latin-1-safe
// strings, and if not, fall back to the English Context for the PDF only
// (UI stays in user locale). Better: a generated PDF in English than no
// PDF at all.

/** True if every char fits the WinAnsi/Latin-1 range PDType1Font accepts. */
private fun String.isLatin1Safe(): Boolean = all { it.code < 0x100 }

/**
 * Returns a Context whose Resources resolve strings in English regardless
 * of the user's locale preference. Used as a fallback for PDF text in
 * locales whose script Type1 Helvetica can't encode.
 */
private fun englishFallbackContext(context: Context): Context {
    val cfg = android.content.res.Configuration(context.resources.configuration)
    cfg.setLocale(java.util.Locale.ENGLISH)
    return context.createConfigurationContext(cfg)
}

/**
 * Soft word-wrap: split [text] into lines that each fit within [maxWidth]
 * points when rendered with [font] at [fontSize]. PdfBox-Android has no
 * built-in wrapping — showText() just runs off the page edge. Translated
 * strings (German +35%, French +20%, Hindi +30%) overflowed the diagram
 * area in RC39+, hence this helper.
 *
 * Tokenises on whitespace; doesn't break inside a long word. If a single
 * word exceeds maxWidth on its own, it's emitted on its own line and will
 * still overrun — acceptable because it implies a degenerate input.
 */
private fun wrapText(
    text: String,
    font: PDFont,
    fontSize: Float,
    maxWidth: Float,
): List<String> {
    if (text.isEmpty()) return listOf("")
    val words = text.split(' ')
    val lines = mutableListOf<String>()
    var current = StringBuilder()
    for (word in words) {
        val candidate = if (current.isEmpty()) word else "$current $word"
        val widthPts = font.getStringWidth(candidate) / 1000f * fontSize
        if (widthPts <= maxWidth || current.isEmpty()) {
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        } else {
            lines.add(current.toString())
            current = StringBuilder(word)
        }
    }
    if (current.isNotEmpty()) lines.add(current.toString())
    return lines
}

/**
 * Emit pre-wrapped lines. Caller must already have called beginText() +
 * setFont() + newLineAtOffset() to position the first line. We advance
 * by [leadingY] (typically negative — PDF Y axis points up) between
 * lines. Returns the number of lines emitted so caller can adjust
 * subsequent vertical layout.
 */
private fun PDPageContentStream.showWrappedText(
    lines: List<String>,
    leadingY: Float,
): Int {
    for ((i, line) in lines.withIndex()) {
        if (i > 0) newLineAtOffset(0f, leadingY)
        showText(line)
    }
    return lines.size
}

/**
 * H-P2.7: render a QR code bitmap for the Play Store URL.
 *
 * Uses ZXing's MultiFormatWriter with QR_CODE format and Level M error
 * correction (15% recovery — plenty for an in-app printed QR). The BitMatrix
 * is converted to a square ARGB Bitmap with white background and black
 * modules. We render at 320x320 px for embedding via PDImageXObject —
 * downsampling at the PDF target size of ~64pt (≈ 0.89 in) keeps modules
 * crisp.
 */
private fun generateQrBitmap(url: String, sizePx: Int = 320): Bitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
    )
    val matrix = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            bmp.setPixel(x, y, if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE)
        }
    }
    return bmp
}

data class TileInfo(
    val row: Int,
    val col: Int,
    val label: String,
    val offsetX: Double,
    val offsetY: Double
)

class PosterLogic {
    fun calculateSheetCount(
        posterW: Double,
        posterH: Double,
        printableW: Double,
        printableH: Double,
        overlap: Double
    ): Triple<Int, Int, Int> {
        val tileStepX = printableW - overlap
        val tileStepY = printableH - overlap

        val cols = if (posterW <= printableW) 1 else ceil((posterW - printableW) / tileStepX).toInt() + 1
        val rows = if (posterH <= printableH) 1 else ceil((posterH - printableH) / tileStepY).toInt() + 1

        return Triple(rows * cols, rows, cols)
    }

    fun getGridLabel(row: Int, col: Int): String {
        var rowLabel = ""
        var r = row
        while (r >= 0) {
            rowLabel = ('A' + (r % 26)).toString() + rowLabel
            r = r / 26 - 1
        }
        return "$rowLabel${col + 1}"
    }

    fun generateTiles(
        posterW: Double,
        posterH: Double,
        printableW: Double,
        printableH: Double,
        overlap: Double
    ): List<TileInfo> {
        val (_, rows, cols) = calculateSheetCount(posterW, posterH, printableW, printableH, overlap)
        val tileStepX = printableW - overlap
        val tileStepY = printableH - overlap

        val tiles = mutableListOf<TileInfo>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                tiles.add(TileInfo(r, c, getGridLabel(r, c), c * tileStepX, r * tileStepY))
            }
        }
        return tiles
    }

    /**
     * Phase H-P1.13: target DPI when rasterizing SVG sources for the PDF.
     * Picked to be high enough to look "vector-quality" at typical viewing
     * distances while keeping per-tile bitmap memory bounded — at 300 DPI a
     * single Letter-size printable area (8" × 10.5") is 2400 × 3150 px ≈
     * 30 MB ARGB_8888, which is fine even on low-RAM devices since we
     * generate tiles serially and recycle between pages.
     */
    private val SVG_TILE_DPI: Double = 300.0

    fun createTiledPoster(
        context: Context,
        bitmap: Bitmap,
        posterW: Double,
        posterH: Double,
        pageW: Double,
        pageH: Double,
        margin: Double,
        overlap: Double,
        outputPath: String,
        showOutlines: Boolean = true,
        outlineStyle: String = "Solid",
        outlineThickness: String = "Medium",
        labelPanes: Boolean = true,
        includeInstructions: Boolean = true,
        logoBitmap: Bitmap? = null,
        sourcePixelW: Int = bitmap.width,
        sourcePixelH: Int = bitmap.height,
        /**
         * Phase H-P1.13: when non-null, the source is an SVG and the PDF path
         * rasterizes each tile fresh via this callback. Signature:
         *   (tilePxW, tilePxH, srcLeft01, srcTop01, srcRight01, srcBottom01) -> Bitmap
         * where the four 0..1 fractions describe the slice of the full poster
         * that this tile should render. The callback returns a tilePxW × tilePxH
         * Bitmap showing exactly that slice straight from the SVG. If null,
         * the poster is drawn from [bitmap] (raster fallback).
         */
        svgTileRenderer: ((tilePxW: Int, tilePxH: Int,
                           srcLeft01: Float, srcTop01: Float,
                           srcRight01: Float, srcBottom01: Float) -> Bitmap)? = null,
        /**
         * RC15: when an upscale is queued or in progress, suppress the embedded
         * "Low Print Resolution Warning" text on the instructions page. The
         * source bitmap is still low-DPI (the upscale happens on a separate
         * pipeline that swaps the source later), so the warning was visually
         * incorrect and contradicted the in-app "Upscaling with X" status.
         */
        suppressLowDpiWarning: Boolean = false,
        /** RC18: "Metric" → instructions-page dimension labels read in
         *  centimeters. "Inches" → inches. Internal point-based math is
         *  unchanged; only the displayed unit string + value scaling
         *  for the printable text shifts. */
        units: String = "Inches",
    ) {
        val doc = PDDocument()
        // RC21 / Phase J: normalize the source bitmap to sRGB before it crosses
        // into PdfBox. PDF readers interpret a DeviceRGB content stream as sRGB
        // by default; if the source bitmap was Display P3 the embedded pixels
        // would render with ~10% hue/saturation drift on vivid colors. The
        // conversion is a no-op on pre-O devices (where bitmaps are already
        // sRGB) and on bitmaps that already declare sRGB.
        val srgbBitmap = com.posterpdf.util.ColorSpaceUtil.ensureSRGB(bitmap)
        // For raster sources we hoist the image once (PDFBox de-duplicates
        // identical XObject streams). For SVG we draw fresh per tile, so we
        // skip this hoist and let each tile create its own PDImageXObject.
        val image = if (svgTileRenderer == null) LosslessFactory.createFromImage(doc, srgbBitmap) else null

        if (includeInstructions) {
            // Instructions page uses the existing single bitmap (rasterized
            // at SVG.documentWidth × .documentHeight by the caller). That's
            // fine for the diagram/preview — only the per-page tiles need
            // vector-quality fidelity.
            val instructionImage = image ?: LosslessFactory.createFromImage(doc, srgbBitmap)
            addInstructionsPage(context, doc, instructionImage, posterW, posterH, pageW, pageH, margin, overlap, logoBitmap, sourcePixelW, sourcePixelH, suppressLowDpiWarning, units)
        }

        val printableW = pageW - 2 * margin
        val printableH = pageH - 2 * margin
        val (_, totalRows, totalCols) = calculateSheetCount(posterW, posterH, printableW, printableH, overlap)
        val tiles = generateTiles(posterW, posterH, printableW, printableH, overlap)

        val thickness = when(outlineThickness) {
            "Thin" -> 0.5f
            "Heavy" -> 2.0f
            else -> 1.0f
        }

        // Phase H-P1.13: per-tile SVG render dimensions (only used when
        // svgTileRenderer != null). Page dimensions are in PDF points (72 pt =
        // 1 inch), so tilePx = (pts / 72) × DPI.
        val printableInchesX = printableW / 72.0
        val printableInchesY = printableH / 72.0
        val svgTilePxW = (printableInchesX * SVG_TILE_DPI).toInt().coerceAtLeast(1)
        val svgTilePxH = (printableInchesY * SVG_TILE_DPI).toInt().coerceAtLeast(1)

        for (tile in tiles) {
            val page = PDPage(PDRectangle(pageW.toFloat(), pageH.toFloat()))
            doc.addPage(page)

            val contentStream = PDPageContentStream(doc, page)
            contentStream.saveGraphicsState()
            contentStream.addRect(margin.toFloat(), margin.toFloat(), printableW.toFloat(), printableH.toFloat())
            contentStream.clip()

            if (svgTileRenderer != null) {
                // SVG path: rasterize JUST this tile's slice of the poster at
                // SVG_TILE_DPI. Compute the slice as 0..1 fractions of the
                // full poster so the renderer can render the whole SVG into
                // a tilePxW × tilePxH bitmap with an offset Canvas.
                // tile.offsetX/Y is the top-left of the tile's printable area
                // in poster-pt coords; adding printableW/H gives the bottom-right.
                val srcLeft01 = (tile.offsetX / posterW).toFloat().coerceIn(0f, 1f)
                val srcTop01 = (tile.offsetY / posterH).toFloat().coerceIn(0f, 1f)
                val srcRight01 = ((tile.offsetX + printableW) / posterW).toFloat().coerceIn(0f, 1f)
                val srcBottom01 = ((tile.offsetY + printableH) / posterH).toFloat().coerceIn(0f, 1f)

                val tileBmpRaw = svgTileRenderer.invoke(
                    svgTilePxW, svgTilePxH,
                    srcLeft01, srcTop01, srcRight01, srcBottom01,
                )
                // RC21 / Phase J: same sRGB normalization as the hoisted-raster
                // path. SVG rendering by AndroidSVG can land on whatever the
                // platform's default colorspace is, which is Display P3 on
                // recent devices.
                val tileBmp = com.posterpdf.util.ColorSpaceUtil.ensureSRGB(tileBmpRaw)
                val tileImage = LosslessFactory.createFromImage(doc, tileBmp)
                contentStream.drawImage(
                    tileImage,
                    margin.toFloat(),
                    margin.toFloat(),
                    printableW.toFloat(),
                    printableH.toFloat(),
                )
                if (tileBmp !== tileBmpRaw) tileBmpRaw.recycle()
                tileBmp.recycle()
            } else {
                val drawX = margin - tile.offsetX
                val drawY = margin - (posterH - tile.offsetY - printableH)
                contentStream.drawImage(image!!, drawX.toFloat(), drawY.toFloat(), posterW.toFloat(), posterH.toFloat())
            }
            contentStream.restoreGraphicsState()
            
            if (showOutlines) {
                // Cut-line: inside the overlap zones, so that aligning tiles on the cut edges yields a seamless poster.
                // Edge tiles (no neighbor on that side) cut at the printable edge (no overlap bleed).
                val hasLeftNeighbor = tile.col > 0
                val hasRightNeighbor = tile.col < totalCols - 1
                val hasTopNeighbor = tile.row > 0
                val hasBottomNeighbor = tile.row < totalRows - 1

                val cutLeft = (margin + if (hasLeftNeighbor) overlap else 0.0).toFloat()
                val cutRight = (margin + printableW - if (hasRightNeighbor) overlap else 0.0).toFloat()
                val cutTop = (margin + printableH - if (hasTopNeighbor) overlap else 0.0).toFloat()
                val cutBottom = (margin + if (hasBottomNeighbor) overlap else 0.0).toFloat()

                contentStream.setLineWidth(thickness)
                when (outlineStyle) {
                    "Dotted" -> contentStream.setLineDashPattern(floatArrayOf(1f, 2f), 0f)
                    "Dashed" -> contentStream.setLineDashPattern(floatArrayOf(5f, 5f), 0f)
                }
                if (outlineStyle == "CropMarks") {
                    val arm = kotlin.math.min(cutRight - cutLeft, cutTop - cutBottom) * 0.12f
                    // top-left
                    contentStream.moveTo(cutLeft, cutTop - arm)
                    contentStream.lineTo(cutLeft, cutTop)
                    contentStream.moveTo(cutLeft, cutTop)
                    contentStream.lineTo(cutLeft + arm, cutTop)
                    // top-right
                    contentStream.moveTo(cutRight - arm, cutTop)
                    contentStream.lineTo(cutRight, cutTop)
                    contentStream.moveTo(cutRight, cutTop)
                    contentStream.lineTo(cutRight, cutTop - arm)
                    // bottom-left
                    contentStream.moveTo(cutLeft, cutBottom + arm)
                    contentStream.lineTo(cutLeft, cutBottom)
                    contentStream.moveTo(cutLeft, cutBottom)
                    contentStream.lineTo(cutLeft + arm, cutBottom)
                    // bottom-right
                    contentStream.moveTo(cutRight - arm, cutBottom)
                    contentStream.lineTo(cutRight, cutBottom)
                    contentStream.moveTo(cutRight, cutBottom)
                    contentStream.lineTo(cutRight, cutBottom + arm)
                    contentStream.stroke()
                } else {
                    contentStream.addRect(cutLeft, cutBottom, cutRight - cutLeft, cutTop - cutBottom)
                    contentStream.stroke()
                }
            }

            if (labelPanes) {
                contentStream.beginText()
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, 10f)
                contentStream.setLineDashPattern(floatArrayOf(), 0f)
                contentStream.newLineAtOffset(margin.toFloat(), (pageH - margin + 5).toFloat())
                contentStream.showText(context.getString(R.string.pdf_tile_label, tile.label, tile.row + 1, tile.col + 1))
                contentStream.endText()
            }
            contentStream.close()
        }
        
        doc.save(File(outputPath))
        doc.close()
    }

    private fun addInstructionsPage(
        context: Context,
        doc: PDDocument,
        image: PDImageXObject,
        pw: Double,
        ph: Double,
        pgw: Double,
        pgh: Double,
        m: Double,
        o: Double,
        logoBitmap: Bitmap?,
        sourcePixelW: Int,
        sourcePixelH: Int,
        suppressLowDpiWarning: Boolean = false,
        units: String = "Inches",
    ) {
        val page = PDPage(PDRectangle(pgw.toFloat(), pgh.toFloat()))
        doc.addPage(page)
        val cs = PDPageContentStream(doc, page)
        val isLandscapePage = pgw > pgh

        // RC42: pick the effective Context for PDF text. PdfBox's bundled
        // Type1 Helvetica only encodes Latin-1 — characters outside that
        // range (Cyrillic, Devanagari, Arabic, CJK) throw on showText()
        // and the entire PDF generation fails. We sample one representative
        // body string; if it has any non-Latin-1 codepoint, fall back to
        // English for ALL PDF text. UI continues in user's locale; only
        // the printed instruction page reverts to English. A future RC
        // can embed Noto Sans + Noto Sans CJK to translate the PDF too.
        val sampleString = context.getString(R.string.pdf_assembly_guide_title) +
            context.getString(R.string.pdf_brand_tagline) +
            context.getString(R.string.pdf_assemble_step1)
        val pdfCtx = if (sampleString.isLatin1Safe()) context else englishFallbackContext(context)

        // Compute print DPI at current poster size (convert 1/72 pt back to inches)
        val posterWInches = pw / 72.0
        val posterHInches = ph / 72.0
        val dpiW = sourcePixelW / posterWInches
        val dpiH = sourcePixelH / posterHInches
        val minDpi = kotlin.math.min(dpiW, dpiH).toInt()

        // === Branded header ===
        // Accent bar
        cs.setNonStrokingColor(0.15f, 0.42f, 0.82f)
        cs.addRect(0f, pgh.toFloat() - 8f, pgw.toFloat(), 8f)
        cs.fill()

        // Title (wordmark)
        cs.setNonStrokingColor(0.12f, 0.12f, 0.15f)
        cs.beginText()
        cs.setFont(PDType1Font.HELVETICA_BOLD, 26f)
        cs.newLineAtOffset(50f, pgh.toFloat() - 50f)
        cs.showText("Poster PDF")
        cs.endText()

        // Logo image (if provided)
        logoBitmap?.let {
            val logo = LosslessFactory.createFromImage(doc, it)
            val logoW = 68f
            val logoH = 54f
            cs.drawImage(logo, pgw.toFloat() - 92f, pgh.toFloat() - 72f, logoW, logoH)
        }

        // Tagline
        cs.setNonStrokingColor(0.45f, 0.45f, 0.48f)
        cs.beginText()
        cs.setFont(PDType1Font.HELVETICA_OBLIQUE, 9f)
        cs.newLineAtOffset(50f, pgh.toFloat() - 64f)
        cs.showText(pdfCtx.getString(R.string.pdf_brand_tagline))
        cs.endText()

        // Section heading
        cs.setNonStrokingColor(0.12f, 0.12f, 0.15f)
        cs.beginText()
        cs.setFont(PDType1Font.HELVETICA_BOLD, 14f)
        cs.newLineAtOffset(50f, pgh.toFloat() - 95f)
        cs.showText(pdfCtx.getString(R.string.pdf_assembly_guide_title))
        cs.endText()

        // Project details
        val detailsX = 50f
        val detailsY = if (isLandscapePage) pgh.toFloat() - 112f else pgh.toFloat() - 115f
        cs.setNonStrokingColor(0.2f, 0.2f, 0.22f)
        cs.beginText()
        cs.setFont(PDType1Font.HELVETICA, 10f)
        cs.newLineAtOffset(detailsX, detailsY)
        // RC18: render dimension labels in the user's chosen unit. Inputs
        // (pgw/72, m/72, etc.) are always in inches because unitScale
        // already converted to PDF points; we just multiply by 2.54 to
        // emit cm when units=="Metric".
        val isMetric = units == "Metric"
        val unitLabel = if (isMetric) "cm" else "in"
        val unitScale = if (isMetric) 2.54 else 1.0
        val dimPoster = "${"%.1f".format(posterWInches * unitScale)}x${"%.1f".format(posterHInches * unitScale)} $unitLabel"
        val dimPaper = "${"%.1f".format(pgw/72.0 * unitScale)}x${"%.1f".format(pgh/72.0 * unitScale)} $unitLabel"
        cs.showText(pdfCtx.getString(R.string.pdf_dimensions_label, dimPoster, dimPaper))
        cs.newLineAtOffset(0f, -13f)
        val dimMargin = "${"%.2f".format(m/72.0 * unitScale)} $unitLabel"
        val dimOverlap = "${"%.2f".format(o/72.0 * unitScale)} $unitLabel"
        cs.showText(pdfCtx.getString(R.string.pdf_margins_label, dimMargin, dimOverlap))
        cs.newLineAtOffset(0f, -13f)
        // RC18: print-resolution label is DPI in Inches, DPCM in Metric.
        // 150 DPI = 59 DPCM, so the math is the same — just relabel.
        val resUnitLabel = if (units == "Metric") "DPCM" else "DPI"
        val resInUserUnit = if (units == "Metric") (minDpi / 2.54).toInt() else minDpi
        cs.showText(pdfCtx.getString(R.string.pdf_source_label, "${sourcePixelW}x${sourcePixelH}px", resInUserUnit, resUnitLabel))
        cs.endText()
        cs.setNonStrokingColor(0f, 0f, 0f)

        // Low-DPI warning. RC15: suppress when an upscale is queued.
        // RC42: word-wrap each warning line. In landscape the warning region
        // is the left ~46% of the page (text column to the left of the
        // diagram), so wrapWidth ≈ pgw * 0.42. In portrait it's full
        // width minus margins.
        if (minDpi < 150 && !suppressLowDpiWarning) {
            val warnWrapWidth = if (isLandscapePage) (pgw * 0.42f).toFloat() else (pgw - 100).toFloat()
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA_BOLD, 11f)
            cs.setNonStrokingColor(0.8f, 0.35f, 0.0f) // warm orange warning
            cs.newLineAtOffset(detailsX, detailsY - 55f)
            cs.showText(pdfCtx.getString(R.string.pdf_dpi_warning_line1))
            cs.setFont(PDType1Font.HELVETICA, 9f)
            cs.setNonStrokingColor(0.25f, 0.25f, 0.28f)
            cs.newLineAtOffset(0f, -12f)
            // RC18: also unit-aware for the warning copy. 150 DPI ≈ 59 DPCM.
            val targetReadable = if (units == "Metric") "59+ DPCM" else "150+ DPI"
            val warnLines = if (isLandscapePage) listOf(
                pdfCtx.getString(R.string.pdf_dpi_warning_line2_landscape, resInUserUnit, resUnitLabel),
                pdfCtx.getString(R.string.pdf_dpi_warning_line3_landscape, targetReadable),
                pdfCtx.getString(R.string.pdf_dpi_warning_line4_landscape),
            ) else listOf(
                pdfCtx.getString(R.string.pdf_dpi_warning_line2_portrait, resInUserUnit, resUnitLabel),
                pdfCtx.getString(R.string.pdf_dpi_warning_line3_portrait, targetReadable),
            )
            for ((i, raw) in warnLines.withIndex()) {
                if (i > 0) cs.newLineAtOffset(0f, -11f)
                val wrapped = wrapText(raw, PDType1Font.HELVETICA, 9f, warnWrapWidth)
                cs.showWrappedText(wrapped, leadingY = -11f)
            }
            cs.endText()
            cs.setNonStrokingColor(0f, 0f, 0f)
        }

        // Diagram Area
        val diagAreaW = if (isLandscapePage) pgw * 0.46 else pgw - 100
        val diagAreaH = if (isLandscapePage) pgh * 0.60 else pgh / 2.2
        val scale = kotlin.math.min(diagAreaW / pw, diagAreaH / ph).toFloat()
        val dw = (pw * scale).toFloat()
        val dh = (ph * scale).toFloat()
        val dx = if (isLandscapePage) (pgw * 0.50).toFloat() else 50f
        // RC61: clamp dy from below so the grid bottom edge can't drop into
        // the instructions-footer band (which starts at y≈132 — "How to
        // assemble:" header at y=120, three step lines down to y=76, plus
        // ~12pt cap height for the bold header text). Pre-RC61, landscape
        // pages with diagAreaH near the cap (0.60 × 612 = 367) put dy at
        // (612-367)/2 = 122 — i.e., BELOW the header, so the header drew
        // on top of the grid. Portrait Letter pages never hit this case
        // (dy ≥ 216) so the clamp is functionally landscape-only.
        val footerTopY = 152f
        val centeredDy =
            if (isLandscapePage) ((pgh - dh) / 2.0).toFloat()
            else (pgh / 2.0 - dh / 2.0).toFloat()
        val dy = kotlin.math.max(footerTopY, centeredDy)

        // 1. Draw low-opacity background image
        cs.saveGraphicsState()
        cs.setGraphicsStateParameters(com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState().apply {
            nonStrokingAlphaConstant = 0.2f
        })
        cs.drawImage(image, dx, dy, dw, dh)
        cs.restoreGraphicsState()

        // 2. Draw Grid and Labels — RC21 rewrite:
        //
        // Pre-RC21 the grid was a uniform `dw/cols` × `dh/rows` cell partition.
        // That hides three things the user actually needs to see when planning
        // the assembly:
        //   • paper margins (blank borders trimmed before taping)
        //   • overlap zones between adjacent printable rects
        //   • the fact that some paper extends past the poster edge (rightmost
        //     column / bottom row often have leftover paper)
        //
        // The new grid plots paper cells in TRUE poster coordinates:
        //   • each paper is `pgw × pgh` points, positioned at
        //     `(c*stepX − m, r*stepY − m)` in poster space
        //   • adjacent papers overlap by `overlap` points in printable space
        //   • cell labels sit on the printable area, not the full paper
        //
        // Drawn in three passes so z-order is sensible:
        //   pass 1: paper bounds (light gray outline)
        //   pass 2: overlap zones (orange-tinted fill, shows shared content)
        //   pass 3: printable rect (black outline) + label
        val (_, rows, cols) = calculateSheetCount(pw, ph, pgw - 2 * m, pgh - 2 * m, o)
        val printableWPts = pgw - 2 * m
        val printableHPts = pgh - 2 * m
        val stepXPts = printableWPts - o
        val stepYPts = printableHPts - o
        val sx = (dw / pw).toFloat()
        val sy = (dh / ph).toFloat()

        // Helper: poster (x, y_top_down) → diagram (x, y_pdf_up).
        fun posterToDiagram(px: Double, py: Double): Pair<Float, Float> {
            val xd = dx + (px * sx)
            // PDF y-up; poster y-down. Flip with dh.
            val yd = dy + dh - (py * sy)
            return xd.toFloat() to yd.toFloat()
        }

        // Pass 1: paper bounds (full sheet, including margins).
        cs.setStrokingColor(0.55f, 0.55f, 0.55f)
        cs.setLineWidth(0.6f)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val paperLeftPoster = c * stepXPts - m
                val paperTopPoster = r * stepYPts - m
                val (px, py) = posterToDiagram(paperLeftPoster, paperTopPoster + pgh)
                cs.addRect(px, py, (pgw * sx).toFloat(), (pgh * sy).toFloat())
                cs.stroke()
            }
        }

        // Pass 2: overlap zones between vertical seams.
        // Each pair (c, c+1) shares a vertical strip at poster x in
        // [c*stepX + printableW − overlap, c*stepX + printableW]. Fill
        // the strip with a light orange so the user can see where adjacent
        // pages duplicate content. Same logic for horizontal seams.
        if (o > 0.5) {
            cs.setNonStrokingColor(1.0f, 0.7f, 0.4f)
            cs.saveGraphicsState()
            cs.setGraphicsStateParameters(com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState().apply {
                nonStrokingAlphaConstant = 0.35f
            })
            for (r in 0 until rows) {
                val seamTopPoster = r * stepYPts
                val seamBotPoster = seamTopPoster + printableHPts
                for (c in 0 until cols - 1) {
                    val seamLeftPoster = c * stepXPts + printableWPts - o
                    val seamRightPoster = c * stepXPts + printableWPts
                    val (lx, ty) = posterToDiagram(seamLeftPoster, seamTopPoster)
                    val w = ((seamRightPoster - seamLeftPoster) * sx).toFloat()
                    val h = (printableHPts * sy).toFloat()
                    cs.addRect(lx, ty - h, w, h)
                    cs.fill()
                }
            }
            for (c in 0 until cols) {
                val seamLeftPoster = c * stepXPts
                val seamRightPoster = seamLeftPoster + printableWPts
                for (r in 0 until rows - 1) {
                    val seamTopPoster = r * stepYPts + printableHPts - o
                    val seamBotPoster = r * stepYPts + printableHPts
                    val (lx, ty) = posterToDiagram(seamLeftPoster, seamTopPoster)
                    val w = (printableWPts * sx).toFloat()
                    val h = ((seamBotPoster - seamTopPoster) * sy).toFloat()
                    cs.addRect(lx, ty - h, w, h)
                    cs.fill()
                }
            }
            cs.restoreGraphicsState()
            cs.setNonStrokingColor(0f, 0f, 0f)
        }

        // Pass 3: printable rect outline + label.
        cs.setLineWidth(1f)
        cs.setStrokingColor(0f, 0f, 0f)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val printableLeftPoster = c * stepXPts
                val printableTopPoster = r * stepYPts
                val (lx, ty) = posterToDiagram(printableLeftPoster, printableTopPoster)
                val w = (printableWPts * sx).toFloat()
                val h = (printableHPts * sy).toFloat()
                cs.addRect(lx, ty - h, w, h)
                cs.stroke()

                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA_BOLD, 14f)
                val label = getGridLabel(r, c)
                cs.newLineAtOffset(lx + w / 2f - 10f, ty - h / 2f - 5f)
                cs.showText(label)
                cs.endText()
            }
        }

        // 3. Rulers (top and left) showing poster scale.
        //
        // RC22: switch tick spacing from inches to centimeters when the user
        // is in Metric mode. Pre-RC22 the ruler always drew inch ticks even
        // when the rest of the page (dimension labels, DPI/DPCM, etc.) had
        // been converted to metric — so a user reading the assembly guide in
        // cm got mismatched units between the body text and the ruler.
        //
        // 1 inch = 72 PDF points, 1 cm = 72/2.54 ≈ 28.3465 points. Major
        // tick on every 2nd unit (i.e. every 2 in or every 5 cm), minor on
        // each unit between.
        val rulerOffset = 12f
        val topRulerY = dy - rulerOffset
        val leftRulerX = dx - rulerOffset
        val isMetricRuler = units == "Metric"
        val unitPerPt = if (isMetricRuler) 72.0 / 2.54 else 72.0
        val rulerLabel = if (isMetricRuler) "cm" else "in"
        val majorEvery = if (isMetricRuler) 5 else 2 // cm: every 5; in: every 2
        val ptsPerUnitOnDiagramX = dw / (pw.toFloat() / unitPerPt.toFloat())
        val ptsPerUnitOnDiagramY = dh / (ph.toFloat() / unitPerPt.toFloat())

        cs.setStrokingColor(0.15f, 0.15f, 0.15f)
        cs.setLineWidth(0.8f)
        // Top ruler baseline
        cs.moveTo(dx, topRulerY)
        cs.lineTo(dx + dw, topRulerY)
        cs.stroke()
        // Left ruler baseline
        cs.moveTo(leftRulerX, dy)
        cs.lineTo(leftRulerX, dy + dh)
        cs.stroke()

        val maxUnitsX = kotlin.math.floor(pw / unitPerPt).toInt()
        val maxUnitsY = kotlin.math.floor(ph / unitPerPt).toInt()

        // Top ruler ticks/labels.
        for (u in 0..maxUnitsX) {
            val x = dx + u * ptsPerUnitOnDiagramX
            val major = u % majorEvery == 0
            val tick = if (major) 8f else 4f
            cs.moveTo(x, topRulerY)
            cs.lineTo(x, topRulerY - tick)
            cs.stroke()
            if (major) {
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 7f)
                cs.newLineAtOffset(x - 4f, topRulerY - tick - 9f)
                cs.showText(u.toString())
                cs.endText()
            }
        }
        // Left ruler ticks/labels.
        for (u in 0..maxUnitsY) {
            val y = dy + u * ptsPerUnitOnDiagramY
            val major = u % majorEvery == 0
            val tick = if (major) 8f else 4f
            cs.moveTo(leftRulerX, y)
            cs.lineTo(leftRulerX - tick, y)
            cs.stroke()
            if (major) {
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 7f)
                cs.newLineAtOffset(leftRulerX - tick - 10f, y - 2f)
                cs.showText(u.toString())
                cs.endText()
            }
        }

        // Ruler unit labels
        cs.beginText()
        cs.setFont(PDType1Font.HELVETICA_OBLIQUE, 8f)
        cs.newLineAtOffset(dx + dw + 6f, topRulerY - 2f)
        cs.showText(rulerLabel)
        cs.endText()
        cs.beginText()
        cs.setFont(PDType1Font.HELVETICA_OBLIQUE, 8f)
        cs.newLineAtOffset(leftRulerX - 14f, dy - 3f)
        cs.showText(rulerLabel)
        cs.endText()

        // Instructions Footer
        cs.setNonStrokingColor(0.12f, 0.12f, 0.15f)
        cs.beginText()
        cs.setFont(PDType1Font.HELVETICA_BOLD, 12f)
        cs.newLineAtOffset(if (isLandscapePage) 42f else 50f, 120f)
        cs.showText(pdfCtx.getString(R.string.pdf_how_to_assemble_header))
        cs.endText()
        // RC42: word-wrap each assembly step. Footer text spans full page
        // width minus margins; we leave 80pt for the QR code on the right.
        val stepWrapWidth = (pgw - 100 - 80).toFloat()
        val stepFont = PDType1Font.HELVETICA
        val stepFontSize = 10f
        val stepLeading = -13f
        val steps = listOf(
            R.string.pdf_assemble_step1,
            R.string.pdf_assemble_step2,
            R.string.pdf_assemble_step3,
        ).map { pdfCtx.getString(it) }
        cs.beginText()
        cs.setFont(stepFont, stepFontSize)
        cs.newLineAtOffset(if (isLandscapePage) 42f else 50f, 102f)
        for ((i, raw) in steps.withIndex()) {
            if (i > 0) cs.newLineAtOffset(0f, stepLeading)
            val wrapped = wrapText(raw, stepFont, stepFontSize, stepWrapWidth)
            cs.showWrappedText(wrapped, leadingY = stepLeading)
        }
        cs.endText()

        // Footer accent bar + credit
        cs.setNonStrokingColor(0.15f, 0.42f, 0.82f)
        cs.addRect(0f, 0f, pgw.toFloat(), 6f)
        cs.fill()
        cs.setNonStrokingColor(0.45f, 0.45f, 0.48f)
        cs.beginText()
        cs.setFont(PDType1Font.HELVETICA, 8f)
        cs.newLineAtOffset(50f, 18f)
        cs.showText(pdfCtx.getString(R.string.pdf_footer_made_with, "Poster PDF"))
        cs.endText()
        cs.setNonStrokingColor(0f, 0f, 0f)

        // H-P2.7: render the Play Store URL as a QR code next to the brand text.
        // Sized at 36pt (\u2248 0.5 in) \u2014 fits comfortably in the 6pt accent bar margin
        // without crowding the credit text. ZXing Level M error correction.
        try {
            val qrBmp = generateQrBitmap(
                "https://play.google.com/store/apps/details?id=com.posterpdf",
                sizePx = 320,
            )
            val qrImage = LosslessFactory.createFromImage(doc, qrBmp)
            val qrSize = 36f
            val qrX = pgw.toFloat() - qrSize - 50f
            val qrY = 8f
            cs.drawImage(qrImage, qrX, qrY, qrSize, qrSize)
            qrBmp.recycle()
        } catch (_: Throwable) {
            // QR rendering is non-critical \u2014 if ZXing or the bitmap pipeline
            // throws, we just skip and ship the text-only footer.
        }

        cs.close()
    }
}
