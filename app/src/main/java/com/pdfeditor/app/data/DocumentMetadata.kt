package com.pdfeditor.app.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns

data class DocumentMetadata(val displayName: String, val sizeBytes: Long?)

/**
 * Resolves the display name and size of a `content://` document via its provider, falling back to
 * the last URI path segment if the provider doesn't return [OpenableColumns.DISPLAY_NAME].
 */
fun resolveDocumentMetadata(
    contentResolver: ContentResolver,
    uri: Uri,
): DocumentMetadata {
    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
            val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
            if (name != null) return DocumentMetadata(name, size)
        }
    }
    return DocumentMetadata(uri.lastPathSegment ?: "Untitled.pdf", null)
}
