package com.pdfeditor.app.domain

import kotlinx.serialization.Serializable

/** Stable identity within a document and the corresponding page in its immutable base PDF. */
@Serializable
data class DocumentPage(val id: String, val sourceIndex: Int) {
    init {
        require(id.isNotBlank()) { "Page id must not be blank" }
        require(sourceIndex >= 0) { "Source page index must not be negative" }
    }
}

/** An editable document. Annotation keys are stable page ids, independent of displayed order. */
@Serializable
data class DocumentSnapshot(
    val pages: List<DocumentPage>,
    val annotations: Map<String, List<Annotation>> = emptyMap(),
) {
    init {
        require(pages.isNotEmpty()) { "A document must contain at least one page" }
        val pageIds = pages.map { it.id }.toSet()
        require(pageIds.size == pages.size) { "Page ids must be unique" }
        require(annotations.keys.all { it in pageIds }) { "Annotations must belong to an existing page" }
        require(annotations.values.all { list -> list.map { it.id }.toSet().size == list.size }) {
            "Annotation ids must be unique within a page"
        }
    }
}

/** One history for page structure and annotations; neither changes the immutable source PDF. */
class DocumentEdits(initial: DocumentSnapshot, saved: DocumentSnapshot = initial) {
    var current: DocumentSnapshot = initial.detached()
        private set

    private var savedSnapshot = saved.detached()
    private val undoHistory = ArrayDeque<DocumentSnapshot>()
    private val redoHistory = ArrayDeque<DocumentSnapshot>()
    private var lastHistoryGroup: String? = null

    val saved: DocumentSnapshot get() = savedSnapshot
    val isDirty: Boolean get() = current != savedSnapshot
    val canUndo: Boolean get() = undoHistory.isNotEmpty()
    val canRedo: Boolean get() = redoHistory.isNotEmpty()

    fun annotationsAt(index: Int): List<Annotation> = current.annotations[current.pages[index].id].orEmpty()

    fun add(
        pageId: String,
        annotation: Annotation,
    ) {
        requirePage(pageId)
        val annotations = current.annotations[pageId].orEmpty()
        require(annotations.none { it.id == annotation.id }) { "Annotation id already exists on this page" }
        updateAnnotations(pageId, annotations + annotation)
    }

    fun replace(
        pageId: String,
        annotation: Annotation,
    ) {
        requirePage(pageId)
        val annotations = current.annotations[pageId].orEmpty()
        require(annotations.any { it.id == annotation.id }) { "Annotation does not exist on this page" }
        updateAnnotations(pageId, annotations.map { if (it.id == annotation.id) annotation else it })
    }

    fun remove(
        pageId: String,
        id: String,
    ) {
        requirePage(pageId)
        updateAnnotations(pageId, current.annotations[pageId].orEmpty().filterNot { it.id == id })
    }

    /** Live typing is durable on each update, while one typing session forms one undo step. */
    fun editText(
        pageId: String,
        annotation: TextBoxAnnotation,
        session: String,
    ) {
        requirePage(pageId)
        val existing = current.annotations[pageId].orEmpty()
        val updated = existing.filterNot { it.id == annotation.id } + if (annotation.text.isEmpty()) emptyList() else listOf(annotation)
        updateAnnotations(pageId, updated, "$pageId:${annotation.id}:$session")
    }

    /** [newOrder] is a permutation of the current displayed page indices. */
    fun reorder(newOrder: List<Int>) {
        require(newOrder.size == current.pages.size && newOrder.toSet() == current.pages.indices.toSet()) {
            "Page order must contain every current page index exactly once"
        }
        change(current.copy(pages = newOrder.map { current.pages[it] }))
    }

    fun delete(index: Int) {
        require(current.pages.size > 1) { "Cannot delete the final page" }
        val pageId = current.pages[index].id
        change(
            current.copy(
                pages = current.pages.filterIndexed { pageIndex, _ -> pageIndex != index },
                annotations = current.annotations - pageId,
            ),
        )
    }

    fun delete(indices: Set<Int>) {
        require(indices.all { it in current.pages.indices } && indices.size < current.pages.size) { "Keep at least one page." }
        val removedIds = indices.map { current.pages[it].id }.toSet()
        change(
            current.copy(
                pages = current.pages.filterIndexed { index, _ -> index !in indices },
                annotations = current.annotations - removedIds,
            ),
        )
    }

    fun append(
        pages: List<DocumentPage>,
        annotationsByPageId: Map<String, List<Annotation>> = emptyMap(),
    ) {
        val newIds = pages.map { it.id }.toSet()
        require(annotationsByPageId.keys.all { it in newIds }) { "Imported annotations must belong to appended pages" }
        change(current.copy(pages = current.pages + pages, annotations = current.annotations + annotationsByPageId))
    }

    fun undo() {
        lastHistoryGroup = null
        if (!canUndo) return
        redoHistory.addLast(current)
        current = undoHistory.removeLast()
    }

    fun redo() {
        lastHistoryGroup = null
        if (!canRedo) return
        undoHistory.addLast(current)
        current = redoHistory.removeLast()
    }

    /** Records exactly what was exported, even if the user has edited again during the export. */
    fun markSaved(snapshot: DocumentSnapshot) {
        lastHistoryGroup = null
        savedSnapshot = snapshot.detached()
    }

    private fun requirePage(pageId: String) {
        require(current.pages.any { it.id == pageId }) { "Page does not exist" }
    }

    private fun updateAnnotations(
        pageId: String,
        annotations: List<Annotation>,
        historyGroup: String? = null,
    ) {
        val updated =
            if (annotations.isEmpty()) {
                current.annotations - pageId
            } else {
                current.annotations + (pageId to annotations)
            }
        change(current.copy(annotations = updated), historyGroup)
    }

    private fun change(
        snapshot: DocumentSnapshot,
        historyGroup: String? = null,
    ) {
        val next = snapshot.detached()
        if (next == current) return
        if (historyGroup == null || historyGroup != lastHistoryGroup) undoHistory.addLast(current)
        lastHistoryGroup = historyGroup
        redoHistory.clear()
        current = next
    }
}

/** Copy collection boundaries and canonicalize empty annotation entries for content equality. */
private fun DocumentSnapshot.detached(): DocumentSnapshot =
    copy(
        pages = pages.toList(),
        annotations = annotations.filterValues { it.isNotEmpty() }.mapValues { (_, annotations) -> annotations.toList() },
    )
