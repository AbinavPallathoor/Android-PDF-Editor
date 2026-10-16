package com.pdfeditor.app.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
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
    private val renames = mutableListOf<String>()

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
                        onRename = { renames.add(it) }, onShare = {}, onCompress = { null },
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
    fun headerRemainsSolidBlackOverAWhiteDocument() {
        launch()
        val pixels = compose.onNodeWithTag("document-header-background").captureToImage().toPixelMap()
        for (fraction in listOf(.15f, .5f, .85f)) {
            val pixel = pixels[(pixels.width * .98f).toInt(), (pixels.height * fraction).toInt()]
            assertTrue("Header must stay black without a white fade", pixel.red < .02f && pixel.green < .02f && pixel.blue < .02f)
        }
    }

    @Test
    fun renameEditsTheTitleInPlaceAndDoneKeepsTheOptionsOpen() {
        launch()
        compose.onNodeWithTag("document-details").performClick()
        compose.onNodeWithTag("expanded-document-name").performClick()
        compose.onNodeWithTag("inline-document-name").performTextReplacement("Review.pdf")
        compose.onNodeWithTag("inline-document-name").performImeAction()
        compose.waitForIdle()
        assertEquals(listOf("Review.pdf"), renames)
        compose.onNodeWithContentDescription("Share").assertIsDisplayed()
        compose.onNodeWithTag("inline-document-name").assertDoesNotExist()
    }

    @Test
    fun dismissingTheOptionsDiscardsAnUnconfirmedRename() {
        launch()
        compose.onNodeWithTag("document-details").performClick()
        compose.onNodeWithTag("expanded-document-name").performClick()
        compose.onNodeWithTag("inline-document-name").performTextReplacement("Unfinished.pdf")
        compose.onNodeWithContentDescription("Dismiss document options").performClick()
        compose.waitForIdle()
        assertTrue(renames.isEmpty())
        compose.onNodeWithText("Share").assertDoesNotExist()
        compose.onNodeWithText("First.pdf").assertIsDisplayed()
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
