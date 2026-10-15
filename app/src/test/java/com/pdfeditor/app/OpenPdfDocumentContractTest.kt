package com.pdfeditor.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pdfeditor.app.ui.nav.persistReadPermission
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OpenPdfDocumentContractTest {
    @Test
    fun `read only picker result persists its actual grant without requesting write`() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val uri = Uri.parse("content://documents.example/document/42")
        val resultIntent =
            Intent().setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

        val result = OpenPdfDocumentContract().parseResult(Activity.RESULT_OK, resultIntent)!!
        val hasPersistentRead = persistReadPermission(result.data!!, result.flags, resolver)

        assertTrue(hasPersistentRead)
        val permission = resolver.persistedUriPermissions.single { it.uri == uri }
        assertTrue(permission.isReadPermission)
        assertFalse(permission.isWritePermission)
    }

    @Test
    fun `cancelled picker cannot open an incidental result URI`() {
        val result =
            OpenPdfDocumentContract().parseResult(
                Activity.RESULT_CANCELED,
                Intent().setData(Uri.parse("content://documents.example/42")),
            )

        assertNull(result)
    }
}
