package com.pdfeditor.app.ui.nav

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OpenDocumentRequestTest {
    private val resolver: ContentResolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
    private val uri = Uri.parse("content://documents.example/document/42")

    @Test
    fun `view request opens content PDF without requiring a write grant`() {
        val intent =
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

        val request = parsePdfOpenRequest(intent, resolver)

        assertNotNull(request)
        assertEquals(uri, request!!.uri)
        assertTrue(request.hasPersistentRead)
        val grant = resolver.persistedUriPermissions.single { it.uri == uri }
        assertTrue(grant.isReadPermission)
        assertFalse(grant.isWritePermission)
    }

    @Test
    fun `temporary view request retains its nonpersistent grant for durable intake`() {
        val intent =
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val request = parsePdfOpenRequest(intent, resolver)

        assertEquals(uri, request?.uri)
        assertFalse(request!!.hasPersistentRead)
        assertTrue(resolver.persistedUriPermissions.isEmpty())
    }

    @Test
    fun `content provider with no MIME type can still open an opaque document URI`() {
        val request = parsePdfOpenRequest(Intent(Intent.ACTION_VIEW, uri), resolver)

        assertEquals(uri, request?.uri)
    }

    @Test
    fun `explicit non PDF type is rejected even with a PDF filename`() {
        val request =
            parsePdfOpenRequest(
                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://documents.example/report.pdf"), "image/png"),
                resolver,
            )

        assertNull(request)
    }

    @Test
    fun `provider MIME type rejects non PDF content when the sender omits a type`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val imageUri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                File(context.filesDir, "working_copies/photo.png"),
            )

        assertNull(parsePdfOpenRequest(Intent(Intent.ACTION_VIEW, imageUri), resolver))
    }

    @Test
    fun `remote PDF URLs are rejected`() {
        assertNull(
            parsePdfOpenRequest(
                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("https://example.org/a.pdf"), "application/pdf"),
                resolver,
            ),
        )
    }

    @Test
    fun `file PDF request opens without a persistable permission`() {
        val fileUri = Uri.parse("file:///sdcard/Download/report.PDF")

        val request = parsePdfOpenRequest(Intent(Intent.ACTION_VIEW, fileUri), resolver)

        assertEquals(fileUri, request?.uri)
        assertFalse(request!!.hasPersistentRead)
    }

    @Test
    fun `shared PDF stream opens`() {
        val intent = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)

        assertEquals(uri, parsePdfOpenRequest(intent, resolver)?.uri)
    }

    @Test
    fun `unrelated intent does not open its data URI`() {
        assertNull(parsePdfOpenRequest(Intent(Intent.ACTION_EDIT).setDataAndType(uri, "application/pdf"), resolver))
    }
}
