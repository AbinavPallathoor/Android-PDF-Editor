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
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.hypot

private enum class TwoFingerClassification { UNCLASSIFIED, PINCH, MODE_SWIPE, OVERVIEW }

/**
 * Reading-mode gesture surface for the main content area: pinch-zoom, double-tap zoom, pan while
 * zoomed, long-press (page slider), two-finger vertical swipe (mode toggle), and pinch-out at
 * minimum zoom (page overview) - all as ONE gesture state machine.
 *
 * This is intentionally not several stacked detectors (e.g. `detectTapGestures` layered next to a
 * separate pinch loop): two coroutines independently calling `awaitPointerEvent()` on the same
 * `pointerInput` block can each miss events during fast sequences (confirmed live in Milestone 2 -
 * double-tap's second tap was sporadically dropped with that split design).
 *
 * Edge-swipe-down-to-close and edge-swipe-up-to-reveal-sheet are deliberately NOT here, even
 * though they're conceptually "gestures on this same surface": a plain `pointerInput`/
 * `awaitPointerEvent()` loop cannot reliably measure a drag that a `Modifier.scrollable` (the
 * LazyColumn beneath) also recognizes as a scroll. Once the scrollable's own internal gesture
 * detector confirms a drag past touch slop, Compose cancels *other* pointer-input consumers
 * watching that same pointer outright (not just a reduced/consumed delta - the change disappears
 * from later events entirely). Confirmed live: an edge-swipe measured only ~55px of a 300px real
 * swipe before its loop saw the pointer go "unpressed" mid-gesture. `NestedScrollConnection` is the
 * mechanism Compose actually provides for this - it observes every scroll attempt cooperatively,
 * pre- or post-consumption, without racing or being cancelled by the scrollable. See
 * `ReadingPager`'s `nestedScroll` usage in `ReaderScreen.kt`.
 */
fun Modifier.readingGestures(
    zoomState: () -> ZoomState,
    viewportSize: () -> Size,
    onZoomChange: (ZoomState) -> Unit,
    onZoomSettled: () -> Unit,
    onModeToggle: () -> Unit,
    onRequestOverview: () -> Unit,
    onLongPress: (Offset) -> Unit,
): Modifier =
    this.pointerInput(Unit) {
        var lastTapUpTimeMillis = 0L
        var lastTapPosition: Offset? = null

        awaitEachGesture {
            val firstDown = awaitFirstDown(requireUnconsumed = false)
            val downTimeMillis = System.currentTimeMillis()
            var handledAsZoomOrPan = false
            // Tracked as absolute position, not accumulated positionChange() deltas: once a
            // descendant (the LazyColumn's own scrollable) consumes part of a drag for its own
            // scrolling, positionChange() on this ancestor node reports the delta *already
            // reduced by that consumption* - confirmed live, an edge-swipe's measured distance
            // was a fraction of the real finger travel once the list started scrolling under it.
            // Raw position isn't affected by who consumed what, so diffing start-vs-latest avoids
            // the problem entirely.
            var lastPosition = firstDown.position
            var longPressFired = false
            var stillPressed = true

            var twoFingerClassification = TwoFingerClassification.UNCLASSIFIED
            var cumulativeZoomRatio = 1f
            var cumulativePanY = 0f

            fun dragDistance() = hypot(lastPosition.x - firstDown.position.x, lastPosition.y - firstDown.position.y)

            while (stillPressed) {
                val elapsed = System.currentTimeMillis() - downTimeMillis
                val remaining = viewConfiguration.longPressTimeoutMillis - elapsed
                val canStillLongPress =
                    !longPressFired && !handledAsZoomOrPan && dragDistance() < viewConfiguration.touchSlop && remaining > 0
                val event: PointerEvent
                if (canStillLongPress) {
                    val maybeEvent = withTimeoutOrNull(remaining) { awaitPointerEvent() }
                    if (maybeEvent == null) {
                        longPressFired = true
                        onLongPress(firstDown.position)
                        continue
                    }
                    event = maybeEvent
                } else {
                    event = awaitPointerEvent()
                }

                val pressedCount = event.changes.count { it.pressed }
                val currentScale = zoomState().scale
                when {
                    pressedCount >= 2 -> {
                        handledAsZoomOrPan = true
                        val zoomFactor = event.calculateZoom()
                        val pan = event.calculatePan()
                        val centroid = event.calculateCentroid()
                        cumulativeZoomRatio *= zoomFactor
                        cumulativePanY += pan.y

                        if (twoFingerClassification == TwoFingerClassification.UNCLASSIFIED) {
                            twoFingerClassification =
                                when {
                                    currentScale <= ZoomState.MIN_SCALE + 0.01f && cumulativeZoomRatio > OVERVIEW_ZOOM_RATIO ->
                                        TwoFingerClassification.OVERVIEW
                                    abs(cumulativeZoomRatio - 1f) > PINCH_RATIO_DEVIATION -> TwoFingerClassification.PINCH
                                    abs(cumulativePanY) > MODE_SWIPE_THRESHOLD_PX -> TwoFingerClassification.MODE_SWIPE
                                    else -> TwoFingerClassification.UNCLASSIFIED
                                }
                        }
                        if (twoFingerClassification == TwoFingerClassification.PINCH &&
                            (zoomFactor != 1f || pan != Offset.Zero)
                        ) {
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
                        // LazyColumn's own scroll. Just track the finger's raw position to tell a
                        // tap from a scroll, and a scroll from an edge-swipe.
                        lastPosition = event.changes.first().position
                    }
                }
                stillPressed = event.changes.any { it.pressed }
            }

            when (twoFingerClassification) {
                TwoFingerClassification.MODE_SWIPE -> {
                    onModeToggle()
                    return@awaitEachGesture
                }
                TwoFingerClassification.OVERVIEW -> {
                    onRequestOverview()
                    return@awaitEachGesture
                }
                else -> Unit
            }
            if (handledAsZoomOrPan) {
                onZoomSettled()
                return@awaitEachGesture
            }
            if (longPressFired) return@awaitEachGesture
            if (dragDistance() >= viewConfiguration.touchSlop) return@awaitEachGesture

            // A genuine tap (not a scroll, not a long press). Check whether it completes a
            // double-tap with the previous one.
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
private const val PINCH_RATIO_DEVIATION = 0.15f
private const val OVERVIEW_ZOOM_RATIO = 1.6f
private const val MODE_SWIPE_THRESHOLD_PX = 140f
