package com.pdfeditor.app.data.fonts

import android.graphics.Typeface
import android.util.AtomicFile
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

@Serializable
data class EditorFont(val id: String, val displayName: String)

/** Private, offline font library. Import and first Typeface load must run away from the UI thread. */
class FontRepository(filesDir: File) {
    private val directory = File(filesDir, "fonts").apply { mkdirs() }
    private val typefaces = mutableMapOf<String, Typeface>()

    @Synchronized
    fun list(): List<EditorFont> =
        BUILT_INS +
            directory.listFiles().orEmpty().map { File(directory, it.name.removeSuffix(".bak")) }
                .filter { it.extension == "json" }.distinct().mapNotNull { file ->
                    readMetadata(file.nameWithoutExtension)?.takeIf { hasStoredFont(it.id) }
                }.sortedBy { it.displayName.lowercase() }

    @Synchronized
    fun contains(id: String): Boolean {
        if (id in BUILT_IN_ASSETS) return true
        if (!isCustom(id) || !hasStoredFont(id) || readMetadata(id) == null) return false
        // A partial two-file import must not block restoration of the font and its catalogue
        // entry from a PDF. Corrupt bytes likewise need recovery, never a fallback Typeface.
        return runCatching { openFont(id).use { true } }.getOrDefault(false)
    }

    @Synchronized
    fun typeface(id: String): Typeface =
        typefaces.getOrPut(id) {
            // Typeface cannot read a stream. Built-in bytes are materialized in the same private
            // directory, while PDF export always uses the original asset.
            val file =
                if (id in BUILT_IN_ASSETS) {
                    File(directory, "builtin-$id.ttf").also { destination ->
                        if (!destination.isFile) openFont(id).use { atomicWrite(destination, it.readBytes()) }
                    }
                } else {
                    requireCustom(id)
                    fontFile(id).also { openFont(id).close() }
                }
            requireNotNull(Typeface.Builder(file).build()) { "The selected font cannot be displayed." }
        }

    @Synchronized
    fun openFont(id: String): InputStream {
        BUILT_IN_ASSETS[id]?.let { return PDFBoxResourceLoader.getStream(it) }
        requireCustom(id)
        val file = fontFile(id)
        require(hasStoredFont(id)) { "The selected font is missing. Import it again before editing or saving." }
        // AtomicFile recovers an interrupted write on API 26 as well as newer Android releases.
        val bytes = AtomicFile(file).openRead().use(::readBounded)
        require(customId(bytes) == id) { "The selected font file has changed. Import the original font again." }
        return ByteArrayInputStream(bytes)
    }

    @Synchronized
    fun importFont(
        input: InputStream,
        displayName: String,
    ): EditorFont = store(input, displayName, null)

    /** Restores only the exact full font referred to by an annotation, never a substitute or subset. */
    @Synchronized
    fun recoverFont(
        id: String,
        input: InputStream,
        displayName: String,
    ): EditorFont {
        requireCustom(id)
        return store(input, displayName, id)
    }

    private fun store(
        input: InputStream,
        displayName: String,
        expectedId: String?,
    ): EditorFont {
        val bytes = readBounded(input)
        val id = customId(bytes)
        require(expectedId == null || id == expectedId) { "The PDF does not contain the original editable font. Import that font again." }
        val existing = list().firstOrNull { it.id == id }
        val font = existing ?: EditorFont(id, displayName.trim().take(120).ifBlank { "Imported font" })
        // Both parsers must accept these same bytes. Checking the sfnt first excludes CFF, TTC,
        // WOFF and variable fonts, whose representation/shaping is not supported by this editor.
        require(bytes.size >= 12 && bytes[0] == 0.toByte() && bytes[1] == 1.toByte() && bytes[2] == 0.toByte() && bytes[3] == 0.toByte()) {
            "Choose a static TrueType font (.ttf or TrueType-based .otf). CFF, collections and web fonts are unsupported."
        }
        val tables = ((bytes[4].toInt() and 255) shl 8) or (bytes[5].toInt() and 255)
        require(tables in 1..256 && bytes.size >= 12 + tables * 16) { "This font file is invalid." }

        fun uint32(offset: Int): Long = (0 until 4).fold(0L) { value, index -> (value shl 8) or (bytes[offset + index].toLong() and 255L) }
        val tags =
            (0 until tables).map { index ->
                val record = 12 + index * 16
                val offset = uint32(record + 8)
                val length = uint32(record + 12)
                require(offset + length <= bytes.size.toLong()) { "This font contains an invalid table." }
                String(bytes, record, 4, Charsets.US_ASCII)
            }
        require("glyf" in tags && "loca" in tags && "fvar" !in tags) { "Only static TrueType outline fonts are supported." }
        val pending = File.createTempFile("import-", ".ttf", directory)
        try {
            pending.writeBytes(bytes)
            try {
                PDDocument().use { document ->
                    ByteArrayInputStream(bytes).use { stream ->
                        PDType0Font.load(document, stream, false).also {
                            require(!it.isDamaged) { "This font file is damaged." }
                        }
                    }
                }
                requireNotNull(Typeface.Builder(pending).build()) { "This font cannot be displayed." }
            } catch (failure: Exception) {
                throw IllegalArgumentException("This font cannot be used or embedded. Choose an embeddable static TrueType font.", failure)
            }
            atomicWrite(fontFile(id), bytes)
            atomicWrite(File(directory, "$id.json"), Json.encodeToString(font).toByteArray())
        } finally {
            pending.delete()
        }
        return font
    }

    private fun readMetadata(id: String): EditorFont? {
        if (!isCustom(id)) return null
        return runCatching {
            val bytes =
                AtomicFile(File(directory, "$id.json")).openRead().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        require(output.size() + count <= 4096) { "Font metadata is invalid." }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            Json.decodeFromString<EditorFont>(bytes.decodeToString()).takeIf {
                it.id == id && it.displayName.isNotBlank() && it.displayName.length <= 120
            }
        }.getOrNull()
    }

    private fun fontFile(id: String) = File(directory, "$id.ttf")

    private fun hasStoredFont(id: String): Boolean = fontFile(id).isFile || File(directory, "$id.ttf.bak").isFile

    private fun requireCustom(id: String) = require(isCustom(id)) { "The selected font is unavailable: $id" }

    private fun atomicWrite(
        file: File,
        bytes: ByteArray,
    ) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    companion object {
        const val MAX_FONT_BYTES = 16 * 1024 * 1024
        val BUILT_INS = listOf(EditorFont("roboto", "Sans"), EditorFont("droid-serif", "Serif"), EditorFont("droid-mono", "Mono"))
        private val CUSTOM_ID = Regex("custom-[0-9a-f]{64}")
        private val BUILT_IN_ASSETS =
            mapOf(
                "roboto" to "fonts/Roboto-Regular.ttf",
                "droid-serif" to "fonts/DroidSerif-Regular.ttf",
                "droid-mono" to "fonts/DroidSansMono.ttf",
            )

        fun isCustom(id: String): Boolean = CUSTOM_ID.matches(id)

        fun openBuiltIn(id: String): InputStream =
            PDFBoxResourceLoader.getStream(requireNotNull(BUILT_IN_ASSETS[id]) { "The selected font is unavailable: $id" })

        private fun customId(bytes: ByteArray) =
            "custom-" +
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                    "%02x".format(it)
                }

        private fun readBounded(input: InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                require(output.size() + count <= MAX_FONT_BYTES) { "Font files must be 16 MiB or smaller." }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}
