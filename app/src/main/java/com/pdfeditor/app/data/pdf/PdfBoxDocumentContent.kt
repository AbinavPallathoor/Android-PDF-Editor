package com.pdfeditor.app.data.pdf

import com.pdfeditor.app.data.fonts.FontRepository
import com.pdfeditor.app.domain.Annotation
import com.pdfeditor.app.domain.DrawStroke
import com.pdfeditor.app.domain.HighlightAnnotation
import com.pdfeditor.app.domain.PdfPoint
import com.pdfeditor.app.domain.PdfRect
import com.pdfeditor.app.domain.PdfTextWord
import com.pdfeditor.app.domain.SignaturePlacement
import com.pdfeditor.app.domain.TextBoxAnnotation
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDCIDFontType2
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.hypot

/**
 * Editable standard PDF annotations, with an app model for lossless re-import. Existing page
 * streams and foreign annotations are retained. Coordinates use the displayed crop box after
 * rotation, in points, with a top-left origin. No PdfRenderer may hold this source open.
 */
class PdfBoxDocumentContent(private val fontRepository: FontRepository? = null) {
    suspend fun readAnnotations(source: File): Map<Int, List<Annotation>> =
        withContext(Dispatchers.IO) {
            PDDocument.load(source).use { document ->
                buildMap {
                    document.pages.forEachIndexed { index, page ->
                        val models =
                            page.annotations.mapNotNull { annotation ->
                                annotation.cosObject.getString(MODEL_KEY)?.let { encoded ->
                                    Json.decodeFromString<Annotation>(encoded).also { model ->
                                        if (model is TextBoxAnnotation && FontRepository.isCustom(model.fontId)) {
                                            val repository = fontRepository
                                            if (repository != null && !repository.contains(model.fontId)) {
                                                val font = annotation.normalAppearanceStream?.resources?.getFont(FONT_RESOURCE)
                                                val embedded = font?.fontDescriptor?.fontFile2
                                                requireNotNull(embedded) {
                                                    "This PDF is missing its editable font. Import the original font again."
                                                }
                                                embedded.createInputStream().use {
                                                    repository.recoverFont(
                                                        model.fontId,
                                                        it,
                                                        annotation.cosObject.getString(FONT_NAME_KEY) ?: "Recovered font",
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        if (models.isNotEmpty()) put(index, models)
                    }
                }
            }
        }

    /** [annotationsByPage] is the complete current model; omitted pages lose only app-owned marks. */
    suspend fun writeAnnotations(
        source: File,
        destination: File,
        annotationsByPage: Map<Int, List<Annotation>>,
    ): File =
        withContext(Dispatchers.IO) {
            require(source.canonicalFile != destination.canonicalFile) { "Save annotations to a new PDF, never the source" }
            require(!destination.exists()) { "Annotation destination must be a new file" }
            PDDocument.load(source).use { document ->
                require(annotationsByPage.keys.all { it in 0 until document.numberOfPages }) { "Annotation page does not exist" }
                val fonts = mutableMapOf<String, PDType0Font>()

                fun fontFor(model: TextBoxAnnotation): PDType0Font =
                    fonts.getOrPut(model.fontId) {
                        val input = fontRepository?.openFont(model.fontId) ?: FontRepository.openBuiltIn(model.fontId)
                        // Preserve original font bytes for recovery after moving the PDF to a new
                        // installation. Subsets cannot be used to edit arbitrary future text.
                        input.use { PDType0Font.load(document, it, !FontRepository.isCustom(model.fontId)) }
                    }
                document.pages.forEachIndexed { pageIndex, page ->
                    val retained = page.annotations.filterNot { it.cosObject.containsKey(MODEL_KEY) }.toMutableList()
                    annotationsByPage[pageIndex].orEmpty().forEach { model ->
                        retained += createAnnotation(document, page, model, if (model is TextBoxAnnotation) fontFor(model) else null)
                    }
                    page.annotations = retained
                }
                // All validation and appearance construction precede creating the destination.
                try {
                    document.save(destination)
                } catch (failure: Exception) {
                    destination.delete()
                    throw failure
                }
            }
            destination
        }

    suspend fun extractText(
        source: File,
        pageIndex: Int,
    ): List<PdfTextWord> = withContext(Dispatchers.IO) { PDDocument.load(source).use { extractText(it, pageIndex) } }

    /** Blocking; [document] must be a read-only snapshot not shared with another thread. */
    internal fun extractText(
        document: PDDocument,
        pageIndex: Int,
    ): List<PdfTextWord> {
        require(pageIndex in 0 until document.numberOfPages) { "Text page does not exist" }
        val words = mutableListOf<PdfTextWord>()
        val coordinates = PageCoordinates(document.getPage(pageIndex))
        val stripper =
            object : PDFTextStripper() {
                override fun writeString(
                    text: String,
                    textPositions: MutableList<TextPosition>,
                ) {
                    if (text.isEmpty() || textPositions.isEmpty()) return
                    val points =
                        textPositions.flatMap { position ->
                            // PDFBox's matrix/end point are already relative to the crop box.
                            val matrix = position.textMatrix
                            val start = PdfPoint(matrix.translateX, matrix.translateY)
                            val end = PdfPoint(position.endX, position.endY)
                            val length = hypot(end.x - start.x, end.y - start.y).coerceAtLeast(.001f)
                            val upX = -(end.y - start.y) / length * position.heightDir
                            val upY = (end.x - start.x) / length * position.heightDir
                            listOf(start, end, PdfPoint(start.x + upX, start.y + upY), PdfPoint(end.x + upX, end.y + upY))
                                .map { coordinates.toDisplayedCropPoint(it) }
                        }
                    words += PdfTextWord(text, points.bounds())
                }

                override fun writeWordSeparator() = appendSeparator(" ")

                override fun writeLineSeparator() = appendSeparator("\n")

                private fun appendSeparator(separator: String) {
                    if (words.isNotEmpty()) words[words.lastIndex] = words.last().let { it.copy(text = it.text + separator) }
                }
            }
        stripper.startPage = pageIndex + 1
        stripper.endPage = pageIndex + 1
        stripper.sortByPosition = true
        stripper.getText(document)
        return words
    }

    private fun createAnnotation(
        document: PDDocument,
        page: PDPage,
        model: Annotation,
        font: PDType0Font?,
    ): PDAnnotationMarkup {
        val coordinates = PageCoordinates(page)
        val colorArgb =
            when (model) {
                is DrawStroke -> model.colorArgb
                is TextBoxAnnotation -> model.colorArgb
                is SignaturePlacement -> model.colorArgb
                is HighlightAnnotation -> model.colorArgb
            }
        val color =
            PDColor(
                floatArrayOf((colorArgb ushr 16 and 255) / 255f, (colorArgb ushr 8 and 255) / 255f, (colorArgb and 255) / 255f),
                PDDeviceRGB.INSTANCE,
            )
        val alpha = (colorArgb ushr 24) / 255f
        val paths =
            when (model) {
                is DrawStroke -> listOf(model.points)
                is SignaturePlacement ->
                    model.strokes.map {
                            path ->
                        path.map { PdfPoint(model.position.x + it.x * model.scale, model.position.y + it.y * model.scale) }
                    }
                else -> emptyList()
            }
        val width = if (model is DrawStroke) model.strokeWidthPoints else 1.5f
        val displayedBounds =
            when (model) {
                is DrawStroke, is SignaturePlacement -> paths.flatten().ifEmpty { listOf(PdfPoint(0f, 0f)) }.bounds().expanded(width)
                is HighlightAnnotation -> model.rects.flatMap { it.corners() }.ifEmpty { listOf(PdfPoint(0f, 0f)) }.bounds()
                is TextBoxAnnotation -> {
                    require(model.fontSizePoints > 0f && model.fontSizePoints.isFinite()) { "Text font size must be positive" }
                    require(model.position.x.isFinite() && model.position.y.isFinite()) { "Text position must be finite" }
                    require(model.widthPoints == null || (model.widthPoints.isFinite() && model.widthPoints > 0f)) {
                        "Text box width must be finite and positive"
                    }
                    require(model.heightPoints == null || (model.heightPoints.isFinite() && model.heightPoints > 0f)) {
                        "Text box height must be finite and positive"
                    }
                    val textFont = requireNotNull(font)
                    val lines = model.text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
                    val maxWidth =
                        try {
                            lines.maxOf { textFont.getStringWidth(it) / 1000f * model.fontSizePoints }
                        } catch (unsupported: IllegalArgumentException) {
                            throw IllegalArgumentException(
                                "The selected font cannot display one or more characters in this text",
                                unsupported,
                            )
                        }
                    val glyphs = textGlyphBounds(textFont, model, lines)
                    PdfRect(
                        minOf(model.position.x, glyphs?.left ?: model.position.x),
                        minOf(model.position.y - model.fontSizePoints, glyphs?.top ?: model.position.y),
                        maxOf(model.position.x + maxOf(maxWidth, model.widthPoints ?: 0f, 1f), glyphs?.right ?: model.position.x),
                        maxOf(
                            model.position.y + (lines.size - 1) * model.fontSizePoints * 1.25f + model.fontSizePoints * .3f,
                            model.position.y - model.fontSizePoints + (model.heightPoints ?: 0f),
                            glyphs?.bottom ?: model.position.y,
                        ),
                    ).also { bounds ->
                        require(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).all { it.isFinite() }) {
                            "Text box geometry is too large"
                        }
                    }
                }
            }
        val nativeBounds = displayedBounds.corners().map { coordinates.toNative(it) }.bounds().expanded(.5f)
        val nativeRect =
            PDRectangle(
                nativeBounds.left,
                nativeBounds.top,
                (nativeBounds.right - nativeBounds.left).coerceAtLeast(1f),
                (nativeBounds.bottom - nativeBounds.top).coerceAtLeast(1f),
            )
        val annotation =
            if (model is HighlightAnnotation) {
                PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT).apply {
                    quadPoints =
                        model.rects.flatMap { rect ->
                            listOf(
                                PdfPoint(rect.left, rect.top),
                                PdfPoint(rect.right, rect.top),
                                PdfPoint(rect.left, rect.bottom),
                                PdfPoint(rect.right, rect.bottom),
                            )
                                .map(coordinates::toNative).flatMap { listOf(it.x, it.y) }
                        }.toFloatArray()
                }
            } else {
                PDAnnotationMarkup().apply {
                    cosObject.setName(COSName.SUBTYPE, if (model is TextBoxAnnotation) "FreeText" else "Ink")
                    if (model !is TextBoxAnnotation) {
                        inkList =
                            paths.map {
                                    path ->
                                path.map(coordinates::toNative).flatMap { listOf(it.x, it.y) }.toFloatArray()
                            }.toTypedArray()
                    }
                    borderStyle = PDBorderStyleDictionary().apply { this.width = width }
                }
            }
        annotation.rectangle = nativeRect
        annotation.page = page
        annotation.color = color
        annotation.constantOpacity = alpha
        annotation.isPrinted = true
        annotation.annotationName = "pdfeditor:${model.id}"
        annotation.cosObject.setString(MODEL_KEY, Json.encodeToString(model))
        if (model is TextBoxAnnotation) {
            annotation.contents = model.text
            annotation.defaultAppearance = "/EditorFont ${model.fontSizePoints} Tf ${color.components.joinToString(" ")} rg"
            fontRepository?.list()?.firstOrNull { it.id == model.fontId }?.let {
                annotation.cosObject.setString(FONT_NAME_KEY, it.displayName)
            }
        }
        val appearance =
            PDAppearanceStream(document).apply {
                resources = PDResources()
                bBox = nativeRect
            }
        PDPageContentStream(document, appearance).use { stream ->
            stream.setStrokingColor(color)
            stream.setNonStrokingColor(color)
            stream.setGraphicsStateParameters(
                PDExtendedGraphicsState().apply {
                    strokingAlphaConstant = alpha
                    nonStrokingAlphaConstant = alpha
                    if (model is HighlightAnnotation) blendMode = BlendMode.MULTIPLY
                },
            )
            when (model) {
                is DrawStroke, is SignaturePlacement -> {
                    stream.setLineWidth(width)
                    stream.setLineCapStyle(1)
                    stream.setLineJoinStyle(1)
                    paths.filter { it.isNotEmpty() }.forEach { path ->
                        val points = path.map(coordinates::toNative)
                        stream.moveTo(points.first().x, points.first().y)
                        if (points.size == 1) stream.lineTo(points.first().x + .01f, points.first().y)
                        points.drop(1).forEach { stream.lineTo(it.x, it.y) }
                        stream.stroke()
                    }
                }
                is HighlightAnnotation ->
                    model.rects.forEach { rect ->
                        val points = rect.corners().map(coordinates::toNative)
                        stream.moveTo(points.first().x, points.first().y)
                        points.drop(1).forEach { stream.lineTo(it.x, it.y) }
                        stream.closePath()
                        stream.fill()
                    }
                is TextBoxAnnotation -> {
                    appearance.resources.put(FONT_RESOURCE, requireNotNull(font))
                    model.text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEachIndexed { index, line ->
                        val origin =
                            coordinates.toNative(
                                PdfPoint(model.position.x, model.position.y + index * model.fontSizePoints * 1.25f),
                            )
                        val xAxis = coordinates.toNative(PdfPoint(1f, 0f))
                        val yAxis = coordinates.toNative(PdfPoint(0f, -1f))
                        val zero = coordinates.toNative(PdfPoint(0f, 0f))
                        stream.beginText()
                        stream.setFont(font, model.fontSizePoints)
                        stream.setTextMatrix(
                            Matrix(xAxis.x - zero.x, xAxis.y - zero.y, yAxis.x - zero.x, yAxis.y - zero.y, origin.x, origin.y),
                        )
                        stream.showText(line)
                        stream.endText()
                    }
                }
            }
        }
        annotation.appearance = PDAppearanceDictionary().apply { setNormalAppearance(appearance) }
        return annotation
    }

    /** Include actual glyph overhangs/accents, which string advance widths alone omit. */
    private fun textGlyphBounds(
        font: PDType0Font,
        model: TextBoxAnnotation,
        lines: List<String>,
    ): PdfRect? {
        val trueType = (font.descendantFont as? PDCIDFontType2)?.trueTypeFont ?: return null
        val cmap = trueType.unicodeCmapLookup
        val scale = model.fontSizePoints / trueType.unitsPerEm
        var bounds: PdfRect? = null
        lines.forEachIndexed { lineIndex, line ->
            var advance = 0f
            var offset = 0
            val baseline = model.position.y + lineIndex * model.fontSizePoints * 1.25f
            while (offset < line.length) {
                val codePoint = line.codePointAt(offset)
                val character = String(Character.toChars(codePoint))
                val glyph = trueType.glyph.getGlyph(cmap.getGlyphId(codePoint))
                if (glyph != null) {
                    val rect =
                        PdfRect(
                            model.position.x + advance + glyph.xMinimum * scale,
                            baseline - glyph.yMaximum * scale,
                            model.position.x + advance + glyph.xMaximum * scale,
                            baseline - glyph.yMinimum * scale,
                        )
                    bounds = bounds?.let {
                        PdfRect(
                            minOf(it.left, rect.left), minOf(it.top, rect.top), maxOf(it.right, rect.right), maxOf(it.bottom, rect.bottom),
                        )
                    } ?: rect
                }
                advance += font.getStringWidth(character) / 1000f * model.fontSizePoints
                offset += Character.charCount(codePoint)
            }
        }
        return bounds
    }

    private class PageCoordinates(page: PDPage) {
        private val crop = page.cropBox
        private val rotation = ((page.rotation % 360) + 360) % 360

        fun toNative(point: PdfPoint): PdfPoint {
            val unrotated =
                when (rotation) {
                    90 -> PdfPoint(point.y, point.x)
                    180 -> PdfPoint(crop.width - point.x, point.y)
                    270 -> PdfPoint(crop.width - point.y, crop.height - point.x)
                    else -> PdfPoint(point.x, crop.height - point.y)
                }
            return PdfPoint(unrotated.x + crop.lowerLeftX, unrotated.y + crop.lowerLeftY)
        }

        fun toDisplayedCropPoint(point: PdfPoint): PdfPoint =
            when (rotation) {
                90 -> PdfPoint(point.y, point.x)
                180 -> PdfPoint(crop.width - point.x, point.y)
                270 -> PdfPoint(crop.height - point.y, crop.width - point.x)
                else -> PdfPoint(point.x, crop.height - point.y)
            }
    }

    private fun PdfRect.corners() = listOf(PdfPoint(left, top), PdfPoint(right, top), PdfPoint(right, bottom), PdfPoint(left, bottom))

    private fun PdfRect.expanded(amount: Float) = PdfRect(left - amount, top - amount, right + amount, bottom + amount)

    private fun List<PdfPoint>.bounds() = PdfRect(minOf { it.x }, minOf { it.y }, maxOf { it.x }, maxOf { it.y })

    companion object {
        private val MODEL_KEY = COSName.getPDFName("PdfEditorAnnotationV1")
        private val FONT_RESOURCE = COSName.getPDFName("EditorFont")
        private val FONT_NAME_KEY = COSName.getPDFName("PdfEditorFontName")
    }
}
