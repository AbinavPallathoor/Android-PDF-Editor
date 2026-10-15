package com.pdfeditor.app.data.pdf

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import com.pdfeditor.app.domain.OpenPdfResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders just the first page of a document at thumbnail size, for the Home screen's recents
 * list and the reader's title bar - opens a short-lived [PdfRendererEngine] and closes it
 * immediately after, rather than keeping a renderer open per row.
 */
suspend fun loadFirstPageThumbnail(
    contentResolver: ContentResolver,
    uri: Uri,
    widthPx: Int,
    heightPx: Int,
): Bitmap? =
    withContext(Dispatchers.IO) {
        if (widthPx <= 0 || heightPx <= 0) return@withContext null
        val pfd =
            try {
                contentResolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                null
            } ?: return@withContext null
        when (val result = PdfRendererEngine.open(pfd)) {
            is OpenPdfResult.Success -> {
                val renderer = result.renderer
                try {
                    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                    renderer.renderPage(0, bitmap)
                    bitmap
                } catch (e: Exception) {
                    null
                } finally {
                    renderer.close()
                }
            }
            else -> null
        }
    }
