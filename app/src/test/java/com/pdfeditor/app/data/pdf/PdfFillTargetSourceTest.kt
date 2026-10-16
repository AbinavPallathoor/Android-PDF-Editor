package com.pdfeditor.app.data.pdf

import androidx.test.core.app.ApplicationProvider
import com.pdfeditor.app.domain.FillTargetKind
import com.pdfeditor.app.domain.PdfFillTarget
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@Config(application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class PdfFillTargetSourceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Before fun setup() = PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())

    // Catches per-page copying/reparsing and reading the live base after the snapshot.
    @Test fun oneSnapshotServesEveryPageAndCloseRemovesIt() =
        runBlocking {
            val base = fixture()
            val copies = temporary.newFolder("copies")
            val loaded = mutableListOf<PDDocument>()
            val source =
                PdfFillTargetSource(copies) { document, page ->
                    loaded += document
                    PdfBoxFillTargetDetector().detect(document, page)
                }
            source.snapshot(base)
            source.snapshot(base)
            assertEquals(1, copies.listFiles()!!.size)
            base.delete()
            val first = source.detect(0)
            assertEquals(listOf(FillTargetKind.TEXT), first.map { it.kind })
            assertTrue(source.detect(1).isEmpty())
            assertEquals(1, loaded.distinct().size)
            source.close()
            assertTrue(copies.listFiles()!!.isEmpty())
            assertTrue(source.detect(0).isEmpty())
        }

    // Catches closing a document under a running lookup, or leaking it when close comes first.
    @Test fun closeDuringLookupReleasesAfterItFinishes() =
        runBlocking {
            val copies = temporary.newFolder("copies")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val source =
                PdfFillTargetSource(copies) { document, _ ->
                    entered.complete(Unit)
                    release.await()
                    assertTrue("closed under a running lookup", !document.document.isClosed)
                    emptyList<PdfFillTarget>()
                }
            source.snapshot(fixture())
            val lookup = async(Dispatchers.Default) { source.detect(0) }
            withTimeout(10_000) { entered.await() }
            source.close()
            assertEquals(1, copies.listFiles()!!.size)
            release.complete(Unit)
            withTimeout(10_000) { lookup.await() }
            assertTrue(copies.listFiles()!!.isEmpty())
        }

    // Catches a canceled lookup leaving the shared document unusable or unreleased.
    @Test fun canceledLookupKeepsSourceUsableAndReleasable() =
        runBlocking {
            val copies = temporary.newFolder("copies")
            val entered = CompletableDeferred<Unit>()
            var calls = 0
            val source =
                PdfFillTargetSource(copies) { document, page ->
                    if (calls++ == 0) {
                        entered.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    }
                    PdfBoxFillTargetDetector().detect(document, page)
                }
            source.snapshot(fixture())
            val lookup = async(Dispatchers.Default) { source.detect(0) }
            withTimeout(10_000) { entered.await() }
            lookup.cancel()
            lookup.join()
            assertEquals(1, source.detect(0).size)
            source.close()
            assertTrue(copies.listFiles()!!.isEmpty())
        }

    private fun fixture(): File {
        val file = File(temporary.root, "form-${System.nanoTime()}.pdf")
        PDDocument().use { pdf ->
            val first = PDPage(PDRectangle(300f, 400f))
            pdf.addPage(first)
            pdf.addPage(PDPage(PDRectangle(300f, 400f)))
            val form = PDAcroForm(pdf)
            pdf.documentCatalog.acroForm = form
            val field = PDTextField(form)
            field.partialName = "Name"
            val widget = field.widgets.single()
            widget.rectangle = PDRectangle(30f, 200f, 180f, 30f)
            widget.page = first
            first.annotations.add(widget)
            form.fields.add(field)
            pdf.save(file)
        }
        return file
    }
}
