package com.pdfeditor.app.ui.writing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Verifies the two/three-finger gesture classification in [writingGestures] using Compose's
 * synthetic multi-pointer touch injection - real ADB touch simulation on an emulator can't drive
 * more than one pointer at a time (see CLAUDE.md), so this is the only reliable way to exercise
 * the undo/redo/mode-swipe branches of that state machine.
 */
class WritingGesturesTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class Recorder {
        var undoCount = 0
        var redoCount = 0
        var modeSwipeCount = 0
        var tapCount = 0
    }

    private fun setContent(recorder: Recorder) {
        composeRule.setContent {
            Box(
                modifier =
                    Modifier
                        .size(300.dp)
                        .writingGestures(
                            isDrawMode = { false },
                            hitTestDraggableId = { null },
                            onDrawStroke = {},
                            onDragAnnotation = { _, _ -> },
                            onDragEnd = {},
                            onTap = { recorder.tapCount++ },
                            onUndo = { recorder.undoCount++ },
                            onRedo = { recorder.redoCount++ },
                            onModeSwipe = { recorder.modeSwipeCount++ },
                            modeSwipeThresholdPx = 120f,
                        ),
            )
        }
    }

    @Test
    fun twoFingerStationaryTap_triggersUndo() {
        val recorder = Recorder()
        setContent(recorder)

        composeRule.onRoot().performTouchInput {
            twoFingerTap(this, Offset(50f, 50f), Offset(150f, 50f))
        }

        assertEquals(1, recorder.undoCount)
        assertEquals(0, recorder.redoCount)
        assertEquals(0, recorder.modeSwipeCount)
    }

    @Test
    fun threeFingerStationaryTap_triggersRedo() {
        val recorder = Recorder()
        setContent(recorder)

        composeRule.onRoot().performTouchInput {
            val p0 = Offset(50f, 50f)
            val p1 = Offset(150f, 50f)
            val p2 = Offset(100f, 150f)
            down(0, p0)
            down(1, p1)
            down(2, p2)
            up(2)
            up(1)
            up(0)
        }

        assertEquals(0, recorder.undoCount)
        assertEquals(1, recorder.redoCount)
        assertEquals(0, recorder.modeSwipeCount)
    }

    @Test
    fun twoFingerVerticalSwipe_triggersModeSwipe_notUndo() {
        val recorder = Recorder()
        setContent(recorder)

        composeRule.onRoot().performTouchInput {
            val leftX = 50f
            val rightX = 150f
            var y = 250f
            down(0, Offset(leftX, y))
            down(1, Offset(rightX, y))
            repeat(6) {
                y -= 40f
                moveTo(0, Offset(leftX, y))
                moveTo(1, Offset(rightX, y))
            }
            up(0)
            up(1)
        }

        assertEquals(0, recorder.undoCount)
        assertEquals(1, recorder.modeSwipeCount)
    }

    private fun twoFingerTap(
        scope: TouchInjectionScope,
        p0: Offset,
        p1: Offset,
    ) = with(scope) {
        down(0, p0)
        down(1, p1)
        up(1)
        up(0)
    }
}
