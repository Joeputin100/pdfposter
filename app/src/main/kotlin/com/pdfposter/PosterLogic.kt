package com.pdfposter

import kotlin.math.ceil
import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
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
        
        if (includeInstructions) {
            addInstructionsPage(doc, posterW, posterH, pageW, pageH, margin, overlap)
        }

        val image = LosslessFactory.createFromImage(doc, bitmap)
        
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
            
            // Draw Outline
            if (showOutlines) {
                contentStream.setLineWidth(thickness)
                when (outlineStyle) {
                    "Dotted" -> contentStream.setLineDashPattern(floatArrayOf(1f, 2f), 0f)
                    "Dashed" -> contentStream.setLineDashPattern(floatArrayOf(5f, 5f), 0f)
                }
                contentStream.addRect(margin.toFloat(), margin.toFloat(), printableW.toFloat(), printableH.toFloat())
                contentStream.stroke()
            }

            // Draw Label
            if (labelPanes) {
                contentStream.beginText()
                contentStream.setFont(PDType1Font.HELVETICA, 10f)
                contentStream.setLineDashPattern(floatArrayOf(), 0f) // Reset dash for text
                contentStream.newLineAtOffset(margin.toFloat(), (pageH - margin + 5).toFloat())
                contentStream.showText("Tile ${tile.label} (Row ${tile.row + 1}, Col ${tile.col + 1})")
                contentStream.endText()
            }
            
            contentStream.close()
        }
        
        doc.save(File(outputPath))
        doc.close()
    }

    private fun addInstructionsPage(doc: PDDocument, pw: Double, ph: Double, pgw: Double, pgh: Double, m: Double, o: Double) {
        val page = PDPage(PDRectangle(pgw.toFloat(), pgh.toFloat()))
        doc.addPage(page)
        val contentStream = PDPageContentStream(doc, page)
        
        contentStream.beginText()
        contentStream.setFont(PDType1Font.HELVETICA_BOLD, 18f)
        contentStream.newLineAtOffset(50f, pgh.toFloat() - 50f)
        contentStream.showText("Poster PDF Assembly Instructions")
        
        contentStream.setFont(PDType1Font.HELVETICA, 11f)
        contentStream.newLineAtOffset(0f, -30f)
        contentStream.showText("Total Poster Size: ${"%.2f".format(pw/72.0)} x ${"%.2f".format(ph/72.0)} inches")
        contentStream.newLineAtOffset(0f, -15f)
        contentStream.showText("Paper Size: ${"%.2f".format(pgw/72.0)} x ${"%.2f".format(pgh/72.0)} inches")
        contentStream.newLineAtOffset(0f, -15f)
        contentStream.showText("Margins: ${"%.2f".format(m/72.0)} in, Overlap: ${"%.2f".format(o/72.0)} in")
        
        contentStream.newLineAtOffset(0f, -40f)
        contentStream.showText("Assembly Steps:")
        contentStream.newLineAtOffset(10f, -20f)
        contentStream.showText("1. Cut along the provided outlines (if enabled).")
        contentStream.newLineAtOffset(0f, -15f)
        contentStream.showText("2. Match the labels (A1, A2...) as shown in the diagram below.")
        contentStream.newLineAtOffset(0f, -15f)
        contentStream.showText("3. Use the overlap areas to tape or glue the pages together.")
        contentStream.endText()

        // Draw Grid Diagram
        val printableW = pgw - 2 * m
        val printableH = pgh - 2 * m
        val tiles = generateTiles(pw, ph, printableW, printableH, o)
        val (_, rows, cols) = calculateSheetCount(pw, ph, printableW, printableH, o)

        val diagMaxWidth = pgw - 100
        val diagMaxHeight = pgh / 2
        val scale = kotlin.math.min(diagMaxWidth / pw, diagMaxHeight / ph).toFloat()
        
        val diagW = (pw * scale).toFloat()
        val diagH = (ph * scale).toFloat()
        val startX = 50f
        val startY = pgh.toFloat() / 2 - 50f

        contentStream.setLineWidth(1f)
        contentStream.setStrokingColor(0.5f, 0.5f, 0.5f)
        
        // Draw the tiles
        val tw = diagW / cols
        val th = diagH / rows
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val tx = startX + c * tw
                val ty = startY + (rows - 1 - r) * th
                
                contentStream.addRect(tx, ty, tw, th)
                contentStream.stroke()
                
                // Add Label in mini tile
                contentStream.beginText()
                contentStream.setFont(PDType1Font.HELVETICA, 8f * scale * 72f / 10f) // Scaled font
                contentStream.setFont(PDType1Font.HELVETICA, 8f)
                contentStream.newLineAtOffset(tx + 2, ty + 2)
                contentStream.showText(getGridLabel(r, c))
                contentStream.endText()
            }
        }

        contentStream.close()
    }
}
