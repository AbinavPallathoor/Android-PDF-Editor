package com.pdfeditor.app.data

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

/** True when the app may read, write and rename files in shared storage directly (Android 11+). */
fun hasAllFilesAccess(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

/**
 * The file in shared storage behind [uri], when one can be found: plain `file://` paths, the
 * device-storage and Downloads document providers, and MediaStore items. Null for anything else
 * (cloud providers, other apps' private files), which can only be renamed inside the app.
 */
fun resolveDocumentFile(
    resolver: ContentResolver,
    uri: Uri,
): File? {
    if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.path?.let(::File)
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
    val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
    return when {
        uri.authority == EXTERNAL_STORAGE_DOCUMENTS && documentId != null ->
            externalStorageFile(documentId, Environment.getExternalStorageDirectory())
        uri.authority == DOWNLOADS_DOCUMENTS && documentId != null -> {
            when {
                documentId.startsWith("raw:") -> File(documentId.removePrefix("raw:"))
                documentId.startsWith("msf:") ->
                    documentId.removePrefix(
                        "msf:",
                    ).toLongOrNull()?.let { mediaFile(resolver, MediaStore.Files.getContentUri("external"), it) }
                else -> null
            }
        }
        uri.authority == MediaStore.AUTHORITY -> mediaFile(resolver, uri, null)
        else -> null
    }
}

/** `primary:Download/a.pdf` → `<primaryRoot>/Download/a.pdf`; other volumes live under `/storage/<id>`. */
internal fun externalStorageFile(
    documentId: String,
    primaryRoot: File,
): File? {
    val volume = documentId.substringBefore(':', "")
    val path = documentId.substringAfter(':', "")
    if (volume.isEmpty() || path.isEmpty()) return null
    val root = if (volume == "primary") primaryRoot else File("/storage", volume)
    return File(root, path)
}

@Suppress("DEPRECATION") // DATA is the only way to learn a MediaStore item's path; readable with all-files access.
private fun mediaFile(
    resolver: ContentResolver,
    collection: Uri,
    id: Long?,
): File? =
    runCatching {
        val item = if (id != null) android.content.ContentUris.withAppendedId(collection, id) else collection
        resolver.query(item, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) File(it.getString(0)) else null
        }
    }.getOrNull()

private const val EXTERNAL_STORAGE_DOCUMENTS = "com.android.externalstorage.documents"
private const val DOWNLOADS_DOCUMENTS = "com.android.providers.downloads.documents"
