package com.pdfeditor.app.data.pdf

import android.graphics.Path
import android.graphics.PointF
import com.pdfeditor.app.domain.FillTargetKind
import com.pdfeditor.app.domain.PdfFillTarget
import com.pdfeditor.app.domain.PdfPoint
import com.pdfeditor.app.domain.PdfRect
import com.pdfeditor.app.domain.PdfTextWord
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Read-only suggestions from form widgets and clearly labelled, empty printed outlines.
 * [source] must be a private immutable read copy, separate from any open PdfRenderer file.
 * No raster interpretation or OCR is attempted. Printed guesses deliberately favour omissions.
 */
class PdfBoxFillTargetDetector {
    suspend fun detect(
        source: File,
        pageIndex: Int,
    ): List<PdfFillTarget> = withContext(Dispatchers.IO) { PDDocument.load(source).use { detect(it, pageIndex) } }

    /** [document] must be an immutable read snapshot; callers serialize access to it. */
    suspend fun detect(
        document: PDDocument,
        pageIndex: Int,
    ): List<PdfFillTarget> =
        withContext(Dispatchers.IO) {
            require(pageIndex in 0 until document.numberOfPages) { "Fill target page does not exist" }
            val page = document.getPage(pageIndex)
            val coordinates = Coordinates(page)
            val widgetBounds = mutableListOf<PdfRect>()
            val targets = mutableListOf<PdfFillTarget>()
            val pageWidgets = page.annotations.filterIsInstance<PDAnnotationWidget>().map { it.cosObject }.toSet()
            document.documentCatalog.acroForm?.fieldTree?.forEachIndexed { fieldIndex, field ->
                currentCoroutineContext().ensureActive()
                if (field !is PDTerminalField) return@forEachIndexed
                field.widgets.forEachIndexed widgetLoop@{ widgetIndex, widget ->
                    val widgetPage = widget.page
                    if (widgetPage?.cosObject != page.cosObject && (widgetPage != null || widget.cosObject !in pageWidgets)) {
                        return@widgetLoop
                    }
                    val bounds = widget.rectangle?.let(coordinates::fromNative) ?: return@widgetLoop
                    if (!coordinates.contains(bounds)) return@widgetLoop
                    // Ineligible widgets also reserve their area: their printed borders must
                    // never reappear as guessed fields that bypass flags or existing values.
                    widgetBounds += bounds
                    if (field.isReadOnly || widget.isReadOnly || widget.isHidden || widget.isInvisible || widget.isNoView) {
                        return@widgetLoop
                    }
                    val kind =
                        when (field) {
                            is PDTextField -> {
                                if (field.isPassword || field.isFileSelect || field.valueAsString.isNotBlank()) return@widgetLoop
                                FillTargetKind.TEXT
                            }
                            is PDSignatureField -> {
                                if (field.cosObject.getDictionaryObject(COSName.V) != null) return@widgetLoop
                                FillTargetKind.SIGNATURE
                            }
                            else -> return@widgetLoop
                        }
                    targets +=
                        PdfFillTarget(
                            "widget:$pageIndex:$fieldIndex:$widgetIndex:${field.fullyQualifiedName}",
                            coordinates.toDisplayed(bounds),
                            kind,
                            field.alternateFieldName?.takeIf { it.isNotBlank() } ?: field.partialName.orEmpty(),
                        )
                }
            }
            currentCoroutineContext().ensureActive()
            val words =
                PdfBoxDocumentContent().extractText(document, pageIndex).map { word ->
                    word.copy(bounds = coordinates.fromDisplayed(word.bounds))
                }
            // Typed underscore blanks ("Name: ______") are text, not graphics; split them out as lines.
            val pieces = words.flatMap(::pieces)
            val labels = pieces.filterNot { it.line }.mapNotNull { piece -> labelKind(piece.word.text)?.let { Label(piece.word, it) } }
            if (labels.isNotEmpty()) {
                val graphics = PrintedGraphics(page, coordinates)
                graphics.processPage(page)
                currentCoroutineContext().ensureActive()
                if (!graphics.tooComplex) {
                    val foreignMarks =
                        page.annotations.filterNot { it is PDAnnotationWidget || it.isHidden || it.isInvisible || it.isNoView }
                            .mapNotNull { it.rectangle?.let(coordinates::fromNative) }
                    graphics.outlines.forEach { bounds ->
                        if (!coordinates.contains(bounds) || widgetBounds.any { overlaps(it, bounds) }) return@forEach
                        val label =
                            labels.filter { near(it.word.bounds, bounds) }
                                .minByOrNull { distance(it.word.bounds, bounds) } ?: return@forEach
                        val interior = bounds.inset(2f)
                        if (words.any { it.text.isNotBlank() && overlaps(interior, it.bounds) }) return@forEach
                        if ((graphics.occupied + foreignMarks).any { overlaps(interior, it) }) return@forEach
                        if (targets.any { overlaps(coordinates.fromDisplayed(it.bounds), bounds) }) return@forEach
                        targets +=
                            PdfFillTarget(
                                "printed:$pageIndex:${bounds.left}:${bounds.top}:${bounds.right}:${bounds.bottom}",
                                coordinates.toDisplayed(bounds),
                                label.kind,
                                label.word.text.trim(),
                            )
                    }
                    // Underscores mark where to write, so only other text counts as an existing entry.
                    val writing = pieces.filter { !it.line && it.word.text.isNotBlank() }.map { it.word.bounds }
                    pieces.filter { it.line }.forEach { line ->
                        val (bounds, label) = underscoreField(line.word.bounds, labels) ?: return@forEach
                        if (!coordinates.contains(bounds) || widgetBounds.any { overlaps(it, bounds) }) return@forEach
                        val interior = bounds.inset(2f)
                        if (writing.any { overlaps(interior, it) }) return@forEach
                        if ((graphics.occupied + foreignMarks).any { overlaps(interior, it) }) return@forEach
                        if (targets.any { overlaps(coordinates.fromDisplayed(it.bounds), bounds) }) return@forEach
                        targets +=
                            PdfFillTarget(
                                "underscore:$pageIndex:${bounds.left}:${bounds.top}:${bounds.right}:${bounds.bottom}",
                                coordinates.toDisplayed(bounds),
                                label.kind,
                                label.word.text.trim(),
                            )
                    }
                }
            }
            targets.sortedWith(compareBy<PdfFillTarget> { it.bounds.top }.thenBy { it.bounds.left }).take(100)
        }

