package com.pdfposter

import kotlin.math.ceil
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File

data class TileInfo(
    val row: Int,
    val col: Int,
    val label: String,
    val offsetX: Double,
    val offsetY: Double
)

class PosterLogic {
    /**
     * Calculates the number of sheets (tiles) needed for a given poster size.
     * Returns a Triple containing (totalSheets, rows, cols).
     */
    fun calculateSheetCount(
        posterW: Double,
        posterH: Double,
        printableW: Double,
        printableH: Double,
        overlap: Double
    ): Triple<Int, Int, Int> {
        val tileStepX = printableW - overlap
        val tileStepY = printableH - overlap

        val cols = if (posterW <= printableW) {
            1
        } else {
            ceil((posterW - printableW) / tileStepX).toInt() + 1
        }

        val rows = if (posterH <= printableH) {
            1
        } else {
            ceil((posterH - printableH) / tileStepY).toInt() + 1
        }

        return Triple(rows * cols, rows, cols)
    }

    /**
     * Returns grid coordinate label (e.g., A1, B2).
     */
    fun getGridLabel(row: Int, col: Int): String {
        var rowLabel = ""
        var r = row
        while (r >= 0) {
            rowLabel = ('A' + (r % 26)).toString() + rowLabel
            r = r / 26 - 1
        }
        val colLabel = (col + 1).toString()
        return "$rowLabel$colLabel"
    }

    /**
     * Generates a list of tiles with their positions and labels.
     */
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
                val offsetX = c * tileStepX
                val offsetY = r * tileStepY
                tiles.add(TileInfo(r, c, getGridLabel(r, c), offsetX, offsetY))
            }
        }
        return tiles
    }

    /**
     * Draws a tiled PDF poster.
     * Note: This is a JVM-specific implementation using PDFBox.
     */
    fun createTiledPoster(
        imagePath: String,
        posterW: Double,
        posterH: Double,
        pageW: Double,
        pageH: Double,
        margin: Double,
        overlap: Double,
        outputPath: String
    ) {
        val doc = PDDocument()
        val image = PDImageXObject.createFromFile(imagePath, doc)
        
        val printableW = pageW - 2 * margin
        val printableH = pageH - 2 * margin
        
        val tiles = generateTiles(posterW, posterH, printableW, printableH, overlap)
        
        for (tile in tiles) {
            val page = PDPage(PDRectangle(pageW.toFloat(), pageH.toFloat()))
            doc.addPage(page)
            
            val contentStream = PDPageContentStream(doc, page)
            
            // Draw image tile
            // Offset logic: The image is drawn with a negative offset to only show the relevant tile
            // We use clipping or just coordinate transformation
            contentStream.saveGraphicsState()
            
            // Clip to printable area
            contentStream.addRect(margin.toFloat(), margin.toFloat(), printableW.toFloat(), printableH.toFloat())
            contentStream.clip()
            
            // Draw the full image at the calculated offset
            // In PDFBox, (0,0) is bottom-left. 
            // The image should be drawn so that the current tile (offsetX, offsetY) is at (margin, margin)
            val drawX = margin - tile.offsetX
            val drawY = margin - (posterH - tile.offsetY - printableH) // Adjust for bottom-up coordinates
            
            contentStream.drawImage(image, drawX.toFloat(), drawY.toFloat(), posterW.toFloat(), posterH.toFloat())
            contentStream.restoreGraphicsState()
            
            // Draw Label
            contentStream.beginText()
            contentStream.setFont(PDType1Font.HELVETICA, 10f)
            contentStream.newLineAtOffset(margin.toFloat(), (pageH - margin + 5).toFloat())
            contentStream.showText("Tile ${tile.label} (Row ${tile.row + 1}, Col ${tile.col + 1})")
            contentStream.endText()
            
            contentStream.close()
        }
        
        doc.save(File(outputPath))
        doc.close()
    }
}
