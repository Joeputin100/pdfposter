package com.pdfposter.ui.components.preview

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class PaneGeometryTest {

    @Test
    fun pageSize_matchesPaperNotImageSlice() {
        // 24x36 poster, 8.5x11 paper, 0.5 margin, 0.25 overlap.
        // Page (paper) = 8.5x11, NOT the image-slice size.
        val g = PaneGeometry.compute(
            posterW = 24.0, posterH = 36.0,
            paperW = 8.5, paperH = 11.0,
            margin = 0.5, overlap = 0.25,
            availableW = 1000f, availableH = 1000f,
            interPaneGap = 18f,
        )
        assertEquals(8.5, g.paperW, 0.001)
        assertEquals(11.0, g.paperH, 0.001)
    }

    @Test
    fun imageInsetByMargin() {
        // The image dst rect must be inset from the page by margin on every side.
        val g = PaneGeometry.compute(
            posterW = 24.0, posterH = 36.0,
            paperW = 8.5, paperH = 11.0,
            margin = 0.5, overlap = 0.25,
            availableW = 1000f, availableH = 1000f,
            interPaneGap = 18f,
        )
        val pane = g.panes.first()
        // image dst rect = page rect inset by marginPx on all sides
        assertEquals(pane.pageLeft + g.marginPx, pane.imageDstLeft, 0.001f)
        assertEquals(pane.pageTop + g.marginPx, pane.imageDstTop, 0.001f)
        assertEquals(pane.pageWidth - 2f * g.marginPx, pane.imageDstWidth, 0.001f)
        assertEquals(pane.pageHeight - 2f * g.marginPx, pane.imageDstHeight, 0.001f)
    }

    @Test
    fun coverage_tilingMatchesPdfLogic() {
        // Verify printable areas fully cover the poster (with overlap).
        // 16-wide poster, printable per page = 8.5 - 2*0.5 = 7.5,
        // overlap 0.5 → step = 7.0, cols = ceil((16 - 7.5) / 7.0) + 1 = 3.
        // Use a poster height of 10 so it fits in a single printable row
        // (printableH = 11 - 2*0.5 = 10).
        val g = PaneGeometry.compute(
            posterW = 16.0, posterH = 10.0,
            paperW = 8.5, paperH = 11.0,
            margin = 0.5, overlap = 0.5,
            availableW = 1000f, availableH = 1000f,
            interPaneGap = 0f,
        )
        assertEquals(3, g.cols)
        assertEquals(1, g.rows)
        // Right edge of last printable must reach poster right edge (within tolerance).
        val lastTilePosterX = (g.cols - 1) * (g.printableW - g.overlap)
        assertTrue(lastTilePosterX + g.printableW >= 16.0 - 0.001)
    }

    @Test
    fun centersWithinAvailableBox() {
        // Layout block = cols*paneW + (cols-1)*gap, must be centered inside available.
        val g = PaneGeometry.compute(
            posterW = 11.0, posterH = 8.5,
            paperW = 8.5, paperH = 11.0,
            margin = 0.5, overlap = 0.5,
            availableW = 1000f, availableH = 500f,
            interPaneGap = 18f,
        )
        val totalW = g.cols * g.paneW + (g.cols - 1) * 18f
        val totalH = g.rows * g.paneH + (g.rows - 1) * 18f
        assertEquals((1000f - totalW) / 2f, g.layoutLeft, 0.001f)
        assertEquals((500f - totalH) / 2f, g.layoutTop, 0.001f)
    }

    @Test
    fun sourceRectMath_interiorTile() {
        // 24"x36" poster, 8.5"x11" paper, margin 0.5, overlap 0.25.
        // printableW = 7.5, stepX = 7.25; cols = ceil((24-7.5)/7.25)+1 = 4.
        // Interior tile (col=1): tilePosterX = 7.25; sourceFracLeft = 7.25/24 = 0.302083...
        val g = PaneGeometry.compute(
            posterW = 24.0, posterH = 36.0,
            paperW = 8.5, paperH = 11.0,
            margin = 0.5, overlap = 0.25,
            availableW = 1000f, availableH = 1000f,
            interPaneGap = 18f,
        )
        val interiorTile = g.panes.first { it.row == 0 && it.col == 1 }
        assertEquals(7.25f / 24f, interiorTile.sourceFracLeft, 0.001f)
        assertEquals(7.5f / 24f, interiorTile.sourceFracWidth, 0.001f)
        // Interior tile content fills the printable area
        assertEquals(interiorTile.imageDstWidth, interiorTile.imageContentWidth, 0.001f)
    }

    @Test
    fun sourceRectMath_edgeTileLeavesBlankPaper() {
        // Same setup; rightmost tile (col=3): tilePosterX = 21.75
        // sourceFracLeft = 21.75/24 = 0.90625
        // Unclamped sourceFracWidth = 7.5/24 = 0.3125
        // Clamped (since left+width > 1.0): 1.0 - 0.90625 = 0.09375
        // imageContentWidth = imageDstWidth * 0.09375/0.3125 = imageDstWidth * 0.3
        val g = PaneGeometry.compute(
            posterW = 24.0, posterH = 36.0,
            paperW = 8.5, paperH = 11.0,
            margin = 0.5, overlap = 0.25,
            availableW = 1000f, availableH = 1000f,
            interPaneGap = 18f,
        )
        val edgeTile = g.panes.first { it.row == 0 && it.col == 3 }
        assertEquals(21.75f / 24f, edgeTile.sourceFracLeft, 0.001f)
        // Clamped width is what's actually left: 1.0 - sourceFracLeft
        assertEquals(1f - 21.75f / 24f, edgeTile.sourceFracWidth, 0.001f)
        // Image content is shorter than printable rect — blank paper to the right
        assertTrue("Edge tile content must be narrower than full dst", edgeTile.imageContentWidth < edgeTile.imageDstWidth)
        val ratio = edgeTile.imageContentWidth / edgeTile.imageDstWidth
        assertEquals(0.3f, ratio, 0.001f)
    }

    @Test
    fun singlePageWhenPosterFitsInPrintable() {
        // 6"x8" poster, 8.5"x11" paper, margin 0.5, overlap 0.25.
        // Poster fits inside printable (7.5 x 10) → single page.
        val g = PaneGeometry.compute(
            posterW = 6.0, posterH = 8.0,
            paperW = 8.5, paperH = 11.0,
            margin = 0.5, overlap = 0.25,
            availableW = 1000f, availableH = 1000f,
            interPaneGap = 18f,
        )
        assertEquals(1, g.cols)
        assertEquals(1, g.rows)
        assertEquals(1, g.panes.size)
        val pane = g.panes.first()
        // Source rect covers (0,0) to (6/6, 8/8) = (0..1, 0..1)
        assertEquals(0f, pane.sourceFracLeft, 0.001f)
        assertEquals(0f, pane.sourceFracTop, 0.001f)
        // sourceFracWidth = printableW/posterW = 7.5/6 = 1.25 → clamped to 1.0 (full source)
        assertEquals(1f, pane.sourceFracWidth, 0.001f)
        assertEquals(1f, pane.sourceFracHeight, 0.001f)
        // Image content is shorter than the printable area (poster is 6" wide on 7.5" printable → 80% width)
        val widthRatio = pane.imageContentWidth / pane.imageDstWidth
        assertEquals(6f / 7.5f, widthRatio, 0.01f)
    }
}
