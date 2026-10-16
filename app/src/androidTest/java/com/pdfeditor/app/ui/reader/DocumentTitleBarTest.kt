package com.pdfeditor.app.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfeditor.app.domain.RecentDocument
import com.pdfeditor.app.ui.theme.PdfEditorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DocumentTitleBarTest {
    @get:Rule val compose = createComposeRule()
    private val switches = mutableListOf<String>()

    private fun launch(
        hasAdjacent: Boolean = true,
        canSwitch: Boolean = true,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val thumb = Bitmap.createBitmap(32, 42, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        val next = RecentDocument(2, "content://missing-preview/two", "Second.pdf", null, 9, 1, 1f, 0f, 0, 0, null, false)
        compose.setContent {
            PdfEditorTheme {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    DocumentTitleBar(
                        "First.pdf", "1 / 3", thumb, true, false, context.contentResolver,
                        adjacentDocument = { if (hasAdjacent) next else null },
                        onRename = {}, onShare = {}, onCompress = { null },
                        onSwitchDocument = {
                            switches.add(it.uri)
                            canSwitch
                        }, onClose = {}, onToggleWriteMode = {}, onToggleInvert = {},
                    )
                }
            }
        }
    }

    @Test
    fun headerTracksTheFingerBeforeReleaseAndAShortDragReturnsToItsPlace() {
        launch()
        val before = compose.onNodeWithText("First.pdf").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("document-details").performTouchInput {
            down(center)
            moveTo(center - Offset(100f, 0f), 160)
        }
        val during = compose.onNodeWithText("First.pdf").getUnclippedBoundsInRoot()
        assertTrue("The title must move with the finger, before it is lifted", during.left < before.left - 12.dp)
        compose.onNodeWithTag("document-details").performTouchInput { up() }
        compose.waitForIdle()
        val after = compose.onNodeWithText("First.pdf").getUnclippedBoundsInRoot()
        assertEquals(before.left.value, after.left.value, 1f)
        assertTrue(switches.isEmpty())
        compose.onNodeWithText("Share").assertDoesNotExist()
    }

    @Test
    fun aCommittedSwipeSwitchesOnlyOnceAndShowsTheIncomingTitle() {
        launch()
        compose.onNodeWithTag("document-details").performTouchInput {
            down(Offset(width * .9f, height * .5f))
            moveTo(Offset(width * .1f, height * .5f), 350)
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf("content://missing-preview/two"), switches)
        compose.onNodeWithText("Second.pdf").assertIsDisplayed()
        compose.onNodeWithText("2 / 9").assertIsDisplayed()
    }

    @Test
    fun aCancelledPointerStreamReturnsTheHeaderAndLeavesItUsable() {
        launch()
        val before = compose.onNodeWithText("First.pdf").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("document-details").performTouchInput {
            down(center)
            moveTo(center - Offset(100f, 0f), 160)
            cancel()
        }
        compose.waitForIdle()
        val after = compose.onNodeWithText("First.pdf").getUnclippedBoundsInRoot()
        assertEquals(before.left.value, after.left.value, 1f)
        assertTrue(switches.isEmpty())
    }

    @Test
    fun aFailedOpenRestoresTheCurrentTitleAndAllowsAnotherGesture() {
        launch(canSwitch = false)
        repeat(2) {
            compose.onNodeWithTag("document-details").performTouchInput {
                down(Offset(width * .9f, height * .5f))
                moveTo(Offset(width * .1f, height * .5f), 350)
                up()
            }
            compose.waitForIdle()
            compose.onNodeWithText("First.pdf").assertIsDisplayed()
            compose.onNodeWithText("1 / 3").assertIsDisplayed()
        }
        assertEquals(2, switches.size)
    }

    @Test
    fun swipingWithoutAnotherDocumentDoesNotOpenTheOptionsOrNavigate() {
        launch(hasAdjacent = false)
        compose.onNodeWithTag("document-details").performTouchInput {
            down(Offset(width * .9f, height * .5f))
            moveTo(Offset(width * .1f, height * .5f), 350)
            up()
        }
        compose.waitForIdle()
        assertTrue(switches.isEmpty())
        compose.onNodeWithText("First.pdf").assertIsDisplayed()
        compose.onNodeWithText("Share").assertDoesNotExist()
    }
}
