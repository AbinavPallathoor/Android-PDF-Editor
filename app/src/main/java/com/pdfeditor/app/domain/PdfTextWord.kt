package com.pdfeditor.app.domain

/** Text in reading order. Whitespace belongs to the preceding word, preserving copied lines. */
data class PdfTextWord(val text: String, val bounds: PdfRect)
