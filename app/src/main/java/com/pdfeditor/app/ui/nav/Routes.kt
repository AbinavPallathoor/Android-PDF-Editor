package com.pdfeditor.app.ui.nav

import android.net.Uri
import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

@Serializable
data class ReaderRoute(val documentId: Long, val encodedUri: String, val displayName: String) {
    companion object {
        fun forDocument(
            documentId: Long,
            uri: Uri,
            displayName: String,
        ) = ReaderRoute(documentId, Uri.encode(uri.toString()), displayName)
    }

    val uri: Uri get() = Uri.parse(Uri.decode(encodedUri))
}
