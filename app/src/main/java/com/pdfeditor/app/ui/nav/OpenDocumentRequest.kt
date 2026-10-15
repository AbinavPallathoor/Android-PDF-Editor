package com.pdfeditor.app.ui.nav

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import java.util.Locale

data class OpenDocumentRequest(val uri: Uri, val hasPersistentRead: Boolean)

/** Accept local document handoffs; PdfRenderer reports malformed or unreadable PDF content. */
fun parsePdfOpenRequest(
    intent: Intent,
    resolver: ContentResolver,
): OpenDocumentRequest? {
    val uri =
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme != ContentResolver.SCHEME_CONTENT && scheme != ContentResolver.SCHEME_FILE) return null

    val statedType = intent.type?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
    val type =
        if (statedType == null || statedType == "application/octet-stream" || statedType == "*/*") {
            try {
                resolver.getType(uri)?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            } catch (_: SecurityException) {
                null
            }
        } else {
            statedType
        }
    if (type != null && type != "application/pdf" && type != "application/octet-stream" && type != "*/*") return null
    // Providers often omit a type and expose opaque document IDs. Let the PDF parser validate
    // those; file URIs have a usable path, so require a PDF extension when no type is available.
    if (scheme == ContentResolver.SCHEME_FILE && type != "application/pdf" && !uri.path.orEmpty().endsWith(".pdf", true)) return null

    return OpenDocumentRequest(uri, persistReadPermission(uri, intent.flags, resolver))
}

/** Only persist grant modes actually offered by the provider, never invent a write grant. */
internal fun persistReadPermission(
    uri: Uri,
    flags: Int,
    resolver: ContentResolver,
): Boolean {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
    if (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0 && flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
        val modes = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            resolver.takePersistableUriPermission(uri, modes)
        } catch (_: SecurityException) {
            // Temporary access is enough for this session, but not for a reopenable Recent.
        }
    }
    return resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
}
