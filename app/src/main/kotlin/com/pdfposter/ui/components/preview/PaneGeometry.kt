package com.pdfposter.ui.components.preview

import kotlin.math.ceil
import kotlin.math.min

/**
 * Pure geometry for the construction preview. Mirrors the model in PosterLogic.kt,
 * which is the source of truth for the actual generated PDF:
 *  - Each PDF page is a full sheet of paper (paperW x paperH).
 *  - Image content is clipped to the printable area = page minus margin on all sides.
 *  - Adjacent tiles share `overlap` of source content so they tile seamlessly when
 *    the user trims along the cut marks (which sit *inside* the overlap region).
 *
 * Inputs are in arbitrary user units (inches, mm). The function picks a single scale
 * factor so the multi-page block fits inside (availableW, availableH) and converts
 * everything to pixels.
 */
object PaneGeometry {

    data class Pane(
        val row: Int,
        val col: Int,
        // Page (paper) rect — what the user holds in their hand.
        val pageLeft: Float, val pageTop: Float,
        val pageWidth: Float, val pageHeight: Float,
        // Image dst rect — where to paint the source bitmap. Inset by margin.
        val imageDstLeft: Float, val imageDstTop: Float,
        val imageDstWidth: Float, val imageDstHeight: Float,
        // Image *content* rect — the portion of imageDst* that actually receives image
        // pixels. ≤ imageDstWidth/Height. For interior tiles these are equal; for edge
        // tiles where the source rect is clamped (poster doesn't divide evenly into
        // pages), the content rect is shorter, leaving blank paper on the trailing
        // side. Mirrors PosterLogic.kt's clip()+drawImage(fullPoster, translated)
        // flow which leaves the unfilled portion as blank paper.
        val imageContentWidth: Float, val imageContentHeight: Float,
        // Source rect — sub-rect of the source bitmap to sample (0..1 in poster space).
        val sourceFracLeft: Float, val sourceFracTop: Float,
        val sourceFracWidth: Float, val sourceFracHeight: Float,
    )

    data class Layout(
        val rows: Int,
        val cols: Int,
        val paperW: Double, val paperH: Double,
        val printableW: Double, val printableH: Double,
        val overlap: Double,
        val scale: Float, // user-units → pixels
        val paneW: Float, val paneH: Float,
        val marginPx: Float,
        val overlapPx: Float,
        val layoutLeft: Float, val layoutTop: Float,
        val panes: List<Pane>,
    )

    /** Hard cap on the preview's pane count.
     *  Phase F security review: posterW/H are user-controlled via free-form TextField;
     *  unbounded values would produce millions of Pane allocations inside Canvas onDraw
     *  and OOM the UI thread. 16x16 = 256 panes is well past any realistic poster grid
     *  and keeps the per-frame draw cost bounded. The PDF generator (PosterLogic.kt)
     *  has no such cap because it doesn't render to screen, but the preview must.
     */
    private const val MAX_PANE_AXIS = 16

    fun compute(
        posterW: Double, posterH: Double,
        paperW: Double, paperH: Double,
        margin: Double, overlap: Double,
        availableW: Float, availableH: Float,
        interPaneGap: Float,
    ): Layout {
        val printableW = paperW - 2.0 * margin
        val printableH = paperH - 2.0 * margin
        val stepX = printableW - overlap
        val stepY = printableH - overlap

        val rawCols = if (posterW <= printableW) 1 else ceil((posterW - printableW) / stepX).toInt() + 1
        val rawRows = if (posterH <= printableH) 1 else ceil((posterH - printableH) / stepY).toInt() + 1
        val cols = rawCols.coerceIn(1, MAX_PANE_AXIS)
        val rows = rawRows.coerceIn(1, MAX_PANE_AXIS)

        // Pick scale that fits (cols paperW + gaps), (rows paperH + gaps) into available.
        val scaleX = if (cols == 1) availableW / paperW.toFloat()
                     else (availableW - (cols - 1) * interPaneGap) / (cols * paperW.toFloat())
        val scaleY = if (rows == 1) availableH / paperH.toFloat()
                     else (availableH - (rows - 1) * interPaneGap) / (rows * paperH.toFloat())
        val scale = min(scaleX, scaleY).coerceAtLeast(0.1f)

        val paneW = (paperW * scale).toFloat()
        val paneH = (paperH * scale).toFloat()
        val marginPx = (margin * scale).toFloat()
        val overlapPx = (overlap * scale).toFloat()

        val totalW = cols * paneW + (cols - 1) * interPaneGap
        val totalH = rows * paneH + (rows - 1) * interPaneGap
        val layoutLeft = (availableW - totalW) / 2f
        val layoutTop = (availableH - totalH) / 2f

        val panes = ArrayList<Pane>(rows * cols)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val pageLeft = layoutLeft + c * (paneW + interPaneGap)
                val pageTop = layoutTop + r * (paneH + interPaneGap)
                val imageDstLeft = pageLeft + marginPx
                val imageDstTop = pageTop + marginPx
                val imageDstWidth = paneW - 2f * marginPx
                val imageDstHeight = paneH - 2f * marginPx

                val tilePosterX = c * stepX
                val tilePosterY = r * stepY
                val sourceFracLeft = (tilePosterX / posterW).toFloat().coerceIn(0f, 1f)
                val sourceFracTop = (tilePosterY / posterH).toFloat().coerceIn(0f, 1f)
                val sourceFracWidthUnclamped = (printableW / posterW).toFloat()
                val sourceFracHeightUnclamped = (printableH / posterH).toFloat()
                val sourceFracWidth = sourceFracWidthUnclamped.coerceAtMost(1f - sourceFracLeft)
                val sourceFracHeight = sourceFracHeightUnclamped.coerceAtMost(1f - sourceFracTop)

                // Edge-tile parity with PosterLogic.kt: when the poster doesn't divide
                // evenly into pages, the rightmost/bottommost tile's source rect is a
                // partial slice. The PDF generator clips to printable + draws the full
                // poster translated, so the unfilled portion stays blank paper.
                // We mirror that here: imageContent* shrinks by the same ratio the
                // source rect was clamped by, leaving blank paper on the trailing edge.
                val imageContentWidth = if (sourceFracWidthUnclamped > 0f)
                    imageDstWidth * (sourceFracWidth / sourceFracWidthUnclamped)
                else imageDstWidth
                val imageContentHeight = if (sourceFracHeightUnclamped > 0f)
                    imageDstHeight * (sourceFracHeight / sourceFracHeightUnclamped)
                else imageDstHeight

                panes.add(
                    Pane(
                        row = r, col = c,
                        pageLeft = pageLeft, pageTop = pageTop,
                        pageWidth = paneW, pageHeight = paneH,
                        imageDstLeft = imageDstLeft, imageDstTop = imageDstTop,
                        imageDstWidth = imageDstWidth, imageDstHeight = imageDstHeight,
                        imageContentWidth = imageContentWidth, imageContentHeight = imageContentHeight,
                        sourceFracLeft = sourceFracLeft, sourceFracTop = sourceFracTop,
                        sourceFracWidth = sourceFracWidth, sourceFracHeight = sourceFracHeight,
                    )
                )
            }
        }

        return Layout(
            rows = rows, cols = cols,
            paperW = paperW, paperH = paperH,
            printableW = printableW, printableH = printableH,
            overlap = overlap,
            scale = scale,
            paneW = paneW, paneH = paneH,
            marginPx = marginPx, overlapPx = overlapPx,
            layoutLeft = layoutLeft, layoutTop = layoutTop,
            panes = panes,
        )
    }
}
