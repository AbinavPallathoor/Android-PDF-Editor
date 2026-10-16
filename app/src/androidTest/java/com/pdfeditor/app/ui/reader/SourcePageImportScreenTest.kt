package com.pdfeditor.app.ui.reader

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SourcePageImportScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun selectedPagesSurviveRecreationAndConfirmOnlyChosenSourcePages() {
        var imported: Set<Int>? = null
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            SourcePageImportContent(
                displayName = "Source.pdf",
                pageAspectRatios = listOf(1.4f, 1.4f, 1.4f),
                renderThumbnail = { _, _, _ -> null },
                onCancel = {},
                onConfirm = { imported = it },
            )
        }
        composeRule.onNodeWithText("Select pages").performClick()
        composeRule.onNodeWithTag("import-confirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("import-source-page-2").performClick()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("Add 1 page").assertExists()
        composeRule.onNodeWithTag("import-source-page-0").performClick()
        composeRule.onNodeWithText("Add 2 pages").performClick()
        assertEquals(linkedSetOf(0, 2), imported)
    }

    @Test fun allPagesImportsTheWholeSourceAndCancelDoesNotImport() {
        var imported: Set<Int>? = null
        var cancellations = 0
        composeRule.setContent {
            SourcePageImportContent(
                displayName = "Source.pdf",
                pageAspectRatios = listOf(1.4f, 1.4f, 1.4f),
                renderThumbnail = { _, _, _ -> null },
                onCancel = { cancellations++ },
                onConfirm = { imported = it },
            )
        }
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancellations)
        assertEquals(null, imported)
        composeRule.onNodeWithText("Add 3 pages").performClick()
        assertEquals(linkedSetOf(0, 1, 2), imported)
    }
}
