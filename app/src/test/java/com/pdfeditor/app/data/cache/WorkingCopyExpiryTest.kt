package com.pdfeditor.app.data.cache

import com.pdfeditor.app.domain.DocumentPage
import com.pdfeditor.app.domain.DocumentSnapshot
import com.pdfeditor.app.domain.DrawStroke
import com.pdfeditor.app.domain.PdfPoint
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class WorkingCopyExpiryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = System.currentTimeMillis()
    private val old = now - TimeUnit.DAYS.toMillis(11)
    private val pages = listOf(DocumentPage("a", 0), DocumentPage("b", 1))
    private val pristine = DocumentSnapshot(pages)

    // Catches deleting real work: annotations, page edits, unsaved changes, or unknown history.
    @Test fun onlyUnusedSessionsWithoutEditsExpire() =
        runBlocking {
            val stroke = DrawStroke("s", listOf(PdfPoint(1f, 1f), PdfPoint(2f, 2f)), 0, 1f)
            val annotated = DocumentSnapshot(pages, mapOf("a" to listOf(stroke)))
            val reordered = DocumentSnapshot(pages.reversed())
            session("read-only", DocumentDraft("base.pdf", pristine, pristine, importedPageCount = 2), old)
            session("no-draft", null, old)
            session("annotated", DocumentDraft("base.pdf", annotated, annotated, importedPageCount = 2), old)
            session("reordered", DocumentDraft("base.pdf", reordered, reordered, importedPageCount = 2), old)
            session("unsaved", DocumentDraft("base.pdf", pristine, annotated, importedPageCount = 2), old)
            session("appended", DocumentDraft("base.pdf", pristine, pristine, importedPageCount = 1), old)
            session("legacy", DocumentDraft("base.pdf", pristine, pristine), old)
            session("recent", DocumentDraft("base.pdf", pristine, pristine, importedPageCount = 2), now - TimeUnit.DAYS.toMillis(9))
            File(temporary.root, ".import-draft-1").apply { mkdirs() }.setLastModified(old)
            WorkingCopyExpiry(temporary.root, clock = { now }).prune()
            assertEquals(
                listOf(".import-draft-1", "annotated", "appended", "legacy", "recent", "reordered", "unsaved"),
                temporary.root.list()!!.sorted(),
            )
        }

    // Catches expiry removing a session that a reader is in the middle of opening.
    @Test fun claimedSessionsAreNotExpired() =
        runBlocking {
            val directory = session("opening", DocumentDraft("base.pdf", pristine, pristine, importedPageCount = 2), old)
            claimWorkingCopySession(directory, clock = { now })
            WorkingCopyExpiry(temporary.root, clock = { now }).prune()
            assertEquals(listOf("opening"), temporary.root.list()!!.toList())
        }

    private fun session(
        name: String,
        draft: DocumentDraft?,
        lastUsed: Long,
    ): File {
        val directory = File(temporary.root, name).apply { mkdirs() }
        File(directory, "base.pdf").writeText("%PDF")
        draft?.let { File(directory, "draft.json").writeText(Json.encodeToString(it)) }
        directory.walkTopDown().forEach { it.setLastModified(lastUsed) }
        return directory
    }
}
