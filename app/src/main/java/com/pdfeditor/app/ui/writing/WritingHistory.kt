package com.pdfeditor.app.ui.writing

import com.pdfeditor.app.domain.Annotation

/**
 * Linear undo/redo history over a page's full annotation list. Simplest correct model for v1
 * scale (a handful of annotations per page): each edit pushes a full snapshot rather than an
 * inverse-operation stack, which is trivial to reason about and to unit test.
 */
class WritingHistory(initial: List<Annotation> = emptyList()) {
    private val states = mutableListOf(initial)
    private var cursor = 0

    val current: List<Annotation> get() = states[cursor]
    val canUndo: Boolean get() = cursor > 0
    val canRedo: Boolean get() = cursor < states.lastIndex

    fun push(newState: List<Annotation>) {
        while (states.lastIndex > cursor) states.removeAt(states.lastIndex)
        states.add(newState)
        cursor++
    }

    fun undo(): List<Annotation> {
        if (canUndo) cursor--
        return current
    }

    fun redo(): List<Annotation> {
        if (canRedo) cursor++
        return current
    }
}
