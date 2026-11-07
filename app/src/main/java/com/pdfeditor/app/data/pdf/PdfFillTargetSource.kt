package com.pdfeditor.app.data.pdf

import com.pdfeditor.app.domain.PdfFillTarget
import com.pdfeditor.app.domain.PdfTextWord
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One private read copy and open PDFBox document shared by every fill-target and text lookup for a
 * single immutable renderer base, so pages don't each copy and reparse the whole PDF. Lookups
 * serialize on this object's own lock rather than the reader's, so they never block rendering or
 * edits. [close] may run while a lookup is in flight: whichever side finishes last releases.
 */
internal class PdfFillTargetSource(
    private val directory: File,
    private val textExtractor: (PDDocument, Int) -> List<PdfTextWord> = PdfBoxDocumentContent()::extractText,
    private val detector: suspend (PDDocument, Int) -> List<PdfFillTarget> = PdfBoxFillTargetDetector()::detect,
) {
    private val lock = Mutex()

    @Volatile private var closed = false

    @Volatile private var copy: File? = null
    private var document: PDDocument? = null

    /**
     * Snapshot [base] once. Call while [base] is guaranteed to exist unchanged (under the reader's
     * lock) and never concurrently with [close]. The copy is stored before returning, so a canceled
     * caller cannot leak it.
     */
    suspend fun snapshot(base: File) {
        if (copy != null || closed) return
        withContext(NonCancellable + Dispatchers.IO) {
            check(directory.mkdirs() || directory.isDirectory)
            val file = File.createTempFile("fill-read-", ".pdf", directory)
            try {
                base.copyTo(file, overwrite = true)
                copy = file
            } catch (failure: Throwable) {
                file.delete()
                throw failure
            }
        }
    }

    /** Empty once closed; callers must treat that result as stale rather than cache it. */
    suspend fun detect(pageIndex: Int): List<PdfFillTarget> = read(emptyList()) { detector(it, pageIndex) }

    /** Positioned words on [pageIndex]; empty once closed, like [detect]. */
    suspend fun text(pageIndex: Int): List<PdfTextWord> = read(emptyList()) { textExtractor(it, pageIndex) }

    val isClosed: Boolean get() = closed

    private suspend fun <T> read(
        whenClosed: T,
        block: suspend (PDDocument) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            if (closed) return@withContext whenClosed
            lock.lock()
            try {
                if (closed) return@withContext whenClosed
                val open =
                    document ?: withContext(NonCancellable) {
                        PDDocument.load(checkNotNull(copy) { "The PDF was read before a snapshot" })
                            .also { document = it }
                    }
                block(open)
            } finally {
                lock.unlock()
                if (closed) releaseIfIdle()
            }
        }

    /** Blocking file/document release; call off the main thread. */
    fun close() {
        closed = true
        releaseIfIdle()
    }

    private fun releaseIfIdle() {
        if (!lock.tryLock()) return
        try {
            document?.close()
            document = null
            copy?.delete()
            copy = null
        } finally {
            lock.unlock()
        }
    }
}
