package com.pdfeditor.app.domain

enum class FillTargetKind { TEXT, SIGNATURE }

/** A suggested empty fill area in displayed crop-box points, with a top-left origin. */
data class PdfFillTarget(
    val id: String,
    val bounds: PdfRect,
    val kind: FillTargetKind,
    val label: String = "",
)
