package com.pdfeditor.app.ui.nav

import android.content.Intent
import android.net.Uri
import android.os.StrictMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfeditor.app.MainActivity
import com.pdfeditor.app.PdfEditorApplication
import com.pdfeditor.app.domain.RecentDocument
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real navigation, Room, reader and thumbnail paths; provider-grant revocation is tested on JVM. */
class TemporaryPdfRecentIntegrationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val repository get() = (context.applicationContext as PdfEditorApplication).recentsRepository
    private var source: File? = null
    private var recent: RecentDocument? = null
    private var scenarioLaunchIntent: Intent? = null

    @After
    fun cleanUp() {
        scenarioLaunchIntent?.let { launch ->
            instrumentation.runOnMainSync { compose.activity.intent = Intent(launch) }
        }
        source?.delete()
        recent?.let { document ->
            runBlocking { repository.remove(document.id) }
            File(Uri.parse(document.uri).path!!).delete()
        }
    }

    @Test
    fun temporaryViewCreatesRecentThatReopensAfterSourceRemovalAndActivityRecreation() {
        val name = "temporary-handoff-${UUID.randomUUID()}.pdf"
        val file = File(context.cacheDir, name).also { source = it }
        PDDocument().use { document ->
            document.addPage(PDPage())
            document.save(file)
        }
        val sourceUri = Uri.fromFile(file)
        scenarioLaunchIntent = Intent(compose.activity.intent)
        // Legacy file:// handoffs remain supported. Relax only this test process's exposure
        // detector while dispatching the Intent; production policy and permissions are untouched.
        instrumentation.runOnMainSync {
            val policy = StrictMode.getVmPolicy()
            try {
                StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
                compose.activity.startActivity(
                    Intent(Intent.ACTION_VIEW).setClass(context, MainActivity::class.java)
                        .setDataAndType(sourceUri, "application/pdf")
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION),
                )
            } finally {
                StrictMode.setVmPolicy(policy)
            }
        }
        compose.waitUntil(timeoutMillis = 30_000) {
            recent = runBlocking { repository.observeRecents().first().firstOrNull { it.displayName == name } }
            recent != null && compose.onAllNodesWithTag("pdf-page-0").fetchSemanticsNodes().isNotEmpty()
        }
        val document = requireNotNull(recent)
        assertNotEquals(sourceUri.toString(), document.uri)
        assertTrue(File(Uri.parse(document.uri).path!!).isFile)
        assertTrue(file.delete())
        assertEquals(Intent.ACTION_VIEW, compose.activity.intent.action)
        assertEquals(sourceUri, compose.activity.intent.data)
        // ActivityScenario identifies lifecycle callbacks by its original MAIN Intent, while
        // onNewIntent correctly stores VIEW. Restore only the harness Intent after proving the
        // real handoff, then stop/resume to resynchronize its monitor before real recreation.
        instrumentation.runOnMainSync { compose.activity.intent = Intent(requireNotNull(scenarioLaunchIntent)) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        val previousActivity = compose.activity
        compose.activityRule.scenario.recreate()
        assertNotSame(previousActivity, compose.activity)
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag("pdf-page-0").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("close-document").performClick()
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag("pdf-page-0").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(name).performClick()
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag("pdf-page-0").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.onNodeWithTag("reader-document-${document.uri}").assertIsDisplayed()
    }
}
