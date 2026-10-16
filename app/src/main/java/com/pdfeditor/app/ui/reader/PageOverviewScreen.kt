package com.pdfeditor.app.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Page grid, animated into view by the reader. Tap to read a page, long-press-drag to reorder,
 * or use its delete badge; the Add pages tile opens a source PDF picker with whole/subset import.
 * Changes belong to the recoverable document session and remain undoable until saving.
 */
@Composable
fun PageOverviewScreen(
    pageCount: Int,
    documentVersion: Int,
    pageAspectRatios: List<Float>,
    renderThumbnail: suspend (pageIndex: Int, widthPx: Int, heightPx: Int) -> Bitmap?,
    onPageSelected: (Int) -> Unit,
    onReorderPages: (newOrder: List<Int>) -> Unit,
    onDeletePage: (Int) -> Unit,
    onAppendRequested: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()
    var order by remember(pageCount) { mutableStateOf((0 until pageCount).toList()) }
    var draggingKey by remember { mutableStateOf<Int?>(null) }
    var dragStartLayoutOffset by remember { mutableStateOf(Offset.Zero) }
    var dragAccumulated by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .testTag("page-overview")
                .background(Color.Black)
                .clickable(onClick = onDismiss),
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(3),
            // The status/nav bars intercept touches in their own inset region before the app ever
            // sees them (see CLAUDE.md's gesture-architecture note) - without this, the top row's
            // delete badges would render but never be reachable by touch on a real device.
            contentPadding =
                WindowInsets.systemBars.asPaddingValues().let {
                    PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 16.dp + it.calculateTopPadding(),
                        bottom = 16.dp + it.calculateBottomPadding(),
                    )
                },
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(count = order.size, key = { order[it] }) { position ->
                val pageIndex = order[position]
                val isDragging = draggingKey == pageIndex

                OverviewThumbnail(
                    pageIndex = pageIndex,
                    documentVersion = documentVersion,
                    aspectRatio = pageAspectRatios[pageIndex],
                    renderThumbnail = renderThumbnail,
                    onClick = { onPageSelected(pageIndex) },
                    onDelete = { onDeletePage(pageIndex) },
                    canDelete = pageCount > 1,
                    modifier =
                        Modifier
                            .graphicsLayer {
                                if (isDragging) {
                                    val layoutOffset =
                                        gridState.layoutInfo.visibleItemsInfo
                                            .find { it.key == pageIndex }
                                            ?.offset
                                    if (layoutOffset != null) {
                                        val desired =
                                            dragStartLayoutOffset + dragAccumulated
                                        translationX = desired.x - layoutOffset.x
                                        translationY = desired.y - layoutOffset.y
                                    }
                                    shadowElevation = 12f
                                    scaleX = 1.04f
                                    scaleY = 1.04f
                                }
                            }
                            .pointerInput(pageIndex, order.size) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingKey = pageIndex
                                        dragAccumulated = Offset.Zero
                                        dragStartLayoutOffset =
                                            gridState.layoutInfo.visibleItemsInfo
                                                .find { it.key == pageIndex }
                                                ?.offset
                                                ?.let { Offset(it.x.toFloat(), it.y.toFloat()) }
                                                ?: Offset.Zero
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragAccumulated += dragAmount
                                        val currentPos = order.indexOf(pageIndex)
                                        val info =
                                            gridState.layoutInfo.visibleItemsInfo.find { it.key == pageIndex }
                                                ?: return@detectDragGesturesAfterLongPress
                                        val desiredCenter =
                                            dragStartLayoutOffset + dragAccumulated +
                                                Offset(info.size.width / 2f, info.size.height / 2f)
                                        val target =
                                            gridState.layoutInfo.visibleItemsInfo.minByOrNull { item ->
                                                val center =
                                                    Offset(
                                                        item.offset.x + item.size.width / 2f,
                                                        item.offset.y + item.size.height / 2f,
                                                    )
                                                (center - desiredCenter).getDistanceSquared()
                                            }
                                        if (target != null && target.index != currentPos && target.key != "append") {
                                            order =
                                                order.toMutableList().apply {
                                                    add(target.index, removeAt(currentPos))
                                                }
                                        }
                                    },
                                    onDragEnd = {
                                        draggingKey = null
                                        onReorderPages(order)
                                        order = order.indices.toList()
                                    },
                                    onDragCancel = {
                                        draggingKey = null
                                        order = order.indices.toList()
                                    },
                                )
                            },
                )
            }

            item(key = "append") {
                AppendTile(onClick = onAppendRequested)
            }
        }
    }
}

@Composable
private fun AppendTile(onClick: () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black)
                .border(1.dp, Color.White.copy(alpha = .25f), RoundedCornerShape(6.dp))
                .semantics { contentDescription = "Add pages from a PDF" }
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "+", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text("Add pages", style = MaterialTheme.typography.labelMedium, color = Color.White)
        }
    }
}

@Composable
private fun OverviewThumbnail(
    pageIndex: Int,
    documentVersion: Int,
    aspectRatio: Float,
    renderThumbnail: suspend (Int, Int, Int) -> Bitmap?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    canDelete: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(1f / aspectRatio)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black)
                .clickable(onClick = onClick),
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = (widthPx * aspectRatio).roundToInt()
        // Keyed on documentVersion too - see PdfPageItem's identical note in ReaderScreen.kt.
        var bitmap by remember(pageIndex, widthPx, documentVersion) { mutableStateOf<Bitmap?>(null) }

        LaunchedEffect(pageIndex, widthPx, documentVersion) {
            bitmap = renderThumbnail(pageIndex, widthPx, heightPx)
        }

        bitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Text(
            text = "${pageIndex + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color.Black, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
        )

        if (canDelete) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.Black)
                        .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "×", style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
        }
    }
}
