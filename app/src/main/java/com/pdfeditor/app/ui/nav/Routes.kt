package com.pdfeditor.app.ui.nav

import android.net.Uri
import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

@Serializable
data class ReaderRoute(
    val documentId: Long,
    val encodedUri: String,
    val displayName: String,
    /** An [OpenOrigin]; kept across header document switches so Back still leaves the app. */
    val origin: Int = OpenOrigin.IN_APP,
) {
    companion object {
        fun forDocument(
            documentId: Long,
            uri: Uri,
            displayName: String,
            origin: Int = OpenOrigin.IN_APP,
        ) = ReaderRoute(documentId, Uri.encode(uri.toString()), displayName, origin)
    }

    val uri: Uri get() = Uri.parse(Uri.decode(encodedUri))
}
