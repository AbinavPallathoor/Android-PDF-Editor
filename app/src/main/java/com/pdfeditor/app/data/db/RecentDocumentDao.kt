package com.pdfeditor.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentDocumentDao {
    @Query("SELECT * FROM recent_documents ORDER BY isPinned DESC, lastOpenedAtEpochMs DESC")
    fun observeAll(): Flow<List<RecentDocumentEntity>>

    @Query("SELECT * FROM recent_documents WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): RecentDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RecentDocumentEntity): Long

    @Update
    suspend fun update(entity: RecentDocumentEntity)

    @Query("DELETE FROM recent_documents WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        "UPDATE recent_documents SET lastPageIndex = :lastPageIndex, lastZoomScale = :lastZoomScale, " +
            "lastScrollFraction = :lastScrollFraction WHERE id = :id",
    )
    suspend fun updateReadingPosition(
        id: Long,
        lastPageIndex: Int,
        lastZoomScale: Float,
        lastScrollFraction: Float,
    )

    @Query("UPDATE recent_documents SET pageCount = :pageCount WHERE id = :id")
    suspend fun updatePageCount(
        id: Long,
        pageCount: Int,
    )

    @Query("UPDATE recent_documents SET isPinned = :isPinned WHERE id = :id")
    suspend fun setPinned(
        id: Long,
        isPinned: Boolean,
    )
}
