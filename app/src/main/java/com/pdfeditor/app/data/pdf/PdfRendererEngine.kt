package com.pdfeditor.app.data.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.pdfeditor.app.domain.OpenPdfResult
import com.pdfeditor.app.domain.PageSize
import com.pdfeditor.app.domain.PdfDocumentRenderer
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Wraps the platform `android.graphics.pdf.PdfRenderer`. `PdfRenderer`/`Page` are not thread-safe
 * and only one `Page` may be open at a time per instance, so every call is serialized through a
 * single dedicated thread + mutex (see CLAUDE.md "Key design decisions to preserve").
 *
 * Page sizes are computed once up front (opening/closing each page is cheap - it doesn't decode
 * content) so [pageSize] can stay a plain, non-suspending call for layout purposes, and so
 * `PdfRenderer`'s own internal rotation handling (page.width/height already reflect the page's
 * effective, rotated orientation) is captured without extra bookkeeping.
 */
class PdfRendererEngine private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private val pageSizes: List<PageSize>,
    private val dispatcher: ExecutorCoroutineDispatcher,
) : PdfDocumentRenderer {
    private val mutex = Mutex()

    override val pageCount: Int get() = pageSizes.size

    override fun pageSize(pageIndex: Int): PageSize = pageSizes[pageIndex]

    override suspend fun renderPage(
        pageIndex: Int,
        into: Bitmap,
        pageRegion: RectF?,
    ) {
        withContext(dispatcher) {
            mutex.withLock {
                // PdfRenderer.Page.render() only paints actual page content - it does not fill a
                // white background first, so anything not explicitly painted (which is most of a
                // typical page) would otherwise show through as whatever garbage/black the bitmap
                // already contained.
                into.eraseColor(Color.WHITE)
                renderer.openPage(pageIndex).use { page ->
                    val matrix = Matrix()
                    if (pageRegion != null) {
                        val scaleX = into.width / pageRegion.width()
                        val scaleY = into.height / pageRegion.height()
                        matrix.postTranslate(-pageRegion.left, -pageRegion.top)
                        matrix.postScale(scaleX, scaleY)
                    } else {
                        val scaleX = into.width.toFloat() / page.width
                        val scaleY = into.height.toFloat() / page.height
                        matrix.postScale(scaleX, scaleY)
                    }
                    if (android.os.Build.VERSION.SDK_INT >= 35) {
                        val parameters =
                            android.graphics.pdf.RenderParams.Builder(android.graphics.pdf.RenderParams.RENDER_MODE_FOR_DISPLAY)
                                .setRenderFlags(
                                    android.graphics.pdf.RenderParams.FLAG_RENDER_TEXT_ANNOTATIONS or
                                        android.graphics.pdf.RenderParams.FLAG_RENDER_HIGHLIGHT_ANNOTATIONS,
                                )
                                .build()
                        page.render(into, null, matrix, parameters)
                    } else {
                        page.render(into, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }

    override fun close() {
        renderer.close()
        pfd.close()
        dispatcher.close()
    }

    companion object {
        suspend fun open(pfd: ParcelFileDescriptor): OpenPdfResult {
            val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "PdfRendererEngine") }.asCoroutineDispatcher()
            return try {
                withContext(dispatcher) {
                    val renderer = PdfRenderer(pfd)
                    val sizes =
                        (0 until renderer.pageCount).map { index ->
                            renderer.openPage(index).use { page ->
                                PageSize(page.width.toFloat(), page.height.toFloat(), 0)
                            }
                        }
                    OpenPdfResult.Success(PdfRendererEngine(pfd, renderer, sizes, dispatcher))
                }
            } catch (e: SecurityException) {
                dispatcher.close()
                pfd.close()
                OpenPdfResult.PasswordProtected
            } catch (e: IOException) {
                dispatcher.close()
                pfd.close()
                OpenPdfResult.Failed(e.message ?: "This file could not be opened - it may be corrupt.")
            }
        }
    }
}
