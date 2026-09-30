package com.pdfeditor.app.ui.writing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.pdfeditor.app.domain.Annotation
import com.pdfeditor.app.domain.PdfPoint
import com.pdfeditor.app.domain.SignaturePlacement
import java.util.UUID

/**
 * Holds one page's in-progress annotations for the current writing-mode session. Deliberately
 * scoped to a single page (writing mode shows only the page that was active in reading mode, at
 * fixed fit-width - see `WritingScreen`'s doc comment for why). Nothing here is persisted until
 * the user explicitly saves a copy; if the app is killed mid-edit, in-progress annotations are
 * lost (documented v1 limitation - resuming unsaved edits is a possible future enhancement, not
 * required now).
 */
class WritingViewModel(initialAnnotations: List<Annotation> = emptyList()) : ViewModel() {
    private val history = WritingHistory(initialAnnotations)

    var annotations by mutableStateOf(history.current)
        private set
    var selectedTool by mutableStateOf(WritingTool.NONE)
        private set
    var savedSignature by mutableStateOf<List<List<PdfPoint>>?>(null)
        private set
    var isDrawingSignature by mutableStateOf(false)
        private set

    fun selectTool(tool: WritingTool) {
        if (tool == WritingTool.SIGN && savedSignature == null) {
            isDrawingSignature = true
        }
        selectedTool = tool
    }

    fun cancelSignatureDrawing() {
        isDrawingSignature = false
        selectedTool = WritingTool.NONE
    }

    fun commitSignature(
        strokes: List<List<PdfPoint>>,
        placeAt: PdfPoint,
        colorArgb: Int,
    ) {
        savedSignature = strokes
        isDrawingSignature = false
        addAnnotation(SignaturePlacement(newId(), placeAt, strokes, scale = 1f, colorArgb = colorArgb))
    }

    fun addAnnotation(annotation: Annotation) {
        annotations = (annotations + annotation)
        history.push(annotations)
    }

    fun replaceAnnotation(updated: Annotation) {
        annotations = annotations.map { if (it.id == updated.id) updated else it }
        history.push(annotations)
    }

    fun removeAnnotation(id: String) {
        val filtered = annotations.filterNot { it.id == id }
        if (filtered.size != annotations.size) {
            annotations = filtered
            history.push(annotations)
        }
    }

    fun undo() {
        annotations = history.undo()
    }

    fun redo() {
        annotations = history.redo()
    }

    val canUndo: Boolean get() = history.canUndo
    val canRedo: Boolean get() = history.canRedo

    class Factory(private val initialAnnotations: List<Annotation>) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = WritingViewModel(initialAnnotations) as T
    }
}

fun newId(): String = UUID.randomUUID().toString()
