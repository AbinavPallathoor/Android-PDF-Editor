package com.pdfeditor.app.ui.reader

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pdfeditor.app.R
import com.pdfeditor.app.data.cache.SavedSignature
import com.pdfeditor.app.data.cache.SignatureRepository
import com.pdfeditor.app.domain.Annotation
import com.pdfeditor.app.domain.HighlightAnnotation
import com.pdfeditor.app.domain.PdfPoint
import com.pdfeditor.app.domain.PdfTextWord
import com.pdfeditor.app.domain.RecentDocument
import com.pdfeditor.app.domain.SignaturePlacement
import com.pdfeditor.app.domain.TextBoxAnnotation
import com.pdfeditor.app.ui.writing.clampAnnotationToPage
import com.pdfeditor.app.ui.writing.hitTestAnnotation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(
    uiState: ReaderUiState,
    renderPage: suspend (Int, Int, Int) -> Bitmap?,
    loadText: suspend (Int) -> List<PdfTextWord>,
    onPositionChanged: (Int, Float, Float) -> Unit,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onSave: (asCopy: Boolean) -> Unit,
    onAddAnnotation: (String, Annotation) -> Unit,
    onReplaceAnnotation: (String, Annotation) -> Unit,
    onRemoveAnnotation: (String, String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onReorderPages: (List<Int>) -> Unit,
    onDeletePage: (Int) -> Unit,
    onAppendRequested: () -> Unit,
    onToggleInvert: () -> Unit,
    onToggleLayout: () -> Unit,
    onRename: (String) -> Unit = {},
    onCompress: () -> Unit = {},
    modifier: Modifier = Modifier,
    adjacentDocument: (Boolean) -> RecentDocument? = { null },
    onSwitchDocument: (Boolean) -> Unit = {},
    onEditText: (String, TextBoxAnnotation, String) -> Unit = { pageId, annotation, _ ->
        val exists = (uiState as? ReaderUiState.Ready)?.annotations?.values?.flatten()?.any { it.id == annotation.id } == true
        if (annotation.text.isEmpty()) {
            onRemoveAnnotation(pageId, annotation.id)
        } else if (exists) {
            onReplaceAnnotation(pageId, annotation)
        } else {
            onAddAnnotation(pageId, annotation)
        }
    },
) {
    when (uiState) {
        ReaderUiState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is ReaderUiState.Error ->
            Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column {
                    Text(uiState.message)
                    TextButton(onClick = onClose) { Text("Back") }
                }
            }
        ReaderUiState.PasswordProtected -> Text("Password-protected PDFs are not supported yet.")
        is ReaderUiState.Ready ->
            EditorDocument(
                uiState,
                renderPage,
                loadText,
                onPositionChanged,
                onClose,
                onShare,
                onSave,
                onAddAnnotation,
                onReplaceAnnotation,
                onRemoveAnnotation,
                onUndo,
                onRedo,
                onReorderPages,
                onDeletePage,
                onAppendRequested,
                onToggleInvert,
                onToggleLayout,
                onRename,
                onCompress,
                modifier,
                adjacentDocument,
                onSwitchDocument,
                onEditText,
            )
    }
}

