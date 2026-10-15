package com.pdfeditor.app.ui.writing

import com.pdfeditor.app.domain.DrawStroke
import com.pdfeditor.app.domain.HighlightAnnotation
import com.pdfeditor.app.domain.PdfPoint
import com.pdfeditor.app.domain.PdfRect
import com.pdfeditor.app.domain.SignaturePlacement
import com.pdfeditor.app.domain.TextBoxAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnnotationHitTestTest {
    // A one-line hit box makes the displayed second/third lines impossible to edit or erase.
    @Test fun multilineTextCanBeSelectedOnItsLastLine() {
        val text = TextBoxAnnotation("text", PdfPoint(30f, 50f), "First\nSecond\nThird", 20f, -1)
        assertEquals(text, hitTestAnnotation(listOf(text), PdfPoint(50f, 100f)))
        assertNull(hitTestAnnotation(listOf(text), PdfPoint(50f, 130f)))
    }

    // Measuring all lines as one long line makes empty space beside short lines selectable.
    @Test fun multilineTextWidthUsesTheLongestLine() {
        val text = TextBoxAnnotation("text", PdfPoint(30f, 50f), "aaa\nbbb\nccc", 20f, -1)
        assertNull(hitTestAnnotation(listOf(text), PdfPoint(120f, 50f)))
    }

    // Vertex-only erasing misses visible ink halfway along a fast/sparsely sampled stroke.
    @Test fun longStrokeCanBeSelectedBetweenSamples() {
        val stroke = DrawStroke("ink", listOf(PdfPoint(10f, 20f), PdfPoint(210f, 20f)), -1, 2f)
        assertEquals(stroke, hitTestAnnotation(listOf(stroke), PdfPoint(110f, 25f)))
        assertNull(hitTestAnnotation(listOf(stroke), PdfPoint(250f, 20f)))
    }

    // Signature hit testing must transform complete segments, including placement and scale.
    @Test fun scaledSignatureCanBeSelectedBetweenSamples() {
        val signature = SignaturePlacement("sign", PdfPoint(40f, 60f), listOf(listOf(PdfPoint(0f, 0f), PdfPoint(100f, 0f))), 2f, -1)
        assertEquals(signature, hitTestAnnotation(listOf(signature), PdfPoint(140f, 65f)))
        assertNull(hitTestAnnotation(listOf(signature), PdfPoint(140f, 90f)))
    }

    // Separate signature strokes must not acquire an invisible connector when hit testing.
    @Test fun separateSignaturePathsDoNotCreateASelectableBridge() {
        val signature =
            SignaturePlacement(
                "sign",
                PdfPoint(40f, 60f),
                listOf(
                    listOf(PdfPoint(0f, 0f), PdfPoint(20f, 0f)),
                    listOf(PdfPoint(200f, 0f), PdfPoint(220f, 0f)),
                ),
                1f,
                -1,
            )
        assertNull(hitTestAnnotation(listOf(signature), PdfPoint(140f, 60f)))
    }

    // Zero-length segments must remain tappable rather than produce a NaN projection.
    @Test fun repeatedPointsAndSinglePointMarksRemainSelectable() {
        val dot = DrawStroke("dot", listOf(PdfPoint(30f, 40f)), -1, 2f)
        val repeated = dot.copy(id = "repeated", points = listOf(PdfPoint(30f, 40f), PdfPoint(30f, 40f)))
        assertEquals(dot, hitTestAnnotation(listOf(dot), PdfPoint(35f, 40f)))
        assertEquals(repeated, hitTestAnnotation(listOf(repeated), PdfPoint(35f, 40f)))
    }

    // Selection must choose the foreground mark while a highlight's empty gap stays untappable.
    @Test fun selectsTopmostOverlappingAnnotationAndRespectsHighlightGaps() {
        val highlight = HighlightAnnotation("highlight", listOf(PdfRect(10f, 10f, 80f, 30f), PdfRect(10f, 60f, 80f, 80f)), -1)
        val foreground = DrawStroke("front", listOf(PdfPoint(10f, 20f), PdfPoint(80f, 20f)), -1, 2f)
        assertEquals(foreground, hitTestAnnotation(listOf(highlight, foreground), PdfPoint(45f, 20f)))
        assertEquals(highlight, hitTestAnnotation(listOf(highlight, foreground), PdfPoint(45f, 70f)))
        assertNull(hitTestAnnotation(listOf(highlight), PdfPoint(45f, 45f)))
    }
}
