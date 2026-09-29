package com.pdfeditor.app.domain

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * Renders pages of an already-open PDF to bitmaps for display. Implemented in Milestone 2 by
 * [com.pdfeditor.app.data.pdf.PdfRendererEngine], wrapping the platform `PdfRenderer`. Kept
 * behind this interface so a future PDFium-backed implementation (see plan doc, item A) can be
 * swapped in without touching UI code, and so tests can use a fake.
 */
interface PdfDocumentRenderer : AutoCloseable {
    val pageCount: Int

    fun pageSize(pageIndex: Int): PageSize

    /**
     * Renders [pageIndex] into [into], mapping page-space rect [pageRegion] (or the whole page
     * when null) through [into]'s bounds. Runs on the renderer's own dispatcher; safe to call
     * from any coroutine context.
     */
    suspend fun renderPage(
        pageIndex: Int,
        into: Bitmap,
        pageRegion: RectF? = null,
    )

    override fun close()
}

data class PageSize(
    val widthPoints: Float,
    val heightPoints: Float,
    val rotationDegrees: Int,
)
