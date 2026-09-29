package com.pdfeditor.app

import android.app.Application
import androidx.room.Room
import com.pdfeditor.app.data.db.AppDatabase
import com.pdfeditor.app.data.db.RoomRecentsRepository
import com.pdfeditor.app.domain.RecentsRepository

/**
 * Hand-rolled dependency container instead of a DI framework (Hilt/Dagger) — this is a small,
 * single-module app and a generated component graph would add build time and APK size for no
 * real payoff at this scale.
 */
class PdfEditorApplication : Application() {
    lateinit var recentsRepository: RecentsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val database =
            Room.databaseBuilder(this, AppDatabase::class.java, "pdf-editor.db")
                .build()
        recentsRepository = RoomRecentsRepository(database.recentDocumentDao())
    }
}
