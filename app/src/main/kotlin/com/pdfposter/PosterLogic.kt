package com.pdfposter

import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import kotlin.math.ceil

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
        includeInstructions: Boolean = true
    ) {
        val doc = PDDocument()
        val image = LosslessFactory.createFromImage(doc, bitmap)

        if (includeInstructions) {
            addInstructionsPage(doc, image, posterW, posterH, pageW, pageH, margin, overlap)
        }

        val printableW = pageW - 2 * margin
        val printableH = pageH - 2 * margin
        val tiles = generateTiles(posterW, posterH, printableW, printableH, overlap)
        
        val thickness = when(outlineThickness) {
            "Thin" -> 0.5f
            "Heavy" -> 2.0f
            else -> 1.0f
        }

        for (tile in tiles) {
            val page = PDPage(PDRectangle(pageW.toFloat(), pageH.toFloat()))
            doc.addPage(page)
            
            val contentStream = PDPageContentStream(doc, page)
            contentStream.saveGraphicsState()
            contentStream.addRect(margin.toFloat(), margin.toFloat(), printableW.toFloat(), printableH.toFloat())
            contentStream.clip()
            
            val drawX = margin - tile.offsetX
            val drawY = margin - (posterH - tile.offsetY - printableH)
            
            contentStream.drawImage(image, drawX.toFloat(), drawY.toFloat(), posterW.toFloat(), posterH.toFloat())
            contentStream.restoreGraphicsState()
            
            if (showOutlines) {
                contentStream.setLineWidth(thickness)
                when (outlineStyle) {
                    "Dotted" -> contentStream.setLineDashPattern(floatArrayOf(1f, 2f), 0f)
                    "Dashed" -> contentStream.setLineDashPattern(floatArrayOf(5f, 5f), 0f)
                }
                contentStream.addRect(margin.toFloat(), margin.toFloat(), printableW.toFloat(), printableH.toFloat())
                contentStream.stroke()
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
        o: Double
    ) {
        val page = PDPage(PDRectangle(pgw.toFloat(), pgh.toFloat()))
        doc.addPage(page)
        val cs = PDPageContentStream(doc, page)
        
        cs.beginText()
        cs.setFont(PDType1Font.HELVETICA_BOLD, 20f)
        cs.newLineAtOffset(50f, pgh.toFloat() - 60f)
        cs.showText("Poster PDF Assembly Guide")
        
        cs.setFont(PDType1Font.HELVETICA, 10f)
        cs.newLineAtOffset(0f, -30f)
        cs.showText("Dimensions: ${"%.1f".format(pw/72.0)}x${"%.1f".format(ph/72.0)} in | Paper: ${"%.1f".format(pgw/72.0)}x${"%.1f".format(pgh/72.0)} in")
        cs.newLineAtOffset(0f, -15f)
        cs.showText("Margins: ${"%.2f".format(m/72.0)} in | Overlap: ${"%.2f".format(o/72.0)} in")
        cs.endText()

        // Diagram Area
        val diagAreaW = pgw - 100
        val diagAreaH = pgh / 2.2
        val scale = kotlin.math.min(diagAreaW / pw, diagAreaH / ph).toFloat()
        val dw = (pw * scale).toFloat()
        val dh = (ph * scale).toFloat()
        val dx = 50f
        val dy = (pgh / 2.0 - dh / 2.0).toFloat()

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

        // Instructions Footer
        cs.beginText()
        cs.setFont(PDType1Font.HELVETICA, 11f)
        cs.newLineAtOffset(50f, 100f)
        cs.showText("1. Cut tiles along outlines. 2. Match labels to the grid above. 3. Glue using overlaps.")
        cs.endText()

        cs.close()
    }
}
