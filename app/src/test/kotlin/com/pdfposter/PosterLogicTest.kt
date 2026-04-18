package com.pdfposter

import org.junit.Test
import org.junit.Assert.*
import java.io.File

class PosterLogicTest {
    @Test
    fun testCalculateSheetCount() {
        val logic = PosterLogic()
        // Example: 11x17 poster on 8.5x11 paper with 0.5 margins and 0.25 overlap
        // Printable: 7.5 x 10 (540x720 pt)
        // Poster: 11 x 17 (792x1224 pt)
        // tileStepX = 540 - 18 = 522
        // tileStepY = 720 - 18 = 702
        // cols = ceil((792-540)/522) + 1 = 1 + 1 = 2
        // rows = ceil((1224-720)/702) + 1 = 1 + 1 = 2
        // total = 4
        val result = logic.calculateSheetCount(792.0, 1224.0, 540.0, 720.0, 18.0)
        assertEquals(4, result.first)
        assertEquals(2, result.second)
        assertEquals(2, result.third)
    }

    @Test
    fun testGetGridLabel() {
        val logic = PosterLogic()
        assertEquals("A1", logic.getGridLabel(0, 0))
        assertEquals("B2", logic.getGridLabel(1, 1))
        assertEquals("Z10", logic.getGridLabel(25, 9))
        assertEquals("AA1", logic.getGridLabel(26, 0))
    }

    @Test
    fun testGenerateTiles() {
        val logic = PosterLogic()
        val tiles = logic.generateTiles(792.0, 1224.0, 540.0, 720.0, 18.0)
        assertEquals(4, tiles.size)
        assertEquals("A1", tiles[0].label)
        assertEquals(0.0, tiles[0].offsetX, 0.001)
        assertEquals(0.0, tiles[0].offsetY, 0.001)
        assertEquals("B2", tiles[3].label)
        // tileStepX = 522
        // tileStepY = 702
        assertEquals(522.0, tiles[3].offsetX, 0.001)
        assertEquals(702.0, tiles[3].offsetY, 0.001)
    }

    /*
    @Test
    fun testCreateTiledPoster() {
        val logic = PosterLogic()
        
        // Valid 1x1 pixel PNG
        val pngBytes = byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0D.toByte(), 0x49.toByte(), 0x48.toByte(), 0x44.toByte(), 0x52.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
            0x08.toByte(), 0x02.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x90.toByte(), 0x77.toByte(), 0x53.toByte(),
            0xDE.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x0C.toByte(), 0x49.toByte(), 0x44.toByte(), 0x41.toByte(),
            0x54.toByte(), 0x08.toByte(), 0xD7.toByte(), 0x63.toByte(), 0xF8.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x3F.toByte(),
            0x00.toByte(), 0x05.toByte(), 0xFE.toByte(), 0x02.toByte(), 0xFE.toByte(), 0xDC.toByte(), 0x44.toByte(), 0x74.toByte(),
            0x3E.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x49.toByte(), 0x45.toByte(), 0x4E.toByte(),
            0x44.toByte(), 0xAE.toByte(), 0x42.toByte(), 0x60.toByte(), 0x82.toByte()
        )
        val imageFile = File("dummy.png")
        imageFile.writeBytes(pngBytes)
        
        try {
            logic.createTiledPoster(
                imagePath = "dummy.png",
                posterW = 792.0,
                posterH = 1224.0,
                pageW = 612.0, // 8.5 inch
                pageH = 792.0, // 11 inch
                margin = 36.0, // 0.5 inch
                overlap = 18.0, // 0.25 inch
                outputPath = "output.pdf"
            )
            
            val outputFile = File("output.pdf")
            assertTrue(outputFile.exists())
            assertTrue(outputFile.length() > 0)
        } finally {
            imageFile.delete()
            File("output.pdf").delete()
        }
    }
    */
}
