package com.pdfeditor.app.ui.writing

import com.pdfeditor.app.domain.Annotation
import com.pdfeditor.app.domain.DrawStroke
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
            val estimatedWidth = annotation.text.length * annotation.fontSizePoints * 0.6f
            val top = annotation.position.y - annotation.fontSizePoints - HIT_RADIUS_POINTS
            val bottom = annotation.position.y + HIT_RADIUS_POINTS
            val xRange = (annotation.position.x - HIT_RADIUS_POINTS)..(annotation.position.x + estimatedWidth + HIT_RADIUS_POINTS)
            point.x in xRange && point.y in top..bottom
        }
        is DrawStroke -> annotation.points.any { distance(it, point) <= HIT_RADIUS_POINTS }
        is SignaturePlacement -> {
            val allPoints =
                annotation.strokes.flatten().map {
                    PdfPoint(annotation.position.x + it.x * annotation.scale, annotation.position.y + it.y * annotation.scale)
                }
            allPoints.any { distance(it, point) <= HIT_RADIUS_POINTS }
        }
    }

private fun distance(
    a: PdfPoint,
    b: PdfPoint,
): Float = hypot(a.x - b.x, a.y - b.y)
