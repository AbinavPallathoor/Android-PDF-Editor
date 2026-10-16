package com.pdfeditor.app.data.pdf

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Own the copy across dispatcher return/cancellation, then restore caller cancellation for reads. */
internal suspend fun <T> withPdfReadCopy(
    source: File,
    directory: File,
    copyDispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: suspend (File) -> T,
): T {
    val caller = currentCoroutineContext()
    // Keep acquisition's receiving context alive too: changing dispatcher with NonCancellable
    // alone can still lose the returned File when resuming into a canceled caller.
    return withContext(NonCancellable) {
        val copy =
            withContext(copyDispatcher) {
                check(directory.mkdirs() || directory.isDirectory)
                val file = File.createTempFile("read-copy-", ".pdf", directory)
                try {
                    source.copyTo(file, overwrite = true)
                    file
                } catch (failure: Throwable) {
                    file.delete()
                    throw failure
                }
            }
        try {
            withContext(caller) {
                ensureActive()
                block(copy)
            }
        } finally {
            withContext(copyDispatcher) { copy.delete() }
        }
    }
}