@Composable
private fun EditorDocument(
    state: ReaderUiState.Ready,
    renderPage: suspend (Int, Int, Int) -> Bitmap?,
    loadText: suspend (Int) -> List<PdfTextWord>,
    onPositionChanged: (Int, Float, Float) -> Unit,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onSave: (Boolean) -> Unit,
    onAdd: (String, Annotation) -> Unit,
    onReplace: (String, Annotation) -> Unit,
    onRemove: (String, String) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onReorder: (List<Int>) -> Unit,
    onDelete: (Int) -> Unit,
    onAppend: () -> Unit,
    onInvert: () -> Unit,
    onLayout: () -> Unit,
    onRename: (String) -> Unit,
    onCompress: () -> Unit,
    modifier: Modifier,
    adjacentDocument: (Boolean) -> RecentDocument?,
    onSwitchDocument: (Boolean) -> Unit,
    onEditText: (String, TextBoxAnnotation, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val list = rememberLazyListState(state.initialPage)
    val pager = rememberPagerState(initialPage = state.initialPage.coerceIn(0, state.pageCount - 1), pageCount = { state.pageCount })
    var tool by remember { mutableStateOf(EditorTool.HAND) }
    var color by remember { mutableIntStateOf(0xFF000000.toInt()) }
    var zoom by remember { mutableFloatStateOf(state.initialZoom.coerceIn(.25f, 5f)) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var viewportWidth by remember { mutableFloatStateOf(1f) }
    var viewportHeight by remember { mutableFloatStateOf(1f) }
    var editingTrayHeight by remember { mutableFloatStateOf(0f) }
    var overview by remember { mutableStateOf(false) }
    var saveMenu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }
    var chromeTick by remember { mutableIntStateOf(0) }
    var titleThumbnail by remember(state.documentVersion) { mutableStateOf<Bitmap?>(null) }
    var zoomVisible by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<TextSelection?>(null) }
    var textTarget by remember { mutableStateOf<PageTextEdit?>(null) }
    var caretBounds by remember { mutableStateOf<Rect?>(null) }
    var selectedObject by remember { mutableStateOf<Pair<String, String>?>(null) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    fun finishText() {
        caretBounds = null
        textTarget = null
        focusManager.clearFocus()
        keyboard?.hide()
    }
    var signaturePicker by remember { mutableStateOf(false) }
    var captureSignature by remember { mutableStateOf(false) }
    val signatureStore = remember { SignatureRepository(File(context.filesDir, "signatures.json")) }
    var signatures by remember { mutableStateOf<List<SavedSignature>>(emptyList()) }
    var activeSignature by remember { mutableStateOf<SavedSignature?>(null) }
    var pendingDelete by remember { mutableStateOf<Int?>(null) }
    var navigationEpoch by remember { mutableIntStateOf(0) }
    var restoredPosition by remember { mutableStateOf(false) }
    val isPaged by rememberUpdatedState(state.paged)
    val pageCount by rememberUpdatedState(state.pageCount)
    val currentPage = (if (state.paged) pager.currentPage else list.firstVisibleItemIndex).coerceIn(0, state.pageCount - 1)
    LaunchedEffect(Unit) {
        signatures = signatureStore.load()
        activeSignature = signatures.lastOrNull()
    }
    LaunchedEffect(state.paged) {
        selection = null
        if (state.paged) {
            pager.scrollToPage(
                list.firstVisibleItemIndex.coerceIn(0, state.pageCount - 1),
            )
        } else {
            list.scrollToItem(pager.currentPage.coerceIn(0, state.pageCount - 1))
        }
        panX = 0f
        panY = 0f
    }
    LaunchedEffect(currentPage, state.documentVersion) {
        selection = null
        panX = 0f
        panY = 0f
    }
    LaunchedEffect(viewportWidth) {
        if (!restoredPosition && viewportWidth > 1f) {
            if (!state.paged) {
                list.scrollToItem(
                    state.initialPage.coerceIn(0, state.pageCount - 1),
                    (
                        viewportWidth * zoom *
                            state.pageAspectRatios[state.initialPage.coerceIn(0, state.pageCount - 1)] *
                            state.initialScrollFraction
                    ).roundToInt(),
                )
            }
            restoredPosition = true
        }
    }
    LaunchedEffect(state.paged, restoredPosition, state.documentVersion, viewportWidth) {
        if (restoredPosition) {
            snapshotFlow {
                Triple(
                    if (state.paged)pager.currentPage else list.firstVisibleItemIndex,
                    if (state.paged)0 else list.firstVisibleItemScrollOffset,
                    zoom,
                )
            }.collect {
                    (index, offset, scale) ->
                val safe = index.coerceIn(0, state.pageCount - 1)
                onPositionChanged(safe, scale, (offset / (viewportWidth * scale * state.pageAspectRatios[safe])).coerceIn(0f, 1f))
            }
        }
    }
    LaunchedEffect(state.documentVersion) {
        titleThumbnail = renderPage(0, 160, (160 * state.pageAspectRatios[0]).roundToInt().coerceAtLeast(1))
    }
    LaunchedEffect(chromeVisible, chromeTick) {
        if (chromeVisible) {
            delay(3500)
            chromeVisible = false
        }
    }
    LaunchedEffect(list.isScrollInProgress) {
        if (list.isScrollInProgress) chromeVisible = false
    }
    LaunchedEffect(zoom) {
        if (zoom != state.initialZoom) {
            zoomVisible = true
            delay(1200)
            zoomVisible = false
        }
    }

    fun finishEditing() {
        finishText()
        selectedObject = null
        editing = false
        tool = EditorTool.HAND
        selection = null
        chromeVisible = true
        chromeTick++
    }
    BackHandler(overview) { overview = false }
    BackHandler(!overview) { if (editing) finishEditing() else onClose() }
    BackHandler(textTarget != null) { finishText() }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    var imeWasVisible by remember(textTarget?.session) { mutableStateOf(false) }
    LaunchedEffect(imeBottom, textTarget?.session) {
        if (textTarget != null) {
            if (imeBottom > 0) {
                imeWasVisible = true
            } else if (imeWasVisible) {
                finishText()
            }
        }
    }

    val editingBottom =
        with(androidx.compose.ui.platform.LocalDensity.current) { (if (editing && textTarget == null) editingTrayHeight else 0f).toDp() }
    val floatingSaveSpace = if (state.dirty || state.saving) 64.dp else 0.dp
    val saveHeight = with(LocalDensity.current) { floatingSaveSpace.toPx() }
    val usableHeight = (viewportHeight - (if (editing && textTarget == null) editingTrayHeight else 0f) - saveHeight).coerceAtLeast(1f)

    LaunchedEffect(usableHeight, caretBounds, textTarget?.session, imeBottom) {
        if (state.paged) {
            val pageHeight = viewportWidth * zoom * state.pageAspectRatios[currentPage]
            val normalMaxY = ((pageHeight - usableHeight) / 2f).coerceAtLeast(0f)
            val caret = caretBounds
            if (textTarget?.pageId == state.pageIds[currentPage] && caret != null && imeBottom > 0) {
                val origin = (usableHeight - pageHeight) / 2f + panY
                val top = origin + caret.top
                val bottom = origin + caret.bottom
                val margin = 16f
                val delta =
                    when {
                        bottom > usableHeight - margin -> usableHeight - margin - bottom
                        top < margin -> margin - top
                        else -> 0f
                    }
                panY = (panY + delta).coerceIn(-normalMaxY - margin, normalMaxY + margin)
            } else {
                panY = panY.coerceIn(-normalMaxY, normalMaxY)
            }
        }
    }

    fun fitPage() {
        zoom = minOf(1f, usableHeight / (viewportWidth * state.pageAspectRatios[currentPage])).coerceIn(.25f, 1f)
        panX = 0f
        panY = 0f
    }
    val updateZoom by rememberUpdatedState<(Float, Offset) -> Unit>({ factor, pan ->
        zoom = (zoom * factor).coerceIn(.25f, 5f)
        val maxX = (viewportWidth * (zoom - 1f) / 2f).coerceAtLeast(0f)
        panX = (panX + pan.x).coerceIn(-maxX, maxX)
        if (state.paged) {
            val maxY = ((viewportWidth * zoom * state.pageAspectRatios[currentPage] - usableHeight) / 2f).coerceAtLeast(0f)
            panY = (panY + pan.y).coerceIn(-maxY, maxY)
        } else if (pan.y != 0f) {
            scope.launch { list.scrollBy(-pan.y) }
        }
    })
    val finishTextNow by rememberUpdatedState(::finishText)
    Box(modifier.fillMaxSize().background(Color.Black).imePadding()) {
        Box(
            Modifier.fillMaxSize().clipToBounds().testTag("document-surface")
                .onSizeChanged {
                    viewportWidth = it.width.toFloat()
                    viewportHeight = it.height.toFloat()
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var hadMultiple = false
                        var multiPanX = 0f
                        var multiZoom = 1f
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                if (!hadMultiple) {
                                    finishTextNow()
                                    selectedObject = null
                                    navigationEpoch++
                                    hadMultiple = true
                                }
                                val factor = event.calculateZoom()
                                val pan = event.calculatePan()
                                multiPanX += pan.x
                                multiZoom *= factor
                                updateZoom(factor, pan)
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (isPaged && zoom <= 1.01f && kotlin.math.abs(multiZoom - 1f) < .1f && kotlin.math.abs(multiPanX) > 60f) {
                            scope.launch {
                                pager.animateScrollToPage(
                                    (pager.currentPage + if (multiPanX < 0) 1 else -1).coerceIn(0, pageCount - 1),
                                )
                            }
                        }
                    }
                },
        ) {
            @Composable fun page(
                index: Int,
                paged: Boolean,
            ) {
                val pageId = state.pageIds.getOrElse(index) { "page-$index" }
                BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
                    val width = maxWidth * zoom
                    val pageHeight = width * state.pageAspectRatios[index]
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val renderWidth = with(density) { width.roundToPx().coerceIn(1, 2048) }
                    val renderHeight = (renderWidth * state.pageAspectRatios[index]).roundToInt().coerceAtLeast(1)
                    var bitmap by remember(pageId, state.documentVersion, renderWidth) { mutableStateOf<Bitmap?>(null) }
                    var words by remember(pageId) { mutableStateOf<List<PdfTextWord>>(emptyList()) }
                    LaunchedEffect(pageId, state.documentVersion, renderWidth) { bitmap = renderPage(index, renderWidth, renderHeight) }
                    LaunchedEffect(pageId) { words = runCatching { loadText(index) }.getOrDefault(emptyList()) }
                    DocumentPageSurface(
                        pageId,
                        index,
                        state.pageWidthsPoints[index],
                        bitmap,
                        state.annotations[index].orEmpty(),
                        words,
                        state.invertColors,
                        tool,
                        color,
                        selection,
                        navigationEpoch = { navigationEpoch },
                        allowAnnotationMove = editing,
                        selectedObjectId = selectedObject?.takeIf { it.first == pageId }?.second,
                        onSelectObject = { selectedObject = it?.let { id -> pageId to id } },
                        inlineText = textTarget?.takeIf { it.pageId == pageId },
                        onFinishText = ::finishText,
                        onInlineCaret = { if (textTarget?.pageId == pageId) caretBounds = it },
                        onInlineChange = { raw, pdfText ->
                            textTarget?.takeIf { it.pageId == pageId }?.let { edit ->
                                val height = state.pageWidthsPoints[index] * state.pageAspectRatios[index]
                                val font = edit.annotation.fontSizePoints
                                val lines = pdfText.count { it == '\n' } + 1
                                val contentHeight = font * 1.25f * lines
                                if (contentHeight <= height) {
                                    val baseline =
                                        edit.annotation.position.y.coerceIn(
                                            font,
                                            height - (lines - 1) * font * 1.25f - font * .25f,
                                        )
                                    val position = edit.annotation.position.copy(y = baseline)
                                    textTarget =
                                        edit.copy(
                                            annotation = edit.annotation.copy(text = raw, position = position),
                                            pdfText = pdfText,
                                        )
                                    onEditText(pageId, edit.annotation.copy(text = pdfText, position = position), edit.session)
                                } else {
                                    android.widget.Toast.makeText(
                                        context,
                                        "This text box fills the page. Add another text box to continue.",
                                        android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        },
                        onPageTap = {
                            chromeVisible = !chromeVisible
                            chromeTick++
                        },
                        onSelection = {
                            selection = it
                            if (it != null) selectedObject = null
                        }, onAdd = {
                            onAdd(pageId, it)
                            selectedObject = pageId to it.id
                        },
                        onReplace = { onReplace(pageId, it) }, onRemove = { onRemove(pageId, it) },
                        onText = { point ->
                            val existing =
                                hitTestAnnotation(
                                    state.annotations[index].orEmpty().filterIsInstance<TextBoxAnnotation>(),
                                    point,
                                ) as? TextBoxAnnotation
                            val annotation =
                                existing ?: TextBoxAnnotation(
                                    UUID.randomUUID().toString(),
                                    PdfPoint(
                                        point.x.coerceIn(4f, (state.pageWidthsPoints[index] - 48f).coerceAtLeast(4f)),
                                        point.y.coerceIn(14f, state.pageWidthsPoints[index] * state.pageAspectRatios[index] - 4f),
                                    ),
                                    "", 14f, color,
                                )
                            caretBounds = null
                            textTarget = PageTextEdit(pageId, annotation)
                            selectedObject = pageId to annotation.id
                            if (existing != null) color = existing.colorArgb
                        },
                        onSign = { point ->
                            val selected = activeSignature
                            if (selected == null) {
                                signaturePicker = true
                            } else {
                                val signature = SignaturePlacement(UUID.randomUUID().toString(), point, selected.strokes, .65f, color)
                                selectedObject = pageId to signature.id
                                onAdd(
                                    pageId,
                                    clampAnnotationToPage(
                                        signature,
                                        state.pageWidthsPoints[index],
                                        state.pageWidthsPoints[index] * state.pageAspectRatios[index],
                                    ),
                                )
                            }
                        },
                        modifier =
                            Modifier.requiredSize(
                                width,
                                pageHeight,
                            ).offset { IntOffset(panX.roundToInt(), if (paged)panY.roundToInt() else 0) },
                    )
                }
            }
            if (state.paged) {
                HorizontalPager(
                    pager,
                    Modifier.fillMaxSize().padding(bottom = editingBottom + floatingSaveSpace).testTag("paged-document"),
                    userScrollEnabled = tool == EditorTool.HAND && zoom <= 1.01f,
                ) { index -> page(index, true) }
            } else {
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize().testTag("scroll-document"),
                    contentPadding = PaddingValues(top = 12.dp, bottom = editingBottom + floatingSaveSpace + 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.pageCount, key = { state.pageIds.getOrElse(it) { "page-$it" } }) { index ->
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val height = with(density) { (viewportWidth * zoom * state.pageAspectRatios[index]).toDp() }
                        Box(Modifier.fillMaxWidth().height(height)) { page(index, false) }
                    }
                }
            }
            if (state.dirty || state.saving) {
                Box(
                    Modifier.align(Alignment.BottomEnd).navigationBarsPadding()
                        .padding(bottom = editingBottom + 12.dp, end = 12.dp),
                ) {
                    IconButton(
                        onClick = { saveMenu = true },
                        enabled = !state.saving,
                        modifier =
                            Modifier.size(
                                44.dp,
                            ).background(
                                Color.Black,
                                androidx.compose.foundation.shape.CircleShape,
                            ).testTag("save-edits"),
                    ) {
                        if (state.saving) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(painterResource(R.drawable.ic_save), contentDescription = "Save edits", tint = Color.White)
                        }
                    }
                    DropdownMenu(expanded = saveMenu, onDismissRequest = { saveMenu = false }) {
                        DropdownMenuItem(text = { Text("Save as new file") }, onClick = {
                            saveMenu = false
                            finishText()
                            onSave(true)
                        }, modifier = Modifier.testTag("save-copy"))
                        DropdownMenuItem(text = { Text("Save to current file") }, enabled = state.canSaveOriginal, onClick = {
                            saveMenu = false
                            finishText()
                            onSave(false)
                        }, modifier = Modifier.testTag("save-current"))
                        if (!state.canSaveOriginal) {
                            Text(
                                "Opened with read-only access",
                                Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            selection?.let { selected ->
                Surface(
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (editing) 116.dp else 16.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = Color.Black,
                ) {
                    Row {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(selected.text))
                            selection = null
                        }, modifier = Modifier.testTag("copy-selection")) { Text("Copy") }
                        TextButton(onClick = {
                            onAdd(
                                selected.pageId,
                                HighlightAnnotation(UUID.randomUUID().toString(), selected.selected.map { it.bounds }, 0x59FFFF00),
                            )
                            selection = null
                        }) { Text("Highlight") }
                        TextButton(onClick = { selection = null }) { Text("Dismiss") }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = editing && textTarget == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier =
                Modifier.align(Alignment.BottomCenter).onSizeChanged { editingTrayHeight = it.height.toFloat() }
                    .testTag("editing-tray").navigationBarsPadding().padding(12.dp),
        ) {
            DocumentEditingTray(
                tool, color, state.canUndo, state.canRedo,
                onTool = { item ->
                    finishText()
                    selectedObject = null
                    selection = null
                    tool = item
                    if (item == EditorTool.SIGN) signaturePicker = true
                    if (item == EditorTool.HIGHLIGHT) {
                        color = 0xFFFFFF00.toInt()
                    } else if (color == 0xFFFFFF00.toInt()) {
                        color = 0xFF000000.toInt()
                    }
                },
                onColor = { color = it }, onUndo = onUndo, onRedo = onRedo, onDone = ::finishEditing,
            )
        }
        AnimatedVisibility(
            visible = !editing && list.isScrollInProgress || zoomVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (editing) 128.dp else 24.dp),
        ) {
            Text(
                if (zoomVisible) "${(zoom * 100).roundToInt()}%" else "${currentPage + 1} / ${state.pageCount}",
                Modifier.background(Color.Black, androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp).testTag("zoom-label"),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        state.message?.let { message ->
            var dismissed by remember(message) { mutableStateOf(false) }
            LaunchedEffect(message) {
                delay(4500)
                dismissed = true
            }
            if (!dismissed) {
                Surface(
                    Modifier.align(
                        Alignment.BottomCenter,
                    ).navigationBarsPadding().padding(bottom = if (editing) 128.dp else 24.dp, start = 16.dp, end = 16.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = Color.Black,
                ) { Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
            }
        }
        DocumentTitleBar(
            state.displayName, "${currentPage + 1} / ${state.pageCount}", titleThumbnail,
            visible = chromeVisible, invertColors = state.invertColors,
            contentResolver = context.contentResolver, adjacentDocument = adjacentDocument,
            onRename = onRename, onShare = onShare, onCompress = {
                onCompress()
                null
            },
            onSwitchDocument = onSwitchDocument, onClose = onClose,
            onToggleWriteMode = {
                if (editing) {
                    finishEditing()
                } else {
                    editing = true
                    tool = EditorTool.DRAW
                    chromeVisible = false
                    chromeTick++
                }
            },
            onToggleInvert = onInvert, editing = editing,
            expandedContent = { dismiss ->
                DocumentOptions(
                    state.paged,
                    onPages = {
                        dismiss()
                        overview = true
                    },
                    onLayout = {
                        onLayout()
                        dismiss()
                    },
                    onFit = {
                        fitPage()
                        dismiss()
                    },
                    onShare = {
                        dismiss()
                        onShare()
                    },
                    onCompress = {
                        dismiss()
                        onCompress()
                    },
                )
            },
        )
    }
    if (overview) {
        PageOverviewScreen(
            state.pageCount, state.documentVersion, state.pageAspectRatios, renderPage,
            onPageSelected = {
                    index ->
                scope.launch { if (state.paged)pager.scrollToPage(index) else list.scrollToItem(index) }
                overview = false
                selection = null
            },
            onReorderPages = onReorder,
            onDeletePage = { pendingDelete = it },
            onAppendRequested = onAppend,
            onDismiss = { overview = false },
        )
    }
    pendingDelete?.let { index ->
        AlertDialog(
            onDismissRequest = {
                pendingDelete = null
            },
            title = { Text("Delete page ${index + 1}?") },
            text = { Text("You can undo this change before saving.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(index)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
    if (signaturePicker) {
        AlertDialog(onDismissRequest = { signaturePicker = false }, title = { Text("Signatures") }, text = {
            Column {
                signatures.forEachIndexed {
                        i,
                        saved,
                    ->
                    TextButton(onClick = {
                        activeSignature = saved
                        signaturePicker = false
                        tool = EditorTool.SIGN
                    }) { Text("Use signature ${i + 1}") }
                }
                TextButton(onClick = {
                    signaturePicker = false
                    captureSignature = true
                }) { Text("Draw a new signature") }
            }
        }, confirmButton = { TextButton(onClick = { signaturePicker = false }) { Text("Cancel") } })
    }
    if (captureSignature) {
        SignatureCapture(onCancel = { captureSignature = false }, onSave = { strokes ->
            val saved = SavedSignature(strokes)
            signatures = signatures + saved
            activeSignature = saved
            captureSignature = false
            tool = EditorTool.SIGN
            scope.launch { signatureStore.save(signatures) }
        })
    }
}

@Composable
private fun SignatureCapture(
    onCancel: () -> Unit,
    onSave: (List<List<PdfPoint>>) -> Unit,
) {
    var strokes by remember { mutableStateOf<List<List<Offset>>>(emptyList()) }
    var active by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var width by remember { mutableFloatStateOf(1f) }
    AlertDialog(onDismissRequest = onCancel, title = { Text("Draw your signature") }, text = {
        Column {
            Canvas(
                Modifier.fillMaxWidth().height(180.dp).background(Color.White).testTag("signature-canvas").onSizeChanged {
                    width = it.width.toFloat()
                }.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val points = mutableListOf(down.position)
                        active = points.toList()
                        do {
                            val event = awaitPointerEvent()
                            val c = event.changes.first()
                            if (c.pressed) {
                                points.add(c.position)
                                active = points.toList()
                            }
                            c.consume()
                        } while (event.changes.any { it.pressed })
                        if (points.size > 1)strokes = strokes + listOf(points.toList())
                        active = emptyList()
                    }
                },
            ) {
                (strokes + listOf(active)).forEach { points ->
                    if (points.size > 1) {
                        val p =
                            Path().apply {
                                moveTo(points.first().x, points.first().y)
                                points.drop(1).forEach { lineTo(it.x, it.y) }
                            }
                        drawPath(p, Color.Black, style = Stroke(3f))
                    }
                }
            }
            TextButton(onClick = { strokes = emptyList() }) { Text("Clear") }
        }
    }, confirmButton = {
        TextButton(enabled = strokes.isNotEmpty(), onClick = {
            onSave(
                strokes.map {
                        stroke ->
                    stroke.map { PdfPoint(it.x / width * 200f, it.y / width * 200f) }
                },
            )
        }) { Text("Save signature") }
    }, dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } })
}
