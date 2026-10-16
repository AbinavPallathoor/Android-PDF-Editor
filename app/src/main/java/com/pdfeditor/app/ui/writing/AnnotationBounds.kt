package com.pdfeditor.app.ui.writing

import com.pdfeditor.app.domain.Annotation
import com.pdfeditor.app.domain.PdfPoint
import com.pdfeditor.app.domain.SignaturePlacement
import com.pdfeditor.app.domain.TextBoxAnnotation

/** Keep movable annotations reachable within the displayed crop/rotation rectangle. */
fun clampAnnotationToPage(
    annotation: Annotation,
    width: Float,
    height: Float,
): Annotation {
    fun position(
        x: Float,
        y: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): PdfPoint {
        val minX = (-left).coerceAtLeast(0f)
        val minY = (-top).coerceAtLeast(0f)
        return PdfPoint(x.coerceIn(minX, (width - right).coerceAtLeast(minX)), y.coerceIn(minY, (height - bottom).coerceAtLeast(minY)))
    }
    return when (annotation) {
        is SignaturePlacement -> {
            val points = annotation.strokes.flatten()
            if (points.isEmpty()) {
                annotation
            } else {
                annotation.copy(
                    position =
                        position(
                            annotation.position.x,
                            annotation.position.y,
                            points.minOf {
                                it.x
                            } * annotation.scale,
                            points.minOf {
                                it.y
                            } * annotation.scale,
                            points.maxOf { it.x } * annotation.scale,
                            points.maxOf { it.y } * annotation.scale,
                        ),
                )
            }
        }
        is TextBoxAnnotation -> {
            val lines = annotation.text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            annotation.copy(
                position =
                    position(
                        annotation.position.x,
                        annotation.position.y,
                        0f,
                        -annotation.fontSizePoints,
                        annotation.widthPoints ?: (lines.maxOf { it.length } * annotation.fontSizePoints * .6f),
                        (annotation.heightPoints ?: (lines.size * annotation.fontSizePoints * 1.25f)) - annotation.fontSizePoints,
                    ),
            )
        }
        else -> annotation
    }
}
