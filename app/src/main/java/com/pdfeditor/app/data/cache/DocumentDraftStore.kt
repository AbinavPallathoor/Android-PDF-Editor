package com.pdfeditor.app.data.cache

import com.pdfeditor.app.domain.DocumentSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class DocumentDraft(
    val baseFileName: String,
    val current: DocumentSnapshot,
    val saved: DocumentSnapshot,
    val sourceFingerprint: String? = null,
    /** Pages in the original at import; lets expiry tell untouched sessions from edited ones. */
    val importedPageCount: Int? = null,
) {
    init {
        require(
            baseFileName.isNotBlank() &&
                baseFileName != "." &&
                baseFileName != ".." &&
                '/' !in baseFileName &&
                '\\' !in baseFileName &&
                '\u0000' !in baseFileName,
        ) { "Draft base file must be a filename within the document's private directory" }
    }
}

/** Missing drafts return null. Invalid or unreadable drafts throw so callers can offer recovery. */
class DocumentDraftStore(private val file: File) {
    private val mutex = Mutex()

    suspend fun load(): DocumentDraft? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!file.exists()) return@withLock null
                Json.decodeFromString<DocumentDraft>(file.readText())
            }
        }

    /** Sync the complete temporary file before atomically replacing the previous recoverable draft. */
    suspend fun save(draft: DocumentDraft) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val parent = requireNotNull(file.absoluteFile.parentFile)
                check(parent.isDirectory || parent.mkdirs()) { "Cannot create draft directory" }
                val temporary = File.createTempFile(".draft-", ".tmp", parent)
                try {
                    FileOutputStream(temporary).use { output ->
                        output.write(Json.encodeToString(draft).toByteArray(Charsets.UTF_8))
                        output.fd.sync()
                    }
                    Files.move(
                        temporary.toPath(),
                        file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } finally {
                    temporary.delete()
                }
            }
        }
    }
}
