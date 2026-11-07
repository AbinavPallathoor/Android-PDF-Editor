package com.pdfeditor.app.domain

import kotlinx.serialization.Serializable

/**
 * A point in PDF user-space points, top-left origin with y growing downward - matching how page
 * sizes and [PdfDocumentRenderer] output are already expressed elsewhere in this app. Only ever
 * flipped to PDF's actual bottom-left/y-up convention at the moment of writing, inside the PDFBox
 * flattening code - nothing above that layer needs to know PDF's coordinate convention exists.
 * Serializable so saved signatures (a list of strokes of these) can be persisted as JSON.
 */
@Serializable
data class PdfPoint(val x: Float, val y: Float)

@Serializable
sealed interface Annotation {
    val id: String
}

@Serializable
data class DrawStroke(
    override val id: String,
    val points: List<PdfPoint>,
    val colorArgb: Int,
    val strokeWidthPoints: Float,
) : Annotation

@Serializable
data class TextBoxAnnotation(
    override val id: String,
    val position: PdfPoint,
    val text: String,
    val fontSizePoints: Float,
    val colorArgb: Int,
    val widthPoints: Float? = null,
    val heightPoints: Float? = null,
    val sourceText: String? = null,
    val fontId: String = "roboto",
) : Annotation

/** [strokes] are stored once in a small canonical box (see `WritingViewModel`) and reused at
 * different [position]s/[scale]s rather than re-drawn each time. [strokeWidth] is in the same
 * canonical units, so the line thickens and thins with the signature instead of distorting it.
 * Null is a signature from before widths were stored: it keeps its original fixed line until it
 * is next resized, when that line is baked in as a canonical width. */
@Serializable
data class SignaturePlacement(
    override val id: String,
    val position: PdfPoint,
    val strokes: List<List<PdfPoint>>,
    val scale: Float,
    val colorArgb: Int,
    val strokeWidth: Float? = null,
) : Annotation {
    val canonicalStrokeWidth: Float
        get() = strokeWidth ?: (LEGACY_SIGNATURE_LINE_POINTS / scale.takeIf { it > 0f && it.isFinite() }.let { it ?: 1f })

    /** Drawn and exported line width, in PDF points. */
    val lineWidthPoints: Float get() = canonicalStrokeWidth * scale

    companion object {
        /** Canonical width for new placements: 1.5pt at the default placement scale of 0.65. */
        const val STROKE_WIDTH = 1.5f / 0.65f
        const val LEGACY_SIGNATURE_LINE_POINTS = 1.5f
    }
}

/** Rectangle in displayed crop-box points: top-left origin, after page rotation. */
@Serializable
data class PdfRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

@Serializable
data class HighlightAnnotation(
    override val id: String,
    val rects: List<PdfRect>,
    val colorArgb: Int,
) : Annotation
