package com.pdfeditor.app.ui.reader

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.collection.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pdfeditor.app.data.pdf.PdfBoxEditor
import com.pdfeditor.app.data.pdf.PdfRendererEngine
import com.pdfeditor.app.domain.OpenPdfResult
import com.pdfeditor.app.domain.PdfDocumentRenderer
import com.pdfeditor.app.domain.PdfEditor
import com.pdfeditor.app.domain.RecentsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

sealed interface ReaderUiState {
    data object Loading : ReaderUiState

    data class Ready(
        val pageCount: Int,
        /** height/width per page, used to lay out correctly-proportioned placeholders before a page renders. */
        val pageAspectRatios: List<Float>,
        /** Page width in PDF points, needed to convert writing-mode screen coordinates back to PDF
         * user-space accurately - see `PdfPageTransform`. Height isn't needed separately: writing
         * mode renders at fit-width, so points-per-pixel is the same in both axes. */
        val pageWidthsPoints: List<Float>,
        val initialPage: Int,
        val initialZoom: Float,
        val invertColors: Boolean,
        /** Bumped on every structural edit (reorder/delete/append) - the page at a given index may
         * now be entirely different content, so any UI caching keyed only on page index (thumbnail
         * bitmaps, remembered page bitmaps) must also key on this to avoid showing stale content. */
        val documentVersion: Int = 0,
    ) : ReaderUiState

    data object PasswordProtected : ReaderUiState

    data class Error(val message: String) : ReaderUiState
}

