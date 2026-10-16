package com.pdfeditor.app.ui.nav

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.pdfeditor.app.MainActivity
import com.pdfeditor.app.PdfEditorApplication
import com.pdfeditor.app.data.pdf.PdfBoxDocumentContent
import com.pdfeditor.app.domain.TextBoxAnnotation
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Exercises PDF widgets -> immutable read snapshot -> real reader hints -> standard annotation export. */
class DetectedFieldIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var source: File? = null
    private var uri: android.net.Uri? = null
    private var coldActivity: MainActivity? = null

    @After
    fun cleanUp() {
        instrumentation.runOnMainSync { coldActivity?.finishAndRemoveTask() }
        instrumentation.waitForIdleSync()
        runBlocking {
            val repository = (context.applicationContext as PdfEditorApplication).recentsRepository
            uri?.let { repository.getByUri(it.toString()) }?.let { repository.remove(it.id) }
        }
        source?.delete()
    }

    @Test
    fun detectedTextFieldTypesInlineSavesExplicitlyAndReopensAsAnEditableObject() {
        val file = File(context.filesDir, "working_copies/detected-fields-${UUID.randomUUID()}.pdf")
        file.parentFile!!.mkdirs()
        source = file
        PDDocument().use { pdf ->
            val page = PDPage(PDRectangle.LETTER)
            pdf.addPage(page)
            val form = PDAcroForm(pdf)
            pdf.documentCatalog.acroForm = form
            form.defaultResources = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
            form.defaultAppearance = "/Helv 12 Tf 0 g"
            val name = PDTextField(form).apply { partialName = "Name" }
            name.widgets.single().apply {
                rectangle = PDRectangle(80f, 600f, 240f, 32f)
                this.page = page
                page.annotations.add(this)
            }
            val signature = PDSignatureField(form).apply { partialName = "Signature" }
            signature.widgets.single().apply {
                rectangle = PDRectangle(80f, 420f, 240f, 70f)
                this.page = page
                page.annotations.add(this)
            }
            form.fields.add(name)
            form.fields.add(signature)
            pdf.save(file)
        }
        val original = file.readBytes()
        val documentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        uri = documentUri
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setClass(context, MainActivity::class.java).setDataAndType(documentUri, "application/pdf")
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                ),
        )
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("pdf-page-0").fetchSemanticsNodes().isNotEmpty() }
        instrumentation.runOnMainSync {
            coldActivity =
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().single()
        }
        compose.onNodeWithTag("toggle-editing").performClick()
        compose.onNodeWithTag("tool-HAND").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Fill Name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Fill Name").performTouchInput {
            down(center)
            up()
        }
        compose.onNodeWithTag("annotation-text").performTextInput("Alex Example")
        compose.onNodeWithTag("annotation-text").performImeAction()
        assertArrayEquals("Typing must not write the source", original, file.readBytes())
        compose.onNodeWithTag("save-edits").performClick()
        compose.onNodeWithTag("save-current").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Saved").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("save-edits").fetchSemanticsNodes().isEmpty()
        }
        val saved =
            runBlocking { PdfBoxDocumentContent().readAnnotations(file)[0].orEmpty().filterIsInstance<TextBoxAnnotation>().single() }
        assertEquals("Alex Example", saved.text)
        assertTrue(saved.position.x >= 80f && saved.position.x + requireNotNull(saved.widthPoints) <= 320f)
        val savedBytes = file.readBytes()
        compose.onNodeWithContentDescription("Finish editing").performClick()
        instrumentation.runOnMainSync { coldActivity?.onBackPressedDispatcher?.onBackPressed() }
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("pdf-page-0").fetchSemanticsNodes().isEmpty() }
        compose.onAllNodesWithText(file.name)[0].performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("pdf-page-0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("toggle-editing").performClick()
        compose.onNodeWithTag("tool-HAND").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("Sign Signature").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Fill Name").assertDoesNotExist()
        compose.onNodeWithContentDescription("Sign Signature").assertIsDisplayed()
        assertArrayEquals("Reopening must not write the saved file", savedBytes, file.readBytes())
    }
}
