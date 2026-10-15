package com.pdfeditor.app.data.pdf

import com.pdfeditor.app.domain.Annotation
import com.pdfeditor.app.domain.AnnotationFlattener
import com.pdfeditor.app.domain.DrawStroke
import com.pdfeditor.app.domain.SignaturePlacement
import com.pdfeditor.app.domain.TextBoxAnnotation
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bakes annotations permanently into a PDF's page content streams via PdfBox-Android. Runs as an
 * APPEND content stream (doesn't touch existing page content), so this only ever adds ink - it
 * can't corrupt what was already on the page. [source] is always a private working copy (never the
 * original SAF file - see CLAUDE.md's Reading/Writing session note), so saving back over the same
 * path is safe.
 */
class PdfBoxAnnotationFlattener : AnnotationFlattener {
    override suspend fun flattenAnnotations(
        source: File,
        annotationsByPage: Map<Int, List<Annotation>>,
    ): File =
        withContext(Dispatchers.IO) {
            val document = PDDocument.load(source)
            try {
                for ((pageIndex, annotations) in annotationsByPage) {
                    if (pageIndex !in 0 until document.numberOfPages) continue
                    val page = document.getPage(pageIndex)
                    val pageHeight = page.mediaBox.height
                    val stream = PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)
                    try {
                        for (annotation in annotations) {
                            when (annotation) {
                                is com.pdfeditor.app.domain.HighlightAnnotation -> {
                                    stream.setNonStrokingColor(255, 220, 0)
                                    for (rect in annotation.rects) {
                                        stream.addRect(rect.left, pageHeight - rect.bottom, rect.right - rect.left, rect.bottom - rect.top)
                                        stream.fill()
                                    }
                                }
                                is DrawStroke -> drawStroke(stream, annotation, pageHeight)
                                is TextBoxAnnotation -> drawText(stream, annotation, pageHeight)
                                is SignaturePlacement -> drawSignature(stream, annotation, pageHeight)
                            }
                        }
                    } finally {
                        stream.close()
                    }
                }
                document.save(source)
            } finally {
                document.close()
            }
            source
        }

    private fun rgbComponents(argb: Int): IntArray = intArrayOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    private fun drawStroke(
        stream: PDPageContentStream,
        stroke: DrawStroke,
        pageHeight: Float,
    ) {
        if (stroke.points.size < 2) return
        val (r, g, b) = rgbComponents(stroke.colorArgb)
        stream.setStrokingColor(r, g, b)
        stream.setLineWidth(stroke.strokeWidthPoints)
        val first = stroke.points.first()
        stream.moveTo(first.x, pageHeight - first.y)
        for (point in stroke.points.drop(1)) {
            stream.lineTo(point.x, pageHeight - point.y)
        }
        stream.stroke()
    }

    private fun drawText(
        stream: PDPageContentStream,
        box: TextBoxAnnotation,
        pageHeight: Float,
    ) {
        val (r, g, b) = rgbComponents(box.colorArgb)
        stream.setNonStrokingColor(r, g, b)
        stream.beginText()
        stream.setFont(PDType1Font.HELVETICA, box.fontSizePoints)
        stream.newLineAtOffset(box.position.x, pageHeight - box.position.y)
        stream.showText(box.text)
        stream.endText()
    }

    private fun drawSignature(
        stream: PDPageContentStream,
        signature: SignaturePlacement,
        pageHeight: Float,
    ) {
        val (r, g, b) = rgbComponents(signature.colorArgb)
        stream.setStrokingColor(r, g, b)
        stream.setLineWidth(1.5f)
        for (path in signature.strokes) {
            if (path.size < 2) continue
            val first = path.first()
            stream.moveTo(
                signature.position.x + first.x * signature.scale,
                pageHeight - (signature.position.y + first.y * signature.scale),
            )
            for (point in path.drop(1)) {
                stream.lineTo(
                    signature.position.x + point.x * signature.scale,
                    pageHeight - (signature.position.y + point.y * signature.scale),
                )
            }
            stream.stroke()
        }
    }
}

private operator fun IntArray.component1() = this[0]

private operator fun IntArray.component2() = this[1]

private operator fun IntArray.component3() = this[2]
