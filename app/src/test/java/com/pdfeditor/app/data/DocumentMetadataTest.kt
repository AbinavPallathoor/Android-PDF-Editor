package com.pdfeditor.app.data

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@Config(application = Application::class)
@RunWith(RobolectricTestRunner::class)
class DocumentMetadataTest {
    private lateinit var application: Application
    private lateinit var provider: MetadataProvider
    private val uri = Uri.parse("content://metadata-test/document/report.pdf")

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        provider = MetadataProvider()
        provider.attachInfo(application, ProviderInfo().apply { authority = "metadata-test" })
        ShadowContentResolver.registerProviderInternal("metadata-test", provider)
    }

    // A revoked provider grant must not prevent navigation into the recoverable document session.
    @Test
    fun revokedProviderReturnsUriNameInsteadOfThrowing() {
        provider.failure = SecurityException("grant revoked")
        assertEquals(DocumentMetadata("report.pdf", null), resolveDocumentMetadata(application.contentResolver, uri))
    }

    // A transient/broken provider is optional metadata, not grounds for crashing the open flow.
    @Test
    fun unavailableProviderWithoutPathReturnsUntitledFallback() {
        provider.failure = IllegalArgumentException("provider unavailable")
        assertEquals(
            DocumentMetadata("Untitled.pdf", null),
            resolveDocumentMetadata(application.contentResolver, Uri.parse("content://metadata-test")),
        )
    }

    // Provider metadata must win over an encoded/document-id URI path segment.
    @Test
    fun providerNameAndSizeAreReturnedWhenAvailable() {
        assertEquals(DocumentMetadata("Named document.pdf", 8192L), resolveDocumentMetadata(application.contentResolver, uri))
    }

    // Providers need not implement OpenableColumns; missing columns must be handled safely.
    @Test
    fun missingOpenableColumnsReturnsUriFallback() {
        provider.columns = arrayOf("other")
        provider.values = arrayOf("ignored")
        assertEquals(DocumentMetadata("report.pdf", null), resolveDocumentMetadata(application.contentResolver, uri))
    }

    class MetadataProvider : ContentProvider() {
        var failure: RuntimeException? = null
        var columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        var values: Array<Any?> = arrayOf("Named document.pdf", 8192L)

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            failure?.let { throw it }
            return MatrixCursor(columns).apply { addRow(values) }
        }

        override fun getType(uri: Uri) = "application/pdf"

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0
    }
}
