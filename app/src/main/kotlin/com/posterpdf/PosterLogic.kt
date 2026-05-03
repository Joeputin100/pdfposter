package com.posterpdf

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import kotlin.math.ceil

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
    ) {
        val doc = PDDocument()
        // For raster sources we hoist the image once (PDFBox de-duplicates
        // identical XObject streams). For SVG we draw fresh per tile, so we
        // skip this hoist and let each tile create its own PDImageXObject.
        val image = if (svgTileRenderer == null) LosslessFactory.createFromImage(doc, bitmap) else null

        if (includeInstructions) {
            // Instructions page uses the existing single bitmap (rasterized
            // at SVG.documentWidth × .documentHeight by the caller). That's
            // fine for the diagram/preview — only the per-page tiles need
            // vector-quality fidelity.
            val instructionImage = image ?: LosslessFactory.createFromImage(doc, bitmap)
            addInstructionsPage(doc, instructionImage, posterW, posterH, pageW, pageH, margin, overlap, logoBitmap, sourcePixelW, sourcePixelH)
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

                val tileBmp = svgTileRenderer.invoke(
                    svgTilePxW, svgTilePxH,
                    srcLeft01, srcTop01, srcRight01, srcBottom01,
                )
                val tileImage = LosslessFactory.createFromImage(doc, tileBmp)
                contentStream.drawImage(
                    tileImage,
                    margin.toFloat(),
                    margin.toFloat(),
                    printableW.toFloat(),
                    printableH.toFloat(),
                )
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
                contentStream.showText("Tile ${tile.label} (Row ${tile.row + 1}, Col ${tile.col + 1})")
                contentStream.endText()
            }
            contentStream.close()
        }
        
        doc.save(File(outputPath))
        doc.close()
    }

    private fun addInstructionsPage(
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
        sourcePixelH: Int
    ) {
        val page = PDPage(PDRectangle(pgw.toFloat(), pgh.toFloat()))
        doc.addPage(page)
        val cs = PDPageContentStream(doc, page)
        val isLandscapePage = pgw > pgh

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
        cs.showText("Tile any image into a printable poster")
        cs.endText()

        // Section heading
        cs.setNonStrokingColor(0.12f, 0.12f, 0.15f)
        cs.beginText()
        cs.setFont(PDType1Font.HELVETICA_BOLD, 14f)
        cs.newLineAtOffset(50f, pgh.toFloat() - 95f)
        cs.showText("Assembly Guide")
        cs.endText()

        // Project details
        val detailsX = 50f
        val detailsY = if (isLandscapePage) pgh.toFloat() - 112f else pgh.toFloat() - 115f
        cs.setNonStrokingColor(0.2f, 0.2f, 0.22f)
        cs.beginText()
        cs.setFont(PDType1Font.HELVETICA, 10f)
        cs.newLineAtOffset(detailsX, detailsY)
        cs.showText("Dimensions: ${"%.1f".format(posterWInches)}x${"%.1f".format(posterHInches)} in | Paper: ${"%.1f".format(pgw/72.0)}x${"%.1f".format(pgh/72.0)} in")
        cs.newLineAtOffset(0f, -13f)
        cs.showText("Margins: ${"%.2f".format(m/72.0)} in | Overlap: ${"%.2f".format(o/72.0)} in")
        cs.newLineAtOffset(0f, -13f)
        cs.showText("Source: ${sourcePixelW}x${sourcePixelH}px | Print resolution: ~$minDpi DPI")
        cs.endText()
        cs.setNonStrokingColor(0f, 0f, 0f)

        // Low-DPI warning
        if (minDpi < 150) {
            cs.beginText()
            cs.setFont(PDType1Font.HELVETICA_BOLD, 11f)
            cs.setNonStrokingColor(0.8f, 0.35f, 0.0f) // warm orange warning
            cs.newLineAtOffset(detailsX, detailsY - 55f)
            cs.showText("! Low Print Resolution Warning")
            cs.setFont(PDType1Font.HELVETICA, 9f)
            cs.setNonStrokingColor(0.25f, 0.25f, 0.28f)
            cs.newLineAtOffset(0f, -12f)
            if (isLandscapePage) {
                cs.showText("This poster prints at about $minDpi DPI.")
                cs.newLineAtOffset(0f, -11f)
                cs.showText("For sharp results, target 150+ DPI.")
                cs.newLineAtOffset(0f, -11f)
                cs.showText("Consider AI upscaling or a smaller poster size.")
            } else {
                cs.showText("This poster will print at approximately $minDpi DPI. For sharp, professional-quality prints,")
                cs.newLineAtOffset(0f, -11f)
                cs.showText("aim for 150+ DPI. Consider using AI upscaling or a smaller poster size.")
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
        val dy = if (isLandscapePage) ((pgh - dh) / 2.0).toFloat() else (pgh / 2.0 - dh / 2.0).toFloat()

        // 1. Draw low-opacity background image
        cs.saveGraphicsState()
        cs.setGraphicsStateParameters(com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState().apply {
            nonStrokingAlphaConstant = 0.2f
        })
        cs.drawImage(image, dx, dy, dw, dh)
        cs.restoreGraphicsState()

        // 2. Draw Grid and Labels
        val (_, rows, cols) = calculateSheetCount(pw, ph, pgw - 2 * m, pgh - 2 * m, o)
        val tw = dw / cols
        val th = dh / rows
        
        cs.setLineWidth(1f)
        cs.setStrokingColor(0f, 0f, 0f)
        
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val tx = dx + c * tw
                val ty = dy + (rows - 1 - r) * th
                cs.addRect(tx, ty, tw, th)
                cs.stroke()
                
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA_BOLD, 14f)
                val label = getGridLabel(r, c)
                // Center label simple logic
                cs.newLineAtOffset(tx + tw/2f - 10f, ty + th/2f - 5f)
                cs.showText(label)
                cs.endText()
            }
        }

        // 3. Rulers (top and left) showing poster scale in inches
        // Origin is top-left corner of grid (0,0) for both rulers.
        val rulerOffset = 12f
        val topRulerY = dy - rulerOffset
        val leftRulerX = dx - rulerOffset
        val ptsPerInchOnDiagramX = dw / (pw.toFloat() / 72f)
        val ptsPerInchOnDiagramY = dh / (ph.toFloat() / 72f)

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

        val maxInchesX = kotlin.math.floor(pw / 72.0).toInt()
        val maxInchesY = kotlin.math.floor(ph / 72.0).toInt()

        // Top ruler ticks/labels (every 1in tick, every 2in label)
        for (inch in 0..maxInchesX) {
            val x = dx + inch * ptsPerInchOnDiagramX
            val major = inch % 2 == 0
            val tick = if (major) 8f else 4f
            cs.moveTo(x, topRulerY)
            cs.lineTo(x, topRulerY - tick)
            cs.stroke()
            if (major) {
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 7f)
                cs.newLineAtOffset(x - 4f, topRulerY - tick - 9f)
                cs.showText(inch.toString())
                cs.endText()
            }
        }
        // Left ruler ticks/labels (every 1in tick, every 2in label)
        for (inch in 0..maxInchesY) {
            val y = dy + inch * ptsPerInchOnDiagramY
            val major = inch % 2 == 0
            val tick = if (major) 8f else 4f
            cs.moveTo(leftRulerX, y)
            cs.lineTo(leftRulerX - tick, y)
            cs.stroke()
            if (major) {
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 7f)
                cs.newLineAtOffset(leftRulerX - tick - 10f, y - 2f)
                cs.showText(inch.toString())
                cs.endText()
            }
        }

        // Ruler unit labels
        cs.beginText()
        cs.setFont(PDType1Font.HELVETICA_OBLIQUE, 8f)
        cs.newLineAtOffset(dx + dw + 6f, topRulerY - 2f)
        cs.showText("in")
        cs.endText()
        cs.beginText()
        cs.setFont(PDType1Font.HELVETICA_OBLIQUE, 8f)
        cs.newLineAtOffset(leftRulerX - 14f, dy - 3f)
        cs.showText("in")
        cs.endText()

        // Instructions Footer
        cs.setNonStrokingColor(0.12f, 0.12f, 0.15f)
        cs.beginText()
        cs.setFont(PDType1Font.HELVETICA_BOLD, 12f)
        cs.newLineAtOffset(if (isLandscapePage) 42f else 50f, 120f)
        cs.showText("How to assemble:")
        cs.endText()
        cs.beginText()
        cs.setFont(PDType1Font.HELVETICA, 10f)
        cs.newLineAtOffset(if (isLandscapePage) 42f else 50f, 102f)
        cs.showText("1. Trim each page along the printed cut line (inside the overlap zone).")
        cs.newLineAtOffset(0f, -13f)
        cs.showText("2. Match labels (A1, A2, B1\u2026) to the grid above.")
        cs.newLineAtOffset(0f, -13f)
        cs.showText("3. Align overlapping edges and glue or tape from the back.")
        cs.endText()

        // Footer accent bar + credit
        cs.setNonStrokingColor(0.15f, 0.42f, 0.82f)
        cs.addRect(0f, 0f, pgw.toFloat(), 6f)
        cs.fill()
        cs.setNonStrokingColor(0.45f, 0.45f, 0.48f)
        cs.beginText()
        cs.setFont(PDType1Font.HELVETICA, 8f)
        cs.newLineAtOffset(50f, 18f)
        cs.showText("Made with Poster PDF \u2022 play.google.com/store/apps/details?id=com.posterpdf")
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
