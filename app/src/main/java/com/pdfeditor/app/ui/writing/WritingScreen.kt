package com.pdfeditor.app.ui.writing

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfeditor.app.domain.Annotation
import com.pdfeditor.app.domain.DrawStroke
import com.pdfeditor.app.domain.PdfPoint
import com.pdfeditor.app.domain.SignaturePlacement
import com.pdfeditor.app.domain.TextBoxAnnotation
import com.pdfeditor.app.ui.theme.Accent
import com.pdfeditor.app.ui.theme.SurfaceElevated
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val InvertFilter =
    ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )

private data class TextEditorState(val id: String?, val initialText: String, val screenPosition: Offset)

/**
 * Writing mode shows only the single page that was active in reading mode, at a fixed fit-width
 * scale with no pan/zoom - drawing/text/signature tools need direct, unambiguous single-finger
 * control of the canvas, which would conflict with reading mode's pinch/pan/continuous-scroll on
 * the same surface. To edit a different page, the user switches back to reading mode, navigates,
 * and toggles back - a real v1 scope cut, not an oversight.
 */
@Composable
fun WritingScreen(
    pageIndex: Int,
    pageWidthPoints: Float,
    pageAspectRatio: Float,
    renderPage: suspend (Int, Int, Int) -> Bitmap?,
    invertColors: Boolean,
    initialAnnotations: List<Annotation>,
    onModeToggle: () -> Unit,
    onSaveCopy: (List<Annotation>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: WritingViewModel =
        viewModel(key = "writing-$pageIndex", factory = WritingViewModel.Factory(initialAnnotations))
    val density = LocalDensity.current
    val modeSwipeThresholdPx = with(density) { 96.dp.toPx() }

    var canvasSizePx by remember { mutableStateOf(Size.Zero) }
    val transform =
        remember(canvasSizePx, pageWidthPoints, pageAspectRatio) {
            PdfPageTransform(pageWidthPoints, pageWidthPoints * pageAspectRatio, canvasSizePx.width, canvasSizePx.height)
        }

    var pageBitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(pageIndex, canvasSizePx) {
        if (canvasSizePx.width > 0 && canvasSizePx.height > 0) {
            pageBitmap = renderPage(pageIndex, canvasSizePx.width.roundToInt(), canvasSizePx.height.roundToInt())
        }
    }

    var trayVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableStateOf(0) }

    fun pokeInteraction() {
        trayVisible = true
        interactionTick++
    }
    LaunchedEffect(interactionTick) {
        delay(4000)
        trayVisible = false
    }

    var draggingId by remember { mutableStateOf<String?>(null) }
    var draggingScreenPos by remember { mutableStateOf(Offset.Zero) }
    var textEditor by remember { mutableStateOf<TextEditorState?>(null) }
    var strokeColorArgb by remember { mutableStateOf(Color.Black.toArgb()) }
    var strokeWidthPoints by remember { mutableStateOf(2.5f) }

    fun displayColor(argb: Int): Color {
        val c = Color(argb)
        return if (invertColors) Color(1f - c.red, 1f - c.green, 1f - c.blue, c.alpha) else c
    }

    Box(
        modifier = modifier.fillMaxSize().background(Color.Black),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { canvasSizePx = Size(it.width.toFloat(), it.height.toFloat()) }
                    .writingGestures(
                        isDrawMode = { viewModel.selectedTool == WritingTool.DRAW },
                        hitTestDraggableId = { screenPos ->
                            if (viewModel.selectedTool == WritingTool.ERASE || viewModel.selectedTool == WritingTool.DRAW) {
                                null
                            } else {
                                val draggable = viewModel.annotations.filter { it is TextBoxAnnotation || it is SignaturePlacement }
                                hitTestAnnotation(draggable, transform.screenToPdf(screenPos))?.id
                            }
                        },
                        onDrawStroke = { screenPoints ->
                            pokeInteraction()
                            val pdfPoints = screenPoints.map { transform.screenToPdf(it) }
                            viewModel.addAnnotation(DrawStroke(newId(), pdfPoints, strokeColorArgb, strokeWidthPoints))
                        },
                        onDragAnnotation = { id, pos ->
                            draggingId = id
                            draggingScreenPos = pos
                            pokeInteraction()
                        },
                        onDragEnd = { id ->
                            val newPos = transform.screenToPdf(draggingScreenPos)
                            when (val existing = viewModel.annotations.find { it.id == id }) {
                                is TextBoxAnnotation -> viewModel.replaceAnnotation(existing.copy(position = newPos))
                                is SignaturePlacement -> viewModel.replaceAnnotation(existing.copy(position = newPos))
                                else -> Unit
                            }
                            draggingId = null
                        },
                        onTap = { screenPos ->
                            if (!trayVisible) {
                                pokeInteraction()
                                return@writingGestures
                            }
                            pokeInteraction()
                            val pdfPoint = transform.screenToPdf(screenPos)
                            when (viewModel.selectedTool) {
                                WritingTool.TEXT -> {
                                    val hit =
                                        hitTestAnnotation(viewModel.annotations.filterIsInstance<TextBoxAnnotation>(), pdfPoint)
                                            as? TextBoxAnnotation
                                    textEditor = TextEditorState(hit?.id, hit?.text ?: "", screenPos)
                                }
                                WritingTool.SIGN -> {
                                    viewModel.savedSignature?.let { saved ->
                                        viewModel.addAnnotation(SignaturePlacement(newId(), pdfPoint, saved, 1f, strokeColorArgb))
                                    }
                                }
                                WritingTool.ERASE -> {
                                    hitTestAnnotation(viewModel.annotations, pdfPoint)?.let { viewModel.removeAnnotation(it.id) }
                                }
                                else -> Unit
                            }
                        },
                        onUndo = {
                            pokeInteraction()
                            viewModel.undo()
                        },
                        onRedo = {
                            pokeInteraction()
                            viewModel.redo()
                        },
                        onModeSwipe = onModeToggle,
                        modeSwipeThresholdPx = modeSwipeThresholdPx,
                    ),
        ) {
            pageBitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    colorFilter = if (invertColors) InvertFilter else null,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                for (annotation in viewModel.annotations) {
                    when (annotation) {
                        is DrawStroke -> {
                            val screenPoints = annotation.points.map { transform.pdfToScreen(it) }
                            drawPolyline(
                                screenPoints,
                                displayColor(annotation.colorArgb),
                                transform.pdfLengthToScreenPx(annotation.strokeWidthPoints),
                            )
                        }
                        is TextBoxAnnotation -> Unit // drawn via overlay Text composables below, not Canvas
                        is SignaturePlacement -> {
                            val origin = if (annotation.id == draggingId) transform.screenToPdf(draggingScreenPos) else annotation.position
                            for (path in annotation.strokes) {
                                val screenPoints =
                                    path.map { p ->
                                        transform.pdfToScreen(
                                            PdfPoint(origin.x + p.x * annotation.scale, origin.y + p.y * annotation.scale),
                                        )
                                    }
                                drawPolyline(screenPoints, displayColor(annotation.colorArgb), transform.pdfLengthToScreenPx(1.5f))
                            }
                        }
                    }
                }
            }

            for (annotation in viewModel.annotations) {
                if (annotation is TextBoxAnnotation && annotation.id != textEditor?.id) {
                    val pdfPos = if (annotation.id == draggingId) transform.screenToPdf(draggingScreenPos) else annotation.position
                    val screenPos = transform.pdfToScreen(pdfPos)
                    Text(
                        text = annotation.text,
                        color = displayColor(annotation.colorArgb),
                        fontSize = with(density) { annotation.fontSizePoints.toSp() },
                        modifier =
                            with(density) {
                                Modifier.offset(screenPos.x.toDp(), (screenPos.y - annotation.fontSizePoints).toDp())
                            },
                    )
                }
            }
        }

        textEditor?.let { editor ->
            TextEditorOverlay(
                editor = editor,
                colorArgb = strokeColorArgb,
                density = density,
                onCommit = { text ->
                    val pdfPos = transform.screenToPdf(editor.screenPosition)
                    if (text.isBlank()) {
                        editor.id?.let { viewModel.removeAnnotation(it) }
                    } else if (editor.id != null) {
                        val existing = viewModel.annotations.find { it.id == editor.id } as? TextBoxAnnotation
                        if (existing != null) viewModel.replaceAnnotation(existing.copy(text = text))
                    } else {
                        viewModel.addAnnotation(TextBoxAnnotation(newId(), pdfPos, text, 14f, strokeColorArgb))
                    }
                    textEditor = null
                },
            )
        }

        if (viewModel.isDrawingSignature) {
            SignatureDrawingOverlay(
                onDone = { strokesScreenSpace, boxSizePx ->
                    val canonical = normalizeSignature(strokesScreenSpace, boxSizePx)
                    val placeAt = PdfPoint(pageWidthPoints * 0.3f, (pageWidthPoints * pageAspectRatio) * 0.5f)
                    viewModel.commitSignature(canonical, placeAt, strokeColorArgb)
                },
                onCancel = { viewModel.cancelSignatureDrawing() },
            )
        }

        AnimatedVisibility(
            visible = trayVisible,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ToolTray(
                selectedTool = viewModel.selectedTool,
                onSelectTool = {
                    viewModel.selectTool(it)
                    pokeInteraction()
                },
                strokeColorArgb = strokeColorArgb,
                onSelectColor = {
                    strokeColorArgb = it
                    pokeInteraction()
                },
                strokeWidthPoints = strokeWidthPoints,
                onSelectWidth = {
                    strokeWidthPoints = it
                    pokeInteraction()
                },
                onSave = { onSaveCopy(viewModel.annotations) },
            )
        }
    }
}

