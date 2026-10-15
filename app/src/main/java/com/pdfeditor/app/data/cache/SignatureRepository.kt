package com.pdfeditor.app.data.cache

import com.pdfeditor.app.domain.PdfPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** One signature, stored in the same canonical 200x80-point box regardless of where it's later placed (see `normalizeSignature`). */
@Serializable
data class SavedSignature(val strokes: List<List<PdfPoint>>)

/**
 * Persists the user's drawn signatures as a small JSON file in [filesDir], so a signature drawn
 * once can be reused across documents and app restarts ("select it again") instead of being
 * redrawn every time. Deliberately a flat file rather than a Room table - this is a handful of
 * small records with no querying needs beyond "load them all".
 */
class SignatureRepository(private val file: File) {
    suspend fun load(): List<SavedSignature> =
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext emptyList()
            try {
                Json.decodeFromString<List<SavedSignature>>(file.readText())
            } catch (e: Exception) {
                emptyList()
            }
        }

    suspend fun save(signatures: List<SavedSignature>) {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            file.writeText(Json.encodeToString(signatures))
        }
    }
}
