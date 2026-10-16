package com.pdfeditor.app.ui.writing

import com.pdfeditor.app.domain.PdfPoint
import com.pdfeditor.app.domain.PdfRect
import com.pdfeditor.app.domain.TextBoxAnnotation
import org.junit.Assert.assertEquals
import org.junit.Test

class AnnotationSelectionBoundsTest {
    @Test fun `selection uses the full explicitly sized box including empty interior`() {
        val text = TextBoxAnnotation("t", PdfPoint(30f, 50f), "Hi", 20f, -1, widthPoints = 200f, heightPoints = 100f)
        assertEquals(PdfRect(30f, 30f, 230f, 130f), annotationSelectionBounds(text) { value, _ -> value.length * 10f })
    }

    @Test fun `legacy multiline selection uses measured line width and complete line height`() {
        val text = TextBoxAnnotation("t", PdfPoint(30f, 50f), "Hi\r\nWorld", 20f, -1)
        assertEquals(PdfRect(30f, 30f, 80f, 80f), annotationSelectionBounds(text) { value, _ -> value.length * 10f })
    }
}