private fun DrawScope.drawPolyline(
    points: List<Offset>,
    color: Color,
    widthPx: Float,
) {
    if (points.size < 2) return
    val path = Path()
    path.moveTo(points.first().x, points.first().y)
    for (p in points.drop(1)) path.lineTo(p.x, p.y)
    drawPath(path, color = color, style = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun normalizeSignature(
    strokes: List<List<Offset>>,
    boxSizePx: Size,
): List<List<PdfPoint>> {
    if (boxSizePx.width <= 0 || boxSizePx.height <= 0) return emptyList()
    // Canonical space: a fixed 200x80-point box, independent of the actual on-screen drawing
    // area's pixel size, so the saved signature scales consistently wherever it's later placed.
    val scaleX = 200f / boxSizePx.width
    val scaleY = 80f / boxSizePx.height
    return strokes.map { path -> path.map { PdfPoint(it.x * scaleX, it.y * scaleY) } }
}

@Composable
private fun TextEditorOverlay(
    editor: TextEditorState,
    colorArgb: Int,
    density: Density,
    onCommit: (String) -> Unit,
) {
    var text by remember { mutableStateOf(editor.initialText) }
    Box(modifier = Modifier.fillMaxSize().clickable(onClick = { onCommit(text) })) {
        val offsetX = with(density) { editor.screenPosition.x.toDp() }
        val offsetY = with(density) { (editor.screenPosition.y - 24f).toDp() }
        TextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(color = Color(colorArgb), fontSize = 14.sp),
            colors =
                TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Accent,
                    unfocusedIndicatorColor = Color.White.copy(alpha = 0.4f),
                ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCommit(text) }),
            modifier = Modifier.offset(offsetX, offsetY),
        )
    }
}