    private data class Label(val word: PdfTextWord, val kind: FillTargetKind)

    /** Part of an extracted word: ordinary text, or a typed underscore [line] to write on. */
    private data class Piece(val word: PdfTextWord, val line: Boolean)

    /** Split runs of underscores out of a word, giving each part proportional horizontal bounds. */
    private fun pieces(word: PdfTextWord): List<Piece> {
        val text = word.text.trimEnd()
        val runs = UNDERSCORES.findAll(text).toList()
        val b = word.bounds
        val width = b.right - b.left
        // Only horizontal text; a rotated run's proportional split would be meaningless.
        if (runs.isEmpty() || width <= b.bottom - b.top) return listOf(Piece(word, false))

        fun part(
            start: Int,
            end: Int,
            line: Boolean,
        ) = Piece(
            PdfTextWord(
                text.substring(start, end),
                PdfRect(b.left + width * start / text.length, b.top, b.left + width * end / text.length, b.bottom),
            ),
            line,
        )
        val parts = mutableListOf<Piece>()
        var cursor = 0
        runs.forEach { run ->
            if (text.substring(cursor, run.range.first).isNotBlank()) parts += part(cursor, run.range.first, false)
            parts += part(run.range.first, run.range.last + 1, true)
            cursor = run.range.last + 1
        }
        if (text.substring(cursor).isNotBlank()) parts += part(cursor, text.length, false)
        return parts
    }

    /**
     * The writing area above a typed underscore line, with its label: one on the same line to the
     * left or just above, or a signature label further above, leaving the space between to sign in.
     */
    private fun underscoreField(
        line: PdfRect,
        labels: List<Label>,
    ): Pair<PdfRect, Label>? {
        if (line.right - line.left < 36f) return null
        val baseline = line.bottom
        val height = max(line.bottom - line.top + 4f, 16f)
        val inline =
            labels.filter {
                val b = it.word.bounds
                b.right <= line.left + 2f && line.left - b.right <= 120f && b.bottom >= line.top - 2f && b.top <= line.bottom + 2f
            }.minByOrNull { line.left - it.word.bounds.right }
        if (inline != null) return PdfRect(line.left, baseline - height, line.right, baseline) to inline
        val above =
            labels.filter {
                val b = it.word.bounds
                val reach = if (it.kind == FillTargetKind.SIGNATURE) 120f else 28f
                b.bottom <= line.top + 2f && baseline - b.bottom <= reach && b.left < line.right && b.right > line.left - 4f
            }.minByOrNull { baseline - it.word.bounds.bottom } ?: return null
        val labelBottom = above.word.bounds.bottom + 2f
        val top = if (above.kind == FillTargetKind.SIGNATURE) max(labelBottom, baseline - 72f) else max(labelBottom, baseline - height)
        return if (baseline - top < 8f) null else PdfRect(line.left, top, line.right, baseline) to above
    }

