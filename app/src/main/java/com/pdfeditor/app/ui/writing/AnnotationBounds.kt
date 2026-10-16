package com.pdfeditor.app.ui.writing

import com.pdfeditor.app.domain.Annotation
import com.pdfeditor.app.domain.DrawStroke
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
        is DrawStroke -> {
            if (annotation.points.isEmpty()) {
                annotation
            } else {
                val pad = annotation.strokeWidthPoints / 2f
                val left = annotation.points.minOf { it.x } - pad
                val top = annotation.points.minOf { it.y } - pad
                val right = annotation.points.maxOf { it.x } + pad
                val bottom = annotation.points.maxOf { it.y } + pad
                // Unlike text/signatures, an ink path stores absolute page points.
                // Clamp one translation so the path retains its shape and stroke width.
                val dx = 0f.coerceIn(-left, (width - right).coerceAtLeast(-left))
                val dy = 0f.coerceIn(-top, (height - bottom).coerceAtLeast(-top))
                annotation.copy(points = annotation.points.map { PdfPoint(it.x + dx, it.y + dy) })
            }
        }
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
