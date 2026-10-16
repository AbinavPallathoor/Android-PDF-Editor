package com.pdfeditor.app.ui.nav

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DurableDocumentIntakeTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var application: Application
    private lateinit var provider: TemporaryPdfProvider
    private lateinit var imports: File
    private val sourceUri = Uri.parse("content://temporary-pdf/document/opaque-id")

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(application)
        imports = temporary.newFolder("imported_documents")
        provider = TemporaryPdfProvider().apply { source = fixture("source.pdf") }
        provider.attachInfo(application, ProviderInfo().apply { authority = "temporary-pdf" })
        ShadowContentResolver.registerProviderInternal("temporary-pdf", provider)
    }

    @Test
    fun `temporary view and send remain readable from their durable URI after source access is revoked`() {
        for (action in listOf(Intent.ACTION_VIEW, Intent.ACTION_SEND)) {
            provider.readable = true
            val intent = Intent(action).setType("application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (action == Intent.ACTION_VIEW) intent.data = sourceUri else intent.putExtra(Intent.EXTRA_STREAM, sourceUri)
            val request = requireNotNull(parsePdfOpenRequest(intent, application.contentResolver))
            assertFalse(request.hasPersistentRead)

            val imported = prepareDurableDocument(application.contentResolver, request.uri, imports)
            provider.readable = false

            assertNotEquals(sourceUri, imported.uri)
            assertTrue(isImportedDocumentUri(imported.uri, imports))
            assertEquals("Shared report.pdf", imported.metadata.displayName)
            assertArrayEquals(
                provider.source.readBytes(),
                application.contentResolver.openInputStream(imported.uri)!!.use { it.readBytes() },
            )
            // Reopening the Recent uses only the owned file and cannot call the revoked provider.
            val reopened = prepareDurableDocument(application.contentResolver, imported.uri, imports)
            assertEquals(imported.uri, reopened.uri)
            assertEquals(1, PDDocument.load(File(reopened.uri.path!!)).use { it.numberOfPages })
        }
    }

    @Test
    fun `persisted SAF document retains original URI and write grant without import`() {
        val flags =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        assertTrue(persistReadPermission(sourceUri, flags, application.contentResolver))

        val opened = prepareDurableDocument(application.contentResolver, sourceUri, imports)

        assertEquals(sourceUri, opened.uri)
        assertTrue(application.contentResolver.persistedUriPermissions.single().isWritePermission)
        assertTrue(imports.listFiles()!!.isEmpty())
    }

    @Test
    fun `our own provider retains its durable URI without copying`() {
        // The authority comes from our package in production. Use the registered provider here
        // to test the authority branch without populating AndroidX FileProvider's static cache.
        val opened = prepareDurableDocument(application.contentResolver, sourceUri, imports, sourceUri.authority)

        assertEquals(sourceUri, opened.uri)
        assertTrue(imports.listFiles()!!.isEmpty())
    }

    @Test
    fun `repeat handoff reuses its copy while changed source keeps prior copy and draft identity`() {
        val first = prepareDurableDocument(application.contentResolver, sourceUri, imports)
        val repeated = prepareDurableDocument(application.contentResolver, sourceUri, imports)
        assertEquals(first.uri, repeated.uri)
        val previousBytes = File(first.uri.path!!).readBytes()
        provider.source = fixture("changed.pdf", pages = 2)

        val changed = prepareDurableDocument(application.contentResolver, sourceUri, imports)

        assertNotEquals(first.uri, changed.uri)
        assertArrayEquals(previousBytes, File(first.uri.path!!).readBytes())
        assertEquals(2, PDDocument.load(File(changed.uri.path!!)).use { it.numberOfPages })
    }

    @Test
    fun `malformed temporary handoff leaves no durable import to record`() {
        provider.source.writeText("This is not a PDF")
        try {
            prepareDurableDocument(application.contentResolver, sourceUri, imports)
            fail("Malformed PDF must not enter recents")
        } catch (_: java.io.IOException) {
            assertTrue(imports.walkTopDown().none { it.isFile })
        }
    }

    @Test
    fun `failed provider access leaves no partial import`() {
        provider.readable = false
        try {
            prepareDurableDocument(application.contentResolver, sourceUri, imports)
            fail("An unreadable source must not enter recents")
        } catch (_: SecurityException) {
            assertTrue(imports.walkTopDown().none { it.isFile })
        }
    }

    private fun fixture(
        name: String,
        pages: Int = 1,
    ): File =
        File(temporary.root, name).also { file ->
            PDDocument().use { pdf ->
                repeat(pages) { pdf.addPage(PDPage()) }
                pdf.save(file)
            }
        }

    class TemporaryPdfProvider : ContentProvider() {
        lateinit var source: File
        var readable = true

        override fun onCreate() = true

        override fun openFile(
            uri: Uri,
            mode: String,
        ): ParcelFileDescriptor {
            if (!readable) throw SecurityException("Temporary URI grant revoked")
            return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            if (!readable) throw SecurityException("Temporary URI grant revoked")
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                addRow(arrayOf<Any>("Shared report.pdf", source.length()))
            }
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
