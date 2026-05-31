package com.posterpdf.ui.components.preview

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

    /** Floor for free-form paper/poster dimensions so a zero/blank value can't
     *  produce an Infinity/NaN scale or source-fraction in [compute]. */
    private const val MIN_DIM = 0.01

    /**
     * RC58: lightweight rows/cols computation that doesn't need a Canvas size.
     * Used at the composable level to detect single-page posters before the
     * Canvas is laid out (so the assembly cycle can skip Tightening + Taping
     * phases when there are no seams to close or tape). Returns [rows, cols]
     * — same logic as [compute] but without pixel scaling.
     */
    fun computePaneCount(
        posterW: Double, posterH: Double,
        paperW: Double, paperH: Double,
        margin: Double, overlap: Double,
    ): Pair<Int, Int> {
        val printableW = paperW - 2.0 * margin
        val printableH = paperH - 2.0 * margin
        val stepX = printableW - overlap
        val stepY = printableH - overlap
        val rawCols = if (posterW <= printableW || stepX <= 0) 1
                      else ceil((posterW - printableW) / stepX).toInt() + 1
        val rawRows = if (posterH <= printableH || stepY <= 0) 1
                      else ceil((posterH - printableH) / stepY).toInt() + 1
        return rawRows.coerceIn(1, MAX_PANE_AXIS) to rawCols.coerceIn(1, MAX_PANE_AXIS)
    }

    fun compute(
        posterW: Double, posterH: Double,
        paperW: Double, paperH: Double,
        margin: Double, overlap: Double,
        availableW: Float, availableH: Float,
        interPaneGap: Float,
    ): Layout {
        // Defensive: paperW/H, posterW/H, margin, overlap come from free-form
        // TextFields. computePaneCount() already guards step<=0; compute() did not,
        // and it additionally divides by paperW/H (scale) and posterW/H (source
        // fractions). A zero/blank dimension or margin*2 >= paper there yields an
        // Infinity/NaN that propagates into every pane rect + the camera math,
        // corrupting the whole frame. Clamp all dims/derived values to safe positives
        // (coerceIn already bounds the final pane count).
        val safePaperW = paperW.coerceAtLeast(MIN_DIM)
        val safePaperH = paperH.coerceAtLeast(MIN_DIM)
        val safePosterW = posterW.coerceAtLeast(MIN_DIM)
        val safePosterH = posterH.coerceAtLeast(MIN_DIM)
        val printableW = (safePaperW - 2.0 * margin).coerceAtLeast(safePaperW * 0.05)
        val printableH = (safePaperH - 2.0 * margin).coerceAtLeast(safePaperH * 0.05)
        val stepX = (printableW - overlap).coerceAtLeast(printableW * 0.05)
        val stepY = (printableH - overlap).coerceAtLeast(printableH * 0.05)

        val rawCols = if (safePosterW <= printableW) 1 else ceil((safePosterW - printableW) / stepX).toInt() + 1
        val rawRows = if (safePosterH <= printableH) 1 else ceil((safePosterH - printableH) / stepY).toInt() + 1
        val cols = rawCols.coerceIn(1, MAX_PANE_AXIS)
        val rows = rawRows.coerceIn(1, MAX_PANE_AXIS)

        // Pick scale that fits (cols paperW + gaps), (rows paperH + gaps) into available.
        val scaleX = if (cols == 1) availableW / safePaperW.toFloat()
                     else (availableW - (cols - 1) * interPaneGap) / (cols * safePaperW.toFloat())
        val scaleY = if (rows == 1) availableH / safePaperH.toFloat()
                     else (availableH - (rows - 1) * interPaneGap) / (rows * safePaperH.toFloat())
        val scale = min(scaleX, scaleY).coerceAtLeast(0.1f)

        val paneW = (safePaperW * scale).toFloat()
        val paneH = (safePaperH * scale).toFloat()
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
                val sourceFracLeft = (tilePosterX / safePosterW).toFloat().coerceIn(0f, 1f)
                val sourceFracTop = (tilePosterY / safePosterH).toFloat().coerceIn(0f, 1f)
                val sourceFracWidthUnclamped = (printableW / safePosterW).toFloat()
                val sourceFracHeightUnclamped = (printableH / safePosterH).toFloat()
                val sourceFracWidth = sourceFracWidthUnclamped.coerceAtMost(1f - sourceFracLeft)
                val sourceFracHeight = sourceFracHeightUnclamped.coerceAtMost(1f - sourceFracTop)

                // Edge-tile parity with PosterLogic.kt: when the poster doesn't divide
                // evenly into pages, the rightmost/bottommost tile's source rect is a
                // partial slice. The PDF generator clips to printable + draws the full
                // poster translated, so the unfilled portion stays blank paper.
                // We mirror that here: imageContent* shrinks by the same ratio the
                // source rect was clamped by, leaving blank paper on the trailing edge.
                //
                // RC48: special case — when rows == 1 && cols == 1 (poster fits on a
                // single page), there are no neighboring tiles, no overlap region,
                // and no "edge clip" semantics to honor. The image should occupy
                // the full printable rect (imageDst*). User-reported bug: a small
                // 1-page poster previously rendered shrunk-to-poster-scale with
                // blank paper around it, which was correct for tiled posters but
                // nonsensical for single-page output where there's nothing to tile.
                val singlePane = rows == 1 && cols == 1
                val imageContentWidth = when {
                    singlePane -> imageDstWidth
                    sourceFracWidthUnclamped > 0f ->
                        imageDstWidth * (sourceFracWidth / sourceFracWidthUnclamped)
                    else -> imageDstWidth
                }
                val imageContentHeight = when {
                    singlePane -> imageDstHeight
                    sourceFracHeightUnclamped > 0f ->
                        imageDstHeight * (sourceFracHeight / sourceFracHeightUnclamped)
                    else -> imageDstHeight
                }

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
            paperW = safePaperW, paperH = safePaperH,
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
