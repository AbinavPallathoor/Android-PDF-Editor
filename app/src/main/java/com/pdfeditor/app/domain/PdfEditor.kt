package com.pdfeditor.app.domain

import java.io.File

/**
 * Structural PDF edits (merge, reorder, delete/append pages, compress, flatten annotations).
 * Implemented from Milestone 4 onward by `PdfBoxEditor`, wrapping PdfBox-Android. Declared now so
 * the writing-mode UI (Milestone 4+) can be designed against a stable contract; never overwrites
 * [source] in place unless the operation explicitly says so.
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

    suspend fun compress(
        source: File,
        preset: CompressionPreset,
    ): CompressionResult
}

enum class CompressionPreset { LOW, MEDIUM, HIGH }

data class CompressionResult(
    val output: File,
    val originalSizeBytes: Long,
    val compressedSizeBytes: Long,
)
