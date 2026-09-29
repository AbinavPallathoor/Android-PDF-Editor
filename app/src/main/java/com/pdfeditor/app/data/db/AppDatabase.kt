package com.pdfeditor.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

// exportSchema is off for now; turn on (with a room.schemaLocation KSP arg) once the schema
// needs real migrations to track against.
@Database(entities = [RecentDocumentEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recentDocumentDao(): RecentDocumentDao
}
