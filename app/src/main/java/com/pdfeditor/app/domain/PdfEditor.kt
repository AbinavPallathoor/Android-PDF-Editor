package com.pdfeditor.app.domain

import java.io.File

/**
 * Structural PDF edits (reorder, delete, append pages). Implemented from Milestone 5 onward by
 * `PdfBoxEditor`, wrapping PdfBox-Android. Always writes its result back into [source] - safe
 * because [source] is always a private working copy, never the original SAF file (see
 * CLAUDE.md's Reading/Writing session note).
 */
interface PdfEditor {
    suspend fun reorderPages(
        source: File,
        newOrder: List<Int>,
    ): File

    suspend fun deletePages(
        source: File,
        pageIndices: Set<Int>,
    ): File

    suspend fun appendPages(
        source: File,
        toAppend: List<File>,
    ): File
}
