package com.pdfeditor.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

class CurvedPageScrollbarTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun draggingTheHandleClampsAtBothEndsAndUpdatesThePage() {
        var progress by mutableFloatStateOf(.5f)
        val dragging = mutableListOf<Boolean>()
        compose.setContent {
            Box(Modifier.width(240.dp).height(400.dp).background(Color.White)) {
                CurvedPageScrollbar(
                    page = (progress * 9).roundToInt(),
                    pageCount = 10,
                    progress = progress,
                    visible = true,
                    onScrub = { progress = it },
                    onDraggingChanged = { dragging.add(it) },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
        compose.onNodeWithContentDescription("Scroll through pages").performTouchInput {
            down(center)
            moveBy(Offset(0f, 2000f), 200)
            up()
        }
        compose.runOnIdle { assertEquals(1f, progress, .001f) }
        compose.onNodeWithText("10 / 10").assertIsDisplayed()
        compose.onNodeWithContentDescription("Scroll through pages").performTouchInput {
            down(center)
            moveBy(Offset(0f, -2000f), 200)
            up()
        }
        compose.runOnIdle {
            assertEquals(0f, progress, .001f)
            assertEquals(listOf(true, false, true, false), dragging)
        }
        compose.onNodeWithText("1 / 10").assertIsDisplayed()
    }

    @Test
    fun aCancelledDragEndsDraggingAndLeavesTheHandleUsable() {
        var progress by mutableFloatStateOf(.5f)
        val dragging = mutableListOf<Boolean>()
        compose.setContent {
            Box(Modifier.width(240.dp).height(400.dp)) {
                CurvedPageScrollbar(4, 10, progress, true, { progress = it }, { dragging.add(it) })
            }
        }
        compose.onNodeWithContentDescription("Scroll through pages").performTouchInput {
            down(center)
            moveBy(Offset(0f, 40f), 100)
            cancel()
        }
        compose.runOnIdle {
            assertTrue(progress > .5f)
            assertEquals(listOf(true, false), dragging)
        }
        compose.onNodeWithContentDescription("Scroll through pages").performTouchInput {
            down(center)
            moveBy(Offset(0f, -2000f), 100)
            up()
        }
        compose.runOnIdle { assertEquals(0f, progress, .001f) }
    }

    @Test
    fun accessibilityScrubsThroughTheSameBoundedProgressContract() {
        var progress by mutableFloatStateOf(0f)
        compose.setContent {
            Box(Modifier.width(240.dp).height(400.dp)) {
                CurvedPageScrollbar((progress * 9).roundToInt(), 10, progress, true, { progress = it })
            }
        }
        compose.onNodeWithContentDescription("Scroll through pages").performSemanticsAction(SemanticsActions.SetProgress) {
            it(.75f)
        }
        compose.runOnIdle { assertEquals(.75f, progress, .001f) }
        compose.onNodeWithText("8 / 10").assertIsDisplayed()
        compose.onNodeWithContentDescription("Scroll through pages").performSemanticsAction(SemanticsActions.SetProgress) {
            it(2f)
        }
        compose.runOnIdle { assertEquals(1f, progress, .001f) }
    }

    @Test
    fun theInvisibleTrackDoesNotBlockTheDocumentAndIdleHidesTheHandle() {
        var visible by mutableStateOf(true)
        var taps = 0
        compose.setContent {
            Box(Modifier.width(240.dp).height(400.dp).clickable { taps++ }) {
                CurvedPageScrollbar(4, 10, .5f, visible, {}, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
        compose.onRoot().performTouchInput { click(Offset(width - 12f, 20f)) }
        compose.runOnIdle { assertEquals(1, taps) }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { visible = false }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription("Scroll through pages").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1500)
        compose.onNodeWithContentDescription("Scroll through pages").assertDoesNotExist()
    }
}
