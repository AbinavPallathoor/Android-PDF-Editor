package com.pdfeditor.app.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * A right-edge scrubber. [page] is zero-based; [progress] and [onScrub] use 0..1.
 * The caller supplies the available track height, outside system bars and fixed controls.
 * Only the moving handle takes touches, leaving the rest of the document interactive.
 */
@Composable
internal fun CurvedPageScrollbar(
    page: Int,
    pageCount: Int,
    progress: Float,
    visible: Boolean,
    onScrub: (Float) -> Unit,
    onDraggingChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (pageCount <= 1) return

    val boundedProgress = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val currentProgress by rememberUpdatedState(boundedProgress)
    val scrub by rememberUpdatedState(onScrub)
    val draggingChanged by rememberUpdatedState(onDraggingChanged)
    var dragging by remember { mutableStateOf(false) }
    var retainedVisible by remember { mutableStateOf(visible) }
    LaunchedEffect(visible, dragging) {
        if (visible || dragging) {
            retainedVisible = true
        } else {
            delay(1000)
            retainedVisible = false
        }
    }

    AnimatedVisibility(
        visible = retainedVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.width(88.dp).fillMaxHeight(),
    ) {
        BoxWithConstraints(Modifier.width(88.dp).fillMaxHeight()) {
            val density = LocalDensity.current
            val labelSpace = 32.dp
            val handleHeight = 56.dp
            val trackTravel = (maxHeight - labelSpace - handleHeight - 8.dp).coerceAtLeast(0.dp)
            val trackTravelPx = with(density) { trackTravel.toPx() }
            val handleTopPx = with(density) { labelSpace.toPx() } + boundedProgress * trackTravelPx
            val displayPage = page.coerceIn(0, pageCount - 1) + 1

            Text(
                text = "$displayPage / $pageCount",
                color = Color.White,
                fontSize = 11.sp,
                maxLines = 1,
                modifier =
                    Modifier.align(Alignment.TopEnd)
                        .offset { IntOffset(-8.dp.roundToPx(), handleTopPx.roundToInt() - 28.dp.roundToPx()) }
                        .background(Color.Black, RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Box(
                Modifier.align(Alignment.TopEnd)
                    .offset { IntOffset(0, handleTopPx.roundToInt()) }
                    .width(48.dp).height(handleHeight)
                    .semantics {
                        contentDescription = "Scroll through pages"
                        stateDescription = "Page $displayPage of $pageCount"
                        progressBarRangeInfo = ProgressBarRangeInfo(boundedProgress, 0f..1f)
                        setProgress { requested ->
                            if (requested.isFinite()) {
                                scrub(requested.coerceIn(0f, 1f))
                                true
                            } else {
                                false
                            }
                        }
                    }
                    .pointerInput(trackTravelPx) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            var gestureProgress = currentProgress
                            dragging = true
                            draggingChanged(true)
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    // Adding another finger cancels scrubbing rather than moving the document unexpectedly.
                                    if (event.changes.count { it.pressed } > 1) break
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    val dy = change.positionChange().y
                                    if (dy != 0f && trackTravelPx > 0f) {
                                        gestureProgress = (gestureProgress + dy / trackTravelPx).coerceIn(0f, 1f)
                                        scrub(gestureProgress)
                                    }
                                    change.consume()
                                    if (!change.pressed) break
                                }
                            } finally {
                                dragging = false
                                draggingChanged(false)
                            }
                        }
                    },
            ) {
                Canvas(Modifier.align(Alignment.CenterEnd).width(24.dp).height(handleHeight)) {
                    val curve =
                        Path().apply {
                            moveTo(size.width, 0f)
                            cubicTo(size.width * .28f, 0f, 0f, size.height * .22f, 0f, size.height * .5f)
                            cubicTo(0f, size.height * .78f, size.width * .28f, size.height, size.width, size.height)
                            close()
                        }
                    drawPath(curve, Color.Black)
                    drawLine(
                        Color.White,
                        Offset(size.width * .6f, size.height * .38f),
                        Offset(size.width * .6f, size.height * .62f),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}
