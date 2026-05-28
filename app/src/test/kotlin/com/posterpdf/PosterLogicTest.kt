package com.posterpdf

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
        // This test now requires Android Bitmap and is better suited for androidTest
    }
    */
}
