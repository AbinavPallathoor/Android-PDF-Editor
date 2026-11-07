package com.pdfeditor.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import com.pdfeditor.app.data.hasAllFilesAccess
import com.pdfeditor.app.ui.nav.OpenDocumentRequest
import com.pdfeditor.app.ui.nav.OpenOrigin
import com.pdfeditor.app.ui.nav.PdfEditorNavGraph
import com.pdfeditor.app.ui.nav.parsePdfOpenRequest
import com.pdfeditor.app.ui.nav.persistReadPermission
import com.pdfeditor.app.ui.nav.requestAllFilesAccess
import com.pdfeditor.app.ui.theme.PdfEditorTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {
    private var pendingPickCallback: ((Uri) -> Unit)? = null
    private val pendingOpenRequests = Channel<OpenDocumentRequest>(Channel.UNLIMITED)
    private val restorableOpenRequests = mutableListOf<OpenDocumentRequest>()
    internal val openRequests =
        pendingOpenRequests.receiveAsFlow().onEach { request -> restorableOpenRequests.remove(request) }

    private val openDocumentLauncher =
        registerForActivityResult(OpenPdfDocumentContract()) { result ->
            val callback = pendingPickCallback
            pendingPickCallback = null
            result?.data?.let { uri ->
                persistReadPermission(uri, result.flags, contentResolver)
                if (callback != null) {
                    callback.invoke(
                        uri,
                    )
                } else {
                    enqueueOpenRequest(
                        OpenDocumentRequest(
                            uri,
                            contentResolver.persistedUriPermissions.any {
                                it.uri == uri && it.isReadPermission
                            },
                        ),
                    )
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Navigation restores its own document after recreation. Replaying the launch Intent
        // would open another reader and discard that restored navigation/reading state.
        if (savedInstanceState == null) {
            askForAllFilesAccessOnce()
            enqueueOpenIntent(intent, OpenOrigin.LAUNCHED_APP)
        } else {
            val persistedReads = savedInstanceState.getBooleanArray(PENDING_PERSISTED_READS)
            val origins = savedInstanceState.getIntArray(PENDING_ORIGINS)
            savedInstanceState.getStringArrayList(PENDING_OPEN_URIS)?.forEachIndexed { index, uri ->
                enqueueOpenRequest(
                    OpenDocumentRequest(
                        Uri.parse(uri),
                        persistedReads?.getOrNull(index) ?: false,
                        origins?.getOrNull(index) ?: OpenOrigin.IN_APP,
                    ),
                )
            }
        }

        val app = application as PdfEditorApplication

        setContent {
            PdfEditorTheme {
                PdfEditorNavGraph(
                    recentsRepository = app.recentsRepository,
                    contentResolver = contentResolver,
                    openRequests = openRequests,
                    onRequestOpenDocument = { onPicked ->
                        pendingPickCallback = onPicked
                        openDocumentLauncher.launch(Unit)
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        enqueueOpenIntent(intent, OpenOrigin.BROUGHT_FORWARD)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(PENDING_OPEN_URIS, ArrayList(restorableOpenRequests.map { it.uri.toString() }))
        outState.putBooleanArray(PENDING_PERSISTED_READS, restorableOpenRequests.map { it.hasPersistentRead }.toBooleanArray())
        outState.putIntArray(PENDING_ORIGINS, restorableOpenRequests.map { it.origin }.toIntArray())
        super.onSaveInstanceState(outState)
    }

    /**
     * On the first launcher start, go straight to the system All files access page, which lets
     * rename change the file itself. Asked once; rename asks again directly if still not granted.
     * Never interrupts a PDF opened from another app.
     */
    private fun askForAllFilesAccessOnce() {
        if (intent?.action != Intent.ACTION_MAIN || hasAllFilesAccess()) return
        val preferences = getSharedPreferences(PERMISSION_PREFERENCES, MODE_PRIVATE)
        if (preferences.getBoolean(ASKED_ALL_FILES_ACCESS, false)) return
        preferences.edit().putBoolean(ASKED_ALL_FILES_ACCESS, true).apply()
        requestAllFilesAccess(this)
    }

    private fun enqueueOpenIntent(
        intent: Intent,
        origin: Int,
    ) {
        parsePdfOpenRequest(intent, contentResolver, origin)?.let(::enqueueOpenRequest)
    }

    private fun enqueueOpenRequest(request: OpenDocumentRequest) {
        restorableOpenRequests.add(request)
        pendingOpenRequests.trySend(request)
    }

    private companion object {
        const val PENDING_OPEN_URIS = "pendingOpenUris"
        const val PENDING_PERSISTED_READS = "pendingPersistedReads"
        const val PENDING_ORIGINS = "pendingOrigins"
        const val PERMISSION_PREFERENCES = "permissions"
        const val ASKED_ALL_FILES_ACCESS = "askedAllFilesAccess"
    }
}

/** Keep the returned grant flags, which the Uri-only standard contract discards. */
internal class OpenPdfDocumentContract : ActivityResultContract<Unit, Intent?>() {
    override fun createIntent(
        context: Context,
        input: Unit,
    ): Intent = ActivityResultContracts.OpenDocument().createIntent(context, arrayOf("application/pdf"))

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): Intent? = intent?.takeIf { resultCode == Activity.RESULT_OK && it.data != null }
}
