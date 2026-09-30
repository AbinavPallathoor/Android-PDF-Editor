package com.pdfeditor.app.ui.writing

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.hypot

/**
 * Writing-mode's single gesture surface: freehand drawing (when the draw tool is active),
 * tap-or-drag-an-existing-annotation otherwise, two/three-finger tap for undo/redo, and a
 * two-finger vertical swipe to toggle back to reading mode - all in ONE state machine, per the
 * same "one gesture loop per surface" rule as `readingGestures`. Mode-swipe must be classified
 * here rather than in a second stacked `pointerInput`/`NestedScrollConnection`, or it would race
 * the two-finger-tap-for-undo detection exactly the way stacked consumers raced each other in
 * reading mode before that was fixed. Safe as a plain `pointerInput` (no `NestedScrollConnection`
 * needed) because writing mode's page canvas has no scrollable content to race against - it's a
 * single static page.
 */
fun Modifier.writingGestures(
    isDrawMode: () -> Boolean,
    hitTestDraggableId: (Offset) -> String?,
    onDrawStroke: (List<Offset>) -> Unit,
    onDragAnnotation: (id: String, currentPosition: Offset) -> Unit,
    onDragEnd: (id: String) -> Unit,
    onTap: (Offset) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onModeSwipe: () -> Unit,
    modeSwipeThresholdPx: Float,
): Modifier =
    this.pointerInput(modeSwipeThresholdPx) {
        awaitEachGesture {
            val firstDown = awaitFirstDown(requireUnconsumed = false)
            var maxPointerCount = 1
            var totalDistance = 0f
            var lastPosition = firstDown.position
            var twoFingerStartAvgY: Float? = null
            var twoFingerVerticalDistance = 0f
            val drawPoints = if (isDrawMode()) mutableListOf(firstDown.position) else null
            val draggingId = if (drawPoints == null) hitTestDraggableId(firstDown.position) else null
            var stillPressed = true

            while (stillPressed) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                val pressedCount = pressed.size
                maxPointerCount = maxOf(maxPointerCount, pressedCount)

                if (pressedCount == 1) {
                    val change = pressed.first()
                    if (change.positionChanged()) {
                        val newPosition = change.position
                        totalDistance += hypot(newPosition.x - lastPosition.x, newPosition.y - lastPosition.y)
                        lastPosition = newPosition
                        when {
                            drawPoints != null -> {
                                drawPoints.add(newPosition)
                                change.consume()
                            }
                            draggingId != null -> {
                                onDragAnnotation(draggingId, newPosition)
                                change.consume()
                            }
                        }
                    }
                } else if (pressedCount == 2) {
                    // Two fingers down: could become a mode-toggle swipe or resolve as an undo
                    // tap at release - track the average-Y movement to disambiguate at the end.
                    val avgY = pressed.sumOf { it.position.y.toDouble() }.toFloat() / pressedCount
                    val startY = twoFingerStartAvgY
                    if (startY == null) {
                        twoFingerStartAvgY = avgY
                    } else {
                        twoFingerVerticalDistance = avgY - startY
                    }
                    pressed.forEach { it.consume() }
                }
                stillPressed = event.changes.any { it.pressed }
            }

            when {
                maxPointerCount >= 3 -> onRedo()
                maxPointerCount == 2 && kotlin.math.abs(twoFingerVerticalDistance) >= modeSwipeThresholdPx -> onModeSwipe()
                maxPointerCount == 2 -> onUndo()
                // A stationary tap with the draw tool active has fewer than 2 points - fall
                // through to onTap (below) rather than silently dropping it, so tapping the
                // canvas still wakes a hidden tool tray while drawing is the active tool.
                drawPoints != null && drawPoints.size >= 2 -> onDrawStroke(drawPoints)
                draggingId != null -> onDragEnd(draggingId)
                totalDistance < viewConfiguration.touchSlop -> onTap(firstDown.position)
            }
        }
    }
