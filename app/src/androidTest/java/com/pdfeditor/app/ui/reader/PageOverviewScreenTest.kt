package com.pdfeditor.app.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Verifies long-press-drag reordering in [PageOverviewScreen] using Compose's synthetic touch
 * injection with explicit timing control (`down` + `advanceEventTime` past the long-press
 * threshold + `moveTo` + `up`) - a plain `adb shell input swipe` can't drive this gesture at all,
 * since it starts moving the pointer immediately rather than holding still first, which cancels
 * `detectDragGesturesAfterLongPress` before it ever recognizes the press.
 */
class PageOverviewScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun longPressDragFirstPage_movesItLaterInTheOrder() {
        var lastOrder: List<Int>? = null

        composeRule.setContent {
            PageOverviewScreen(
                pageCount = 6,
                documentVersion = 0,
                pageAspectRatios = List(6) { 1.4f },
                renderThumbnail = { _, _, _ -> null },
                onPageSelected = {},
                onReorderPages = { lastOrder = it },
                onDeletePage = {},
                onAppendRequested = {},
                onDismiss = {},
            )
        }

        // Long-press page 1 (grid position 0, top-left) and drag it two columns to the right -
        // roughly into page 3's slot - then release.
        composeRule.onRoot().performTouchInput {
            val cellWidth = width / 3f
            val start = Offset(cellWidth / 2f, cellWidth / 2f)
            down(start)
            advanceEventTime(600)
            moveTo(start + Offset(cellWidth, 0f))
            advanceEventTime(50)
            moveTo(start + Offset(cellWidth * 2, 0f))
            advanceEventTime(50)
            up()
        }

        composeRule.waitForIdle()
        val order = checkNotNull(lastOrder) { "onReorderPages was never called - drag wasn't recognized" }

        assertEquals("reorder must be a permutation of all pages", (0 until 6).toSet(), order.toSet())
        assertNotEquals("page 0 should have moved from its original first slot", 0, order.indexOf(0))
        assertTrue("page 0 should have moved later, not earlier", order.indexOf(0) > 0)
    }
}
