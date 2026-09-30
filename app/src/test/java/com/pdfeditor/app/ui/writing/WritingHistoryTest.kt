package com.pdfeditor.app.ui.writing

import com.pdfeditor.app.domain.DrawStroke
import com.pdfeditor.app.domain.PdfPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingHistoryTest {
    private fun stroke(id: String) = DrawStroke(id, listOf(PdfPoint(0f, 0f), PdfPoint(1f, 1f)), colorArgb = 0, strokeWidthPoints = 1f)

    @Test
    fun `starts empty with no undo or redo available`() {
        val history = WritingHistory()

        assertEquals(emptyList<Any>(), history.current)
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun `push adds a new state and enables undo`() {
        val history = WritingHistory()

        history.push(listOf(stroke("a")))

        assertEquals(listOf(stroke("a")), history.current)
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun `undo restores the previous state and enables redo`() {
        val history = WritingHistory()
        history.push(listOf(stroke("a")))
        history.push(listOf(stroke("a"), stroke("b")))

        val result = history.undo()

        assertEquals(listOf(stroke("a")), result)
        assertTrue(history.canRedo)
    }

    @Test
    fun `redo reapplies the undone state`() {
        val history = WritingHistory()
        history.push(listOf(stroke("a")))
        history.push(listOf(stroke("a"), stroke("b")))
        history.undo()

        val result = history.redo()

        assertEquals(listOf(stroke("a"), stroke("b")), result)
        assertFalse(history.canRedo)
    }

    @Test
    fun `pushing after an undo discards the redo branch`() {
        val history = WritingHistory()
        history.push(listOf(stroke("a")))
        history.push(listOf(stroke("a"), stroke("b")))
        history.undo()

        history.push(listOf(stroke("a"), stroke("c")))

        assertEquals(listOf(stroke("a"), stroke("c")), history.current)
        assertFalse(history.canRedo)
    }

    @Test
    fun `undo at the start is a no-op`() {
        val history = WritingHistory()
        history.push(listOf(stroke("a")))
        history.undo()

        val result = history.undo()

        assertEquals(emptyList<Any>(), result)
        assertFalse(history.canUndo)
    }
}
