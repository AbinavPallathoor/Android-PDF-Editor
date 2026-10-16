package com.pdfeditor.app.ui.reader

import com.pdfeditor.app.domain.Annotation
import com.pdfeditor.app.domain.DrawStroke
import com.pdfeditor.app.domain.FillTargetKind
import com.pdfeditor.app.domain.HighlightAnnotation
import com.pdfeditor.app.domain.PdfFillTarget
import com.pdfeditor.app.domain.PdfPoint
import com.pdfeditor.app.domain.PdfRect
import com.pdfeditor.app.domain.SignaturePlacement
import com.pdfeditor.app.domain.TextBoxAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FillTargetPlacementTest {
    @Test fun `text starts empty with editable bounds inside the field`() {
        val text = textForFillTarget(target(PdfRect(10f, 20f, 110f, 60f)), -123, "serif", "text")

        assertEquals("text", text.id)
        assertEquals("", text.text)
        assertEquals("", text.sourceText)
        assertEquals("serif", text.fontId)
        assertEquals(-123, text.colorArgb)
        assertEquals(PdfPoint(12f, 36f), text.position)
        assertEquals(14f, text.fontSizePoints, .001f)
        assertEquals(96f, text.widthPoints!!, .001f)
        assertEquals(36f, text.heightPoints!!, .001f)
    }

    @Test fun `text line height fits a shallow field without crossing its bottom`() {
        val text = textForFillTarget(target(PdfRect(10f, 20f, 110f, 30f)), -1, "roboto")

        assertEquals(4.8f, text.fontSizePoints, .001f)
        assertEquals(PdfPoint(12f, 26.8f), text.position)
        assertEquals(6f, text.heightPoints!!, .001f)
        assertTrue(text.fontSizePoints * 1.25f <= text.heightPoints!!)
    }

    @Test fun `very thin fields retain positive text space and internal padding`() {
        val text = textForFillTarget(target(PdfRect(10f, 20f, 110f, 23f)), -1, "roboto")

        assertEquals(1.5f, text.heightPoints!!, .001f)
        assertEquals(1.2f, text.fontSizePoints, .001f)
        assertEquals(20.75f, text.position.y - text.fontSizePoints, .001f)
        assertTrue(text.position.x > 10f)
        assertTrue(text.position.x + text.widthPoints!! < 110f)
    }

    @Test fun `signature preserves aspect and centers canonical points away from zero`() {
        val strokes = listOf(listOf(PdfPoint(20f, -10f), PdfPoint(60f, 10f)))
        val signature = signatureForFillTarget(target(PdfRect(10f, 20f, 110f, 60f)), strokes, -123, "signature")

        assertEquals("signature", signature.id)
        assertEquals(-123, signature.colorArgb)
        assertEquals(strokes, signature.strokes)
        assertEquals(1.8f, signature.scale, .001f)
        assertEquals(PdfPoint(-12f, 40f), signature.position)
        assertPlacedPoint(signature, PdfPoint(20f, -10f), 24f, 22f)
        assertPlacedPoint(signature, PdfPoint(60f, 10f), 96f, 58f)
    }

    @Test fun `wide signature fits a shallow field by its height`() {
        val signature =
            signatureForFillTarget(
                target(PdfRect(10f, 20f, 210f, 30f)),
                listOf(listOf(PdfPoint(-20f, 30f), PdfPoint(80f, 50f))),
                -1,
            )

        assertEquals(.3f, signature.scale, .001f)
        assertPlacedPoint(signature, PdfPoint(-20f, 30f), 95f, 22f)
        assertPlacedPoint(signature, PdfPoint(80f, 50f), 125f, 28f)
    }

    @Test fun `straight signature strokes fit without division by zero`() {
        val horizontal = signatureForFillTarget(target(), listOf(listOf(PdfPoint(5f, 8f), PdfPoint(45f, 8f))), -1)
        val vertical = signatureForFillTarget(target(), listOf(listOf(PdfPoint(5f, 8f), PdfPoint(5f, 28f))), -1)

        assertEquals(2.4f, horizontal.scale, .001f)
        assertPlacedPoint(horizontal, PdfPoint(5f, 8f), 12f, 40f)
        assertPlacedPoint(horizontal, PdfPoint(45f, 8f), 108f, 40f)
        assertEquals(1.8f, vertical.scale, .001f)
        assertPlacedPoint(vertical, PdfPoint(5f, 8f), 60f, 22f)
        assertPlacedPoint(vertical, PdfPoint(5f, 28f), 60f, 58f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty signature is rejected before creating an annotation`() {
        signatureForFillTarget(target(), listOf(emptyList()), -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `stationary signature is rejected before creating an annotation`() {
        signatureForFillTarget(target(), listOf(listOf(PdfPoint(5f, 8f), PdfPoint(5f, 8f))), -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nonfinite signature is rejected before creating an annotation`() {
        signatureForFillTarget(target(), listOf(listOf(PdfPoint(Float.NaN, 8f), PdfPoint(5f, 28f))), -1)
    }

    @Test fun `placed text hides its hint and deleting it makes the hint available again`() {
        val field = target()
        val text = textForFillTarget(field, -1, "roboto")

        assertTrue(availableFillTargets(listOf(field), listOf(text)).isEmpty())
        assertEquals(listOf(field), availableFillTargets(listOf(field), emptyList()))
    }

    @Test fun `reopened legacy text and signatures occupy detected fields`() {
        val textField = target(id = "text")
        val signatureField = target(PdfRect(120f, 20f, 220f, 60f), "signature", FillTargetKind.SIGNATURE)
        val annotations =
            listOf<Annotation>(
                TextBoxAnnotation("old", PdfPoint(15f, 40f), "Name", 14f, -1),
                SignaturePlacement("signed", PdfPoint(125f, 25f), listOf(listOf(PdfPoint(0f, 0f), PdfPoint(60f, 20f))), 1f, -1),
            )

        assertTrue(availableFillTargets(listOf(textField, signatureField), annotations).isEmpty())
    }

    @Test fun `ink occupies a field while highlights and unrelated edge overlaps do not`() {
        val field = target()
        val ink = DrawStroke("ink", listOf(PdfPoint(20f, 30f), PdfPoint(90f, 45f)), -1, 2f)
        val touchingText = TextBoxAnnotation("edge", PdfPoint(108f, 34f), "Other", 14f, -1, 60f, 20f)
        val highlight = HighlightAnnotation("highlight", listOf(field.bounds), -1)

        assertTrue(availableFillTargets(listOf(field), listOf(ink)).isEmpty())
        assertEquals(listOf(field), availableFillTargets(listOf(field), listOf(touchingText, highlight)))
    }

    @Test fun `ink bounds around a field do not hide it when the path stays outside`() {
        val field = target()
        val enclosingInk =
            DrawStroke(
                "outside",
                listOf(PdfPoint(0f, 0f), PdfPoint(0f, 80f), PdfPoint(120f, 80f)),
                -1,
                2f,
            )
        val edgeInk = DrawStroke("edge", listOf(PdfPoint(10f, 20f), PdfPoint(110f, 20f)), -1, 1f)

        assertEquals(listOf(field), availableFillTargets(listOf(field), listOf(enclosingInk, edgeInk)))
    }

    private fun target(
        bounds: PdfRect = PdfRect(10f, 20f, 110f, 60f),
        id: String = "field",
        kind: FillTargetKind = FillTargetKind.TEXT,
    ) = PdfFillTarget(id, bounds, kind)

    private fun assertPlacedPoint(
        signature: SignaturePlacement,
        point: PdfPoint,
        x: Float,
        y: Float,
    ) {
        assertEquals(x, signature.position.x + point.x * signature.scale, .001f)
        assertEquals(y, signature.position.y + point.y * signature.scale, .001f)
    }
}
