package com.pdfeditor.app.ui.writing

import com.pdfeditor.app.domain.PdfPoint
import com.pdfeditor.app.domain.SignaturePlacement
import com.pdfeditor.app.domain.TextBoxAnnotation
import org.junit.Assert.assertEquals
import org.junit.Test

class AnnotationBoundsTest {
    @Test fun `moving a signature beyond page edge keeps the complete signature visible`() {
        val sign = SignaturePlacement("s", PdfPoint(290f, 390f), listOf(listOf(PdfPoint(0f, 0f), PdfPoint(200f, 80f))), 1f, -1)
        assertEquals(PdfPoint(100f, 320f), (clampAnnotationToPage(sign, 300f, 400f) as SignaturePlacement).position)
    }

    @Test fun `multiline text baseline leaves every line inside page`() {
        val text = TextBoxAnnotation("t", PdfPoint(290f, 390f), "Hello\nWorld", 20f, -1)
        assertEquals(PdfPoint(240f, 370f), (clampAnnotationToPage(text, 300f, 400f) as TextBoxAnnotation).position)
    }
}
