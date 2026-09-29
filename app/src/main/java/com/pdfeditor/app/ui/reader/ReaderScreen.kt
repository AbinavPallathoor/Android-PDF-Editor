package com.pdfeditor.app.ui.reader

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pdfeditor.app.ui.theme.OnSurfaceSecondary
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val InvertColorFilter =
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

@Composable
fun ReaderScreen(
    uiState: ReaderUiState,
    renderPage: suspend (pageIndex: Int, widthPx: Int, heightPx: Int) -> Bitmap?,
    onPositionChanged: (pageIndex: Int, zoomScale: Float, scrollFraction: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        when (uiState) {
            is ReaderUiState.Loading -> Unit
            is ReaderUiState.Ready -> ReadingPager(uiState, renderPage, onPositionChanged)
            is ReaderUiState.PasswordProtected ->
                CenteredMessage(title = "Password-protected PDF", subtitle = "Encrypted PDFs aren't supported yet")
            is ReaderUiState.Error -> CenteredMessage(title = "Couldn't open this PDF", subtitle = uiState.message)
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun ReadingPager(
    state: ReaderUiState.Ready,
    renderPage: suspend (Int, Int, Int) -> Bitmap?,
    onPositionChanged: (Int, Float, Float) -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.initialPage)
    var zoom by remember { mutableStateOf(ZoomState(scale = state.initialZoom.coerceIn(ZoomState.MIN_SCALE, ZoomState.MAX_SCALE))) }
    var viewportSize by remember { mutableStateOf(Size.Zero) }
    var indicatorVisible by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            indicatorVisible = true
        } else {
            delay(1500)
            indicatorVisible = false
        }
    }

    // "First visible item" per Compose is whichever item has any pixel showing at the top, which
    // under-reports once you're mostly scrolled past it (e.g. still reads page 1 with only a
    // sliver of it left onscreen). Bump to the next page once we're more than half scrolled
    // through the current one, so the indicator matches what the reader actually perceives.
    fun effectivePage(): Int {
        val index = listState.firstVisibleItemIndex
        if (viewportSize.width <= 0 || index >= state.pageAspectRatios.size) return index
        val itemHeightPx = viewportSize.width * state.pageAspectRatios[index]
        return if (listState.firstVisibleItemScrollOffset > itemHeightPx / 2) {
            (index + 1).coerceAtMost(state.pageCount - 1)
        } else {
            index
        }
    }

    LaunchedEffect(viewportSize) {
        if (viewportSize.height <= 0) return@LaunchedEffect
        snapshotFlow { Triple(effectivePage(), listState.firstVisibleItemScrollOffset, zoom.scale) }
            .debounce(500)
            .collectLatest { (pageIndex, offsetPx, scale) ->
                val fraction = (offsetPx / viewportSize.height).coerceIn(0f, 1f)
                onPositionChanged(pageIndex, scale, fraction)
            }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onSizeChanged { viewportSize = Size(it.width.toFloat(), it.height.toFloat()) }
                .pinchZoomAndDoubleTap(
                    zoomState = { zoom },
                    viewportSize = { viewportSize },
                    onZoomChange = { zoom = it },
                    onZoomSettled = {
                        scope.launch { onPositionChanged(effectivePage(), zoom.scale, 0f) }
                    },
                ),
    ) {
        LazyColumn(
            state = listState,
            userScrollEnabled = zoom.scale <= ZoomState.MIN_SCALE + 0.01f,
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom.scale
                        scaleY = zoom.scale
                        translationX = zoom.offset.x
                        translationY = zoom.offset.y
                    },
        ) {
            items(count = state.pageCount, key = { it }) { index ->
                PdfPageItem(
                    pageIndex = index,
                    aspectRatio = state.pageAspectRatios[index],
                    invertColors = state.invertColors,
                    renderPage = renderPage,
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = indicatorVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
        ) {
            PageIndicator(current = effectivePage() + 1, total = state.pageCount)
        }
    }
}

@Composable
private fun PdfPageItem(
    pageIndex: Int,
    aspectRatio: Float,
    invertColors: Boolean,
    renderPage: suspend (Int, Int, Int) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = (widthPx * aspectRatio).roundToInt()
        val heightDp = with(density) { heightPx.toDp() }
        var bitmap by remember(pageIndex, widthPx) { mutableStateOf<Bitmap?>(null) }

        LaunchedEffect(pageIndex, widthPx) {
            bitmap = renderPage(pageIndex, widthPx, heightPx)
        }

        Box(
            modifier = Modifier.fillMaxWidth().height(heightDp).background(Color(0xFF0A0A0A)),
        ) {
            bitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    colorFilter = if (invertColors) InvertColorFilter else null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun PageIndicator(
    current: Int,
    total: Int,
) {
    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.12f))
                .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text = "$current / $total",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun CenteredMessage(
    title: String,
    subtitle: String,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, start = 32.dp, end = 32.dp),
            )
        }
    }
}
