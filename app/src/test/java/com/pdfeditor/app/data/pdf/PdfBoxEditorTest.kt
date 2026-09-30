package com.pdfeditor.app.data.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Robolectric provides a working `android.util.Log`, which `PDFMergerUtility.appendDocument`'s AcroForm merge path calls into. */
@RunWith(RobolectricTestRunner::class)
class PdfBoxEditorTest {
    private lateinit var editor: PdfBoxEditor
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        editor = PdfBoxEditor()
        tempDir =
            File.createTempFile("pdfboxeditor", "").apply {
                delete()
                mkdirs()
            }
    }

    /** Builds an N-page PDF with a distinct media box width per page, so page identity survives reordering can be verified. */
    private fun buildPdf(
        name: String,
        pageCount: Int,
        widthStart: Float = 100f,
    ): File {
        val file = File(tempDir, name)
        val document = PDDocument()
        try {
            for (i in 0 until pageCount) {
                document.addPage(PDPage(PDRectangle(widthStart + i, 200f)))
            }
            document.save(file)
        } finally {
            document.close()
        }
        return file
    }

    private fun pageWidths(file: File): List<Float> {
        val document = PDDocument.load(file)
        return try {
            (0 until document.numberOfPages).map { document.getPage(it).mediaBox.width }
        } finally {
            document.close()
        }
    }

    @Test
    fun reorderPages_reversesOrder() =
        runTest {
            val file = buildPdf("reorder.pdf", pageCount = 3)
            editor.reorderPages(file, listOf(2, 0, 1))
            assertEquals(listOf(102f, 100f, 101f), pageWidths(file))
        }

    @Test
    fun deletePages_removesRequestedIndices() =
        runTest {
            val file = buildPdf("delete.pdf", pageCount = 4)
            editor.deletePages(file, setOf(1, 3))
            assertEquals(listOf(100f, 102f), pageWidths(file))
        }

    @Test
    fun appendPages_addsPagesFromOtherFiles() =
        runTest {
            val base = buildPdf("base.pdf", pageCount = 2)
            val extra = buildPdf("extra.pdf", pageCount = 2, widthStart = 300f)
            editor.appendPages(base, listOf(extra))
            assertEquals(listOf(100f, 101f, 300f, 301f), pageWidths(base))
        }
}
