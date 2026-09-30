package com.pdfeditor.app.data.pdf

import com.pdfeditor.app.domain.PdfEditor
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Structural page edits via PdfBox-Android. Every operation loads [source] fresh, builds the
 * result, and saves back over the same path - safe since [source] is always a private working
 * copy (see CLAUDE.md's Reading/Writing session note), never the original SAF file.
 */
class PdfBoxEditor : PdfEditor {
    override suspend fun reorderPages(
        source: File,
        newOrder: List<Int>,
    ): File =
        withContext(Dispatchers.IO) {
            val original = PDDocument.load(source)
            try {
                val reordered = PDDocument()
                try {
                    for (index in newOrder) {
                        reordered.importPage(original.getPage(index))
                    }
                    reordered.save(source)
                } finally {
                    reordered.close()
                }
            } finally {
                original.close()
            }
            source
        }

    override suspend fun deletePages(
        source: File,
        pageIndices: Set<Int>,
    ): File =
        withContext(Dispatchers.IO) {
            val document = PDDocument.load(source)
            try {
                // Remove from highest index to lowest so earlier removals don't shift the
                // indices of pages still waiting to be removed.
                for (index in pageIndices.sortedDescending()) {
                    document.removePage(index)
                }
                document.save(source)
            } finally {
                document.close()
            }
            source
        }

    override suspend fun appendPages(
        source: File,
        toAppend: List<File>,
    ): File =
        withContext(Dispatchers.IO) {
            val destination = PDDocument.load(source)
            try {
                val merger = PDFMergerUtility()
                for (file in toAppend) {
                    val addition = PDDocument.load(file)
                    try {
                        merger.appendDocument(destination, addition)
                    } finally {
                        addition.close()
                    }
                }
                destination.save(source)
            } finally {
                destination.close()
            }
            source
        }
}
