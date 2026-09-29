package com.pdfeditor.app.domain

/**
 * A PDF the user has previously opened, as shown on the Home screen.
 * [lastScrollFraction] is normalized (0f..1f) within [lastPageIndex] so it survives
 * rotation and zoom changes, unlike a raw pixel offset.
 */
data class RecentDocument(
    val id: Long,
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
