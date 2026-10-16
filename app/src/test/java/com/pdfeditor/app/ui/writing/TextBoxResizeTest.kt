package com.pdfeditor.app.ui.writing

import com.pdfeditor.app.domain.PdfPoint
import com.pdfeditor.app.domain.PdfRect
import com.pdfeditor.app.domain.TextBoxAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TextBoxResizeTest {
    private val annotation =
        TextBoxAnnotation(
            id = "text",
            position = PdfPoint(50f, 70f),
            text = "abcdefghij",
            fontSizePoints = 20f,
            colorArgb = 0xff123456.toInt(),
            widthPoints = 100f,
            heightPoints = 100f,
        )
    private val original = PdfRect(50f, 50f, 150f, 150f)
    private val reflow: (String, Float) -> String = { raw, width ->
        raw.split('\n').flatMap { it.chunked((width / 10f).toInt().coerceAtLeast(1)).ifEmpty { listOf("") } }.joinToString("\n")
    }

    @Test fun `each corner keeps its opposite corner fixed when the page has space`() {
        val cases =
            listOf(
                ResizeCorner.TOP_LEFT to PdfRect(30f, 30f, 150f, 150f),
                ResizeCorner.TOP_RIGHT to PdfRect(50f, 30f, 130f, 150f),
                ResizeCorner.BOTTOM_LEFT to PdfRect(30f, 50f, 150f, 130f),
                ResizeCorner.BOTTOM_RIGHT to PdfRect(50f, 50f, 130f, 130f),
            )
        cases.forEach { (corner, expected) ->
            val resized = resizeTextBox(annotation, original, corner, PdfPoint(-20f, -20f), 300f, 400f, reflow)!!
            assertEquals(expected, annotationSelectionBounds(resized) { text, _ -> text.length * 10f })
            assertEquals(annotation.fontSizePoints, resized.fontSizePoints, 0f)
            assertEquals(annotation.colorArgb, resized.colorArgb)
            assertEquals(annotation.id, resized.id)
            assertEquals(annotation.fontId, resized.fontId)
        }
    }

    @Test fun `narrowing reflows the original source and widening removes only automatic wraps`() {
        val source = annotation.copy(text = "abcde\nfghij\nkl", sourceText = "abcdefghij\nkl")
        val narrow = resizeTextBox(source, original, ResizeCorner.BOTTOM_RIGHT, PdfPoint(-50f, 0f), 300f, 400f, reflow)!!
        assertEquals("abcde\nfghij\nkl", narrow.text)
        val bounds = annotationSelectionBounds(narrow) { text, _ -> text.length * 10f }!!
        val wide = resizeTextBox(narrow, bounds, ResizeCorner.BOTTOM_RIGHT, PdfPoint(50f, 0f), 300f, 400f, reflow)!!
        assertEquals("abcdefghij\nkl", wide.text)
        assertEquals("abcdefghij\nkl", wide.sourceText)
    }

    @Test fun `shrinking height stops before the final line descender leaves the box`() {
        val resized = resizeTextBox(annotation, original, ResizeCorner.BOTTOM_RIGHT, PdfPoint(-50f, -1000f), 300f, 400f, reflow)!!
        assertEquals("abcde\nfghij", resized.text)
        assertEquals(50f, resized.heightPoints!!, 0f)
        assertEquals(PdfRect(50f, 50f, 100f, 100f), annotationSelectionBounds(resized) { text, _ -> text.length * 10f })
    }

    @Test fun `dragging through the opposite corner keeps a usable minimum width`() {
        val resized = resizeTextBox(annotation, original, ResizeCorner.TOP_LEFT, PdfPoint(1000f, 0f), 300f, 400f, reflow)!!
        assertEquals(40f, resized.widthPoints!!, 0f)
        assertEquals(150f, resized.position.x + resized.widthPoints!!, 0f)
        assertEquals("abcdefghij", resized.sourceText)
    }

    @Test fun `dragging out of the page clamps all four box edges`() {
        val topLeft = resizeTextBox(annotation, original, ResizeCorner.TOP_LEFT, PdfPoint(-1000f, -1000f), 300f, 400f, reflow)!!
        assertEquals(PdfRect(0f, 0f, 150f, 150f), annotationSelectionBounds(topLeft) { text, _ -> text.length * 10f })
        val bottomRight = resizeTextBox(annotation, original, ResizeCorner.BOTTOM_RIGHT, PdfPoint(1000f, 1000f), 300f, 400f, reflow)!!
        assertEquals(PdfRect(50f, 50f, 300f, 400f), annotationSelectionBounds(bottomRight) { text, _ -> text.length * 10f })
    }

    @Test fun `newly wrapped lines shift a bottom anchored box upward only when needed`() {
        val nearBottom = annotation.copy(position = PdfPoint(50f, 120f), heightPoints = 50f)
        val resized =
            resizeTextBox(
                nearBottom,
                PdfRect(50f, 100f, 150f, 150f),
                ResizeCorner.BOTTOM_RIGHT,
                PdfPoint(-60f, 0f),
                300f,
                160f,
                reflow,
            )!!
        assertEquals("abcd\nefgh\nij", resized.text)
        assertEquals(75f, resized.heightPoints!!, 0f)
        assertEquals(105f, resized.position.y, 0f)
        assertEquals(160f, resized.position.y - resized.fontSizePoints + resized.heightPoints!!, 0f)
    }

    @Test fun `top corner moves the opposite edge only if content cannot fit above it`() {
        val resized =
            resizeTextBox(
                annotation,
                PdfRect(50f, 0f, 150f, 25f),
                ResizeCorner.TOP_RIGHT,
                PdfPoint(-50f, 0f),
                300f,
                400f,
                reflow,
            )!!
        assertEquals(20f, resized.position.y, 0f)
        assertEquals(50f, resized.heightPoints!!, 0f)
    }

    @Test fun `returns no preview if the narrowed text is taller than the entire page`() {
        val source = annotation.copy(text = "a".repeat(100))
        assertNull(resizeTextBox(source, original, ResizeCorner.BOTTOM_RIGHT, PdfPoint(-60f, 0f), 300f, 200f, reflow))
        assertNotNull(resizeTextBox(source, original, ResizeCorner.BOTTOM_RIGHT, PdfPoint(100f, 0f), 300f, 200f, reflow))
    }

    @Test fun `invalid page or pointer geometry cannot produce a corrupt annotation`() {
        assertNull(resizeTextBox(annotation, original, ResizeCorner.BOTTOM_RIGHT, PdfPoint(Float.NaN, 0f), 300f, 400f, reflow))
        assertNull(resizeTextBox(annotation, original, ResizeCorner.BOTTOM_RIGHT, PdfPoint(0f, 0f), 0f, 400f, reflow))
    }
}
