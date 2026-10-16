package com.pdfeditor.app.data.cache

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Keep the historical URI identity so v0.2.2 drafts remain recoverable. */
internal fun documentSessionDirectory(
    workingCopiesDir: File,
    uri: Uri,
): File =
    File(
        workingCopiesDir,
        MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) },
    )

/**
 * Adopt a legacy temporary-URI draft before opening its durable imported copy. Copy only the
 * captured draft, its immutable base and preferences; leave the entire original session intact.
 */
internal suspend fun migrateImportedDraft(
    sourceUri: Uri,
    durableUri: Uri,
    workingCopiesDir: File,
): Boolean =
    withContext(Dispatchers.IO) {
        workingCopySessions.withLock {
            if (sourceUri == durableUri) return@withLock false
            val source = documentSessionDirectory(workingCopiesDir, sourceUri)
            val destination = documentSessionDirectory(workingCopiesDir, durableUri)
            if (File(destination, "draft.json").exists()) return@withLock false
            val draft = DocumentDraftStore(File(source, "draft.json")).load() ?: return@withLock false
            val base = File(source, draft.baseFileName)
            check(base.isFile) { "The previous unsaved draft's PDF is missing. Its draft is retained." }
            check(!destination.exists()) { "The imported PDF already has a private session. The previous draft is retained." }
            val preferences = File(source, "preferences.json").takeIf { it.isFile }?.readBytes()
            val staged = Files.createTempDirectory(workingCopiesDir.toPath(), ".import-draft-").toFile()
            try {
                val copiedBase = base.copyTo(File(staged, draft.baseFileName))
                check(copiedBase.isFile && copiedBase.length() == base.length()) { "Could not copy the previous draft's PDF." }
                preferences?.let { File(staged, "preferences.json").writeBytes(it) }
                DocumentDraftStore(File(staged, "draft.json")).save(draft)
                check(DocumentDraftStore(File(staged, "draft.json")).load() == draft) { "Could not verify the recovered draft." }
                currentCoroutineContext().ensureActive()
                // Another intake may have completed while the snapshot was copied. Its newer
                // destination draft always wins; never replace it with the legacy snapshot.
                if (File(destination, "draft.json").exists()) return@withLock false
                check(!destination.exists()) { "The imported PDF already has a private session. The previous draft is retained." }
                Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                true
            } finally {
                staged.deleteRecursively()
            }
        }
    }
