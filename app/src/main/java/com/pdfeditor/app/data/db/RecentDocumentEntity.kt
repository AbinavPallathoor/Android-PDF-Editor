package com.pdfeditor.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "recent_documents",
    indices = [Index(value = ["uri"], unique = true), Index(value = ["lastOpenedAtEpochMs"])],
)
data class RecentDocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val displayName: String,
    val fileSizeBytes: Long?,
    val pageCount: Int?,
    val lastPageIndex: Int,
    val lastZoomScale: Float,
    val lastScrollFraction: Float,
    val lastOpenedAtEpochMs: Long,
    val createdAtEpochMs: Long,
    val thumbnailCacheKey: String?,
    val isPinned: Boolean,
)
