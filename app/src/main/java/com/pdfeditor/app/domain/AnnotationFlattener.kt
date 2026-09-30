package com.pdfeditor.app.domain

import java.io.File

/**
 * Bakes [Annotation]s permanently into a PDF's page content streams. Separate from [PdfEditor]
 * (reorder/delete/append/compress, Milestones 5-6) since it's implemented first and has nothing to
 * do with page-list structure - interface segregation rather than one interface with unimplemented
 * methods sitting around early.
 */
interface AnnotationFlattener {
    suspend fun flattenAnnotations(
        source: File,
        annotationsByPage: Map<Int, List<Annotation>>,
    ): File
}
