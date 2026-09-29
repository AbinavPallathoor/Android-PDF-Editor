package com.pdfeditor.app.ui.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.hypot

/**
 * Reading-mode zoom gesture: two-finger pinch (from any zoom level), one-finger pan once already
 * zoomed in, and double-tap to toggle fit-width <-> 2x. Deliberately does NOT touch single-finger
 * drags while at fit-width - those must fall through untouched to the LazyColumn's own scroll
 * beneath this modifier, which is why pinch detection here only starts consuming once a second
 * pointer actually appears, and reads live values via [zoomState]/[viewportSize] lambdas rather
 * than captured snapshots.
 *
 * This is intentionally ONE gesture loop, not `detectTapGestures` layered next to a separate
 * pinch detector: two coroutines independently calling `awaitPointerEvent()` on the same
 * `pointerInput` block can each miss events during genuinely fast sequences (confirmed live -
 * double-tap's second tap was sporadically dropped with that split design). A single state
 * machine reading the stream once is both simpler and doesn't have that race. See CLAUDE.md's
 * gesture-arbitration note; the full multi-gesture engine (edge swipes, multi-finger taps) is a
 * separate, later milestone - this is reading-mode only.
 */
fun Modifier.pinchZoomAndDoubleTap(
    zoomState: () -> ZoomState,
    viewportSize: () -> Size,
    onZoomChange: (ZoomState) -> Unit,
    onZoomSettled: () -> Unit,
): Modifier =
    this.pointerInput(Unit) {
        var lastTapUpTimeMillis = 0L
        var lastTapPosition: Offset? = null

        awaitEachGesture {
            val firstDown = awaitFirstDown(requireUnconsumed = false)
            var handledAsZoomOrPan = false
            var totalDrag = 0f
            var event: PointerEvent

            do {
                event = awaitPointerEvent()
                val pressedCount = event.changes.count { it.pressed }
                val currentScale = zoomState().scale
                when {
                    pressedCount >= 2 -> {
                        handledAsZoomOrPan = true
                        val zoomFactor = event.calculateZoom()
                        val pan = event.calculatePan()
                        val centroid = event.calculateCentroid()
                        if (zoomFactor != 1f || pan != Offset.Zero) {
                            onZoomChange(ZoomMath.applyPinch(zoomState(), centroid, pan, zoomFactor, viewportSize()))
                        }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                    pressedCount == 1 && currentScale > ZoomState.MIN_SCALE + 0.01f -> {
                        handledAsZoomOrPan = true
                        val change = event.changes.first()
                        if (change.positionChanged()) {
                            onZoomChange(ZoomMath.applyPan(zoomState(), change.positionChange(), viewportSize()))
                            change.consume()
                        }
                    }
                    pressedCount == 1 -> {
                        // Fit-width, single pointer: never consume - this must fall through to the
                        // LazyColumn's own scroll. Just track drag distance to tell a tap from a scroll.
                        val change = event.changes.first()
                        if (change.positionChanged()) {
                            val d = change.positionChange()
                            totalDrag += hypot(d.x, d.y)
                        }
                    }
                }
            } while (event.changes.any { it.pressed })

            if (handledAsZoomOrPan) {
                onZoomSettled()
                return@awaitEachGesture
            }
            if (totalDrag >= viewConfiguration.touchSlop) return@awaitEachGesture

            // A genuine tap (not a scroll). Check whether it completes a double-tap with the
            // previous one.
            val now = System.currentTimeMillis()
            val previousTap = lastTapPosition
            val isDoubleTap =
                previousTap != null &&
                    (now - lastTapUpTimeMillis) < viewConfiguration.doubleTapTimeoutMillis &&
                    hypot(firstDown.position.x - previousTap.x, firstDown.position.y - previousTap.y) < DOUBLE_TAP_SLOP_PX
            if (isDoubleTap) {
                onZoomChange(ZoomMath.toggleDoubleTap(zoomState(), firstDown.position, viewportSize()))
                onZoomSettled()
                lastTapPosition = null
            } else {
                lastTapUpTimeMillis = now
                lastTapPosition = firstDown.position
            }
        }
    }

private const val DOUBLE_TAP_SLOP_PX = 100f
