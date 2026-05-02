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

        val cols = if (posterW <= printableW) 1 else ceil((posterW - printableW) / stepX).toInt() + 1
        val rows = if (posterH <= printableH) 1 else ceil((posterH - printableH) / stepY).toInt() + 1

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
                val sourceFracWidth = (printableW / posterW).toFloat().coerceAtMost(1f - sourceFracLeft)
                val sourceFracHeight = (printableH / posterH).toFloat().coerceAtMost(1f - sourceFracTop)

                panes.add(
                    Pane(
                        row = r, col = c,
                        pageLeft = pageLeft, pageTop = pageTop,
                        pageWidth = paneW, pageHeight = paneH,
                        imageDstLeft = imageDstLeft, imageDstTop = imageDstTop,
                        imageDstWidth = imageDstWidth, imageDstHeight = imageDstHeight,
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
