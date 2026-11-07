package com.pdfeditor.app.ui.reader

import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pdfeditor.app.domain.Annotation
import com.pdfeditor.app.domain.DrawStroke
import com.pdfeditor.app.domain.HighlightAnnotation
import com.pdfeditor.app.domain.PdfFillTarget
import com.pdfeditor.app.domain.PdfPoint
import com.pdfeditor.app.domain.PdfTextWord
import com.pdfeditor.app.domain.SignaturePlacement
import com.pdfeditor.app.domain.TextBoxAnnotation
import com.pdfeditor.app.ui.writing.ResizeCorner
import com.pdfeditor.app.ui.writing.annotationSelectionBounds
import com.pdfeditor.app.ui.writing.clampAnnotationToPage
import com.pdfeditor.app.ui.writing.hitTestAnnotation
import com.pdfeditor.app.ui.writing.hitTestAnnotations
import com.pdfeditor.app.ui.writing.resizeSignature
import com.pdfeditor.app.ui.writing.resizeTextBox
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Tools share one document surface. Hand allows navigation; two fingers navigate in every tool. */
enum class EditorTool(val label: String) { HAND("Hand"), DRAW("Pen"), HIGHLIGHT("Highlight"), TEXT("Text"), SIGN("Sign"), ERASE("Erase") }

data class TextSelection(val pageId: String, val words: List<PdfTextWord>, val start: Int, val end: Int) {
    val selected: List<PdfTextWord> get() = words.subList(minOf(start, end), maxOf(start, end) + 1)
    val text: String get() = selected.joinToString("") { it.text }.trimEnd()
}

private val Invert =
    ColorFilter.colorMatrix(
        ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)),
    )

