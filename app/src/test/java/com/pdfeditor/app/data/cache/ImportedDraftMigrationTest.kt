package com.pdfeditor.app.data.cache

import android.net.Uri
import com.pdfeditor.app.domain.DocumentEdits
import com.pdfeditor.app.domain.DocumentPage
import com.pdfeditor.app.domain.DocumentSnapshot
import com.pdfeditor.app.domain.PdfPoint
import com.pdfeditor.app.domain.TextBoxAnnotation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class ImportedDraftMigrationTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val sourceUri = Uri.parse("content://external/document/report")
    private val importedUri = Uri.parse("file:///data/user/0/com.pdfeditor.app/files/imported_documents/source/content.pdf")

    @Test
    fun `legacy unsaved edits immutable base preferences and fingerprint survive imported identity`() =
        runBlocking {
            val old = session(sourceUri).apply { mkdirs() }
            val original = draft("Legacy unsaved text", "legacy-source-fingerprint")
            DocumentDraftStore(File(old, "draft.json")).save(original)
            val baseBytes = "immutable PDF base".toByteArray()
            File(old, original.baseFileName).writeBytes(baseBytes)
            val preferences = """{"invertColors":true,"paged":true}"""
            File(old, "preferences.json").writeText(preferences)
            File(old, "in-flight-text.pdf").writeText("temporary renderer/export snapshot")
            File(old, "before-replace-backup.pdf").writeText("retained old backup")

            assertTrue(migrateImportedDraft(sourceUri, importedUri, temporary.root))

            val destination = session(importedUri)
            val restored = DocumentDraftStore(File(destination, "draft.json")).load()
            assertEquals(original, restored)
            assertNotNull(restored)
            assertTrue(DocumentEdits(restored!!.current, restored.saved).isDirty)
            assertEquals("legacy-source-fingerprint", restored.sourceFingerprint)
            assertArrayEquals(baseBytes, File(destination, original.baseFileName).readBytes())
            assertEquals(preferences, File(destination, "preferences.json").readText())
            assertEquals(setOf("draft.json", original.baseFileName, "preferences.json"), destination.list()!!.toSet())
            assertEquals(original, DocumentDraftStore(File(old, "draft.json")).load())
            assertTrue(File(old, "before-replace-backup.pdf").isFile)
        }

    @Test
    fun `existing imported draft wins over legacy edits without overwriting anything`() =
        runBlocking {
            val old = session(sourceUri).apply { mkdirs() }
            DocumentDraftStore(File(old, "draft.json")).save(draft("Old draft", "old-fingerprint"))
            File(old, "base.pdf").writeText("old base")
            val destination = session(importedUri).apply { mkdirs() }
            val newer = draft("Newer imported edit", "newer-fingerprint")
            DocumentDraftStore(File(destination, "draft.json")).save(newer)
            File(destination, "base.pdf").writeText("newer base")
            File(destination, "preferences.json").writeText("newer preferences")

            assertFalse(migrateImportedDraft(sourceUri, importedUri, temporary.root))

            assertEquals(newer, DocumentDraftStore(File(destination, "draft.json")).load())
            assertEquals("newer base", File(destination, "base.pdf").readText())
            assertEquals("newer preferences", File(destination, "preferences.json").readText())
            assertEquals("old base", File(old, "base.pdf").readText())
        }

    @Test
    fun `missing legacy base never publishes partial draft or removes recoverable source`() =
        runBlocking {
            val old = session(sourceUri).apply { mkdirs() }
            val original = draft("Keep my unsaved text", "fingerprint")
            DocumentDraftStore(File(old, "draft.json")).save(original)

            assertThrows(IllegalStateException::class.java) {
                runBlocking { migrateImportedDraft(sourceUri, importedUri, temporary.root) }
            }

            assertFalse(session(importedUri).exists())
            assertEquals(original, DocumentDraftStore(File(old, "draft.json")).load())
            assertEquals(listOf(old.name), temporary.root.list()!!.toList())
        }

    @Test
    fun `no legacy draft does not create a new session`() =
        runBlocking {
            assertFalse(migrateImportedDraft(sourceUri, importedUri, temporary.root))
            assertTrue(temporary.root.listFiles()!!.isEmpty())
        }

    private fun draft(
        text: String,
        fingerprint: String,
    ): DocumentDraft {
        val saved = DocumentSnapshot(listOf(DocumentPage("page-one", 0)))
        val edits = DocumentEdits(saved)
        edits.add("page-one", TextBoxAnnotation("unsaved", PdfPoint(20f, 40f), text, 14f, -16777216))
        return DocumentDraft("base.pdf", edits.current, saved, fingerprint)
    }

    // Deliberately reproduce v0.2.2's identity independently so the migration must use exactly
    // the historical SHA-256 of the complete URI encoded as UTF-8.
    private fun session(uri: Uri): File =
        File(
            temporary.root,
            MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) },
        )
}
