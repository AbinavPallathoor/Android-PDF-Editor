package com.pdfeditor.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pdfeditor.app.domain.RecentDocument
import com.pdfeditor.app.ui.theme.OnSurfaceSecondary
import com.pdfeditor.app.ui.theme.SurfaceDim
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(
    recents: List<RecentDocument>,
    onOpenDocument: () -> Unit,
    onOpenRecent: (RecentDocument) -> Unit,
    onRemoveRecent: (RecentDocument) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            if (recents.isEmpty()) {
                EmptyState(modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(recents, key = { it.id }) { doc ->
                        SwipeableRecentRow(
                            document = doc,
                            onOpen = { onOpenRecent(doc) },
                            onRemove = { onRemoveRecent(doc) },
                        )
                    }
                }
            }
            OpenAction(onClick = onOpenDocument)
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "No PDFs yet",
            style = MaterialTheme.typography.bodyLarge,
            color = OnSurfaceSecondary,
        )
    }
}

@Composable
private fun SwipeableRecentRow(
    document: RecentDocument,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val dismissState =
        rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                val shouldDismiss = value == SwipeToDismissBoxValue.EndToStart || value == SwipeToDismissBoxValue.StartToEnd
                if (shouldDismiss) onRemove()
                shouldDismiss
            },
        )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { SwipeBackground() },
    ) {
        RecentRow(document = document, onClick = onOpen)
    }
}

@Composable
private fun SwipeBackground() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                .padding(horizontal = 24.dp),
    )
}

@Composable
private fun RecentRow(
    document: RecentDocument,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumbnail()
            Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                Text(
                    text = document.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = rowSubtitle(document),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    HorizontalDivider(color = SurfaceDim, thickness = 1.dp)
}

@Composable
private fun Thumbnail() {
    // Real lazy/cached page thumbnails land in Milestone 2 once rendering exists; a flat
    // placeholder keeps row layout stable until then.
    Box(
        modifier =
            Modifier
                .size(width = 40.dp, height = 52.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(SurfaceDim),
    )
}

private fun rowSubtitle(document: RecentDocument): String {
    val page = "Page ${document.lastPageIndex + 1}${document.pageCount?.let { " of $it" } ?: ""}"
    val opened = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(document.lastOpenedAtEpochMs))
    return "$page · $opened"
}

@Composable
private fun OpenAction(onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        TextButton(onClick = onClick) {
            Text(
                text = "Open",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
