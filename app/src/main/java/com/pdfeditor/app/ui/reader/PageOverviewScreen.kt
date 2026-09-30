package com.pdfeditor.app.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Opened by pinching out at minimum zoom. Reuses the same `renderThumbnail` render pipeline as the
 * reading pager (just at a lower target resolution), so there's no separate thumbnail cache to
 * maintain. Tap jumps back into reading mode at that page. Drag-to-reorder is Milestone 5 - it
 * needs PdfBox-Android to actually persist a new page order, which doesn't exist yet, so this is
 * read-only navigation for now rather than a half-working reorder UI.
 */
@Composable
fun PageOverviewScreen(
    pageCount: Int,
    pageAspectRatios: List<Float>,
    renderThumbnail: suspend (pageIndex: Int, widthPx: Int, heightPx: Int) -> Bitmap?,
    onPageSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(count = pageCount, key = { it }) { index ->
                OverviewThumbnail(
                    pageIndex = index,
                    aspectRatio = pageAspectRatios[index],
                    renderThumbnail = renderThumbnail,
                    onClick = { onPageSelected(index) },
                )
            }
        }
    }
}

@Composable
private fun OverviewThumbnail(
    pageIndex: Int,
    aspectRatio: Float,
    renderThumbnail: suspend (Int, Int, Int) -> Bitmap?,
    onClick: () -> Unit,
) {
    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f / aspectRatio)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF0A0A0A))
                .clickable(onClick = onClick),
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = (widthPx * aspectRatio).roundToInt()
        var bitmap by remember(pageIndex, widthPx) { mutableStateOf<Bitmap?>(null) }

        LaunchedEffect(pageIndex, widthPx) {
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
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}
