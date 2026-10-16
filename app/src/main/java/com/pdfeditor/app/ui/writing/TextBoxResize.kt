package com.pdfeditor.app.ui.writing

import com.pdfeditor.app.domain.PdfPoint
import com.pdfeditor.app.domain.PdfRect
import com.pdfeditor.app.domain.TextBoxAnnotation

internal enum class TextResizeCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * Resize in displayed PDF points, keeping the opposite corner fixed whenever the page allows it.
 * [delta] is the total drag from [originalBounds], not an incremental change from the last preview.
 * [reflow] must wrap the source using the annotation's actual font at the supplied content width.
 * The baseline remains one font size below the box top; full line extents include descenders.
 * A null preview means the requested width cannot retain every line inside the page.
 */
internal fun resizeTextBox(
    annotation: TextBoxAnnotation,
    originalBounds: PdfRect,
    corner: TextResizeCorner,
    delta: PdfPoint,
    pageWidth: Float,
    pageHeight: Float,
    reflow: (raw: String, width: Float) -> String,
): TextBoxAnnotation? {
    val values =
        listOf(
            originalBounds.left,
            originalBounds.top,
            originalBounds.right,
            originalBounds.bottom,
            delta.x,
            delta.y,
            pageWidth,
            pageHeight,
            annotation.fontSizePoints,
        )
    if (values.any { !it.isFinite() } || pageWidth <= 0f || pageHeight <= 0f || annotation.fontSizePoints <= 0f) return null
    if (originalBounds.right < originalBounds.left || originalBounds.bottom < originalBounds.top) return null

    val movesLeft = corner == TextResizeCorner.TOP_LEFT || corner == TextResizeCorner.BOTTOM_LEFT
    val movesTop = corner == TextResizeCorner.TOP_LEFT || corner == TextResizeCorner.TOP_RIGHT
    val minimumWidth = (annotation.fontSizePoints * 2f).coerceAtMost(pageWidth)
    val fixedX =
        if (movesLeft) {
            originalBounds.right.coerceIn(minimumWidth, pageWidth)
        } else {
            originalBounds.left.coerceIn(0f, pageWidth - minimumWidth)
        }
    val desiredWidth = originalBounds.right - originalBounds.left + if (movesLeft) -delta.x else delta.x
    val width = desiredWidth.coerceIn(minimumWidth, if (movesLeft) fixedX else pageWidth - fixedX)
    val left = if (movesLeft) fixedX - width else fixedX

    val source = annotation.sourceText ?: annotation.text
    val wrapped = reflow(source, width).replace("\r\n", "\n").replace('\r', '\n')
    val minimumHeight = wrapped.split('\n').size * annotation.fontSizePoints * 1.25f
    if (!minimumHeight.isFinite() || minimumHeight > pageHeight) return null

    val fixedY =
        if (movesTop) {
            originalBounds.bottom.coerceIn(minimumHeight, pageHeight)
        } else {
            originalBounds.top.coerceIn(0f, pageHeight - minimumHeight)
        }
    val desiredHeight = originalBounds.bottom - originalBounds.top + if (movesTop) -delta.y else delta.y
    val height = desiredHeight.coerceIn(minimumHeight, if (movesTop) fixedY else pageHeight - fixedY)
    val top = if (movesTop) fixedY - height else fixedY
    return annotation.copy(
        position = PdfPoint(left, top + annotation.fontSizePoints),
        text = wrapped,
        widthPoints = width,
        heightPoints = height,
        sourceText = source,
    )
}
