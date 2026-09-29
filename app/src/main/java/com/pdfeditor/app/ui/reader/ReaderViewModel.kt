package com.pdfeditor.app.ui.reader

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import androidx.collection.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pdfeditor.app.data.pdf.PdfRendererEngine
import com.pdfeditor.app.domain.OpenPdfResult
import com.pdfeditor.app.domain.PdfDocumentRenderer
import com.pdfeditor.app.domain.RecentsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ReaderUiState {
    data object Loading : ReaderUiState

    data class Ready(
        val pageCount: Int,
        /** height/width per page, used to lay out correctly-proportioned placeholders before a page renders. */
        val pageAspectRatios: List<Float>,
        val initialPage: Int,
        val initialZoom: Float,
        val invertColors: Boolean,
    ) : ReaderUiState

    data object PasswordProtected : ReaderUiState

    data class Error(val message: String) : ReaderUiState
}

class ReaderViewModel(
    private val recentsRepository: RecentsRepository,
    private val documentId: Long,
    private val uri: Uri,
    private val contentResolver: ContentResolver,
    cacheBudgetBytes: Int,
) : ViewModel() {
    private val _uiState = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val uiState: StateFlow<ReaderUiState> = _uiState

    private var engine: PdfDocumentRenderer? = null

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
                    _uiState.value =
                        ReaderUiState.Ready(
                            pageCount = renderer.pageCount,
                            pageAspectRatios =
                                (0 until renderer.pageCount).map { index ->
                                    val size = renderer.pageSize(index)
                                    size.heightPoints / size.widthPoints
                                },
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

    override fun onCleared() {
        engine?.close()
        bitmapCache.evictAll()
    }

    class Factory(
        private val recentsRepository: RecentsRepository,
        private val documentId: Long,
        private val uri: Uri,
        private val contentResolver: ContentResolver,
        private val cacheBudgetBytes: Int,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ReaderViewModel(recentsRepository, documentId, uri, contentResolver, cacheBudgetBytes) as T
    }
}
