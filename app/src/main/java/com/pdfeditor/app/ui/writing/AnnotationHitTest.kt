package com.pdfeditor.app.ui.writing

import com.pdfeditor.app.domain.Annotation
import com.pdfeditor.app.domain.DrawStroke
import com.pdfeditor.app.domain.HighlightAnnotation
import com.pdfeditor.app.domain.PdfPoint
import com.pdfeditor.app.domain.SignaturePlacement
import com.pdfeditor.app.domain.TextBoxAnnotation
import kotlin.math.hypot

/** Radius, in PDF points, within which a tap is considered "on" an annotation. */
private const val HIT_RADIUS_POINTS = 14f

/**
 * Finds the topmost (last-drawn) annotation under [point], or null. Pure PDF-point-space math so
 * it's usable from a ViewModel and unit-testable without Compose.
 */
fun hitTestAnnotation(
    annotations: List<Annotation>,
    point: PdfPoint,
): Annotation? = annotations.lastOrNull { annotation -> isHit(annotation, point) }

private fun isHit(
    annotation: Annotation,
    point: PdfPoint,
): Boolean =
    when (annotation) {
        is TextBoxAnnotation -> {
            val lines = annotation.text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            val estimatedWidth = lines.maxOf { it.length } * annotation.fontSizePoints * 0.6f
            val top = annotation.position.y - annotation.fontSizePoints - HIT_RADIUS_POINTS
            val bottom = annotation.position.y + (lines.size - 1) * annotation.fontSizePoints * 1.25f + HIT_RADIUS_POINTS
            val xRange = (annotation.position.x - HIT_RADIUS_POINTS)..(annotation.position.x + estimatedWidth + HIT_RADIUS_POINTS)
            point.x in xRange && point.y in top..bottom
        }
        is HighlightAnnotation -> annotation.rects.any { point.x in it.left..it.right && point.y in it.top..it.bottom }
        is DrawStroke -> hitPath(annotation.points, point)
        is SignaturePlacement -> {
            annotation.strokes.any { stroke ->
                hitPath(
                    stroke.map {
                        PdfPoint(annotation.position.x + it.x * annotation.scale, annotation.position.y + it.y * annotation.scale)
                    },
                    point,
                )
            }
        }
    }

private fun distance(
    a: PdfPoint,
    b: PdfPoint,
): Float = hypot(a.x - b.x, a.y - b.y)

/** Preserve disjoint paths; only adjacent vertices in the same stroke form visible segments. */
private fun hitPath(
    points: List<PdfPoint>,
    point: PdfPoint,
): Boolean {
    if (points.size == 1) return distance(points.first(), point) <= HIT_RADIUS_POINTS
    return points.zipWithNext().any { (start, end) ->
        val dx = end.x - start.x
        val dy = end.y - start.y
        val lengthSquared = dx * dx + dy * dy
        val fraction =
            if (lengthSquared == 0f) {
                0f
            } else {
                (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared).coerceIn(0f, 1f)
            }
        distance(PdfPoint(start.x + fraction * dx, start.y + fraction * dy), point) <= HIT_RADIUS_POINTS
    }
}
