package com.pdfeditor.app.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pdfeditor.app.domain.DocumentEdits
import com.pdfeditor.app.domain.DocumentPage
import com.pdfeditor.app.domain.DocumentSnapshot
import com.pdfeditor.app.domain.PdfPoint
import com.pdfeditor.app.domain.PdfRect
import com.pdfeditor.app.domain.PdfTextWord
import com.pdfeditor.app.domain.SignaturePlacement
import com.pdfeditor.app.domain.TextBoxAnnotation
import com.pdfeditor.app.ui.theme.PdfEditorTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MultipageEditorTest {
    @get:Rule val compose = createComposeRule()
    private val doc = DocumentEdits(DocumentSnapshot(List(3) { DocumentPage("p$it", it) }))
    private var ui by mutableStateOf(ready())
    private var saveCopy: Boolean? = null

    private fun ready() =
        ReaderUiState.Ready(
            3, List(3) { 792f / 612f }, List(3) { 612f }, 0, 1f, false, displayName = "Test.pdf",
            pageIds = doc.current.pages.map { it.id }, annotations = doc.current.pages.indices.associateWith { doc.annotationsAt(it) },
            dirty = doc.isDirty, canUndo = doc.canUndo, canRedo = doc.canRedo, canSaveOriginal = true,
        )

    private fun launch(height: Dp? = null) {
        val bitmap = Bitmap.createBitmap(306, 396, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        compose.setContent {
            PdfEditorTheme {
                ReaderScreen(
                    ui, { _, _, _ -> bitmap }, {
                        listOf(PdfTextWord("Hello ", PdfRect(40f, 40f, 100f, 60f)), PdfTextWord("world", PdfRect(102f, 40f, 160f, 60f)))
                    },
                    { _, _, _ -> }, {}, {}, { saveCopy = it },
                    {
                            id,
                            a,
                        ->
                        doc.add(id, a)
                        ui = ready()
                    }, { id, a ->
                        doc.replace(id, a)
                        ui = ready()
                    }, { id, a ->
                        doc.remove(id, a)
                        ui = ready()
                    },
                    {
                        doc.undo()
                        ui = ready()
                    }, {
                        doc.redo()
                        ui = ready()
                    },
                    {
                        doc.reorder(it)
                        ui = ready().copy(documentVersion = ui.documentVersion + 1)
                    },
                    {
                        doc.delete(it)
                        ui = ready()
                    }, {},
                    { ui = ui.copy(invertColors = !ui.invertColors) }, { ui = ui.copy(paged = !ui.paged) },
                    onEditText = { pageId, annotation, session ->
                        doc.editText(pageId, annotation, session)
                        ui = ready().copy(paged = ui.paged, invertColors = ui.invertColors)
                    },
                    modifier = if (height != null) Modifier.height(height) else Modifier,
                )
            }
        }
    }

    private fun enterEditing() {
        if (compose.onAllNodesWithTag("tool-DRAW").fetchSemanticsNodes().isEmpty()) {
            if (compose.onAllNodesWithTag("toggle-editing").fetchSemanticsNodes().isEmpty()) {
                compose.onNodeWithTag("pdf-page-0").performTouchInput {
                    down(Offset(width * .8f, height * .6f))
                    up()
                }
            }
            compose.onNodeWithTag("toggle-editing").performClick()
        }
    }

    private fun openDocumentOptions() {
        if (compose.onAllNodesWithTag("document-details").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("tool-HAND").performClick()
            compose.onNodeWithTag("pdf-page-0").performTouchInput {
                down(Offset(width * .8f, height * .6f))
                up()
            }
        }
        compose.onNodeWithTag("document-details").performClick()
    }

    @Test
    fun resizingSelectedTextReflowsItWithoutChangingItsFontAndIsOneUndoStep() {
        launch()
        enterEditing()
        compose.onNodeWithTag("tool-TEXT").performClick()
        compose.onNodeWithTag("pdf-page-0").performTouchInput {
            down(Offset(width * .2f, height * .35f))
            up()
        }
        val raw = "Resize this annotation without changing the font size"
        compose.onNodeWithTag("annotation-text").performTextInput(raw)
        compose.onNodeWithTag("annotation-text").performImeAction()
        val before = doc.annotationsAt(0).single() as TextBoxAnnotation
        val original = compose.onNodeWithTag("object-selection-${before.id}").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("text-resize-bottom-right").performTouchInput {
            down(center)
            moveTo(center + Offset(-140f, 50f), 240)
            up()
        }
        val after = doc.annotationsAt(0).single() as TextBoxAnnotation
        val resized = compose.onNodeWithTag("object-selection-${before.id}").getUnclippedBoundsInRoot()
        assertTrue("Dragging the handle must narrow the box", resized.right - resized.left < original.right - original.left - 20.dp)
        assertEquals(before.fontSizePoints, after.fontSizePoints)
        assertTrue("Narrowing reflows all the original words", after.text.count { it == '\n' } > before.text.count { it == '\n' })
        assertEquals(raw, after.text.replace('\n', ' ').replace(Regex(" +"), " ").trim())
        compose.onNodeWithTag("undo").performClick()
        assertEquals(before, doc.annotationsAt(0).single())
        compose.onNodeWithTag("redo").performClick()
        assertEquals(after, doc.annotationsAt(0).single())
    }

    @Test
    fun selectedTextColourAndFontChangeWithoutOpeningATextDialog() {
        launch()
        enterEditing()
        compose.onNodeWithTag("tool-TEXT").performClick()
        compose.onNodeWithTag("pdf-page-0").performTouchInput {
            down(Offset(width * .2f, height * .35f))
            up()
        }
        compose.onNodeWithTag("annotation-text").performTextInput("A note with its own style")
        compose.onNodeWithTag("annotation-text").performImeAction()
        val before = doc.annotationsAt(0).single() as TextBoxAnnotation
        compose.onNodeWithTag("font-options").performClick()
        compose.onNodeWithTag("font-option-droid-serif").performClick()
        compose.waitUntil(5_000) { (doc.annotationsAt(0).single() as TextBoxAnnotation).fontId == "droid-serif" }
        val serif = doc.annotationsAt(0).single() as TextBoxAnnotation
        assertEquals("droid-serif", serif.fontId)
        assertEquals(before.fontSizePoints, serif.fontSizePoints)
        compose.onNodeWithTag("ink-options").performClick()
        compose.onNodeWithTag("ink-color-2").performClick()
        assertEquals(0xFFFF3B30.toInt(), (doc.annotationsAt(0).single() as TextBoxAnnotation).colorArgb)
        compose.onNodeWithTag("undo").performClick()
        assertEquals(serif, doc.annotationsAt(0).single())
        compose.onNodeWithTag("undo").performClick()
        assertEquals(before, doc.annotationsAt(0).single())
        compose.onNodeWithTag("font-options").performClick()
        compose.onNodeWithTag("import-font").assertIsDisplayed()
    }

    @Test
    fun cancellingATextResizeDiscardsThePreview() {
        launch()
        enterEditing()
        compose.onNodeWithTag("tool-TEXT").performClick()
        compose.onNodeWithTag("pdf-page-0").performTouchInput {
            down(Offset(width * .2f, height * .35f))
            up()
        }
        compose.onNodeWithTag("annotation-text").performTextInput("Keep this text intact")
        compose.onNodeWithTag("annotation-text").performImeAction()
        val before = doc.annotationsAt(0).single()
        compose.onNodeWithTag("text-resize-bottom-right").performTouchInput {
            down(center)
            moveTo(center + Offset(-60f, 30f), 160)
            cancel()
        }
        compose.waitForIdle()
        assertEquals(before, doc.annotationsAt(0).single())
        compose.onNodeWithTag("text-resize-bottom-right").assertIsDisplayed()
    }

    @Test
    fun longPagedNoteKeepsTheCaretVisibleAcrossSuccessiveTypingUpdates() {
        ui = ready().copy(paged = true)
        launch(height = 450.dp)
        enterEditing()
        compose.onNodeWithTag("tool-TEXT").performClick()
        compose.onNodeWithTag("pdf-page-0").performTouchInput {
            down(Offset(width * .3f, height * .3f))
            up()
        }
        compose.onNodeWithTag("annotation-text").performTextInput((1..30).joinToString("\n") { "Line $it" })
        repeat(2) {
            compose.onNodeWithTag("annotation-text").performTextInput("\nMore text")
            compose.waitForIdle()
            val layouts = mutableListOf<TextLayoutResult>()
            val node = compose.onNodeWithTag("annotation-text")
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            val selection = node.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
            val caret = layouts.single().getCursorRect(selection.end)
            val bounds = node.getUnclippedBoundsInRoot()
            val viewport = compose.onNodeWithTag("document-surface").getUnclippedBoundsInRoot()
            val save = compose.onNodeWithTag("save-edits").getUnclippedBoundsInRoot()
            val top = bounds.top + with(compose.density) { caret.top.toDp() }
            val bottom = bounds.top + with(compose.density) { caret.bottom.toDp() }
            assertTrue(
                "Caret [$top, $bottom] must stay within visible page [${viewport.top}, ${save.top}]",
                top >= viewport.top && bottom <= save.top,
            )
        }
        compose.onNodeWithTag("annotation-text").performImeAction()
    }

    @Test
    fun lowerPagedTextRemainsVisibleAndMultilineContentStaysInsideThePdf() {
        ui = ready().copy(paged = true)
        launch()
        enterEditing()
        compose.onNodeWithTag("tool-TEXT").performClick()
        compose.onNodeWithTag("pdf-page-0").performTouchInput {
            down(Offset(width * .3f, height * .94f))
            up()
        }
        compose.onNodeWithTag("annotation-text").performTextInput("Bottom line\nSecond line")
        compose.runOnIdle {
            val text = doc.annotationsAt(0).single() as TextBoxAnnotation
            assertTrue(text.position.y + text.fontSizePoints * 1.5f <= 792f)
        }
        val field = compose.onNodeWithTag("annotation-text").getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("document-surface").getUnclippedBoundsInRoot()
        assertTrue("The active text field must remain above the keyboard", field.bottom <= viewport.bottom)
        compose.onNodeWithTag("annotation-text").performImeAction()
    }

    @Test
    fun typingIsLiveAndOneUndoRemovesTheWholeTextEdit() {
        launch()
        enterEditing()
        compose.onNodeWithTag("tool-TEXT").performClick()
        compose.onNodeWithTag("pdf-page-0").performTouchInput {
            down(Offset(width * .25f, height * .3f))
            up()
        }
        compose.onNodeWithTag("annotation-text").performTextInput("First")
        compose.runOnIdle { assertEquals("First", (doc.annotationsAt(0).single() as TextBoxAnnotation).text) }
        compose.onNodeWithTag("annotation-text").performTextInput(" note")
        compose.onNodeWithTag("annotation-text").performImeAction()
        compose.onNodeWithTag("undo").performClick()
        compose.runOnIdle { assertTrue(doc.annotationsAt(0).isEmpty()) }
        compose.onNodeWithTag("redo").performClick()
        compose.runOnIdle { assertEquals("First note", (doc.annotationsAt(0).single() as TextBoxAnnotation).text) }
    }

    @Test
    fun trayIsPureBlackAndHandSelectsAnExistingStroke() {
        val stroke = com.pdfeditor.app.domain.DrawStroke("ink", listOf(PdfPoint(50f, 180f), PdfPoint(180f, 220f)), -16777216, 2.5f)
        doc.add("p0", stroke)
        ui = ready()
        launch()
        enterEditing()
        val pixels = compose.onNodeWithTag("editing-tray-surface").captureToImage().toPixelMap()
        val pixel = pixels[pixels.width / 2, 10]
        assertTrue("Tray background must be pure black", pixel.red < .01f && pixel.green < .01f && pixel.blue < .01f)
        compose.onNodeWithTag("tool-HAND").performClick()
        compose.onNodeWithTag("pdf-page-0").performTouchInput {
            val scale = width / 612f
            down(Offset(115f * scale, 200f * scale))
            up()
        }
        compose.onNodeWithTag("object-selection-ink").assertIsDisplayed()
    }

    @Test
    fun textIsTypedOnThePageWithoutADialogAndSaveStaysAtTheBottomRight() {
        launch()
        enterEditing()
        compose.onNodeWithTag("tool-TEXT").performClick()
        compose.onNodeWithTag("pdf-page-0").performTouchInput {
            down(Offset(width * .25f, height * .3f))
            up()
        }
        compose.onNodeWithText("Text annotation").assertDoesNotExist()
        compose.onNodeWithTag("annotation-text").assertIsDisplayed().performTextInput("Inline text")
        compose.onNodeWithTag("annotation-text").performImeAction()
        compose.runOnIdle { assertEquals("Inline text", (doc.annotationsAt(0).single() as TextBoxAnnotation).text) }
        val id = doc.annotationsAt(0).single().id
        compose.onNodeWithTag("object-selection-$id").assertIsDisplayed()
        val save = compose.onNodeWithTag("save-edits").getUnclippedBoundsInRoot()
        val page = compose.onNodeWithTag("document-surface").getUnclippedBoundsInRoot()
        val tray = compose.onNodeWithTag("editing-tray").getUnclippedBoundsInRoot()
        assertTrue(
            "Save belongs in the lower right, above contextual tools",
            (save.left + save.right) / 2 > (page.right - page.left) * .75f &&
                save.top > (page.bottom - page.top) / 2 && save.bottom <= tray.top,
        )
    }

    @Test
    fun lastPageCanScrollAboveTheEditingTray() {
        launch()
        enterEditing()
        compose.onNodeWithTag("tool-HAND").performClick()
        compose.onNodeWithTag("scroll-document").performScrollToIndex(2)
        val lastPage = compose.onNodeWithTag("pdf-page-2").getUnclippedBoundsInRoot()
        val tray = compose.onNodeWithTag("editing-tray").getUnclippedBoundsInRoot()
        assertTrue("Last-page content must be reachable above the tray", lastPage.bottom <= tray.top)
    }

    @Test
    fun expandedDocumentActionsRemainReachableOnAShortScreen() {
        launch(height = 300.dp)
        compose.onNodeWithTag("document-details").performClick()
        compose.onNodeWithText("Compress a copy").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pages").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun readingStaysClearUntilEditingIsRequested() {
        launch()
        compose.onNodeWithTag("tool-DRAW").assertDoesNotExist()
        compose.onNodeWithTag("layout-toggle").assertDoesNotExist()
        compose.onNodeWithTag("toggle-editing").performClick()
        compose.onNodeWithTag("tool-DRAW").assertIsDisplayed()
        compose.onNodeWithTag("ink-colors").assertDoesNotExist()
        compose.onNodeWithTag("ink-options").performClick()
        compose.onNodeWithTag("ink-colors").assertIsDisplayed()
        compose.onNodeWithTag("ink-color-2").performClick()
        compose.onNodeWithTag("finish-editing").performClick()
        compose.onNodeWithTag("tool-DRAW").assertDoesNotExist()
    }

    @Test
    fun headerFadeHasNoHardSeamAtTheTopOfThePaper() {
        launch()
        val page = compose.onNodeWithTag("pdf-page-0").getUnclippedBoundsInRoot()
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val edge = with(compose.density) { page.top.roundToPx() }
        val x = pixels.width / 2
        val largestStep =
            ((edge - 3)..(edge + 3)).maxOf { y ->
                kotlin.math.abs(pixels[x, y].red - pixels[x, y - 1].red)
            }
        assertTrue("The header fade must blend continuously into the paper, without a dark/white step", largestStep < .12f)
    }

    @Test
    fun returningToTheTopRevealsTheHeaderAndKeepsTheFirstPageBelowIt() {
        launch()
        compose.onNodeWithTag("scroll-document").performScrollToIndex(1)
        compose.onNodeWithTag("scroll-document").performScrollToIndex(0)
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(4000)
        compose.onNodeWithTag("document-details").assertIsDisplayed()
        val header = compose.onNodeWithTag("document-details").getUnclippedBoundsInRoot()
        val page = compose.onNodeWithTag("pdf-page-0").getUnclippedBoundsInRoot()
        assertTrue("The first page must start below all header controls", page.top >= header.bottom)
    }

    @Test
    fun firstPageInPagedViewAlsoLeavesRoomForTheHeader() {
        ui = ready().copy(paged = true)
        launch()
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(4000)
        compose.onNodeWithTag("document-details").assertIsDisplayed()
        val header = compose.onNodeWithTag("document-details").getUnclippedBoundsInRoot()
        val page = compose.onNodeWithTag("pdf-page-0").getUnclippedBoundsInRoot()
        assertTrue("Paged content must leave the header controls unobstructed", page.top >= header.bottom)
    }

    @Test
    fun idleReadingHidesChromeAndATapBringsItBack() {
        launch()
        compose.onNodeWithTag("close-document").assertIsDisplayed()
        compose.onNodeWithTag("scroll-document").performScrollToIndex(1)
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(4000)
        compose.onNodeWithTag("close-document").assertDoesNotExist()
        compose.onNodeWithTag("pdf-page-1").performTouchInput {
            down(Offset(width * .7f, height * .6f))
            up()
        }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("close-document").assertIsDisplayed()
        compose.onNodeWithTag("tool-DRAW").assertDoesNotExist()
    }

    @Test
    fun plainToolbarTextHasVisibleForegroundOnBlack() {
        launch()
        val image = compose.onNodeWithText("Test.pdf").captureToImage().toPixelMap()
        var bright = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val pixel = image[x, y]
            if (pixel.red > .5f && pixel.green > .5f && pixel.blue > .5f) bright++
        }
        assertTrue("Document title must be readable on the black toolbar", bright > 10)
    }

    @Test fun inkOnTwoPagesSurvivesNavigationAndUsesOneSaveMenu() {
        launch()
        enterEditing()
        compose.onNodeWithTag("tool-DRAW").performClick()
        compose.onNodeWithTag("pdf-page-0").performTouchInput {
            down(Offset(80f, 90f))
            moveTo(Offset(150f, 125f), 100)
            up()
        }
        compose.runOnIdle { assertEquals(1, doc.annotationsAt(0).size) }
        compose.onNodeWithTag("scroll-document").performScrollToIndex(2)
        compose.onNodeWithTag("pdf-page-2").performTouchInput {
            down(Offset(70f, 80f))
            moveTo(Offset(160f, 110f), 100)
            up()
        }
        compose.runOnIdle {
            assertEquals(1, doc.annotationsAt(2).size)
            assertEquals(1, doc.annotationsAt(0).size)
        }
        compose.onNodeWithTag("save-edits").performClick()
        compose.onNodeWithTag("save-copy").performClick()
        compose.runOnIdle { assertEquals(true, saveCopy) }
        compose.onNodeWithTag("undo").performClick()
        compose.runOnIdle {
            assertTrue(doc.annotationsAt(2).isEmpty())
            assertEquals(1, doc.annotationsAt(0).size)
        }
        compose.onNodeWithTag("redo").performClick()
        compose.runOnIdle { assertEquals(1, doc.annotationsAt(2).size) }
    }

    @Test fun longPressSelectsCopyableTextInsteadOfScrubber() {
        launch()
        compose.waitForIdle()
        compose.onNodeWithTag("pdf-page-0").performTouchInput { longClick(Offset(width / 612f * 70f, width / 612f * 50f), 800) }
        compose.onNodeWithTag("copy-selection").assertIsDisplayed().performClick()
    }

    @Test fun pinchAcrossPageMarginCancelsInk() {
        ui = ready().copy(initialZoom = .5f)
        launch()
        enterEditing()
        compose.onNodeWithTag("tool-DRAW").performClick()
        compose.onNodeWithTag("document-surface").performTouchInput {
            down(0, Offset(width / 2f, 100f))
            down(1, Offset(10f, 100f))
            moveTo(0, Offset(width / 2f + 30f, 110f))
            moveTo(1, Offset(40f, 110f))
            advanceEventTime(40)
            up(0)
            up(1)
        }
        compose.runOnIdle { assertTrue(doc.current.annotations.isEmpty()) }
    }

    @Test fun longPressWithFingerJitterStillSelectsText() {
        launch()
        compose.onNodeWithTag("pdf-page-0").performTouchInput {
            val point = Offset(width / 612f * 70f, width / 612f * 50f)
            down(point)
            moveTo(point + Offset(.5f, .5f), 40)
            advanceEventTime(800)
            moveTo(point + Offset(.7f, .7f), 10)
            up()
        }
        compose.onNodeWithTag("copy-selection").assertIsDisplayed()
    }

    @Test
    fun handDragsExistingSignatureAndUndoRestoresItsPosition() {
        doc.add("p0", SignaturePlacement("sig", PdfPoint(40f, 40f), listOf(listOf(PdfPoint(0f, 0f), PdfPoint(80f, 40f))), 1f, -16777216))
        ui = ready()
        launch()
        enterEditing()
        compose.onNodeWithTag("tool-HAND").performClick()
        compose.onNodeWithTag("pdf-page-0").performTouchInput {
            val scale = width / 612f
            down(Offset(60f * scale, 50f * scale))
            moveTo(Offset(120f * scale, 90f * scale), 200)
            up()
        }
        compose.runOnIdle {
            val position = (doc.annotationsAt(0).single() as SignaturePlacement).position
            assertEquals(100f, position.x, .01f)
            assertEquals(80f, position.y, .01f)
        }
        compose.onNodeWithTag("undo").performClick()
        compose.runOnIdle { assertEquals(PdfPoint(40f, 40f), (doc.annotationsAt(0).single() as SignaturePlacement).position) }
    }

    @Test fun bothLayoutsAndExplicitPagesAreAvailableDuringEditing() {
        launch()
        enterEditing()
        compose.onNodeWithTag("tool-DRAW").performClick()
        openDocumentOptions()
        compose.onNodeWithTag("layout-toggle").performClick()
        compose.onNodeWithTag("paged-document").assertIsDisplayed()
        compose.onNodeWithTag("tool-DRAW").assertIsDisplayed()
        openDocumentOptions()
        compose.onNodeWithTag("layout-toggle").performClick()
        compose.onNodeWithTag("scroll-document").assertIsDisplayed()
        openDocumentOptions()
        compose.onNodeWithTag("pages").performClick()
        compose.onNodeWithTag("page-overview").assertIsDisplayed()
    }

    @Test fun twoFingerPinchZoomsOutAndCancelsPendingInk() {
        launch()
        enterEditing()
        compose.onNodeWithTag("tool-DRAW").performClick()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("document-surface").performTouchInput {
            val y = height * .3f
            val cx = width / 2f
            down(0, Offset(cx - 100, y))
            down(1, Offset(cx + 100, y))
            for (i in 1..8) {
                moveTo(0, Offset(cx - 100 + i * 7, y))
                moveTo(1, Offset(cx + 100 - i * 7, y))
                advanceEventTime(20)
            }
            up(0)
            up(1)
        }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertTrue(doc.current.annotations.isEmpty()) }
        compose.onNodeWithTag("zoom-label").assert(hasText("100%").not())
    }
}
