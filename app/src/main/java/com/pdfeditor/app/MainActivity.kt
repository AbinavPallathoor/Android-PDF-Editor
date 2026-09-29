package com.pdfeditor.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.pdfeditor.app.ui.nav.PdfEditorNavGraph
import com.pdfeditor.app.ui.theme.PdfEditorTheme

class MainActivity : ComponentActivity() {
    private var pendingPickCallback: ((Uri) -> Unit)? = null

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val callback = pendingPickCallback
            pendingPickCallback = null
            if (uri != null) {
                // Some providers don't support persistable grants; the doc still opens this
                // session, it just won't be safely reopenable later, so we don't add it to
                // Recents when this fails (see HomeViewModel/recordOpened call site).
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                } catch (_: SecurityException) {
                }
                callback?.invoke(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as PdfEditorApplication

        setContent {
            PdfEditorTheme {
                PdfEditorNavGraph(
                    recentsRepository = app.recentsRepository,
                    contentResolver = contentResolver,
                    onRequestOpenDocument = { onPicked ->
                        pendingPickCallback = onPicked
                        openDocumentLauncher.launch(arrayOf("application/pdf"))
                    },
                )
            }
        }
    }
}
