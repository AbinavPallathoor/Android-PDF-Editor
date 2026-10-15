package com.pdfeditor.app.data.cache

import com.pdfeditor.app.domain.DocumentEdits
import com.pdfeditor.app.domain.DocumentPage
import com.pdfeditor.app.domain.DocumentSnapshot
import com.pdfeditor.app.domain.DrawStroke
import com.pdfeditor.app.domain.HighlightAnnotation
import com.pdfeditor.app.domain.PdfPoint
import com.pdfeditor.app.domain.PdfRect
import com.pdfeditor.app.domain.SignaturePlacement
import com.pdfeditor.app.domain.TextBoxAnnotation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DocumentDraftStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `multipage draft recovers page mapping annotations and saved content`() =
        runBlocking {
            val file = File(temporaryFolder.root, "document/draft.json")
            val saved = DocumentSnapshot(listOf(DocumentPage("a", 0), DocumentPage("b", 1)))
            val edits = DocumentEdits(saved)
            edits.add("a", DrawStroke("stroke", listOf(PdfPoint(1f, 2f)), -1, 2f))
            edits.add("b", TextBoxAnnotation("text", PdfPoint(3f, 4f), "hello", 12f, -1))
            edits.add("a", SignaturePlacement("signature", PdfPoint(5f, 6f), listOf(listOf(PdfPoint(7f, 8f))), 1f, -1))
            edits.add("b", HighlightAnnotation("highlight", listOf(PdfRect(1f, 2f, 3f, 4f)), -256))
            edits.reorder(listOf(1, 0))
            val draft = DocumentDraft("base.pdf", edits.current, saved)
            DocumentDraftStore(file).save(draft)

            val recovered = DocumentDraftStore(file).load()
            assertNotNull(recovered)
            assertEquals(draft, recovered)
            val restored = DocumentEdits(recovered!!.current, recovered.saved)
            assertTrue(restored.isDirty)
            assertEquals("b", restored.current.pages.first().id)
            assertEquals(listOf("text", "highlight"), restored.annotationsAt(0).map { it.id })
            assertFalse(restored.canUndo)

            restored.markSaved(restored.current)
            DocumentDraftStore(file).save(DocumentDraft("base.pdf", restored.current, restored.current))
            val clean = DocumentDraftStore(file).load()!!
            assertFalse(DocumentEdits(clean.current, clean.saved).isDirty)
            assertEquals(listOf("draft.json"), file.parentFile!!.list()!!.toList())
        }

    @Test
    fun `missing draft returns null but malformed draft is surfaced`() =
        runBlocking {
            val file = File(temporaryFolder.root, "draft.json")
            val store = DocumentDraftStore(file)
            assertNull(store.load())
            file.writeText("{unfinished")
            assertThrows(Exception::class.java) { runBlocking { store.load() } }
            assertEquals("{unfinished", file.readText())
        }

    @Test
    fun `draft cannot reference files outside its private directory`() =
        runBlocking {
            val file = File(temporaryFolder.root, "draft.json")
            file.writeText(
                """
                {
                    "baseFileName":"../outside.pdf",
                    "current":{"pages":[{"id":"p","sourceIndex":0}]},
                    "saved":{"pages":[{"id":"p","sourceIndex":0}]}
                }
                """.trimIndent(),
            )
            assertThrows(IllegalArgumentException::class.java) { runBlocking { DocumentDraftStore(file).load() } }
            Unit
        }
}
