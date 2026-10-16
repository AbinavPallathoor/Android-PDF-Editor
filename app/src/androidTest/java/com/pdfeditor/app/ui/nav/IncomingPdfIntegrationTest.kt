package com.pdfeditor.app.ui.nav

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.pdfeditor.app.MainActivity
import com.pdfeditor.app.PdfEditorApplication
import com.pdfeditor.app.data.pdf.PdfBoxDocumentContent
import com.pdfeditor.app.domain.DrawStroke
import com.pdfeditor.app.domain.SignaturePlacement
import com.pdfeditor.app.domain.TextBoxAnnotation
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Uses the production Activity, navigation graph, importer, and platform PDF renderer. */
class IncomingPdfIntegrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fixtures = mutableListOf<File>()
    private val fixtureRecents = mutableListOf<Long>()
    private var coldActivity: MainActivity? = null
    private var restoreSignatures = false
    private var originalSignatures: ByteArray? = null

    @After
    fun cleanUp() {
        // CLEAR_TASK below replaces the ActivityScenario rule's launcher Activity. Finish the
        // replacement too so the next integration test starts with a clean Activity task.
        instrumentation.runOnMainSync {
            coldActivity?.takeUnless { it.isFinishing || it.isDestroyed }?.finishAndRemoveTask()
        }
        instrumentation.waitForIdleSync()
        runBlocking {
            val repository = (context.applicationContext as PdfEditorApplication).recentsRepository
            val workingRoot = File(context.filesDir, "working_copies").canonicalPath + File.separator
            val fixtureUris =
                fixtures.filter { it.canonicalPath.startsWith(workingRoot) }.map { file ->
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
                }.toSet()
            val automaticallyRecorded = repository.observeRecents().first().filter { it.uri in fixtureUris }.map { it.id }
            (fixtureRecents + automaticallyRecorded).distinct().forEach { repository.remove(it) }
        }
        fixtures.forEach { it.delete() }
        if (restoreSignatures) {
            val store = File(context.filesDir, "signatures.json")
            originalSignatures?.let { store.writeBytes(it) } ?: store.delete()
        }
    }

    @Test
    fun inlineRenameStaysInACompactRowAboveTheNativeKeyboard() {
        val pdf = createPdf("rename-keyboard", pageCount = 3)
        launchCold(pdf.uri)
        awaitPdf(pdf.name, pageCount = 3)
        composeRule.onNodeWithTag("document-details").performClick()
        composeRule.onNodeWithTag("expanded-document-name").performClick()
        val activity = checkNotNull(resumedActivity())
        composeRule.waitUntil(timeoutMillis = 15_000) {
            var shown = false
            instrumentation.runOnMainSync {
                shown = ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            shown
        }
        composeRule.waitForIdle()
        val field = composeRule.onNodeWithTag("inline-document-name").fetchSemanticsNode().boundsInWindow
        var keyboardTop = 0
        var density = 1f
        instrumentation.runOnMainSync {
            val view = activity.window.decorView
            keyboardTop = view.height - ViewCompat.getRootWindowInsets(view)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
            density = view.resources.displayMetrics.density
        }
        assertTrue("The rename field must stay visible above the keyboard", field.bottom <= keyboardTop + 2)
        assertTrue("Renaming must not leave a large empty panel above the keyboard", keyboardTop - field.bottom < 96 * density)
        composeRule.onNodeWithTag("inline-document-name").performImeAction()
        composeRule.onNodeWithTag("pages").assertIsDisplayed()
    }

    @Test
    fun swipingBetweenDocumentsKeepsTheUnsavedDraftAndFinishesTheRenderedHandoff() {
        val source = createPdf("swipe-source", pageCount = 3)
        val target = createPdf("swipe-target", pageCount = 5)
        val repository = (context.applicationContext as PdfEditorApplication).recentsRepository
        runBlocking {
            // Seed both owned URIs before launch so the reader has adjacent documents.
            listOf(target, source).forEach { pdf ->
                fixtureRecents.add(repository.recordOpened(pdf.uri.toString(), pdf.name, null).id)
            }
        }
        launchCold(source.uri)
        awaitPdf(source.name, 3)
        selectTool("DRAW")
        composeRule.onNodeWithTag("pdf-page-0").performTouchInput {
            down(Offset(width * .2f, height * .4f))
            moveTo(Offset(width * .5f, height * .45f), 160)
            up()
        }
        revealChrome()
        composeRule.onNodeWithTag("document-details").performTouchInput {
            down(Offset(width * .9f, height * .5f))
            moveTo(Offset(width * .1f, height * .5f), 400)
            up()
        }
        awaitSwitchedDocument(target)
        awaitPdf(target.name, 5)
        composeRule.onNodeWithTag("document-details").performTouchInput {
            down(Offset(width * .1f, height * .5f))
            moveTo(Offset(width * .9f, height * .5f), 400)
            up()
        }
        awaitSwitchedDocument(source)
        awaitPdf(source.name, 3)
        composeRule.onNodeWithTag("save-edits").assertIsDisplayed()
        assertTrue(
            "Swiping must retain a draft without modifying its source",
            runBlocking {
                PdfBoxDocumentContent().readAnnotations(copySource(source.uri)).values.flatten().isEmpty()
            },
        )
    }

    private fun awaitSwitchedDocument(pdf: PdfFixture) {
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag("reader-document-${pdf.uri}").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("document-switch-frame").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun coldViewOpensRequestedPdf_andWarmViewOpensDifferentPdfInSameActivity() {
        val cold = createPdf("cold", pageCount = 3)
        val warm = createPdf("warm", pageCount = 7)
        val launcherActivity = composeRule.activity

        launchCold(cold.uri)
        awaitPdf(cold.name, pageCount = 3)
        val openedActivity = checkNotNull(resumedActivity())
        assertNotSame("VIEW must exercise a fresh onCreate", launcherActivity, openedActivity)
        assertEquals(cold.uri, openedActivity.intent.data)

        launchWarm(warm.uri)
        awaitPdf(warm.name, pageCount = 7)
        assertSame("Warm VIEW must exercise onNewIntent on the existing reader", openedActivity, resumedActivity())
        assertEquals(warm.uri, openedActivity.intent.data)
        composeRule.onNodeWithText(cold.name).assertDoesNotExist()
    }

    @Test
    fun normalPdfCanSwitchToDark_andDarkPreferenceSurvivesClosingAndReopening() {
        val pdf = createPdf("colors", pageCount = 4)
        launchCold(pdf.uri)
        awaitPdf(pdf.name, pageCount = 4)
        composeRule.onNodeWithTag("invert-toggle").assertContentDescriptionEquals("Normal PDF")

        composeRule.onNodeWithTag("invert-toggle").performClick()
        awaitColorMode("Dark PDF")
        revealChrome()
        composeRule.onNodeWithTag("close-document").performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("page-count").fetchSemanticsNodes().isEmpty()
        }

        // Closing removes the reader's back-stack entry and ViewModel. Reopening must load
        // the persisted preference from the document session, not retain a live UI toggle.
        launchWarm(pdf.uri)
        awaitPdf(pdf.name, pageCount = 4)
        awaitColorMode("Dark PDF")
        composeRule.onNodeWithTag("invert-toggle").assertContentDescriptionEquals("Dark PDF")
    }

    @Test
    fun editsOnFirstAndThirdPagesSaveToCurrentPdf_andReopenWithoutUnsavedChanges() {
        val pdf = createPdf("edits", pageCount = 3)
        launchCold(pdf.uri)
        awaitPdf(pdf.name, pageCount = 3)

        selectTool("DRAW")
        composeRule.onNodeWithTag("pdf-page-0").performTouchInput {
            down(Offset(width * .20f, height * .20f))
            moveTo(Offset(width * .35f, height * .24f), 100)
            moveTo(Offset(width * .50f, height * .20f), 100)
            up()
        }
        selectTool("HAND")
        composeRule.onNodeWithTag("scroll-document").performScrollToIndex(2)
        composeRule.onNodeWithTag("pdf-page-2").assertIsDisplayed()
        selectTool("TEXT")
        composeRule.onNodeWithTag("pdf-page-2").performTouchInput {
            down(Offset(width * .30f, height * .30f))
            up()
        }
        composeRule.onNodeWithTag("annotation-text").performTextInput("Saved on page three")
        composeRule.onNodeWithTag("annotation-text").performImeAction()
        saveCurrentAndAwaitSaved()

        // Read the original content URI, not the reader's private working copy or export file.
        val snapshot = copySource(pdf.uri)
        val annotations = runBlocking { PdfBoxDocumentContent().readAnnotations(snapshot) }
        val strokes = annotations[0].orEmpty().filterIsInstance<DrawStroke>()
        assertEquals(1, strokes.size)
        assertTrue(strokes.single().points.size >= 3)
        val text = annotations[2].orEmpty().filterIsInstance<TextBoxAnnotation>()
        assertEquals(listOf("Saved on page three"), text.map { it.text })
        assertOriginalContent(snapshot, "edits", pageCount = 3)

        closeAndReopen(pdf, pageCount = 3)
        composeRule.onNodeWithTag("save-edits").assertDoesNotExist()
        // Android's base raster omits Ink; the actual editor must restore and draw saved marks.
        val pixels = composeRule.onNodeWithTag("pdf-page-0").captureToImage().toPixelMap()
        val pointA = strokes.single().points[0]
        val pointB = strokes.single().points[1]
        val x = ((pointA.x + pointB.x) / 2f * pixels.width / 612f).toInt()
        val y = ((pointA.y + pointB.y) / 2f * pixels.width / 612f).toInt()
        assertTrue(
            "Reopened saved Ink must be visible in the editor",
            ((x - 5)..(x + 5)).any { px ->
                ((y - 5)..(y + 5)).any { py ->
                    if (px !in 0 until pixels.width || py !in 0 until pixels.height) {
                        false
                    } else {
                        pixels[px, py].let {
                            it.red < .25f && it.green < .25f && it.blue < .25f
                        }
                    }
                }
            },
        )
    }

    @Test
    fun capturedSignatureCanBePlacedOnFirstAndThirdPages_andSavedInOriginalPdf() {
        val signatureStore = File(context.filesDir, "signatures.json")
        originalSignatures = signatureStore.takeIf { it.exists() }?.readBytes()
        restoreSignatures = true
        val pdf = createPdf("signatures", pageCount = 3)
        launchCold(pdf.uri)
        awaitPdf(pdf.name, pageCount = 3)

        selectTool("SIGN")
        composeRule.onNodeWithText("Draw a new signature").performClick()
        composeRule.onNodeWithTag("signature-canvas").performTouchInput {
            down(Offset(width * .15f, height * .65f))
            moveTo(Offset(width * .30f, height * .25f), 100)
            moveTo(Offset(width * .45f, height * .65f), 100)
            moveTo(Offset(width * .70f, height * .40f), 100)
            up()
        }
        composeRule.onNodeWithText("Save signature").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("pdf-page-0").performTouchInput {
            down(Offset(width * .30f, height * .30f))
            up()
        }
        // Scroll by the real LazyColumn's semantics action while retaining the captured
        // signature as the active tool, then place the same signature on the later page.
        composeRule.onNodeWithTag("scroll-document").performScrollToIndex(2)
        composeRule.onNodeWithTag("pdf-page-2").performTouchInput {
            down(Offset(width * .40f, height * .30f))
            up()
        }
        saveCurrentAndAwaitSaved()

        val snapshot = copySource(pdf.uri)
        val annotations = runBlocking { PdfBoxDocumentContent().readAnnotations(snapshot) }
        val first = annotations[0].orEmpty().filterIsInstance<SignaturePlacement>().single()
        val third = annotations[2].orEmpty().filterIsInstance<SignaturePlacement>().single()
        assertTrue(first.strokes.flatten().size >= 4)
        assertEquals(first.strokes, third.strokes)
        assertOriginalContent(snapshot, "signatures", pageCount = 3)
        closeAndReopen(pdf, pageCount = 3)
        composeRule.onNodeWithTag("save-edits").assertDoesNotExist()
    }

    private fun revealChrome() {
        if (composeRule.onAllNodesWithTag("close-document").fetchSemanticsNodes().isEmpty()) {
            if (composeRule.onAllNodesWithTag("tool-HAND").fetchSemanticsNodes().isNotEmpty()) {
                composeRule.onNodeWithTag("tool-HAND").performClick()
            }
            val page = (0..6).last { composeRule.onAllNodesWithTag("pdf-page-$it").fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithTag("pdf-page-$page").performTouchInput {
                down(Offset(width * .9f, height * .25f))
                up()
            }
        }
    }

    private fun selectTool(name: String) {
        if (composeRule.onAllNodesWithTag("tool-$name").fetchSemanticsNodes().isEmpty()) {
            revealChrome()
            composeRule.onNodeWithTag("toggle-editing").performClick()
        }
        composeRule.onNodeWithTag("tool-$name").performClick()
    }

    private fun saveCurrentAndAwaitSaved() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("save-edits").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("save-edits").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("save-current").assertIsEnabled().performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("Saved").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("save-edits").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("Saved").assertIsDisplayed()
    }

    private fun copySource(uri: Uri): File {
        val copy = File.createTempFile("incoming-saved-", ".pdf", context.cacheDir)
        fixtures.add(copy)
        checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            copy.outputStream().use { output -> input.copyTo(output) }
        }
        return copy
    }

    private fun assertOriginalContent(
        file: File,
        label: String,
        pageCount: Int,
    ) {
        PDDocument.load(file).use { document ->
            assertEquals(pageCount, document.numberOfPages)
            val text = PDFTextStripper().getText(document)
            repeat(pageCount) { index ->
                assertTrue(
                    "Original page ${index + 1} text must survive saving",
                    text.contains("$label PDF - page ${index + 1} of $pageCount"),
                )
            }
        }
    }

    private fun closeAndReopen(
        pdf: PdfFixture,
        pageCount: Int,
    ) {
        revealChrome()
        composeRule.onNodeWithTag("close-document").performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("page-count").fetchSemanticsNodes().isEmpty()
        }
        launchWarm(pdf.uri)
        awaitPdf(pdf.name, pageCount)
    }

    private fun launchCold(uri: Uri) {
        val launcher = composeRule.activity
        instrumentation.runOnMainSync {
            launcher.startActivity(
                viewIntent(uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        }
        composeRule.waitUntil(timeoutMillis = 20_000) {
            resumedActivity()?.let { it !== launcher && it.intent.data == uri } == true
        }
        coldActivity = checkNotNull(resumedActivity())
    }

    private fun launchWarm(uri: Uri) {
        val current = checkNotNull(resumedActivity()) { "The test reader is not resumed" }
        instrumentation.runOnMainSync { current.startActivity(viewIntent(uri)) }
    }

    private fun viewIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW)
            .setClass(context, MainActivity::class.java)
            .setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun resumedActivity(): MainActivity? {
        var activity: MainActivity? = null
        instrumentation.runOnMainSync {
            activity =
                ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>()
                    .singleOrNull()
        }
        return activity
    }

    private fun awaitPdf(
        name: String,
        pageCount: Int,
    ) {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodes(hasTestTag("page-count") and hasText("1 / $pageCount"))
                    .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(name).assertIsDisplayed()
        composeRule.onNodeWithTag("page-count").assertTextEquals("1 / $pageCount")
        composeRule.onNodeWithTag("pdf-page-0").assertIsDisplayed()
    }

    private fun awaitColorMode(label: String) {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodes(hasTestTag("invert-toggle") and hasContentDescription(label))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun createPdf(
        label: String,
        pageCount: Int,
    ): PdfFixture {
        PDFBoxResourceLoader.init(context.applicationContext)
        val directory = File(context.filesDir, "working_copies").apply { mkdirs() }
        val name = "incoming-$label-${UUID.randomUUID().toString().take(8)}.pdf"
        val file = File(directory, name)
        fixtures.add(file)
        PDDocument().use { document ->
            repeat(pageCount) { index ->
                val page = PDPage(PDRectangle.LETTER)
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(PDType1Font.HELVETICA, 18f)
                    stream.newLineAtOffset(48f, 700f)
                    stream.showText("$label PDF - page ${index + 1} of $pageCount")
                    stream.endText()
                }
            }
            document.save(file)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return PdfFixture(name, uri)
    }

    private data class PdfFixture(val name: String, val uri: Uri)
}
