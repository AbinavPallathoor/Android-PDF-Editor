package com.pdfeditor.app.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DocumentSwitchOverlayTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun holdsTheOldFrameAndBlocksEditsUntilTheNewDocumentHasRendered() {
        val image = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }.asImageBitmap()
        var frame by mutableStateOf<DocumentSwitchFrame?>(DocumentSwitchFrame("next", image))
        var finished = 0
        var edits = 0
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color.Blue).clickable { edits++ }) {
                frame?.let { held ->
                    DocumentSwitchOverlay(held) {
                        finished++
                        frame = null
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(4000)
        compose.onNodeWithTag("document-switch-frame", useUnmergedTree = true).performTouchInput { click(center) }
        val waiting = compose.onRoot().captureToImage().toPixelMap()
        assertTrue("The last complete frame must remain visible while loading", waiting[waiting.width / 2, waiting.height / 2].red > .95f)
        assertEquals(0, finished)
        assertEquals(0, edits)
        compose.runOnIdle { frame = frame!!.copy(ready = true) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals(1, finished)
        val ready = compose.onRoot().captureToImage().toPixelMap()
        assertTrue("The rendered document must be visible after the handoff", ready[ready.width / 2, ready.height / 2].blue > .95f)
    }
}
