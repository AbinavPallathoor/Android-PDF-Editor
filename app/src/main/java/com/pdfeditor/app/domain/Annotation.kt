package com.pdfeditor.app.domain

/**
 * A point in PDF user-space points, top-left origin with y growing downward - matching how page
 * sizes and [PdfDocumentRenderer] output are already expressed elsewhere in this app. Only ever
 * flipped to PDF's actual bottom-left/y-up convention at the moment of writing, inside the PDFBox
 * flattening code - nothing above that layer needs to know PDF's coordinate convention exists.
 */
data class PdfPoint(val x: Float, val y: Float)

sealed interface Annotation {
    val id: String
}

data class DrawStroke(
    override val id: String,
    val points: List<PdfPoint>,
    val colorArgb: Int,
    val strokeWidthPoints: Float,
) : Annotation

data class TextBoxAnnotation(
    override val id: String,
    val position: PdfPoint,
    val text: String,
    val fontSizePoints: Float,
    val colorArgb: Int,
) : Annotation

/** [strokes] are stored once in a small canonical box (see `WritingViewModel`) and reused at
 * different [position]s/[scale]s rather than re-drawn each time. */
data class SignaturePlacement(
    override val id: String,
    val position: PdfPoint,
    val strokes: List<List<PdfPoint>>,
    val scale: Float,
    val colorArgb: Int,
) : Annotation
