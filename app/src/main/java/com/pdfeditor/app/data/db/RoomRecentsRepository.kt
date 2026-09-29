package com.pdfeditor.app.data.db

import com.pdfeditor.app.domain.RecentDocument
import com.pdfeditor.app.domain.RecentsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomRecentsRepository(
    private val dao: RecentDocumentDao,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : RecentsRepository {
    override fun observeRecents(): Flow<List<RecentDocument>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getByUri(uri: String): RecentDocument? = dao.getByUri(uri)?.toDomain()

    override suspend fun recordOpened(
        uri: String,
        displayName: String,
        fileSizeBytes: Long?,
    ): RecentDocument {
        val now = clockMillis()
        val existing = dao.getByUri(uri)
        val entity =
            existing?.copy(
                displayName = displayName,
                fileSizeBytes = fileSizeBytes,
                lastOpenedAtEpochMs = now,
            ) ?: RecentDocumentEntity(
                uri = uri,
                displayName = displayName,
                fileSizeBytes = fileSizeBytes,
                pageCount = null,
                lastPageIndex = 0,
                lastZoomScale = 1f,
                lastScrollFraction = 0f,
                lastOpenedAtEpochMs = now,
                createdAtEpochMs = now,
                thumbnailCacheKey = null,
                isPinned = false,
            )
        val id = dao.upsert(entity)
        return (if (existing != null) entity else entity.copy(id = id)).toDomain()
    }

    override suspend fun updateReadingPosition(
        id: Long,
        lastPageIndex: Int,
        lastZoomScale: Float,
        lastScrollFraction: Float,
    ) = dao.updateReadingPosition(id, lastPageIndex, lastZoomScale, lastScrollFraction)

    override suspend fun updatePageCount(
        id: Long,
        pageCount: Int,
    ) = dao.updatePageCount(id, pageCount)

    override suspend fun remove(id: Long) = dao.deleteById(id)

    override suspend fun setPinned(
        id: Long,
        isPinned: Boolean,
    ) = dao.setPinned(id, isPinned)
}

private fun RecentDocumentEntity.toDomain() =
    RecentDocument(
        id = id,
        uri = uri,
        displayName = displayName,
        fileSizeBytes = fileSizeBytes,
        pageCount = pageCount,
        lastPageIndex = lastPageIndex,
        lastZoomScale = lastZoomScale,
        lastScrollFraction = lastScrollFraction,
        lastOpenedAtEpochMs = lastOpenedAtEpochMs,
        createdAtEpochMs = createdAtEpochMs,
        thumbnailCacheKey = thumbnailCacheKey,
        isPinned = isPinned,
    )