    private fun labelKind(text: String): FillTargetKind? {
        val label = text.trim().lowercase(Locale.ROOT).trimEnd(':', ' ', '_').replace(Regex("\\s+"), " ")
        return when {
            label in SIGNATURE_LABELS -> FillTargetKind.SIGNATURE
            label in TEXT_LABELS -> FillTargetKind.TEXT
            else -> null
        }
    }

    private fun near(
        label: PdfRect,
        field: PdfRect,
    ): Boolean {
        val left =
            label.right <= field.left - 2f && field.left - label.right <= 120f &&
                label.bottom >= field.top && label.top <= field.bottom
        val above =
            label.bottom <= field.top + 2f && field.top - label.bottom <= 20f &&
                label.left < field.right && label.right > field.left - 4f
        return left || above
    }

    private fun distance(
        label: PdfRect,
        field: PdfRect,
    ) = max(0f, field.left - label.right) + max(0f, field.top - label.bottom)

    /** All heuristics use unrotated crop points. Only the public result uses display rotation. */
    private class Coordinates(page: PDPage) {
        private val crop = page.cropBox
        val area get() = crop.width * crop.height
        private val rotation = ((page.rotation % 360) + 360) % 360

        fun point(
            x: Float,
            y: Float,
        ) = PdfPoint(x - crop.lowerLeftX, crop.upperRightY - y)

        fun fromNative(rect: PDRectangle): PdfRect? {
            if (rect.width <= 0f || rect.height <= 0f) return null
            return bounds(listOf(point(rect.lowerLeftX, rect.lowerLeftY), point(rect.upperRightX, rect.upperRightY)))
        }

        fun contains(rect: PdfRect) =
            listOf(rect.left, rect.top, rect.right, rect.bottom).all { it.isFinite() } &&
                rect.left >= 0f && rect.top >= 0f && rect.right <= crop.width && rect.bottom <= crop.height &&
                rect.right > rect.left && rect.bottom > rect.top

        fun toDisplayed(rect: PdfRect) =
            bounds(
                rect.corners().map { point ->
                    when (rotation) {
                        90 -> PdfPoint(crop.height - point.y, point.x)
                        180 -> PdfPoint(crop.width - point.x, crop.height - point.y)
                        270 -> PdfPoint(point.y, crop.width - point.x)
                        else -> point
                    }
                },
            )

        fun fromDisplayed(rect: PdfRect) =
            bounds(
                rect.corners().map { point ->
                    when (rotation) {
                        90 -> PdfPoint(point.y, crop.height - point.x)
                        180 -> PdfPoint(crop.width - point.x, crop.height - point.y)
                        270 -> PdfPoint(crop.width - point.y, point.x)
                        else -> point
                    }
                },
            )
    }

    private class PrintedGraphics(page: PDPage, private val coordinates: Coordinates) : PDFGraphicsStreamEngine(page) {
        val outlines = mutableListOf<PdfRect>()
        val occupied = mutableListOf<PdfRect>()
        var tooComplex = false
            private set
        private val paths = mutableListOf<Subpath>()
        private var current = PointF()

        private class Subpath(val points: MutableList<PdfPoint>, var closed: Boolean = false, var curved: Boolean = false)

        override fun appendRectangle(
            p0: PointF,
            p1: PointF,
            p2: PointF,
            p3: PointF,
        ) {
            paths += Subpath(listOf(p0, p1, p2, p3).map { coordinates.point(it.x, it.y) }.toMutableList(), closed = true)
            current = p0
            checkComplexity()
        }

        override fun moveTo(
            x: Float,
            y: Float,
        ) {
            current = PointF(x, y)
            paths += Subpath(mutableListOf(coordinates.point(x, y)))
            checkComplexity()
        }

        override fun lineTo(
            x: Float,
            y: Float,
        ) {
            current = PointF(x, y)
            addPoint(x, y)
        }

        override fun curveTo(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            x3: Float,
            y3: Float,
        ) {
            paths.lastOrNull()?.curved = true
            addPoint(x1, y1)
            addPoint(x2, y2)
            addPoint(x3, y3)
            current = PointF(x3, y3)
        }

        override fun getCurrentPoint(): PointF = current

        override fun closePath() {
            paths.lastOrNull()?.let { path ->
                path.closed = true
                // Only used by operators that follow a close-path with another segment.
                path.points.firstOrNull()?.let { point ->
                    val last = path.points.lastOrNull()
                    if (last != point) path.points += point
                }
            }
        }

        override fun clip(windingRule: Path.FillType) {
            // Callbacks still expose outlines excluded by the clipping path. Without a full
            // clip interpreter, omit printed guesses for this page rather than suggest invisible
            // fields. Independently visible AcroForm widgets remain available.
            tooComplex = true
        }

        override fun endPath() = paths.clear()