@Composable
private fun SignatureDrawingOverlay(
    onDone: (List<List<Offset>>, Size) -> Unit,
    onCancel: () -> Unit,
) {
    var boxSize by remember { mutableStateOf(Size.Zero) }
    var strokes by remember { mutableStateOf<List<List<Offset>>>(emptyList()) }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Draw your signature", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Box(
                modifier =
                    Modifier
                        .padding(top = 16.dp)
                        .size(width = 300.dp, height = 120.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .onSizeChanged { boxSize = Size(it.width.toFloat(), it.height.toFloat()) }
                        .signatureDrawingInput(onStroke = { path -> strokes = strokes + listOf(path) }),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    for (path in strokes) drawPolyline(path, Color.Black, 4f)
                }
            }
            Row(modifier = Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Text("Clear", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.clickable { strokes = emptyList() })
                Text("Cancel", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.clickable(onClick = onCancel))
                Text(
                    text = "Done",
                    color = Accent,
                    modifier =
                        Modifier.clickable {
                            if (strokes.isNotEmpty()) onDone(strokes, boxSize) else onCancel()
                        },
                )
            }
        }
    }
}

private fun Modifier.signatureDrawingInput(onStroke: (List<Offset>) -> Unit): Modifier =
    this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val points = mutableListOf(down.position)
            var pressed = true
            while (pressed) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: break
                if (change.positionChanged()) {
                    points.add(change.position)
                    change.consume()
                }
                pressed = event.changes.any { it.pressed }
            }
            if (points.size >= 2) onStroke(points)
        }
    }

@Composable
private fun ToolTray(
    selectedTool: WritingTool,
    onSelectTool: (WritingTool) -> Unit,
    strokeColorArgb: Int,
    onSelectColor: (Int) -> Unit,
    strokeWidthPoints: Float,
    onSelectWidth: (Float) -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(SurfaceElevated)
                .navigationBarsPadding()
                .padding(vertical = 12.dp),
    ) {
        if (selectedTool == WritingTool.DRAW) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (swatch in listOf(Color.Black, Color.White, Color(0xFFE53935), Accent)) {
                    val isSelected = swatch.toArgb() == strokeColorArgb
                    Box(
                        modifier =
                            Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) Accent.copy(alpha = 0.4f) else Color.Transparent)
                                .padding(3.dp)
                                .clip(CircleShape)
                                .background(swatch)
                                .clickable { onSelectColor(swatch.toArgb()) },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 12.dp)) {
                    for (width in listOf(1.5f, 2.5f, 4f)) {
                        Box(
                            modifier =
                                Modifier
                                    .size((width * 4).dp)
                                    .clip(CircleShape)
                                    .background(if (width == strokeWidthPoints) Accent else Color.White.copy(alpha = 0.4f))
                                    .clickable { onSelectWidth(width) },
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TrayAction("Sign", selectedTool == WritingTool.SIGN) { onSelectTool(WritingTool.SIGN) }
            TrayAction("Text", selectedTool == WritingTool.TEXT) { onSelectTool(WritingTool.TEXT) }
            TrayAction("Draw", selectedTool == WritingTool.DRAW) { onSelectTool(WritingTool.DRAW) }
            TrayAction("Erase", selectedTool == WritingTool.ERASE) { onSelectTool(WritingTool.ERASE) }
            TrayAction("Save", false, onSave)
        }
    }
}

@Composable
private fun TrayAction(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) Accent.copy(alpha = 0.25f) else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) Accent else Color.White,
        )
    }
}
