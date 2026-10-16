package com.pdfeditor.app.data.cache

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
private data class SourceStamp(val sizeBytes: Long, val lastModified: Long, val fingerprint: String)

/**
 * Remembers the original's SHA-256 against its reported size and modification time, so reopening
 * an unchanged document doesn't re-read the whole file. Any metadata change, missing metadata, or a
 * stamp unused for [maxAgeMillis] falls back to hashing. Blocking; call on IO.
 */
internal class SourceFingerprintCache(
    private val file: File,
    private val maxAgeMillis: Long = TimeUnit.DAYS.toMillis(10),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun read(
        sizeBytes: Long?,
        lastModified: Long?,
    ): String? {
        if (sizeBytes == null || lastModified == null || lastModified <= 0L || !file.isFile) return null
        if (clock() - file.lastModified() > maxAgeMillis) return null
        val stamp = runCatching { Json.decodeFromString<SourceStamp>(file.readText()) }.getOrNull() ?: return null
        if (stamp.sizeBytes != sizeBytes || stamp.lastModified != lastModified) return null
        file.setLastModified(clock())
        return stamp.fingerprint
    }

    fun write(
        sizeBytes: Long?,
        lastModified: Long?,
        fingerprint: String,
    ) {
        if (sizeBytes == null || lastModified == null || lastModified <= 0L) {
            file.delete()
            return
        }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            temporary.writeText(Json.encodeToString(SourceStamp(sizeBytes, lastModified, fingerprint)))
            if (!temporary.renameTo(file)) file.delete()
        } catch (_: Exception) {
            file.delete()
        } finally {
            temporary.delete()
        }
    }
}
