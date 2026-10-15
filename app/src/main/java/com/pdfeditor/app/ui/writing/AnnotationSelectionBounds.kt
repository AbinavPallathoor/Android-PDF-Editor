package com.pdfeditor.app.ui.writing

import com.pdfeditor.app.domain.Annotation
import com.pdfeditor.app.domain.DrawStroke
import com.pdfeditor.app.domain.HighlightAnnotation
import com.pdfeditor.app.domain.PdfPoint
import com.pdfeditor.app.domain.PdfRect
import com.pdfeditor.app.domain.SignaturePlacement
import com.pdfeditor.app.domain.TextBoxAnnotation

/** Displayed PDF coordinates, shared by the selection outline at every zoom level. */
internal fun annotationSelectionBounds(
    annotation: Annotation,
    measureText: (String, Float) -> Float,
): PdfRect? {
    fun points(
        values: List<PdfPoint>,
        pad: Float = 0f,
    ): PdfRect? =
        values.takeIf { it.isNotEmpty() }?.let {
            PdfRect(it.minOf { p -> p.x } - pad, it.minOf { p -> p.y } - pad, it.maxOf { p -> p.x } + pad, it.maxOf { p -> p.y } + pad)
        }
    return when (annotation) {
        is DrawStroke -> points(annotation.points, annotation.strokeWidthPoints / 2)
        is SignaturePlacement ->
            points(
                annotation.strokes.flatten().map {
                    PdfPoint(annotation.position.x + it.x * annotation.scale, annotation.position.y + it.y * annotation.scale)
                },
                .75f,
            )
        is TextBoxAnnotation -> {
            val lines = annotation.text.split('\n')
            PdfRect(
                annotation.position.x,
                annotation.position.y - annotation.fontSizePoints,
                annotation.position.x + lines.maxOf { measureText(it, annotation.fontSizePoints) },
                annotation.position.y + (lines.size - 1) * annotation.fontSizePoints * 1.25f + annotation.fontSizePoints * .25f,
            )
        }
        is HighlightAnnotation ->
            annotation.rects.takeIf { it.isNotEmpty() }?.let {
                PdfRect(it.minOf { r -> r.left }, it.minOf { r -> r.top }, it.maxOf { r -> r.right }, it.maxOf { r -> r.bottom })
            }
    }
}
