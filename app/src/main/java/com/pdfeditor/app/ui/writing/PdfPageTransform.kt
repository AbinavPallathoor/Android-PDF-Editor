package com.pdfeditor.app.ui.writing

import androidx.compose.ui.geometry.Offset
import com.pdfeditor.app.domain.PdfPoint

/**
 * Converts between on-screen pixels (within the writing-mode page canvas) and PDF user-space
 * points. Writing mode shows the page at a fixed fit-width scale with no pan/zoom, so this is a
 * plain linear scale - no zoom/offset state to account for, unlike reading mode's transform.
 */
data class PdfPageTransform(
    val pageWidthPoints: Float,
    val pageHeightPoints: Float,
    val canvasWidthPx: Float,
    val canvasHeightPx: Float,
) {
    private val pointsPerPx: Float get() = if (canvasWidthPx > 0) pageWidthPoints / canvasWidthPx else 1f

    fun screenToPdf(offset: Offset): PdfPoint = PdfPoint(offset.x * pointsPerPx, offset.y * pointsPerPx)

    fun pdfToScreen(point: PdfPoint): Offset = Offset(point.x / pointsPerPx, point.y / pointsPerPx)

    /** Converts a length (e.g. a stroke width stored in PDF points) to on-screen pixels. */
    fun pdfLengthToScreenPx(lengthPoints: Float): Float = lengthPoints / pointsPerPx
}
