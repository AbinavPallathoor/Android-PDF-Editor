package com.pdfeditor.app.ui.writing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pdfeditor.app.data.cache.SavedSignature
import com.pdfeditor.app.data.cache.SignatureRepository
import com.pdfeditor.app.domain.Annotation
import com.pdfeditor.app.domain.PdfPoint
import com.pdfeditor.app.domain.SignaturePlacement
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Holds one page's in-progress annotations for the current writing-mode session. Deliberately
 * scoped to a single page (writing mode shows only the page that was active in reading mode, at
 * fixed fit-width - see `WritingScreen`'s doc comment for why). Nothing here is persisted until
 * the user explicitly saves a copy; if the app is killed mid-edit, in-progress annotations are
 * lost (documented v1 limitation - resuming unsaved edits is a possible future enhancement, not
 * required now). Saved *signatures* are the one exception - those persist via [signatureRepository]
 * independently of the page/document, so a signature drawn once can be reused everywhere.
 */
class WritingViewModel(
    initialAnnotations: List<Annotation> = emptyList(),
    private val signatureRepository: SignatureRepository,
) : ViewModel() {
    private val history = WritingHistory(initialAnnotations)

    var annotations by mutableStateOf(history.current)
        private set
    var selectedTool by mutableStateOf(WritingTool.NONE)
        private set
    var savedSignatures by mutableStateOf<List<SavedSignature>>(emptyList())
        private set
    var activeSignatureIndex by mutableStateOf<Int?>(null)
        private set
    var isDrawingSignature by mutableStateOf(false)
        private set
    var showSignaturePicker by mutableStateOf(false)
        private set

    val activeSignature: SavedSignature?
        get() = activeSignatureIndex?.let { savedSignatures.getOrNull(it) }

    init {
        viewModelScope.launch {
            savedSignatures = signatureRepository.load()
            if (savedSignatures.isNotEmpty()) activeSignatureIndex = savedSignatures.lastIndex
        }
    }

    fun selectTool(tool: WritingTool) {
        if (tool == WritingTool.SIGN) {
            if (savedSignatures.isEmpty()) {
                isDrawingSignature = true
            } else {
                showSignaturePicker = true
            }
        }
        selectedTool = tool
    }

    fun pickSignature(index: Int) {
        activeSignatureIndex = index
        showSignaturePicker = false
    }

    fun startDrawingNewSignature() {
        showSignaturePicker = false
        isDrawingSignature = true
    }

    fun dismissSignaturePicker() {
        showSignaturePicker = false
        if (activeSignatureIndex == null) selectedTool = WritingTool.NONE
    }

    fun cancelSignatureDrawing() {
        isDrawingSignature = false
        if (savedSignatures.isEmpty()) selectedTool = WritingTool.NONE
    }

    fun commitSignature(
        strokes: List<List<PdfPoint>>,
        placeAt: PdfPoint,
        colorArgb: Int,
    ) {
        val saved = SavedSignature(strokes)
        savedSignatures = savedSignatures + saved
        activeSignatureIndex = savedSignatures.lastIndex
        isDrawingSignature = false
        addAnnotation(SignaturePlacement(newId(), placeAt, strokes, scale = 1f, colorArgb = colorArgb))
        viewModelScope.launch { signatureRepository.save(savedSignatures) }
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

    class Factory(
        private val initialAnnotations: List<Annotation>,
        private val signatureRepository: SignatureRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = WritingViewModel(initialAnnotations, signatureRepository) as T
    }
}

fun newId(): String = UUID.randomUUID().toString()
