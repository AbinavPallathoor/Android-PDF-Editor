package com.pdfeditor.app

import android.app.Application
import androidx.room.Room
import com.pdfeditor.app.data.cache.PageRenderCache
import com.pdfeditor.app.data.cache.WorkingCopyExpiry
import com.pdfeditor.app.data.db.AppDatabase
import com.pdfeditor.app.data.db.RoomRecentsRepository
import com.pdfeditor.app.domain.RecentsRepository
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Hand-rolled dependency container instead of a DI framework (Hilt/Dagger) — this is a small,
 * single-module app and a generated component graph would add build time and APK size for no
 * real payoff at this scale.
 */
class PdfEditorApplication : Application() {
    lateinit var recentsRepository: RecentsRepository
        private set
    lateinit var pageRenderCache: PageRenderCache
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        val database =
            Room.databaseBuilder(this, AppDatabase::class.java, "pdf-editor.db")
                .build()
        recentsRepository = RoomRecentsRepository(database.recentDocumentDao())
        pageRenderCache = PageRenderCache(File(cacheDir, "rendered_pages"))
        CoroutineScope(Dispatchers.IO).launch {
            pageRenderCache.prune()
            WorkingCopyExpiry(File(filesDir, "working_copies")).prune()
        }
    }
}
