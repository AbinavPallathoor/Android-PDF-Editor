package com.pdfeditor.app.domain

import kotlinx.coroutines.flow.Flow

/**
 * Persists the list of recently opened PDFs. Backed by Room in production
 * ([com.pdfeditor.app.data.db.RoomRecentsRepository]); kept behind an interface so
 * domain/UI code and tests never depend on Room directly.
 */
interface RecentsRepository {
    fun observeRecents(): Flow<List<RecentDocument>>

    suspend fun getByUri(uri: String): RecentDocument?

    /** Inserts a newly opened document, or updates its lastOpenedAt/display fields if it already exists. */
    suspend fun recordOpened(
        uri: String,
        displayName: String,
        fileSizeBytes: Long?,
    ): RecentDocument

    suspend fun updateReadingPosition(
        id: Long,
        lastPageIndex: Int,
        lastZoomScale: Float,
        lastScrollFraction: Float,
    )

    suspend fun updatePageCount(
        id: Long,
        pageCount: Int,
    )

    /** Removes the row from recents. Never touches the underlying file. */
    suspend fun remove(id: Long)

    suspend fun setPinned(
        id: Long,
        isPinned: Boolean,
    )
}
