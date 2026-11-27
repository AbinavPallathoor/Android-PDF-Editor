package com.pdfeditor.app.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DocumentEditingTrayTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun verticalRailStacksToolsBesideActionsAtAFixedWidth() {
        var tool by mutableStateOf(EditorTool.HAND)
        var done = false
        compose.setContent {
            DocumentEditingTray(
                tool, 0xFF000000.toInt(), canUndo = true, canRedo = false,
                onTool = { tool = it }, onColor = {}, onUndo = {}, onRedo = {}, onDone = { done = true },
                vertical = true,
            )
        }
        compose.onNodeWithTag("editing-tray-surface").assertWidthIsEqualTo(SIDE_TRAY_WIDTH)
        val hand = compose.onNodeWithTag("tool-HAND").getBoundsInRoot()
        val erase = compose.onNodeWithTag("tool-ERASE").getBoundsInRoot()
        val finish = compose.onNodeWithTag("finish-editing").getBoundsInRoot()
        assertEquals(hand.left, erase.left)
        assertTrue(erase.top > hand.top)
        assertTrue(finish.left > hand.left)

        compose.onNodeWithTag("tool-DRAW").performClick()
        compose.onNodeWithTag("ink-options").assertExists()
        compose.onNodeWithTag("finish-editing").performClick()
        compose.runOnIdle {
            assertEquals(EditorTool.DRAW, tool)
            assertTrue(done)
        }
    }
}
