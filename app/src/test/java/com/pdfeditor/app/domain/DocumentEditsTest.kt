package com.pdfeditor.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentEditsTest {
    private val a = DrawStroke("a", listOf(PdfPoint(10f, 20f), PdfPoint(30f, 40f)), -16777216, 2f)

    private fun document() = DocumentEdits(DocumentSnapshot(listOf(DocumentPage("p0", 0), DocumentPage("p1", 1), DocumentPage("p2", 2))))

    @Test fun `live text is recoverable after every keystroke and undoes as one edit`() {
        val doc = document()
        val text = TextBoxAnnotation("text", PdfPoint(20f, 40f), "H", 14f, -16777216)
        doc.editText("p0", text, "typing-1")
        assertEquals("H", (doc.annotationsAt(0).single() as TextBoxAnnotation).text)
        doc.editText("p0", text.copy(text = "Hello"), "typing-1")
        doc.undo()
        assertTrue(doc.annotationsAt(0).isEmpty())
        assertFalse(doc.isDirty)
        doc.redo()
        assertEquals("Hello", (doc.annotationsAt(0).single() as TextBoxAnnotation).text)
    }

    @Test fun `text sessions do not absorb other edits and clearing text removes its object`() {
        val doc = document()
        val text = TextBoxAnnotation("text", PdfPoint(20f, 40f), "First", 14f, -16777216)
        doc.editText("p0", text, "typing-1")
        doc.add("p1", a)
        doc.editText("p0", text.copy(text = "Second"), "typing-2")
        doc.editText("p0", text.copy(text = ""), "typing-2")
        assertTrue(doc.annotationsAt(0).isEmpty())
        doc.undo()
        assertEquals("First", (doc.annotationsAt(0).single() as TextBoxAnnotation).text)
        assertEquals(a, doc.annotationsAt(1).single())
        doc.undo()
        assertTrue(doc.annotationsAt(1).isEmpty())
    }

    @Test fun `annotations follow pages through reorder delete undo and redo`() {
        val doc = document()
        doc.add("p0", a)
        doc.add("p2", a.copy(id = "b"))
        doc.reorder(listOf(2, 0, 1))
        assertEquals("p2", doc.current.pages[0].id)
        assertEquals("b", doc.annotationsAt(0).single().id)
        doc.delete(1)
        assertEquals(listOf("p2", "p1"), doc.current.pages.map { it.id })
        doc.undo()
        assertEquals("a", doc.annotationsAt(1).single().id)
        doc.redo()
        assertEquals(listOf(2, 1), doc.current.pages.map { it.sourceIndex })
    }

    @Test fun `saved snapshot tracks exact save and leaves subsequent changes dirty`() {
        val doc = document()
        doc.add("p0", a)
        val exporting = doc.current
        doc.add("p1", a.copy(id = "b"))
        doc.markSaved(exporting)
        assertTrue(doc.isDirty)
        doc.undo()
        assertFalse(doc.isDirty)
        doc.redo()
        assertTrue(doc.isDirty)
    }

    @Test fun `reject invalid reorder and deleting final page without losing content`() {
        val doc = document()
        assertThrows(IllegalArgumentException::class.java) { doc.reorder(listOf(0, 0, 1)) }
        doc.delete(2)
        doc.delete(1)
        assertThrows(IllegalArgumentException::class.java) { doc.delete(0) }
        assertEquals("p0", doc.current.pages.single().id)
    }

    @Test fun `append undo and branching edit preserve page identities and clear redo`() {
        val doc = document()
        doc.append(listOf(DocumentPage("appended", 8)))
        doc.add("appended", a)
        doc.undo()
        doc.undo()
        assertEquals(3, doc.current.pages.size)
        doc.redo()
        assertEquals(DocumentPage("appended", 8), doc.current.pages.last())
        doc.add("p1", a)
        assertFalse(doc.canRedo)
        assertTrue(doc.annotationsAt(3).isEmpty())
    }

    @Test fun `annotation replacement and removal are reversible and returning to saved content is clean`() {
        val doc = document()
        doc.add("p0", a)
        doc.markSaved(doc.current)
        doc.replace("p0", a.copy(strokeWidthPoints = 7f))
        assertTrue(doc.isDirty)
        assertEquals(7f, (doc.annotationsAt(0).single() as DrawStroke).strokeWidthPoints)
        doc.undo()
        assertFalse(doc.isDirty)
        doc.remove("p0", "a")
        assertTrue(doc.annotationsAt(0).isEmpty())
        doc.undo()
        assertFalse(doc.isDirty)
    }

    @Test fun `no op edits do not create undo entries`() {
        val doc = document()
        doc.reorder(listOf(0, 1, 2))
        doc.append(emptyList())
        doc.remove("p0", "absent")
        assertFalse(doc.canUndo)
        assertFalse(doc.isDirty)
    }

    @Test fun `invalid page identities and annotation references leave history intact`() {
        val doc = document()
        assertThrows(IllegalArgumentException::class.java) { doc.append(listOf(DocumentPage("p0", 3))) }
        assertThrows(IllegalArgumentException::class.java) { doc.add("absent", a) }
        assertThrows(IllegalArgumentException::class.java) { doc.replace("p0", a) }
        assertFalse(doc.canUndo)
        assertFalse(doc.isDirty)
    }

    @Test fun `appended pages and their imported annotations undo and redo together`() {
        val doc = document()
        val original = doc.current
        val pages = listOf(DocumentPage("append0", 8), DocumentPage("append1", 9))
        doc.append(pages, mapOf("append0" to listOf(a), "append1" to listOf(a.copy(id = "b"))))
        val appended = doc.current

        doc.undo()
        assertEquals(original, doc.current)
        assertFalse(doc.canUndo)
        assertFalse(doc.isDirty)
        doc.redo()
        assertEquals(appended, doc.current)
        assertEquals(listOf(8, 9), doc.current.pages.takeLast(2).map { it.sourceIndex })
        assertEquals("a", doc.annotationsAt(3).single().id)
        assertEquals("b", doc.annotationsAt(4).single().id)
    }

    @Test fun `append annotation keys cannot overwrite existing pages`() {
        val doc = document()
        assertThrows(IllegalArgumentException::class.java) {
            doc.append(listOf(DocumentPage("appended", 8)), mapOf("p0" to listOf(a)))
        }
        assertFalse(doc.isDirty)
        assertFalse(doc.canUndo)
    }

    @Test fun `bulk delete and its annotations undo together`() {
        val doc = document()
        doc.add("p0", a)
        doc.add("p2", a.copy(id = "b"))
        val original = doc.current
        doc.delete(setOf(0, 1))
        val deleted = doc.current
        assertEquals(listOf(DocumentPage("p2", 2)), deleted.pages)
        assertEquals(setOf("p2"), deleted.annotations.keys)
        doc.undo()
        assertEquals(original, doc.current)
        doc.redo()
        assertEquals(deleted, doc.current)
    }

    @Test fun `invalid bulk deletion is rejected before any mutation`() {
        val doc = document()
        val original = doc.current
        assertThrows(IllegalArgumentException::class.java) { doc.delete(setOf(0, 1, 2)) }
        assertThrows(IllegalArgumentException::class.java) { doc.delete(setOf(0, 3)) }
        assertEquals(original, doc.current)
        assertFalse(doc.isDirty)
        assertFalse(doc.canUndo)
    }
}
