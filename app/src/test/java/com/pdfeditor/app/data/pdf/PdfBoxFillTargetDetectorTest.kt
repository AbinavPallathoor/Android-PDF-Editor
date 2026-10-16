package com.pdfeditor.app.data.pdf

import androidx.test.core.app.ApplicationProvider
import com.pdfeditor.app.domain.FillTargetKind
import com.pdfeditor.app.domain.PdfRect
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDNonTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@org.robolectric.annotation.Config(application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class PdfBoxFillTargetDetectorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val detector = PdfBoxFillTargetDetector()

    @Before fun setup() = PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())

    // Catches losing real fields, treating signature widgets as text, and modifying source bytes.
    @Test fun findsEmptyTextAndUnsignedSignatureWithoutWritingSource() =
        runTest {
            val source =
                fixture { document, form ->
                    addField(document, form, PDTextField(form).apply { partialName = "Name" }, 0, PDRectangle(30f, 500f, 200f, 30f))
                    addField(
                        document,
                        form,
                        PDSignatureField(form).apply { partialName = "Signature" },
                        0,
                        PDRectangle(30f, 400f, 250f, 40f),
                    )
                }
            val bytes = source.readBytes()
            val targets = detector.detect(source, 0)
            assertEquals(listOf(FillTargetKind.TEXT, FillTargetKind.SIGNATURE), targets.map { it.kind })
            assertEquals(listOf("Name", "Signature"), targets.map { it.label })
            assertEquals(PdfRect(30f, 70f, 230f, 100f), targets.first().bounds)
            assertEquals(targets, detector.detect(source, 0))
            assertArrayEquals(bytes, source.readBytes())
        }

    // Catches proposing already-filled, protected, hidden or invalid form widgets.
    @Test fun skipsFilledReadonlyPasswordHiddenAndInvalidFields() =
        runTest {
            val source =
                fixture { document, form ->
                    listOf("filled", "readonly", "password", "hidden", "invisible", "outside", "zero", "signed").forEachIndexed {
                            index,
                            name,
                        ->
                        val field: PDTerminalField =
                            if (name == "signed") PDSignatureField(form) else PDTextField(form)
                        field.partialName = name
                        when (name) {
                            "filled" -> field.cosObject.setString(COSName.V, "Already entered")
                            "readonly" -> field.isReadOnly = true
                            "password" -> (field as PDTextField).isPassword = true
                            "signed" -> field.cosObject.setItem(COSName.V, COSDictionary())
                        }
                        val bounds =
                            when (name) {
                                "outside" -> PDRectangle(600f, 500f, 100f, 20f)
                                "zero" -> PDRectangle(40f, 300f, 0f, 20f)
                                else -> PDRectangle(40f, 500f - index * 40f, 100f, 20f)
                            }
                        addField(document, form, field, 0, bounds)
                        if (name == "hidden") field.widgets.single().isHidden = true
                        if (name == "invisible") field.widgets.single().isInvisible = true
                    }
                }
            assertTrue(detector.detect(source, 0).isEmpty())
        }

    // Catches field-tree traversal and widgets without /P attached to distinct page annotation lists.
    @Test fun nestedFieldWidgetsBelongOnlyToTheirAnnotatedPage() =
        runTest {
            val source =
                fixture { document, form ->
                    val parent = PDNonTerminalField(form).apply { partialName = "Contact" }
                    val field = PDTextField(form).apply { partialName = "Email" }
                    val widgets =
                        (0..1).map { index ->
                            PDAnnotationWidget().apply {
                                rectangle = PDRectangle(40f, 500f - index * 100f, 150f, 25f)
                                setParent(field)
                                document.getPage(index).annotations.add(this)
                            }
                        }
                    field.widgets = widgets
                    parent.children = listOf(field)
                    form.fields = listOf(parent)
                }
            assertEquals(PdfRect(40f, 75f, 190f, 100f), detector.detect(source, 0).single().bounds)
            assertEquals(PdfRect(40f, 175f, 190f, 200f), detector.detect(source, 1).single().bounds)
            assertTrue(detector.detect(source, 2).isEmpty())
        }

    // Hand-derived bounds catch media-box substitution and every quarter-turn axis/origin error.
    @Test fun widgetBoundsUseRotatedDisplayedCropBox() =
        runTest {
            val expected =
                listOf(
                    0 to PdfRect(20f, 40f, 120f, 70f),
                    90 to PdfRect(330f, 20f, 360f, 120f),
                    180 to PdfRect(180f, 330f, 280f, 360f),
                    270 to PdfRect(40f, 180f, 70f, 280f),
                )
            expected.forEach { (rotation, bounds) ->
                val source =
                    fixture { document, form ->
                        document.getPage(0).apply {
                            cropBox = PDRectangle(50f, 100f, 300f, 400f)
                            this.rotation = rotation
                        }
                        addField(document, form, PDTextField(form).apply { partialName = "Name" }, 0, PDRectangle(70f, 430f, 100f, 30f))
                    }
                assertEquals("rotation $rotation", bounds, detector.detect(source, 0).single().bounds)
            }
        }

    // Catches missing flattened forms and signature lines while retaining a conservative label gate.
    @Test fun labelledPrintedBoxAndSignatureLineProduceFillSuggestions() =
        runTest {
            val source =
                fixture { document, _ ->
                    PDPageContentStream(document, document.getPage(0)).use { stream ->
                        stream.label("Name:", 30f, 512f)
                        stream.addRect(120f, 500f, 200f, 32f)
                        stream.stroke()
                        stream.label("Sign here:", 30f, 422f)
                        stream.moveTo(120f, 420f)
                        stream.lineTo(320f, 420f)
                        stream.stroke()
                    }
                }
            val targets = detector.detect(source, 0)
            assertEquals(listOf(FillTargetKind.TEXT, FillTargetKind.SIGNATURE), targets.map { it.kind })
            assertEquals(PdfRect(120f, 68f, 320f, 100f), targets.first().bounds)
            assertEquals(120f, targets.last().bounds.left, .1f)
            assertEquals(320f, targets.last().bounds.right, .1f)
            assertEquals(180f, targets.last().bounds.bottom, .1f)
            assertTrue(targets.last().bounds.top < 170f)
        }

    // Catches over-eager table/decoration suggestions and offering to overwrite printed answers.
    @Test fun skipsUnlabelledOccupiedFilledAndUnrelatedPrintedGraphics() =
        runTest {
            val source =
                fixture { document, _ ->
                    PDPageContentStream(document, document.getPage(0)).use { stream ->
                        stream.addRect(120f, 500f, 200f, 32f)
                        stream.stroke()
                        stream.label("Name:", 30f, 412f)
                        stream.addRect(120f, 400f, 200f, 32f)
                        stream.stroke()
                        stream.label("Already entered", 130f, 412f)
                        stream.label("Email:", 30f, 312f)
                        stream.addRect(120f, 300f, 200f, 32f)
                        stream.fill()
                        stream.label("Ordinary paragraph", 30f, 212f)
                        stream.moveTo(180f, 200f)
                        stream.lineTo(320f, 200f)
                        stream.stroke()
                    }
                }
            assertTrue(detector.detect(source, 0).isEmpty())
        }

    // Catches duplicated suggestions when a real widget has a printed border and nearby label.
    @Test fun formWidgetsTakePrecedenceOverOverlappingPrintedTargets() =
        runTest {
            val source =
                fixture { document, form ->
                    addField(document, form, PDTextField(form).apply { partialName = "Full name" }, 0, PDRectangle(120f, 500f, 200f, 32f))
                    PDPageContentStream(document, document.getPage(0)).use { stream ->
                        stream.label("Name:", 30f, 512f)
                        stream.addRect(120f, 500f, 200f, 32f)
                        stream.stroke()
                    }
                }
            val target = detector.detect(source, 0).single()
            assertEquals("Full name", target.label)
            assertEquals(PdfRect(120f, 68f, 320f, 100f), target.bounds)
        }

    // Catches applying page rotation twice to graphics or comparing labels in a different space.
    @Test fun printedTargetsAndLabelsShareCropCoordinatesAfterRotation() =
        runTest {
            val expected =
                listOf(
                    0 to PdfRect(100f, 80f, 260f, 110f),
                    90 to PdfRect(290f, 100f, 320f, 260f),
                    180 to PdfRect(40f, 290f, 200f, 320f),
                    270 to PdfRect(80f, 40f, 110f, 200f),
                )
            expected.forEach { (rotation, bounds) ->
                val source =
                    fixture { document, _ ->
                        document.getPage(0).apply {
                            cropBox = PDRectangle(50f, 100f, 300f, 400f)
                            this.rotation = rotation
                        }
                        PDPageContentStream(document, document.getPage(0)).use { stream ->
                            stream.label("Email:", 70f, 400f)
                            stream.addRect(150f, 390f, 160f, 30f)
                            stream.stroke()
                        }
                    }
                assertEquals("rotation $rotation", bounds, detector.detect(source, 0).single().bounds)
            }
        }

    // Catches the printed heuristic bypassing a form field's value or read-only flag.
    @Test fun printedBorderCannotReofferAnIneligibleWidget() =
        runTest {
            val source =
                fixture { document, form ->
                    val field =
                        PDTextField(form).apply {
                            partialName = "Name"
                            isReadOnly = true
                        }
                    addField(document, form, field, 0, PDRectangle(120f, 500f, 200f, 32f))
                    PDPageContentStream(document, document.getPage(0)).use { stream ->
                        stream.label("Name:", 30f, 512f)
                        stream.addRect(120f, 500f, 200f, 32f)
                        stream.stroke()
                    }
                }
            assertTrue(detector.detect(source, 0).isEmpty())
        }

    // Catches suggesting an outline that exists in the stream but is hidden by a clipping path.
    @Test fun clippedPrintedOutlineDoesNotBecomeAVisibleFillHint() =
        runTest {
            val source =
                fixture { document, _ ->
                    PDPageContentStream(document, document.getPage(0)).use { stream ->
                        stream.label("Name:", 30f, 512f)
                        stream.saveGraphicsState()
                        stream.addRect(0f, 0f, 10f, 10f)
                        stream.clip()
                        stream.addRect(120f, 500f, 200f, 32f)
                        stream.stroke()
                        stream.restoreGraphicsState()
                    }
                }
            assertTrue(detector.detect(source, 0).isEmpty())
        }

    // Catches rejecting independently visible form widgets when printed geometry is uncertain.
    @Test fun clippedPrintedGeometryLeavesEligibleFormWidgetsAvailable() =
        runTest {
            val source =
                fixture { document, form ->
                    addField(
                        document,
                        form,
                        PDSignatureField(form).apply { partialName = "Signature" },
                        0,
                        PDRectangle(30f, 400f, 250f, 40f),
                    )
                    PDPageContentStream(document, document.getPage(0)).use { stream ->
                        stream.label("Name:", 30f, 512f)
                        stream.addRect(0f, 0f, 10f, 10f)
                        stream.clip()
                        stream.addRect(120f, 500f, 200f, 32f)
                        stream.stroke()
                    }
                }
            val target = detector.detect(source, 0).single()
            assertEquals(FillTargetKind.SIGNATURE, target.kind)
            assertEquals(PdfRect(30f, 160f, 280f, 200f), target.bounds)
        }

    private fun fixture(build: (PDDocument, PDAcroForm) -> Unit): File {
        val file = temporary.newFile("fixture-${System.nanoTime()}.pdf")
        PDDocument().use { document ->
            repeat(3) { document.addPage(PDPage(PDRectangle(400f, 600f))) }
            val form = PDAcroForm(document)
            document.documentCatalog.acroForm = form
            form.needAppearances = true
            build(document, form)
            document.save(file)
        }
        return file
    }

    private fun addField(
        document: PDDocument,
        form: PDAcroForm,
        field: PDTerminalField,
        pageIndex: Int,
        bounds: PDRectangle,
    ) {
        form.fields.add(field)
        field.widgets.single().apply {
            rectangle = bounds
            page = document.getPage(pageIndex)
            page.annotations.add(this)
        }
    }

    private fun PDPageContentStream.label(
        value: String,
        x: Float,
        y: Float,
    ) {
        beginText()
        setFont(PDType1Font.HELVETICA, 12f)
        newLineAtOffset(x, y)
        showText(value)
        endText()
    }
}
