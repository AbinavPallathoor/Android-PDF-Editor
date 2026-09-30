package com.pdfeditor.app.domain

import java.io.File

/**
 * Image re-encode compression (Milestone 6). Declared now, alongside [PdfEditor], so the file
 * actions sheet can be designed against a stable contract before the implementation exists.
 */
interface PdfCompressor {
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
