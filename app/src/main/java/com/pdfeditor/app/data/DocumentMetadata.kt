package com.pdfeditor.app.data

import android.content.ContentResolver
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
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
    try {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                if (name != null) return DocumentMetadata(name, size)
            }
        }
    } catch (_: Exception) {
        // Metadata is optional: revoked grants/stale providers must not block draft recovery.
    }
    return DocumentMetadata(uri.lastPathSegment ?: "Untitled.pdf", null)
}

/**
 * Human-readable folder for a saved document, e.g. "Internal storage › Download", for telling
 * the user where a file went. Null when the provider gives nothing better than the file name.
 */
fun describeDocumentLocation(
    packageManager: PackageManager,
    uri: Uri,
): String? {
    if (uri.scheme == ContentResolver.SCHEME_FILE) {
        return uri.path?.let { sharedStorageFolderLabel(it, android.os.Environment.getExternalStorageDirectory().path) }
    }
    val authority = uri.authority ?: return null
    val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
    return storageFolderLabel(authority, documentId)
        ?: runCatching { packageManager.resolveContentProvider(authority, 0)?.loadLabel(packageManager)?.toString() }
            .getOrNull()?.takeIf { it.isNotBlank() }
}

/** "Internal storage › Download" for a file path in shared storage; null for app-private paths. */
internal fun sharedStorageFolderLabel(
    path: String,
    primaryRoot: String,
): String? {
    val root = primaryRoot.trimEnd('/') + "/"
    if (!path.startsWith(root)) return null
    val folders = path.removePrefix(root).split('/').dropLast(1).filter { it.isNotBlank() }
    return (listOf("Internal storage") + folders).joinToString(" › ")
}

/** Folder label from a storage provider's document id; null for providers with opaque ids. */
internal fun storageFolderLabel(
    authority: String,
    documentId: String?,
): String? =
    when (authority) {
        EXTERNAL_STORAGE_AUTHORITY -> {
            val id = documentId ?: return null
            val volume = id.substringBefore(':', "")
            val path = id.substringAfter(':', "")
            val root =
                when (volume) {
                    "primary" -> "Internal storage"
                    "home" -> "Documents"
                    "" -> return null
                    else -> "SD card"
                }
            val folders = path.split('/').dropLast(1).filter { it.isNotBlank() }
            (listOf(root) + folders).joinToString(" › ")
        }
        DOWNLOADS_AUTHORITY -> "Downloads"
        else -> null
    }

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
private const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"
