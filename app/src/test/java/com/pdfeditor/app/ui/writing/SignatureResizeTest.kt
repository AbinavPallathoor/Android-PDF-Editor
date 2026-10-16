package com.pdfeditor.app.ui.writing

import com.pdfeditor.app.domain.PdfPoint
import com.pdfeditor.app.domain.SignaturePlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignatureResizeTest {
    // Ink spans x 10..110, y 20..60 in canonical units: 100 x 40 at scale 1, placed at page (100, 200).
    private val signature =
        SignaturePlacement(
            "sig",
            PdfPoint(90f, 180f),
            listOf(listOf(PdfPoint(10f, 20f), PdfPoint(110f, 60f))),
            scale = 1f,
            colorArgb = 0,
        )

    private fun ink(s: SignaturePlacement): List<Float> {
        val points = s.strokes.flatten().map { PdfPoint(s.position.x + it.x * s.scale, s.position.y + it.y * s.scale) }
        return listOf(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y })
    }

    private fun assertInk(
        expected: List<Float>,
        actual: SignaturePlacement?,
    ) = expected.zip(ink(actual!!)).forEach { (e, a) -> assertEquals(e, a, .01f) }

    // Catches growing from the wrong anchor or distorting the signature's aspect ratio.
    @Test fun bottomRightGrowsUniformlyFromTopLeft() {
        val resized = resizeSignature(signature, ResizeCorner.BOTTOM_RIGHT, PdfPoint(100f, 40f), 600f, 800f)
        assertEquals(2f, resized!!.scale, .001f)
        assertInk(listOf(100f, 200f, 300f, 280f), resized)
        assertEquals(signature.strokes, resized.strokes)
    }

    // Catches the top-left handle moving the bottom-right corner.
    @Test fun topLeftShrinksTowardFixedBottomRight() {
        assertInk(listOf(150f, 220f, 200f, 240f), resizeSignature(signature, ResizeCorner.TOP_LEFT, PdfPoint(50f, 20f), 600f, 800f))
    }

    // Catches resizing off the page or collapsing the signature to nothing.
    @Test fun sizeIsClampedToPageAndMinimum() {
        assertInk(listOf(100f, 200f, 300f, 280f), resizeSignature(signature, ResizeCorner.BOTTOM_RIGHT, PdfPoint(900f, 900f), 300f, 800f))
        val tiny = resizeSignature(signature, ResizeCorner.BOTTOM_RIGHT, PdfPoint(-500f, -500f), 600f, 800f)
        assertEquals(16f, ink(tiny!!).let { it[2] - it[0] }, .01f)
    }

    // Catches crashes or NaN placements from unusable signatures.
    @Test fun degenerateSignaturesAreRejected() {
        assertNull(resizeSignature(signature.copy(strokes = emptyList()), ResizeCorner.TOP_LEFT, PdfPoint(1f, 1f), 600f, 800f))
        assertNull(
            resizeSignature(
                signature.copy(strokes = listOf(listOf(PdfPoint(5f, 5f)))),
                ResizeCorner.TOP_LEFT,
                PdfPoint(1f, 1f),
                600f,
                800f,
            ),
        )
        assertNull(resizeSignature(signature, ResizeCorner.TOP_LEFT, PdfPoint(Float.NaN, 1f), 600f, 800f))
    }
}