        override fun strokePath() {
            if (graphicsState.alphaConstant > 0.05 && graphicsState.lineWidth <= 3f) {
                paths.forEach { path ->
                    val points = path.points.distinct()
                    if (points.size < 2) return@forEach
                    val rect = bounds(points)
                    val rectangle =
                        path.closed && !path.curved && points.size == 4 &&
                            points.all { point ->
                                (abs(point.x - rect.left) < .5f || abs(point.x - rect.right) < .5f) &&
                                    (abs(point.y - rect.top) < .5f || abs(point.y - rect.bottom) < .5f)
                            }
                    when {
                        rectangle && rect.right - rect.left >= 36f && rect.bottom - rect.top in 12f..120f -> outlines += rect
                        !path.closed && !path.curved && points.size == 2 &&
                            abs(points[0].y - points[1].y) < .5f && rect.right - rect.left >= 50f ->
                            outlines += PdfRect(rect.left, rect.top - 24f, rect.right, rect.bottom)
                        else -> occupied += rect.expanded(1f)
                    }
                }
            } else {
                paths.filter { it.points.isNotEmpty() }.forEach { occupied += bounds(it.points).expanded(1f) }
            }
            paths.clear()
            checkComplexity()
        }

        override fun fillPath(windingRule: Path.FillType) {
            // White paint and page-sized backgrounds of any colour are not evidence of an answer.
            if (!isWhite(graphicsState.nonStrokingColor) && graphicsState.nonStrokeAlphaConstant > 0.05) {
                paths.filter { it.points.isNotEmpty() }.map { bounds(it.points) }.forEach { rect ->
                    if ((rect.right - rect.left) * (rect.bottom - rect.top) < coordinates.area * .9f) occupied += rect
                }
            }
            paths.clear()
            checkComplexity()
        }

        /**
         * PDFBox-Android has no ICC/calibrated colour spaces and resolves them to DeviceRGB, so a
         * one-component ICC grey such as white `1` would convert to black. Read those as grey.
         */
        private fun isWhite(color: PDColor): Boolean {
            val components = color.components
            if (components.size == 1 && color.colorSpace?.numberOfComponents != 1) return components[0] >= .98f
            val rgb = runCatching { color.toRGB() }.getOrNull() ?: return false
            return (rgb shr 16 and 255) >= 250 && (rgb shr 8 and 255) >= 250 && (rgb and 255) >= 250
        }

        override fun fillAndStrokePath(windingRule: Path.FillType) {
            // Filled boxes are not treated as empty outlines, even when also stroked.
            fillPath(windingRule)
        }

        override fun drawImage(image: PDImage) {
            val matrix = graphicsState.currentTransformationMatrix
            occupied +=
                bounds(
                    listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f).map { (x, y) ->
                        matrix.transformPoint(x, y).let { coordinates.point(it.x, it.y) }
                    },
                )
            checkComplexity()
        }

        override fun shadingFill(shadingName: COSName) {
            // A shaded page cannot safely be classified as an empty printed form.
            tooComplex = true
        }

        private fun addPoint(
            x: Float,
            y: Float,
        ) {
            paths.lastOrNull()?.let { path ->
                if (path.points.size < 128) path.points += coordinates.point(x, y) else tooComplex = true
            }
        }

        private fun checkComplexity() {
            if (paths.size + outlines.size + occupied.size > 2000) {
                tooComplex = true
                paths.clear()
                outlines.clear()
                occupied.clear()
            }
        }
    }

    companion object {
        private val UNDERSCORES = Regex("_{5,}")
        private val SIGNATURE_LABELS =
            setOf("signature", "sign here", "signed", "signed by", "applicant signature", "signature of applicant")
        private val TEXT_LABELS =
            setOf(
                "name", "full name", "first name", "last name", "middle name", "legal name", "printed name", "print name",
                "date", "date of birth", "dob", "email", "e-mail", "email address", "e-mail address", "address", "street address",
                "phone", "phone number", "telephone", "telephone number", "mobile", "mobile number", "city", "state", "zip", "zip code",
                "postal code", "country",
            )
    }
}

private fun PdfRect.corners() = listOf(PdfPoint(left, top), PdfPoint(right, top), PdfPoint(right, bottom), PdfPoint(left, bottom))

private fun bounds(points: List<PdfPoint>) =
    PdfRect(
        points.minOf {
            it.x
        },
        points.minOf { it.y },
        points.maxOf { it.x },
        points.maxOf { it.y },
    )

private fun PdfRect.inset(amount: Float) = PdfRect(left + amount, top + amount, right - amount, bottom - amount)

private fun PdfRect.expanded(amount: Float) = PdfRect(left - amount, top - amount, right + amount, bottom + amount)

private fun overlaps(
    first: PdfRect,
    second: PdfRect,
) = min(first.right, second.right) > max(first.left, second.left) && min(first.bottom, second.bottom) > max(first.top, second.top)
