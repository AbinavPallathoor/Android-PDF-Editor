package com.pdfeditor.app.data.pdf

import androidx.test.core.app.ApplicationProvider
import com.pdfeditor.app.domain.DrawStroke
import com.pdfeditor.app.domain.HighlightAnnotation
import com.pdfeditor.app.domain.PdfPoint
import com.pdfeditor.app.domain.PdfRect
import com.pdfeditor.app.domain.SignaturePlacement
import com.pdfeditor.app.domain.TextBoxAnnotation
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@org.robolectric.annotation.Config(application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class PdfBoxDocumentContentTest {
    @get:Rule val temporary = TemporaryFolder()
    private val content = PdfBoxDocumentContent()

    @Before fun setup() = PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())

    // Catches flattening, single-page-only persistence, dropped annotations, and absent AP streams.
    @Test fun roundTripPreservesEditableObjectsOnSeparatePages() =
        runTest {
            val source = fixture()
            val annotations =
                mapOf(
                    0 to
                        listOf(
                            DrawStroke("ink", listOf(PdfPoint(20f, 30f), PdfPoint(90f, 60f)), 0x80123456.toInt(), 3f),
                            TextBoxAnnotation("text", PdfPoint(40f, 90f), "Café Ελληνικά\nremain editable", 14f, 0xFF102030.toInt()),
                        ),
                    2 to
                        listOf(
                            SignaturePlacement(
                                "sign", PdfPoint(80f, 120f),
                                listOf(listOf(PdfPoint(0f, 0f), PdfPoint(20f, 10f))),
                                2f, 0xFF654321.toInt(),
                            ),
                            HighlightAnnotation("mark", listOf(PdfRect(40f, 40f, 130f, 55f)), 0x66FFFF00),
                        ),
                )
            val saved = content.writeAnnotations(source, File(temporary.root, "saved.pdf"), annotations)
            assertEquals(annotations, content.readAnnotations(saved))
            PDDocument.load(saved).use { doc ->
                assertEquals(listOf("Text", "Ink", "FreeText"), doc.getPage(0).annotations.map { it.subtype })
                assertEquals(listOf("Ink", "Highlight"), doc.getPage(2).annotations.map { it.subtype })
                doc.pages.flatMap { it.annotations }.filter { it.subtype != "Text" }.forEach {
                    assertNotNull("saved marks need independent-reader appearances", it.normalAppearanceStream)
                    assertTrue(it.normalAppearanceStream.stream.length > 0)
                }
            }
        }

    // Catches duplicate app marks, removal of foreign notes, and damage to the original bytes.
    @Test fun repeatedSaveReplacesOnlyOwnedAnnotationsAndLeavesSourceUnchanged() =
        runTest {
            val source = fixture()
            val original = source.readBytes()
            val stroke = DrawStroke("ink", listOf(PdfPoint(20f, 30f), PdfPoint(50f, 60f)), -1, 2f)
            val saved = content.writeAnnotations(source, File(temporary.root, "once.pdf"), mapOf(0 to listOf(stroke)))
            val revised = stroke.copy(points = listOf(PdfPoint(100f, 30f), PdfPoint(150f, 60f)))
            val twice = content.writeAnnotations(saved, File(temporary.root, "twice.pdf"), mapOf(2 to listOf(revised)))
            assertArrayEquals(original, source.readBytes())
            assertEquals(mapOf(2 to listOf(revised)), content.readAnnotations(twice))
            PDDocument.load(twice).use { doc ->
                assertEquals(3, doc.numberOfPages)
                assertEquals("foreign note", doc.getPage(0).annotations.single().contents)
                assertEquals(1, doc.getPage(2).annotations.size)
            }
            assertEquals("Hello world\nSecond line", content.extractText(twice, 0).joinToString("") { it.text }.trim())
        }

    // Catches save-to-source aliases before any bytes can be modified.
    @Test fun refusesToOverwriteSource() =
        runTest {
            val source = fixture()
            val original = source.readBytes()
            try {
                content.writeAnnotations(source, File(source.parentFile, "./${source.name}"), emptyMap())
                fail("source must remain immutable")
            } catch (_: IllegalArgumentException) {
                assertArrayEquals(original, source.readBytes())
            }
        }

    // Hand-derived crop coordinates: displayed (20,30) -> crop-relative native (30,20) at 90°.
    @Test fun nativeInkCoordinatesRespectCropAndAllPageRotations() =
        runTest {
            for ((rotation, expected) in listOf(
                0 to floatArrayOf(70f, 470f, 100f, 440f),
                90 to floatArrayOf(80f, 120f, 110f, 150f),
                180 to floatArrayOf(330f, 130f, 300f, 160f),
                270 to floatArrayOf(320f, 480f, 290f, 450f),
            )) {
                val source = fixture(rotation)
                val stroke = DrawStroke("ink", listOf(PdfPoint(20f, 30f), PdfPoint(50f, 60f)), -16777216, 2f)
                val saved = content.writeAnnotations(source, File(temporary.root, "rot$rotation.pdf"), mapOf(0 to listOf(stroke)))
                PDDocument.load(saved).use { doc ->
                    val ink = doc.getPage(0).annotations.last() as com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
                    assertArrayEquals("rotation $rotation", expected, ink.inkList.single(), .01f)
                }
            }
        }

    // Catches off-by-one pages, media/crop confusion, lost spaces/lines, and rotated text hit boxes.
    @Test fun extractedTextHasNaturalSeparatorsAndDisplayedCropBounds() =
        runTest {
            val source = fixture()
            val words = content.extractText(source, 0)
            assertEquals("Hello world\nSecond line", words.joinToString("") { it.text }.trim())
            assertEquals(20f, words.first().bounds.left, .5f)
            assertTrue(words.first().bounds.top in 130f..140f)
            assertTrue(words.first().bounds.bottom in 139f..141f)
            assertTrue(content.extractText(source, 2).isEmpty())
            val rotated = content.extractText(fixture(90), 0).first().bounds
            assertTrue("90 degree text lies by the crop right edge", rotated.left in 259f..261f)
            assertEquals(20f, rotated.top, .5f)
            assertTrue(rotated.bottom > rotated.top)
        }

    // Catches silent replacement of glyphs not present in the bundled font.
    @Test fun unsupportedGlyphFailsClearlyWithoutCreatingDestination() =
        runTest {
            val destination = File(temporary.root, "unsupported.pdf")
            try {
                content.writeAnnotations(
                    fixture(),
                    destination,
                    mapOf(
                        0 to
                            listOf(
                                TextBoxAnnotation("text", PdfPoint(10f, 20f), "漢字", 14f, -16777216),
                            ),
                    ),
                )
                fail("unsupported glyph must be reported")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message.orEmpty().contains("font", ignoreCase = true))
                assertFalse(destination.exists())
            }
        }

    private fun fixture(rotation: Int = 0): File {
        val source = File(temporary.root, "source${rotation}_${System.nanoTime()}.pdf")
        PDDocument().use { doc ->
            repeat(3) {
                doc.addPage(
                    PDPage(PDRectangle(500f, 600f)).apply {
                        cropBox = PDRectangle(50f, 100f, 300f, 400f)
                        this.rotation = rotation
                    },
                )
            }
            val page = doc.getPage(0)
            page.annotations.add(
                PDAnnotationText().apply {
                    contents = "foreign note"
                    rectangle = PDRectangle(60f, 200f, 20f, 20f)
                },
            )
            PDPageContentStream(doc, page).use {
                it.beginText()
                it.setFont(PDType1Font.HELVETICA, 12f)
                it.newLineAtOffset(70f, 360f)
                it.showText("Hello world")
                it.newLineAtOffset(0f, -20f)
                it.showText("Second line")
                it.endText()
            }
            doc.save(source)
        }
        return source
    }
}
