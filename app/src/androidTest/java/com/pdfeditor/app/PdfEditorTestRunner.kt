package com.pdfeditor.app

import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * Marks the first-launch All files access request as already asked, so UI tests that start the
 * app from the launcher stay in the app instead of opening the system settings page.
 */
class PdfEditorTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        targetContext.getSharedPreferences("permissions", Context.MODE_PRIVATE)
            .edit().putBoolean("askedAllFilesAccess", true).commit()
    }
}