@Composable
internal fun DocumentPageSurface(
    pageId: String,
    pageIndex: Int,
    widthPoints: Float,
    bitmap: Bitmap?,
    annotations: List<Annotation>,
    words: List<PdfTextWord>,
    invert: Boolean,
    tool: EditorTool,
    color: Int,
    selection: TextSelection?,
    navigationEpoch: () -> Int,
    onSelection: (TextSelection?) -> Unit,
    onAdd: (Annotation) -> Unit,
    onReplace: (Annotation) -> Unit,
    onRemove: (Set<String>) -> Unit,
    onText: (PdfPoint) -> Unit,
    onSign: (PdfPoint) -> Unit,
    modifier: Modifier = Modifier,
    onPageTap: () -> Unit = {},
    allowAnnotationMove: Boolean = true,
    selectedObjectId: String? = null,
    onSelectObject: (String?) -> Unit = {},
    inlineText: PageTextEdit? = null,
    onInlineChange: (String, String) -> Unit = { _, _ -> },
    onFinishText: () -> Unit = {},
    onInlineCaret: (Rect) -> Unit = {},
    fontTypeface: (String) -> Typeface = { Typeface.DEFAULT },
    fontAvailable: (String) -> Boolean = { true },
    fillTargets: List<PdfFillTarget> = emptyList(),
    onFillTarget: (PdfFillTarget) -> Unit = {},
    detail: PageDetail? = null,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val resolveTypeface by rememberUpdatedState(fontTypeface)
    val fontReady by rememberUpdatedState(fontAvailable)
    val selectedId by rememberUpdatedState(selectedObjectId)
    val inlineActive by rememberUpdatedState(inlineText != null)
    val currentFillTargets by rememberUpdatedState(fillTargets)
    val fillAction by rememberUpdatedState(onFillTarget)

    fun boundsFor(annotation: Annotation) =
        annotationSelectionBounds(annotation) { text, size ->
            android.graphics.Paint().apply {
                textSize = size
                typeface = resolveTypeface((annotation as? TextBoxAnnotation)?.fontId ?: "roboto")
            }.measureText(text)
        }
    var widthPx by remember { mutableFloatStateOf(1f) }
    var moving by remember(pageId) { mutableStateOf<Annotation?>(null) }
    // Swept by the eraser in the current gesture: hidden at once, removed together on lift.
    var erased by remember(pageId) { mutableStateOf(emptySet<String>()) }
    var eraserAt by remember(pageId) { mutableStateOf<Offset?>(null) }
    LaunchedEffect(annotations) {
        if (erased.isNotEmpty()) erased = erased.filterTo(mutableSetOf()) { id -> annotations.any { it.id == id } }
    }
    val visibleAnnotations = annotations.filterNot { it.id in erased }.map { if (it.id == moving?.id) moving!! else it }
    var preview by remember(pageId) { mutableStateOf<List<Offset>>(emptyList()) }
    val scale = widthPx / widthPoints
    val currentAnnotations by rememberUpdatedState(annotations)
    val currentWords by rememberUpdatedState(words)
    val currentSelection by rememberUpdatedState(selection)
    val currentScale by rememberUpdatedState(scale)
    val parentEpoch by rememberUpdatedState(navigationEpoch)
    val select by rememberUpdatedState(onSelection)
    val add by rememberUpdatedState(onAdd)
    val replace by rememberUpdatedState(onReplace)
    val remove by rememberUpdatedState(onRemove)
    val textAction by rememberUpdatedState(onText)
    val signAction by rememberUpdatedState(onSign)
    val pageTap by rememberUpdatedState(onPageTap)
    val selectObject by rememberUpdatedState(onSelectObject)
    var inlineBounds by remember { mutableStateOf<Rect?>(null) }
    val textBounds by rememberUpdatedState(if (inlineText != null) inlineBounds else null)

    fun displayed(argb: Int): Color {
        val c = Color(argb)
        return if (invert) Color(1f - c.red, 1f - c.green, 1f - c.blue, c.alpha) else c
    }
    Box(
        modifier.background(if (invert) Color.Black else Color.White).testTag("pdf-page-$pageIndex")
            .pointerInput(pageId, tool, color, allowAnnotationMove) {
                awaitEachGesture {
                    var committedErase = false
                    try {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (textBounds?.contains(down.position) == true) {
                            var pressed = true
                            while (pressed) pressed = awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }
                            return@awaitEachGesture
                        }
                        // Text boxes and signatures both resize from their corner handles.
                        val resizable =
                            when (val selected = currentAnnotations.firstOrNull { it.id == selectedId }) {
                                is TextBoxAnnotation -> selected.takeIf { fontReady(it.fontId) }
                                is SignaturePlacement -> selected
                                else -> null
                            }
                        val selectedText = resizable as? TextBoxAnnotation
                        val originalBounds = resizable?.let(::boundsFor)
                        // One bottom-right handle; the rest of the box stays free for moving it.
                        val resizeCorner =
                            if (allowAnnotationMove && !inlineActive && originalBounds != null) {
                                val handle = Offset(originalBounds.right, originalBounds.bottom) * currentScale
                                ResizeCorner.BOTTOM_RIGHT.takeIf { (down.position - handle).getDistance() <= RESIZE_HANDLE_REACH.toPx() }
                            } else {
                                null
                            }
                        if (resizeCorner != null) down.consume()
                        val initialEpoch = parentEpoch()
                        val drawing = tool == EditorTool.DRAW || tool == EditorTool.HIGHLIGHT
                        val actsOnTap = tool == EditorTool.TEXT || tool == EditorTool.SIGN || tool == EditorTool.ERASE
                        if (drawing || actsOnTap) down.consume()
                        var last = down.position
                        var distance = 0f
                        var multiple = false
                        var cancelled = false
                        var selecting = false
                        var anchor = 0
                        val points = mutableListOf(down.position)
                        val draggable =
                            if (resizeCorner == null && tool == EditorTool.HAND && allowAnnotationMove) {
                                hitTestAnnotation(
                                    currentAnnotations.filter {
                                        it is SignaturePlacement || it is TextBoxAnnotation || it is DrawStroke
                                    },
                                    PdfPoint(down.position.x / currentScale, down.position.y / currentScale),
                                )
                            } else {
                                null
                            }
                        if (draggable != null) {
                            down.consume()
                            selectObject(draggable.id)
                        }

                        fun pdf(p: Offset) =
                            PdfPoint(
                                (p.x / currentScale).coerceIn(0f, widthPoints),
                                (p.y / currentScale).coerceAtLeast(0f),
                            )

                        fun eraseAlong(
                            from: Offset,
                            to: Offset,
                        ) {
                            eraserAt = to
                            val start = pdf(from)
                            val end = pdf(to)
                            val steps = (hypot(end.x - start.x, end.y - start.y) / ERASER_STEP_POINTS).toInt().coerceAtLeast(1)
                            var hits = erased
                            for (i in 0..steps) {
                                val t = i / steps.toFloat()
                                val point = PdfPoint(start.x + (end.x - start.x) * t, start.y + (end.y - start.y) * t)
                                hits = hits + hitTestAnnotations(currentAnnotations.filterNot { it.id in hits }, point).map { it.id }
                            }
                            erased = hits
                        }
                        if (tool == EditorTool.ERASE) eraseAlong(down.position, down.position)

                        fun nearest(p: Offset): Int? {
                            val pos = pdf(p)
                            return currentWords.indices.minByOrNull { i ->
                                val b = currentWords[i].bounds
                                val dx =
                                    when {
                                        pos.x < b.left -> b.left - pos.x
                                        pos.x > b.right -> pos.x - b.right
                                        else -> 0f
                                    }
                                val dy =
                                    when {
                                        pos.y < b.top -> b.top - pos.y
                                        pos.y > b.bottom -> pos.y - b.bottom
                                        else -> 0f
                                    }
                                dx * dx + dy * dy
                            }
                        }
                        // Drag either endpoint of a visible selection to extend or contract it.
                        val existing = currentSelection?.takeIf { it.pageId == pageId }
                        if (tool == EditorTool.HAND && existing != null) {
                            val first = existing.selected.first().bounds
                            val end = existing.selected.last().bounds
                            val startHandle = Offset(first.left * currentScale, first.bottom * currentScale)
                            val endHandle = Offset(end.right * currentScale, end.bottom * currentScale)
                            if ((down.position - startHandle).getDistance() < 28.dp.toPx()) {
                                anchor = existing.end
                                selecting = true
                                down.consume()
                            } else if ((down.position - endHandle).getDistance() < 28.dp.toPx()) {
                                anchor = existing.start
                                selecting = true
                                down.consume()
                            }
                        }
                        var eventTime = down.uptimeMillis
                        val longPressDeadline = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                        while (true) {
                            val remaining = longPressDeadline - eventTime
                            val event =
                                if (tool == EditorTool.HAND && draggable == null && !selecting && !multiple &&
                                    distance < viewConfiguration.touchSlop && remaining > 0
                                ) {
                                    withTimeoutOrNull(remaining) { awaitPointerEvent(PointerEventPass.Initial) }
                                } else {
                                    awaitPointerEvent(PointerEventPass.Initial)
                                }
                            if (event == null) {
                                if (distance < viewConfiguration.touchSlop && !multiple) {
                                    nearest(down.position)?.let {
                                        anchor = it
                                        selecting = true
                                        select(TextSelection(pageId, currentWords, it, it))
                                    }
                                }
                                continue
                            }
                            // Compose synthesizes cancellation as consumed releases, including
                            // on the Initial pass. Never commit that last resize/ink preview.
                            if (event.changes.none { it.pressed } && event.changes.any { it.previousPressed && it.isConsumed }) {
                                cancelled = true
                                break
                            }
                            eventTime = event.changes.maxOf { it.uptimeMillis }
                            if (event.changes.count { it.pressed } >= 2 || parentEpoch() != initialEpoch) {
                                multiple = true
                                moving = null
                                preview = emptyList()
                                select(null)
                                selectObject(null)
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val pos = change.position
                            val segmentStart = last
                            distance += hypot(pos.x - last.x, pos.y - last.y)
                            last = pos
                            if (!multiple) {
                                when {
                                    resizeCorner != null && selectedText != null && originalBounds != null -> {
                                        resizeTextBox(
                                            selectedText,
                                            originalBounds,
                                            resizeCorner,
                                            PdfPoint((pos.x - down.position.x) / currentScale, (pos.y - down.position.y) / currentScale),
                                            widthPoints,
                                            size.height / currentScale,
                                        ) { raw, width ->
                                            textMeasurer.reflowTextBox(
                                                selectedText,
                                                raw,
                                                width,
                                                density,
                                                resolveTypeface(selectedText.fontId),
                                            )
                                        }?.let { moving = it }
                                        change.consume()
                                    }
                                    resizeCorner != null && resizable is SignaturePlacement -> {
                                        resizeSignature(
                                            resizable,
                                            resizeCorner,
                                            PdfPoint((pos.x - down.position.x) / currentScale, (pos.y - down.position.y) / currentScale),
                                            widthPoints,
                                            size.height / currentScale,
                                        )?.let { moving = it }
                                        change.consume()
                                    }
                                    draggable != null -> {
                                        val delta = (pos - down.position) / currentScale
                                        val translated =
                                            when (draggable) {
                                                is DrawStroke ->
                                                    draggable.copy(
                                                        points = draggable.points.map { PdfPoint(it.x + delta.x, it.y + delta.y) },
                                                    )
                                                is SignaturePlacement ->
                                                    draggable.copy(
                                                        position = PdfPoint(draggable.position.x + delta.x, draggable.position.y + delta.y),
                                                    )
                                                is TextBoxAnnotation ->
                                                    draggable.copy(
                                                        position = PdfPoint(draggable.position.x + delta.x, draggable.position.y + delta.y),
                                                    )
                                                else -> draggable
                                            }
                                        moving = clampAnnotationToPage(translated, widthPoints, size.height / currentScale)
                                        change.consume()
                                    }
                                    selecting -> {
                                        nearest(pos)?.let { select(TextSelection(pageId, currentWords, anchor, it)) }
                                        change.consume()
                                    }
                                    drawing -> {
                                        if (change.pressed) points.add(pos)
                                        preview = points.toList()
                                        change.consume()
                                    }
                                    tool == EditorTool.ERASE -> {
                                        eraseAlong(segmentStart, pos)
                                        change.consume()
                                    }
                                    actsOnTap -> change.consume()
                                }
                            }
                            if (event.changes.none { it.pressed }) break
                        }
                        preview = emptyList()
                        if (!cancelled && !multiple && (draggable != null || resizeCorner != null) &&
                            distance > viewConfiguration.touchSlop
                        ) {
                            moving?.let {
                                replace(clampAnnotationToPage(it, widthPoints, size.height / currentScale))
                            }
                        }
                        moving = null
                        // One swipe, however much it erased, is one undo step.
                        if (!cancelled && !multiple && erased.isNotEmpty()) {
                            remove(erased)
                            committedErase = true
                        }
                        if (!cancelled && !multiple && !selecting && resizeCorner == null) {
                            when {
                                drawing && points.size > 1 && distance > viewConfiguration.touchSlop -> {
                                    val alphaColor = if (tool == EditorTool.HIGHLIGHT) (color and 0x00ffffff) or 0x59000000 else color
                                    add(
                                        DrawStroke(
                                            java.util.UUID.randomUUID().toString(),
                                            points.map(::pdf),
                                            alphaColor,
                                            if (tool == EditorTool.HIGHLIGHT) 14f else 2.5f,
                                        ),
                                    )
                                }
                                distance < viewConfiguration.touchSlop ->
                                    when (tool) {
                                        EditorTool.TEXT -> textAction(pdf(down.position))
                                        EditorTool.SIGN -> signAction(pdf(down.position))
                                        EditorTool.ERASE -> Unit
                                        EditorTool.HAND -> {
                                            select(null)
                                            val hit =
                                                if (allowAnnotationMove) {
                                                    hitTestAnnotation(
                                                        currentAnnotations,
                                                        pdf(down.position),
                                                    )
                                                } else {
                                                    null
                                                }
                                            selectObject(hit?.id)
                                            if (hit == null) {
                                                val point = pdf(down.position)
                                                val target =
                                                    currentFillTargets.firstOrNull {
                                                        point.x in it.bounds.left..it.bounds.right &&
                                                            point.y in it.bounds.top..it.bounds.bottom
                                                    }
                                                if (target != null) fillAction(target) else pageTap()
                                            }
                                        }
                                        else -> select(null)
                                    }
                            }
                        }
                    } finally {
                        moving = null
                        preview = emptyList()
                        // Committed ids stay hidden until the model drops them (see below).
                        if (!committedErase) erased = emptySet()
                        eraserAt = null
                    }
                }
            },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            SideEffect { widthPx = with(density) { maxWidth.toPx() } }
            bitmap?.let {
                Image(
                    it.asImageBitmap(),
                    null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                    colorFilter = if (invert) Invert else null,
                )
            }
            // A full-resolution render of just the visible part, placed in PDF points so it stays
            // aligned (if briefly soft) while zoom keeps changing.
            detail?.let { patch ->
                Image(
                    patch.bitmap.asImageBitmap(),
                    null,
                    Modifier.offset { IntOffset((patch.region.left * scale).roundToInt(), (patch.region.top * scale).roundToInt()) }
                        .requiredSize(
                            with(density) { (patch.region.width() * scale).toDp() },
                            with(density) { (patch.region.height() * scale).toDp() },
                        ),
                    contentScale = ContentScale.FillBounds,
                    colorFilter = if (invert) Invert else null,
                )
            }
            FillTargetHints(fillTargets, scale, invert, onFillTarget)
            Canvas(Modifier.fillMaxSize()) {
                fun line(
                    points: List<PdfPoint>,
                    c: Color,
                    width: Float,
                    smooth: Boolean = false,
                ) {
                    if (points.size < 2) return
                    val path =
                        Path().apply {
                            moveTo(points[0].x * scale, points[0].y * scale)
                            if (smooth && points.size > 2) {
                                // Curves through segment midpoints, using each sample as the control
                                // point, so enlarged signatures stay round instead of polygonal.
                                for (i in 1 until points.lastIndex) {
                                    val mid = PdfPoint((points[i].x + points[i + 1].x) / 2f, (points[i].y + points[i + 1].y) / 2f)
                                    quadraticTo(points[i].x * scale, points[i].y * scale, mid.x * scale, mid.y * scale)
                                }
                                lineTo(points.last().x * scale, points.last().y * scale)
                            } else {
                                points.drop(1).forEach { lineTo(it.x * scale, it.y * scale) }
                            }
                        }
                    drawPath(path, c, style = Stroke(width * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                for (annotation in visibleAnnotations) when (annotation) {
                    is DrawStroke -> line(annotation.points, displayed(annotation.colorArgb), annotation.strokeWidthPoints)
                    is SignaturePlacement ->
                        annotation.strokes.forEach {
                                stroke ->
                            line(
                                stroke.map {
                                    PdfPoint(
                                        annotation.position.x + it.x * annotation.scale,
                                        annotation.position.y + it.y * annotation.scale,
                                    )
                                },
                                displayed(annotation.colorArgb),
                                annotation.lineWidthPoints,
                                smooth = true,
                            )
                        }
                    is HighlightAnnotation ->
                        annotation.rects.forEach {
                                r ->
                            drawRect(
                                displayed(annotation.colorArgb),
                                Offset(r.left * scale, r.top * scale),
                                androidx.compose.ui.geometry.Size((r.right - r.left) * scale, (r.bottom - r.top) * scale),
                            )
                        }
                    is TextBoxAnnotation -> Unit
                }
                if (preview.size > 1) {
                    val path =
                        Path().apply {
                            moveTo(preview[0].x, preview[0].y)
                            preview.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                    drawPath(
                        path,
                        displayed(color).copy(alpha = if (tool == EditorTool.HIGHLIGHT).35f else 1f),
                        style = Stroke(if (tool == EditorTool.HIGHLIGHT)14f * scale else 2.5f * scale, cap = StrokeCap.Round),
                    )
                }
                eraserAt?.let {
                    drawCircle(Color.Gray.copy(alpha = .25f), ERASER_CURSOR.toPx(), it)
                    drawCircle(Color.Gray, ERASER_CURSOR.toPx(), it, style = Stroke(1.dp.toPx()))
                }
                selection?.takeIf { it.pageId == pageId }?.let { chosen ->
                    chosen.selected.forEach {
                            w ->
                        val r = w.bounds
                        drawRect(
                            (if (invert) Color.White else Color.Black).copy(alpha = .2f),
                            Offset(r.left * scale, r.top * scale),
                            androidx.compose.ui.geometry.Size((r.right - r.left) * scale, (r.bottom - r.top) * scale),
                        )
                    }
                    val a = chosen.selected.first().bounds
                    val b = chosen.selected.last().bounds
                    drawCircle(if (invert) Color.White else Color.Black, 6.dp.toPx(), Offset(a.left * scale, a.bottom * scale))
                    drawCircle(if (invert) Color.White else Color.Black, 6.dp.toPx(), Offset(b.right * scale, b.bottom * scale))
                }
            }
            visibleAnnotations.filterIsInstance<TextBoxAnnotation>().filterNot { it.id == inlineText?.annotation?.id }.forEach {
                    annotation ->
                // Android text baseline is measured explicitly; PDF position uses that same baseline.
                Canvas(Modifier.fillMaxSize()) {
                    val paint =
                        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            this.color = displayed(annotation.colorArgb).toArgb()
                            textSize = annotation.fontSizePoints * scale
                            typeface = resolveTypeface(annotation.fontId)
                        }
                    annotation.text.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEachIndexed { line, text ->
                        drawContext.canvas.nativeCanvas.drawText(
                            text,
                            annotation.position.x * scale,
                            (annotation.position.y + line * annotation.fontSizePoints * 1.25f) * scale,
                            paint,
                        )
                    }
                }
            }
            if (inlineText == null) {
                visibleAnnotations.firstOrNull { it.id == selectedObjectId }?.let { selected ->
                    val bounds = boundsFor(selected)
                    bounds?.let { b ->
                        val pad = with(density) { 4.dp.toPx() }
                        Box(
                            Modifier.offset { IntOffset((b.left * scale - pad).roundToInt(), (b.top * scale - pad).roundToInt()) }
                                .requiredSize(
                                    with(density) { ((b.right - b.left) * scale + 2 * pad).coerceAtLeast(2 * pad).toDp() },
                                    with(density) { ((b.bottom - b.top) * scale + 2 * pad).coerceAtLeast(2 * pad).toDp() },
                                )
                                .border(1.dp, if (invert) Color.White else Color.Black).testTag("object-selection-${selected.id}"),
                        )
                        val handles =
                            when {
                                !allowAnnotationMove -> null
                                selected is TextBoxAnnotation && fontReady(selected.fontId) -> "text"
                                selected is SignaturePlacement -> "signature"
                                else -> null
                            }
                        if (handles != null) {
                            val radius = with(density) { (RESIZE_HANDLE_SIZE / 2).toPx() }
                            val centerX = (b.right * scale + pad).coerceIn(radius, (widthPx - radius).coerceAtLeast(radius))
                            val pageHeightPx = with(density) { maxHeight.toPx() }
                            val centerY = (b.bottom * scale + pad).coerceIn(radius, (pageHeightPx - radius).coerceAtLeast(radius))
                            Box(
                                Modifier.offset { IntOffset((centerX - radius).roundToInt(), (centerY - radius).roundToInt()) }
                                    .size(RESIZE_HANDLE_SIZE).background(if (invert) Color.Black else Color.White, CircleShape)
                                    .border(1.5.dp, if (invert) Color.White else Color.Black, CircleShape)
                                    .testTag("$handles-resize-bottom-right"),
                            )
                        }
                    }
                }
            }
            inlineText?.let { edit ->
                InlinePageText(
                    edit,
                    scale,
                    widthPx,
                    invert,
                    onInlineChange,
                    onFinishText,
                    onBounds = { inlineBounds = it },
                    onCaret = onInlineCaret,
                    typeface = resolveTypeface(edit.annotation.fontId),
                )
            }
        }
    }
}

private val RESIZE_HANDLE_SIZE = 14.dp

/** Touch reach around the handle centre; generous since it's the only one. */
private val RESIZE_HANDLE_REACH = 28.dp

/** Spacing of eraser hit samples along a swipe, in PDF points. */
private const val ERASER_STEP_POINTS = 3f
private val ERASER_CURSOR = 12.dp

/** A sharp render of [region] (displayed PDF points) of a page, drawn over its base bitmap. */
class PageDetail(val bitmap: Bitmap, val region: android.graphics.RectF)