class ReaderViewModel(
    private val recentsRepository: RecentsRepository,
    private val documentId: Long,
    private val uri: Uri,
    private val contentResolver: ContentResolver,
    private val workingCopiesDir: File,
    cacheBudgetBytes: Int,
    private val pdfEditor: PdfEditor = PdfBoxEditor(),
) : ViewModel() {
    private val _uiState = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val uiState: StateFlow<ReaderUiState> = _uiState

    private var engine: PdfDocumentRenderer? = null

    /**
     * Set once a structural edit (reorder/delete/append) has been made - a private copy that from
     * then on is the real source of truth, since the original SAF file is never touched. Save-as-
     * copy and share must use this over [uri] once it exists, or a structural edit would silently
     * vanish from the exported/shared file.
     */
    private var workingFile: File? = null

    /** The file to export/share from: the working copy if structural edits exist, else null (caller falls back to [uri]). */
    fun currentWorkingFile(): File? = workingFile

    private val bitmapCache =
        object : LruCache<String, Bitmap>(cacheBudgetBytes) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ) = value.byteCount
        }

    init {
        viewModelScope.launch {
            val pfd =
                try {
                    contentResolver.openFileDescriptor(uri, "r")
                } catch (e: Exception) {
                    null
                }
            if (pfd == null) {
                _uiState.value = ReaderUiState.Error("This file could not be found.")
                return@launch
            }
            when (val result = PdfRendererEngine.open(pfd)) {
                is OpenPdfResult.Success -> {
                    val renderer = result.renderer
                    engine = renderer
                    recentsRepository.updatePageCount(documentId, renderer.pageCount)
                    val recent = recentsRepository.getByUri(uri.toString())
                    val sizes = (0 until renderer.pageCount).map { index -> renderer.pageSize(index) }
                    _uiState.value =
                        ReaderUiState.Ready(
                            pageCount = renderer.pageCount,
                            pageAspectRatios = sizes.map { it.heightPoints / it.widthPoints },
                            pageWidthsPoints = sizes.map { it.widthPoints },
                            initialPage = recent?.lastPageIndex?.coerceIn(0, renderer.pageCount - 1) ?: 0,
                            initialZoom = recent?.lastZoomScale?.takeIf { it > 0f } ?: 1f,
                            invertColors = true,
                        )
                }
                OpenPdfResult.PasswordProtected ->
                    _uiState.value =
                        ReaderUiState.Error("This PDF is password-protected. Encrypted PDFs aren't supported yet.")
                is OpenPdfResult.Failed ->
                    _uiState.value = ReaderUiState.Error(result.message)
            }
        }
    }

    /** Renders (or returns a cached copy of) [pageIndex] at [widthPx] x [heightPx]. Null if the engine isn't ready. */
    suspend fun renderPage(
        pageIndex: Int,
        widthPx: Int,
        heightPx: Int,
    ): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val key = "$pageIndex:$widthPx:$heightPx"
        bitmapCache.get(key)?.let { return it }
        // PdfRenderer.Page.render() only accepts ARGB_8888 - RGB_565 throws "Unsupported pixel
        // format" at the native layer, so the memory saving isn't actually available to us here.
        val target = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val currentEngine = engine ?: return null
        return try {
            currentEngine.renderPage(pageIndex, target)
            bitmapCache.put(key, target)
            target
        } catch (e: Exception) {
            android.util.Log.e("ReaderViewModel", "Failed to render page $pageIndex", e)
            null
        }
    }

    fun updateReadingPosition(
        pageIndex: Int,
        zoomScale: Float,
        scrollFraction: Float,
    ) {
        viewModelScope.launch {
            recentsRepository.updateReadingPosition(documentId, pageIndex, zoomScale, scrollFraction)
        }
    }

    fun toggleInvert() {
        _uiState.update { state ->
            if (state is ReaderUiState.Ready) state.copy(invertColors = !state.invertColors) else state
        }
    }

    fun reorderPages(newOrder: List<Int>) {
        viewModelScope.launch {
            val file = ensureWorkingFile() ?: return@launch
            pdfEditor.reorderPages(file, newOrder)
            reloadFromWorkingFile()
        }
    }

    fun deletePages(pageIndices: Set<Int>) {
        viewModelScope.launch {
            val file = ensureWorkingFile() ?: return@launch
            pdfEditor.deletePages(file, pageIndices)
            reloadFromWorkingFile()
        }
    }

    fun appendPages(files: List<File>) {
        viewModelScope.launch {
            val file = ensureWorkingFile() ?: return@launch
            pdfEditor.appendPages(file, files)
            reloadFromWorkingFile()
        }
    }

    /** Copies the original SAF file into a private working copy the first time a structural edit is made. */
    private suspend fun ensureWorkingFile(): File? {
        workingFile?.let { return it }
        val file = File(workingCopiesDir.apply { mkdirs() }, "$documentId.pdf")
        val copied =
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                true
            } catch (e: Exception) {
                android.util.Log.e("PageOverviewDebug", "copy to working file failed", e)
                false
            }
        if (!copied) return null
        workingFile = file
        return file
    }

    /** Reopens the renderer against [workingFile] after a structural edit changed the page count/order. */
    private suspend fun reloadFromWorkingFile() {
        val file = workingFile ?: return
        val previousState = _uiState.value as? ReaderUiState.Ready
        val pfd =
            try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: Exception) {
                null
            }
        if (pfd == null) {
            _uiState.value = ReaderUiState.Error("This file could not be found.")
            return
        }
        when (val result = PdfRendererEngine.open(pfd)) {
            is OpenPdfResult.Success -> {
                engine?.close()
                val renderer = result.renderer
                engine = renderer
                bitmapCache.evictAll()
                recentsRepository.updatePageCount(documentId, renderer.pageCount)
                val sizes = (0 until renderer.pageCount).map { index -> renderer.pageSize(index) }
                _uiState.value =
                    ReaderUiState.Ready(
                        pageCount = renderer.pageCount,
                        pageAspectRatios = sizes.map { it.heightPoints / it.widthPoints },
                        pageWidthsPoints = sizes.map { it.widthPoints },
                        initialPage = (previousState?.initialPage ?: 0).coerceIn(0, renderer.pageCount - 1),
                        initialZoom = previousState?.initialZoom ?: 1f,
                        invertColors = previousState?.invertColors ?: true,
                        documentVersion = (previousState?.documentVersion ?: 0) + 1,
                    )
            }
            OpenPdfResult.PasswordProtected ->
                _uiState.value = ReaderUiState.Error("This PDF is password-protected. Encrypted PDFs aren't supported yet.")
            is OpenPdfResult.Failed ->
                _uiState.value = ReaderUiState.Error(result.message)
        }
    }

    override fun onCleared() {
        engine?.close()
        bitmapCache.evictAll()
    }

    class Factory(
        private val recentsRepository: RecentsRepository,
        private val documentId: Long,
        private val uri: Uri,
        private val contentResolver: ContentResolver,
        private val workingCopiesDir: File,
        private val cacheBudgetBytes: Int,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ReaderViewModel(recentsRepository, documentId, uri, contentResolver, workingCopiesDir, cacheBudgetBytes) as T
    }
}
