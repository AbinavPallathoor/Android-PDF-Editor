package com.pdfeditor.app.ui.writing

import com.pdfeditor.app.domain.PdfPoint
import com.pdfeditor.app.domain.SignaturePlacement
import kotlin.math.max
import kotlin.math.min

/** Smallest signature, by its longer side, in points; keeps it visible and grabbable. */
private const val MINIMUM_SIGNATURE_POINTS = 16f

/**
 * Uniformly rescale a signature in displayed PDF points, keeping the opposite corner of its ink
 * fixed whenever the page allows it. The canonical strokes never change, only scale and position.
 * [delta] is the total drag from the original placement. The drag is projected onto the box's
 * diagonal so the aspect ratio holds regardless of drag direction. Null means nothing can resize.
 */
internal fun resizeSignature(
    annotation: SignaturePlacement,
    corner: ResizeCorner,
    delta: PdfPoint,
    pageWidth: Float,
    pageHeight: Float,
): SignaturePlacement? {
    val points = annotation.strokes.flatten()
    if (points.isEmpty() || points.any { !it.x.isFinite() || !it.y.isFinite() }) return null
    val values = listOf(annotation.position.x, annotation.position.y, annotation.scale, delta.x, delta.y, pageWidth, pageHeight)
    if (values.any { !it.isFinite() } || annotation.scale <= 0f || pageWidth <= 0f || pageHeight <= 0f) return null

    val minX = points.minOf { it.x }
    val minY = points.minOf { it.y }
    val inkWidth = points.maxOf { it.x } - minX
    val inkHeight = points.maxOf { it.y } - minY
    val width = inkWidth * annotation.scale
    val height = inkHeight * annotation.scale
    val diagonal = width * width + height * height
    if (diagonal <= 0f || !diagonal.isFinite()) return null

    val movesLeft = corner == ResizeCorner.TOP_LEFT || corner == ResizeCorner.BOTTOM_LEFT
    val movesTop = corner == ResizeCorner.TOP_LEFT || corner == ResizeCorner.TOP_RIGHT
    val left = annotation.position.x + minX * annotation.scale
    val top = annotation.position.y + minY * annotation.scale
    val fixedX = (if (movesLeft) left + width else left).coerceIn(0f, pageWidth)
    val fixedY = (if (movesTop) top + height else top).coerceIn(0f, pageHeight)

    val grownWidth = width + if (movesLeft) -delta.x else delta.x
    val grownHeight = height + if (movesTop) -delta.y else delta.y
    var factor = (grownWidth * width + grownHeight * height) / diagonal
    val roomX = if (movesLeft) fixedX else pageWidth - fixedX
    val roomY = if (movesTop) fixedY else pageHeight - fixedY
    val maximum =
        min(if (width > 0f) roomX / width else Float.POSITIVE_INFINITY, if (height > 0f) roomY / height else Float.POSITIVE_INFINITY)
    val minimum = MINIMUM_SIGNATURE_POINTS / max(width, height)
    if (!maximum.isFinite() || maximum <= 0f) return null
    factor = factor.coerceIn(min(minimum, maximum), maximum)

    val scale = annotation.scale * factor
    val newLeft = if (movesLeft) fixedX - width * factor else fixedX
    val newTop = if (movesTop) fixedY - height * factor else fixedY
    return annotation.copy(position = PdfPoint(newLeft - minX * scale, newTop - minY * scale), scale = scale)
}
