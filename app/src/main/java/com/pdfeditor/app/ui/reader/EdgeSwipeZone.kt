package com.pdfeditor.app.ui.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs

/**
 * A minimal two-finger-vertical-swipe detector with no pinch/tap disambiguation, for surfaces with
 * no zoomable/scrollable content of their own (e.g. the writing-mode placeholder) where the full
 * `readingGestures` state machine would be overkill. Edge-swipe-to-close/reveal-sheet are handled
 * inside `readingGestures` itself, not as a separate overlay here - see that file's doc comment for
 * why a standalone overlay `Box` racing the content for the same drag doesn't work reliably.
 */
fun Modifier.twoFingerVerticalSwipeToTrigger(
    thresholdPx: Float,
    onTriggered: () -> Unit,
): Modifier =
    this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var cumulativePanY = 0f
            var sawTwoPointers = false
            do {
                val event = awaitPointerEvent()
                val pressedCount = event.changes.count { it.pressed }
                if (pressedCount >= 2) {
                    sawTwoPointers = true
                    cumulativePanY += event.calculatePan().y
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
            if (sawTwoPointers && abs(cumulativePanY) > thresholdPx) onTriggered()
        }
    }
