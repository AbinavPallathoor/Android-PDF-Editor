package com.pdfeditor.app.domain

/**
 * Result of attempting to open a PDF for reading. Modeled explicitly (rather than throwing) so the
 * two out-of-scope-for-v1 failure modes called out in the brief - password-protected PDFs and
 * corrupt files - can be surfaced to the user as a clear message instead of a crash.
 */
sealed interface OpenPdfResult {
    data class Success(val renderer: PdfDocumentRenderer) : OpenPdfResult

    data object PasswordProtected : OpenPdfResult

    data class Failed(val message: String) : OpenPdfResult
}
