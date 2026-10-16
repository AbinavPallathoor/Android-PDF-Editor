package com.pdfeditor.app.ui.writing

import com.pdfeditor.app.domain.DrawStroke
import com.pdfeditor.app.domain.PdfPoint
import com.pdfeditor.app.domain.SignaturePlacement
import com.pdfeditor.app.domain.TextBoxAnnotation
import org.junit.Assert.assertEquals
import org.junit.Test

class AnnotationBoundsTest {
    @Test fun `ink outside the page moves as one path and includes its stroke width`() {
        val ink = DrawStroke("ink", listOf(PdfPoint(-20f, 370f), PdfPoint(80f, 430f)), -1, 10f)
        val clamped = clampAnnotationToPage(ink, 300f, 400f) as DrawStroke
        assertEquals(listOf(PdfPoint(5f, 335f), PdfPoint(105f, 395f)), clamped.points)
        assertEquals(ink.copy(points = clamped.points), clamped)
    }

    @Test fun `empty ink is safe to clamp`() {
        val ink = DrawStroke("empty", emptyList(), -1, 10f)
        assertEquals(ink, clampAnnotationToPage(ink, 300f, 400f))
    }

    @Test fun `moving a signature beyond page edge keeps the complete signature visible`() {
        val sign = SignaturePlacement("s", PdfPoint(290f, 390f), listOf(listOf(PdfPoint(0f, 0f), PdfPoint(200f, 80f))), 1f, -1)
        assertEquals(PdfPoint(100f, 320f), (clampAnnotationToPage(sign, 300f, 400f) as SignaturePlacement).position)
    }

    @Test fun `multiline text baseline leaves every line inside page`() {
        val text = TextBoxAnnotation("t", PdfPoint(290f, 390f), "Hello\nWorld", 20f, -1)
        assertEquals(PdfPoint(240f, 370f), (clampAnnotationToPage(text, 300f, 400f) as TextBoxAnnotation).position)
    }

    @Test fun `moving a resized text box keeps its empty space inside the page`() {
        val text = TextBoxAnnotation("t", PdfPoint(290f, 390f), "Hi", 20f, -1, widthPoints = 180f, heightPoints = 100f)
        val moved = clampAnnotationToPage(text, 300f, 400f) as TextBoxAnnotation
        assertEquals(PdfPoint(120f, 320f), moved.position)
        assertEquals(text.copy(position = moved.position), moved)
    }
}
