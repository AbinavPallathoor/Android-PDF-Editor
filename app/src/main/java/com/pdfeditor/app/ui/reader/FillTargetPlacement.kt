package com.pdfeditor.app.ui.reader

import com.pdfeditor.app.domain.Annotation
import com.pdfeditor.app.domain.DrawStroke
import com.pdfeditor.app.domain.HighlightAnnotation
import com.pdfeditor.app.domain.PdfFillTarget
import com.pdfeditor.app.domain.PdfPoint
import com.pdfeditor.app.domain.PdfRect
import com.pdfeditor.app.domain.SignaturePlacement
import com.pdfeditor.app.domain.TextBoxAnnotation
import com.pdfeditor.app.ui.writing.annotationSelectionBounds
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** Start a regular editable text box; even an empty box occupies its suggested field. */
internal fun textForFillTarget(
    target: PdfFillTarget,
    colorArgb: Int,
    fontId: String,
    id: String = UUID.randomUUID().toString(),
): TextBoxAnnotation {
    val bounds = target.interior()
    val height = bounds.bottom - bounds.top
    val fontSize = min(14f, height / 1.25f)
    return TextBoxAnnotation(
        id = id,
        position = PdfPoint(bounds.left, bounds.top + fontSize),
        text = "",
        fontSizePoints = fontSize,
        colorArgb = colorArgb,
        widthPoints = bounds.right - bounds.left,
        heightPoints = height,
        sourceText = "",
        fontId = fontId,
    )
}

/** Keep the saved canonical strokes intact and fit their actual extents inside the field. */
internal fun signatureForFillTarget(
    target: PdfFillTarget,
    strokes: List<List<PdfPoint>>,
    colorArgb: Int,
    id: String = UUID.randomUUID().toString(),
): SignaturePlacement {
    val points = strokes.flatten()
    require(points.isNotEmpty() && points.all { it.x.isFinite() && it.y.isFinite() }) { "The signature has no valid strokes" }
    val left = points.minOf { it.x }
    val top = points.minOf { it.y }
    val width = points.maxOf { it.x } - left
    val height = points.maxOf { it.y } - top
    require(width.isFinite() && height.isFinite() && (width > 0f || height > 0f)) { "The signature has no visible strokes" }
    val bounds = target.interior()
    val availableWidth = bounds.right - bounds.left
    val availableHeight = bounds.bottom - bounds.top
    val scale =
        min(
            if (width > 0f) availableWidth / width else Float.POSITIVE_INFINITY,
            if (height > 0f) availableHeight / height else Float.POSITIVE_INFINITY,
        )
    require(scale.isFinite() && scale > 0f) { "The signature cannot fit inside this field" }
    return SignaturePlacement(
        id = id,
        position =
            PdfPoint(
                bounds.left + (availableWidth - width * scale) / 2f - left * scale,
                bounds.top + (availableHeight - height * scale) / 2f - top * scale,
            ),
        strokes = strokes,
        scale = scale,
        colorArgb = colorArgb,
    )
}

/** Recompute from the current page models so deleting/undoing an answer restores its hint. */
internal fun availableFillTargets(
    targets: List<PdfFillTarget>,
    annotations: List<Annotation>,
): List<PdfFillTarget> =
    targets.filter { target ->
        annotations.none { annotation ->
            when (annotation) {
                is HighlightAnnotation -> false
                is DrawStroke -> inkOccupiesTarget(annotation, target.bounds)
                else -> {
                    // Older reopened text may lack explicit dimensions. Use the same fallback
                    // geometry as the editor's bounds clamp without requiring an Android font.
                    val bounds = annotationSelectionBounds(annotation) { text, size -> text.length * size * .6f }
                    bounds != null && substantiallyOverlaps(target.bounds, bounds)
                }
            }
        }
    }

private fun PdfFillTarget.interior(): PdfRect {
    val width = bounds.right - bounds.left
    val height = bounds.bottom - bounds.top
    require(width.isFinite() && height.isFinite() && width > 0f && height > 0f) { "The field has no usable area" }
    // Ordinary fields get 2pt padding; a narrow field still keeps half its space usable.
    val padding = min(2f, min(width, height) / 4f)
    return PdfRect(bounds.left + padding, bounds.top + padding, bounds.right - padding, bounds.bottom - padding)
}

private fun substantiallyOverlaps(
    target: PdfRect,
    annotation: PdfRect,
): Boolean {
    val width = min(target.right, annotation.right) - max(target.left, annotation.left)
    val height = min(target.bottom, annotation.bottom) - max(target.top, annotation.top)
    if (width <= 0f || height <= 0f) return false
    val targetArea = (target.right - target.left) * (target.bottom - target.top)
    val annotationArea = (annotation.right - annotation.left) * (annotation.bottom - annotation.top)
    val smallerArea = min(targetArea, annotationArea)
    return smallerArea > 0f && width * height >= smallerArea * .5f
}

private fun inkOccupiesTarget(
    ink: DrawStroke,
    target: PdfRect,
): Boolean {
    // A broad stroke bounding box may enclose a field without ink entering it at all.
    // Check the path through the field's interior and ignore incidental edge contact.
    val insetX = (target.right - target.left) * .1f
    val insetY = (target.bottom - target.top) * .1f
    val pad = ink.strokeWidthPoints.coerceAtLeast(0f) / 2f
    val interior = PdfRect(target.left + insetX - pad, target.top + insetY - pad, target.right - insetX + pad, target.bottom - insetY + pad)
    return ink.points.zipWithNext().any { (a, b) -> segmentCrosses(a, b, interior) } ||
        ink.points.any { it.x >= interior.left && it.x <= interior.right && it.y >= interior.top && it.y <= interior.bottom }
}

private fun segmentCrosses(
    a: PdfPoint,
    b: PdfPoint,
    bounds: PdfRect,
): Boolean {
    var lower = 0f
    var upper = 1f

    fun clip(
        start: Float,
        delta: Float,
        min: Float,
        max: Float,
    ): Boolean {
        if (delta == 0f) return start in min..max
        val first = (min - start) / delta
        val second = (max - start) / delta
        lower = max(lower, min(first, second))
        upper = min(upper, max(first, second))
        return lower <= upper
    }
    return clip(a.x, b.x - a.x, bounds.left, bounds.right) && clip(a.y, b.y - a.y, bounds.top, bounds.bottom)
}
