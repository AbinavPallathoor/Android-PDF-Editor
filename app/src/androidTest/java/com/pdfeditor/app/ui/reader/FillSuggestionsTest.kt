package com.pdfeditor.app.ui.reader

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfeditor.app.data.cache.SavedSignature
import com.pdfeditor.app.data.cache.SignatureRepository
import com.pdfeditor.app.domain.DocumentEdits
import com.pdfeditor.app.domain.DocumentPage
import com.pdfeditor.app.domain.DocumentSnapshot
import com.pdfeditor.app.domain.FillTargetKind
import com.pdfeditor.app.domain.PdfFillTarget
import com.pdfeditor.app.domain.PdfPoint
import com.pdfeditor.app.domain.PdfRect
import com.pdfeditor.app.domain.SignaturePlacement
import com.pdfeditor.app.domain.TextBoxAnnotation
import com.pdfeditor.app.ui.theme.PdfEditorTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class FillSuggestionsTest {
    @get:Rule val compose = createComposeRule()
    private val edits = DocumentEdits(DocumentSnapshot(listOf(DocumentPage("page", 0))))
    private val name = PdfFillTarget("name", PdfRect(80f, 180f, 320f, 220f), FillTargetKind.TEXT, "Name")
    private val signature = PdfFillTarget("signature", PdfRect(80f, 320f, 320f, 390f), FillTargetKind.SIGNATURE, "Signature")
    private var state by mutableStateOf(ready())
    private var signatureFile: File? = null
    private var previousSignatures: ByteArray? = null

    private fun ready() =
        ReaderUiState.Ready(
            1, listOf(792f / 612f), listOf(612f), 0, 1f, false, displayName = "Form.pdf",
            pageIds = listOf("page"), annotations = mapOf(0 to edits.annotationsAt(0)),
            dirty = edits.isDirty, canUndo = edits.canUndo, canRedo = edits.canRedo, canSaveOriginal = true,
        )

    private fun launch() {
        val bitmap = Bitmap.createBitmap(306, 396, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        compose.setContent {
            PdfEditorTheme {
                ReaderScreen(
                    state, { _, _, _ -> bitmap }, { emptyList() }, { _, _, _ -> }, {}, {}, {},
                    { page, item ->
                        edits.add(page, item)
                        state = ready()
                    },
                    { page, item ->
                        edits.replace(page, item)
                        state = ready()
                    },
                    { page, id ->
                        edits.remove(page, id)
                        state = ready()
                    },
                    {
                        edits.undo()
                        state = ready()
                    }, {
                        edits.redo()
                        state = ready()
                    },
                    {}, {}, {}, {}, {},
                    onEditText = { page, annotation, session ->
                        edits.editText(page, annotation, session)
                        state = ready()
                    },
                    onDiscardChanges = {
                        edits.discardChanges()
                        state = ready()
                    },
                    loadFillTargets = { listOf(name, signature) },
                )
            }
        }
    }

    private fun hand() {
        compose.onNodeWithTag("toggle-editing").performClick()
        compose.onNodeWithTag("tool-HAND").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("fill-target-name").fetchSemanticsNodes().isNotEmpty() }
    }

    @After
    fun restoreSignatureStore() {
        signatureFile?.let { file -> previousSignatures?.let(file::writeBytes) ?: file.delete() }
    }

    @Test
    fun suggestionsStayHiddenWhileReading() {
        launch()
        compose.onNodeWithTag("fill-target-name").assertDoesNotExist()
        compose.onNodeWithTag("fill-target-signature").assertDoesNotExist()
        hand()
        compose.onNodeWithTag("fill-target-name").assertIsDisplayed()
    }

    @Test
    fun tappingTextSuggestionTypesInPlaceAndDiscardRestoresTheSuggestion() {
        launch()
        hand()
        compose.onNodeWithTag("fill-target-name").performTouchInput {
            down(center)
            up()
        }
        compose.onNodeWithTag("annotation-text").performTextInput("Alex")
        compose.onNodeWithTag("annotation-text").performImeAction()
        compose.runOnIdle {
            val text = edits.annotationsAt(0).single() as TextBoxAnnotation
            assertEquals("Alex", text.text)
            assertTrue(text.position.x >= name.bounds.left)
            assertTrue(text.position.x + requireNotNull(text.widthPoints) <= name.bounds.right)
        }
        compose.onNodeWithTag("fill-target-name").assertDoesNotExist()
        compose.onNodeWithTag("save-edits").performClick()
        compose.onNodeWithTag("discard-changes").performClick()
        compose.onNodeWithTag("save-edits").assertDoesNotExist()
        compose.onNodeWithTag("fill-target-name").assertIsDisplayed()
    }

    @Test
    fun cancellingAnEmptyTextFillLeavesNoEdit() {
        launch()
        hand()
        compose.onNodeWithTag("fill-target-name").performClick()
        compose.onNodeWithTag("annotation-text").performImeAction()
        compose.onNodeWithTag("save-edits").assertDoesNotExist()
        compose.onNodeWithTag("fill-target-name").assertIsDisplayed()
        assertTrue(edits.annotationsAt(0).isEmpty())
    }

    @Test
    fun aNewSignatureIsInsertedIntoTheRequestedFieldAfterCapture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "signatures.json")
        previousSignatures = file.takeIf { it.exists() }?.readBytes()
        signatureFile = file
        runBlocking { SignatureRepository(file).save(emptyList()) }
        launch()
        hand()
        compose.onNodeWithTag("fill-target-signature").performClick()
        compose.onNodeWithText("Draw a new signature").performClick()
        compose.onNodeWithTag("signature-canvas").performTouchInput {
            down(Offset(width * .15f, height * .3f))
            moveTo(Offset(width * .4f, height * .7f), 100)
            moveTo(Offset(width * .8f, height * .4f), 100)
            up()
        }
        compose.onNodeWithText("Save signature").performClick()
        compose.waitUntil(10_000) { edits.annotationsAt(0).isNotEmpty() }
        assertTrue(edits.annotationsAt(0).single() is SignaturePlacement)
        compose.onNodeWithTag("fill-target-signature").assertDoesNotExist()
        compose.onNodeWithTag("tool-HAND").assertIsDisplayed()
    }

    @Test
    fun signatureSuggestionPlacesTheStoredSignatureAndUndoBringsBackTheHint() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "signatures.json")
        previousSignatures = file.takeIf { it.exists() }?.readBytes()
        signatureFile = file
        runBlocking {
            SignatureRepository(file).save(listOf(SavedSignature(listOf(listOf(PdfPoint(20f, 30f), PdfPoint(220f, 110f))))))
        }
        launch()
        hand()
        compose.onNodeWithTag("fill-target-signature").performClick()
        compose.waitUntil(10_000) { edits.annotationsAt(0).isNotEmpty() }
        compose.runOnIdle {
            val sign = edits.annotationsAt(0).single() as SignaturePlacement
            val points = sign.strokes.flatten().map { PdfPoint(sign.position.x + it.x * sign.scale, sign.position.y + it.y * sign.scale) }
            assertTrue(points.all { it.x >= signature.bounds.left && it.x <= signature.bounds.right })
            assertTrue(points.all { it.y >= signature.bounds.top && it.y <= signature.bounds.bottom })
        }
        compose.onNodeWithTag("fill-target-signature").assertDoesNotExist()
        compose.onNodeWithContentDescription("Undo").performClick()
        compose.onNodeWithTag("fill-target-signature").assertIsDisplayed()
    }
}
