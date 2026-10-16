package com.pdfeditor.app.data.cache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/** Serializes whole-session changes: expiry, legacy draft migration, and a reader claiming a session. */
internal val workingCopySessions = Mutex()

/** Mark [session] as just used, so expiry can never remove it while a reader is opening it. */
internal suspend fun claimWorkingCopySession(
    session: File,
    clock: () -> Long = System::currentTimeMillis,
) {
    withContext(Dispatchers.IO) {
        workingCopySessions.withLock {
            check(session.isDirectory || session.mkdirs()) { "Could not create the document's private storage." }
            session.setLastModified(clock())
        }
    }
}

/**
 * True when a draft holds nothing a fresh import of the original wouldn't recreate: no unsaved
 * changes, no annotations, and exactly the originally imported pages in their original order.
 * Drafts that never recorded their imported page count are always kept.
 */
internal fun isDisposableDraft(draft: DocumentDraft): Boolean {
    val count = draft.importedPageCount ?: return false
    val pristine = draft.current.pages.map { it.sourceIndex } == (0 until count).toList()
    return draft.current == draft.saved && pristine && draft.current.annotations.values.all { it.isEmpty() }
}

/**
 * Removes per-document working copies unused for [maxAgeMillis] that hold no edits, so storage
 * doesn't grow forever with documents that were only read. Sessions with annotations, page
 * changes, unsaved work or an unreadable draft are never touched. The next open re-imports.
 */
class WorkingCopyExpiry(
    private val directory: File,
    private val maxAgeMillis: Long = TimeUnit.DAYS.toMillis(10),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun prune() {
        withContext(Dispatchers.IO) {
            workingCopySessions.withLock {
                directory.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { session ->
                    if (expired(session)) session.deleteRecursively()
                }
            }
        }
    }

    private fun expired(session: File): Boolean {
        val lastUsed = session.walkTopDown().maxOf { it.lastModified() }
        if (clock() - lastUsed <= maxAgeMillis) return false
        val draft = File(session, "draft.json")
        // Without a draft nothing is recoverable from this session; a reader would re-import anyway.
        if (!draft.exists()) return true
        return runCatching { isDisposableDraft(Json.decodeFromString<DocumentDraft>(draft.readText())) }.getOrDefault(false)
    }
}
