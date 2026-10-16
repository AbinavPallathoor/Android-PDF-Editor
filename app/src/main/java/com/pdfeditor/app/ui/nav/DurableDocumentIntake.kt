package com.pdfeditor.app.ui.nav

import android.content.ContentResolver
import android.net.Uri
import com.pdfeditor.app.data.DocumentMetadata
import com.pdfeditor.app.data.resolveDocumentMetadata
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest

internal data class DurableDocument(val uri: Uri, val metadata: DocumentMetadata)

/**
 * Called on IO before recording a Recent. Temporary handoffs become owned copies so the
 * document remains readable after the sending app's grant expires. The source is never written.
 */
internal fun prepareDurableDocument(
    resolver: ContentResolver,
    uri: Uri,
    importsDirectory: File,
    ownedProviderAuthority: String? = null,
): DurableDocument {
    val metadata = resolveDocumentMetadata(resolver, uri)
    val ownedProvider =
        ownedProviderAuthority != null && uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority == ownedProviderAuthority
    if (ownedProvider || isImportedDocumentUri(uri, importsDirectory) ||
        resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
    ) {
        return DurableDocument(uri, metadata)
    }
    check(importsDirectory.mkdirs() || importsDirectory.isDirectory) { "Could not create storage for the imported PDF." }
    val incoming = File.createTempFile("incoming-", ".pdf", importsDirectory)
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = resolver.openInputStream(uri) ?: error("The file provider could not open this PDF.")
        DigestInputStream(input, digest).use { source -> incoming.outputStream().use { source.copyTo(it) } }
        try {
            // Validate the independent copy before publishing either its durable URI or a row.
            PDDocument.load(incoming).use { document ->
                require(!document.isEncrypted) { "Password-protected PDFs are not supported yet." }
                require(document.numberOfPages > 0) { "This PDF has no pages." }
            }
        } catch (_: InvalidPasswordException) {
            throw IOException("Password-protected PDFs are not supported yet.")
        }
        val size = incoming.length()
        val sourceHash = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray(Charsets.UTF_8)).hex()
        val sourceDirectory = File(importsDirectory, sourceHash)
        check(sourceDirectory.mkdirs() || sourceDirectory.isDirectory) { "Could not store the imported PDF." }
        // Repeated handoffs reuse their draft identity. Changed bytes get a new identity, so an
        // incoming version can never overwrite the previous imported file or its unsaved edits.
        val target = File(sourceDirectory, "${digest.digest().hex()}.pdf")
        if (!target.isFile) check(incoming.renameTo(target)) { "Could not store the imported PDF." }
        return DurableDocument(Uri.fromFile(target), metadata.copy(sizeBytes = size))
    } finally {
        incoming.delete()
    }
}

internal fun isImportedDocumentUri(
    uri: Uri,
    importsDirectory: File,
): Boolean =
    uri.scheme == ContentResolver.SCHEME_FILE &&
        runCatching {
            val file = File(requireNotNull(uri.path)).canonicalFile
            file.isFile && file.path.startsWith(importsDirectory.canonicalPath + File.separator)
        }.getOrDefault(false)

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
