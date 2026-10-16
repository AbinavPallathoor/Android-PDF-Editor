package com.pdfeditor.app.ui.reader

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.pdfeditor.app.data.pdf.loadFirstPageThumbnail
import com.pdfeditor.app.domain.RecentDocument
import com.pdfeditor.app.ui.theme.OnSurfaceSecondary
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Only document identity moves; the black header and its action buttons stay anchored. */
@Composable
internal fun HeaderDocumentSwitcher(
    displayName: String,
    subtitle: String,
    thumbnail: Bitmap?,
    thumbnailAlpha: Float,
    contentResolver: ContentResolver,
    adjacentDocument: (Boolean) -> RecentDocument?,
    onTap: () -> Unit,
    onSwitchDocument: suspend (RecentDocument) -> Boolean,
    onThumbnailBoundsChanged: (Rect) -> Unit,
    onSwipeActive: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val threshold = with(density) { 56.dp.toPx() }
    val scope = rememberCoroutineScope()
    val tapNow by rememberUpdatedState(onTap)
    val switchNow by rememberUpdatedState(onSwitchDocument)
    val activeNow by rememberUpdatedState(onSwipeActive)
    val adjacentNow by rememberUpdatedState(adjacentDocument)
    val previous = adjacentDocument(false)
    val next = adjacentDocument(true)
    val previews = remember { mutableStateMapOf<String, Bitmap?>() }
    val thumbWidth = with(density) { 32.dp.roundToPx() }
    val thumbHeight = with(density) { 42.dp.roundToPx() }
    // Fetch before interaction, never between the outgoing and incoming animation frames.
    LaunchedEffect(previous?.uri, next?.uri, thumbWidth, thumbHeight) {
        listOfNotNull(previous, next).distinctBy { it.uri }.forEach { document ->
            launch {
                previews[document.uri] = loadFirstPageThumbnail(contentResolver, Uri.parse(document.uri), thumbWidth, thumbHeight)
            }
        }
    }
    var shownName by remember { mutableStateOf(displayName) }
    var shownSubtitle by remember { mutableStateOf(subtitle) }
    var shownThumbnail by remember { mutableStateOf(thumbnail) }
    var settling by remember { mutableStateOf(false) }
    var incoming by remember { mutableStateOf<RecentDocument?>(null) }
    var forward by remember { mutableStateOf(true) }
    var width by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(displayName, subtitle, thumbnail, settling) {
        if (!settling) {
            shownName = displayName
            shownSubtitle = subtitle
            shownThumbnail = thumbnail
        }
    }

    fun settle(
        commit: Boolean,
        velocity: Float,
    ) {
        val target = incoming
        val direction = forward
        settling = true
        scope.launch {
            val destination =
                if (commit) {
                    if (direction) -width else width
                } else {
                    0f
                }
            Animatable(offset).animateTo(
                destination,
                spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
                initialVelocity = velocity.coerceIn(-width * 5, width * 5),
            ) { offset = value }
            if (commit && target != null) {
                shownName = target.displayName
                shownSubtitle = target.positionLabel()
                shownThumbnail = previews[target.uri]
                offset = 0f
                incoming = null
                // Stay locked until navigation replaces this reader. Repeated release events
                // cannot switch again while its draft is flushing or the next PDF is opening.
                if (!switchNow(target)) {
                    settling = false
                    activeNow(false)
                }
            } else {
                offset = 0f
                incoming = null
                settling = false
                activeNow(false)
            }
        }
    }

    fun switchAccessible(direction: Boolean): Boolean {
        if (settling) return false
        val target = adjacentNow(direction) ?: return false
        forward = direction
        incoming = target
        activeNow(true)
        settle(true, 0f)
        return true
    }
    Box(
        modifier.height(56.dp).clipToBounds().testTag("document-details")
            .semantics {
                role = Role.Button
                contentDescription = "Document options"
                onClick {
                    if (!settling) tapNow()
                    true
                }
                customActions =
                    listOfNotNull(
                        previous?.let { CustomAccessibilityAction("Previous PDF") { switchAccessible(false) } },
                        next?.let { CustomAccessibilityAction("Next PDF") { switchAccessible(true) } },
                    )
            }.onKeyEvent {
                if (it.type == KeyEventType.KeyUp && (it.key == Key.Enter || it.key == Key.Spacebar)) {
                    if (!settling) tapNow()
                    true
                } else {
                    false
                }
            }.focusable().onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (settling) {
                        down.consume()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        return@awaitEachGesture
                    }
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var dx = 0f
                    var dy = 0f
                    var dragging = false
                    var cancelled = false
                    var released = false
                    try {
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } > 1) cancelled = true
                            event.changes.firstOrNull { it.id == down.id }?.let { change ->
                                tracker.addPosition(change.uptimeMillis, change.position)
                                dx += change.position.x - change.previousPosition.x
                                dy += change.position.y - change.previousPosition.y
                                if (!dragging && abs(dy) > viewConfiguration.touchSlop && abs(dy) > abs(dx)) cancelled = true
                                if (!cancelled && abs(dx) > viewConfiguration.touchSlop && abs(dx) > abs(dy)) {
                                    if (!dragging) activeNow(true)
                                    dragging = true
                                    forward = dx < 0f
                                    incoming = adjacentNow(forward)
                                    offset = (if (incoming == null) dx * .15f else dx).coerceIn(-width, width)
                                }
                                if (dragging) change.consume()
                            }
                        } while (event.changes.any { it.pressed })
                        released = true
                        if (dragging) {
                            settle(!cancelled && abs(dx) >= threshold && incoming != null, tracker.calculateVelocity().x)
                        } else if (!cancelled) {
                            tapNow()
                        }
                    } finally {
                        if (dragging && !released) settle(false, 0f)
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        HeaderDocumentIdentity(
            shownName,
            shownSubtitle,
            shownThumbnail,
            thumbnailAlpha,
            Modifier.fillMaxWidth().graphicsLayer { translationX = offset },
            onThumbnailBoundsChanged,
        )
        incoming?.let { document ->
            HeaderDocumentIdentity(
                document.displayName,
                document.positionLabel(),
                previews[document.uri],
                1f,
                Modifier.fillMaxWidth().graphicsLayer { translationX = offset + if (forward) width else -width },
            )
        }
    }
}

private fun RecentDocument.positionLabel(): String = pageCount?.let { "${lastPageIndex + 1} / $it" } ?: " "

@Composable
private fun HeaderDocumentIdentity(
    name: String,
    subtitle: String,
    thumbnail: Bitmap?,
    thumbnailAlpha: Float,
    modifier: Modifier,
    onThumbnailBoundsChanged: (Rect) -> Unit = {},
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(32.dp, 42.dp).clip(RoundedCornerShape(4.dp))
                .graphicsLayer { alpha = thumbnailAlpha }.background(Color.White)
                .onGloballyPositioned { onThumbnailBoundsChanged(Rect(it.positionInRoot(), it.size.toSize())) },
        ) {
            thumbnail?.let {
                Image(it.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(name, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                Modifier.testTag("page-count"),
                color = OnSurfaceSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
