package com.pdfeditor.app.ui.nav

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.pdfeditor.app.PdfEditorApplication
import com.pdfeditor.app.data.cache.migrateImportedDraft
import com.pdfeditor.app.data.describeDocumentLocation
import com.pdfeditor.app.data.hasAllFilesAccess
import com.pdfeditor.app.data.resolveDocumentFile
import com.pdfeditor.app.data.resolveDocumentMetadata
import com.pdfeditor.app.domain.RecentsRepository
import com.pdfeditor.app.ui.home.HomeScreen
import com.pdfeditor.app.ui.home.HomeViewModel
import com.pdfeditor.app.ui.reader.DocumentExport
import com.pdfeditor.app.ui.reader.DocumentSwitchFrame
import com.pdfeditor.app.ui.reader.DocumentSwitchOverlay
import com.pdfeditor.app.ui.reader.ReaderScreen
import com.pdfeditor.app.ui.reader.ReaderViewModel
import com.pdfeditor.app.ui.reader.SourcePageImportDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun PdfEditorNavGraph(
    recentsRepository: RecentsRepository,
    contentResolver: ContentResolver,
    onRequestOpenDocument: (onPicked: (Uri) -> Unit) -> Unit,
    openRequests: Flow<OpenDocumentRequest> = emptyFlow(),
) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val importsDirectory = remember(context) { File(context.filesDir, "imported_documents") }
    val home: HomeViewModel = viewModel(factory = HomeViewModel.Factory(recentsRepository))
    val scope = rememberCoroutineScope()
    var beforeLeave by remember { mutableStateOf<suspend () -> Unit>({}) }
    var openError by remember { mutableStateOf<String?>(null) }
    // Where the last save went; outlives the reader switching to the saved copy.
    var savedNotice by remember { mutableStateOf<String?>(null) }
    var documentSwitch by remember { mutableStateOf<DocumentSwitchFrame?>(null) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(documentSwitch?.image) {
        if (documentSwitch != null) focusManager.clearFocus(force = true)
    }
    val activeEntry by nav.currentBackStackEntryAsState()
    LaunchedEffect(activeEntry) {
        // Back can also leave a reader while its next document is still loading.
        if (nav.previousBackStackEntry == null) documentSwitch = null
    }

    suspend fun open(
        uri: Uri,
        existingRecent: com.pdfeditor.app.domain.RecentDocument? = null,
        origin: Int = OpenOrigin.IN_APP,
    ): Boolean {
        try {
            if (documentSwitch?.uri != uri.toString()) documentSwitch = null
            beforeLeave()
            val opened =
                withContext(Dispatchers.IO) {
                    prepareDurableDocument(
                        contentResolver,
                        uri,
                        importsDirectory,
                        "${context.packageName}.fileprovider",
                        directFileAccess = hasAllFilesAccess(),
                    ).also { opened ->
                        if (opened.uri != uri && isImportedDocumentUri(opened.uri, importsDirectory)) {
                            migrateImportedDraft(uri, opened.uri, File(context.filesDir, "working_copies"))
                        }
                    }
                }
            val metadata = opened.metadata
            val previous = existingRecent ?: recentsRepository.getByUri(opened.uri.toString())
            val doc =
                existingRecent ?: home.recordOpened(
                    opened.uri.toString(),
                    previous?.displayName ?: metadata.displayName,
                    metadata.sizeBytes ?: previous?.fileSizeBytes,
                )
            withContext(Dispatchers.Main.immediate) {
                nav.navigate(
                    ReaderRoute.forDocument(doc.id, opened.uri, doc.displayName, origin),
                ) { popUpTo<HomeRoute> { inclusive = false } }
            }
            return true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            withContext(Dispatchers.Main.immediate) { openError = e.message ?: "Could not open this PDF." }
            return false
        }
    }

    LaunchedEffect(openRequests) { openRequests.collect { request -> open(request.uri, origin = request.origin) } }
    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = nav,
            startDestination = HomeRoute,
            modifier =
                if (documentSwitch != null) {
                    Modifier.clearAndSetSemantics { }.onPreviewKeyEvent { true }
                } else {
                    Modifier
                },
        ) {
            composable<HomeRoute> {
                val recents by home.recents.collectAsState()
                HomeScreen(
                    recents = recents,
                    contentResolver = contentResolver,
                    onOpenDocument = { onRequestOpenDocument { uri -> scope.launch { open(uri) } } },
                    onOpenRecent = { doc -> scope.launch { open(Uri.parse(doc.uri)) } },
                    onRemoveRecent = { home.removeFromRecents(it.id) },
                )
            }
            composable<ReaderRoute>(
                enterTransition = { if (documentSwitch != null) EnterTransition.None else fadeIn(tween(180)) },
                exitTransition = { if (documentSwitch != null) ExitTransition.None else fadeOut(tween(180)) },
            ) { entry ->
                val route = entry.toRoute<ReaderRoute>()
                val recents by home.recents.collectAsState()
                val context = LocalContext.current
                val working = remember { File(context.filesDir, "working_copies").apply { mkdirs() } }
                val model: ReaderViewModel =
                    viewModel(
                        factory =
                            ReaderViewModel.Factory(
                                recentsRepository,
                                route.documentId,
                                route.uri,
                                route.displayName,
                                contentResolver,
                                working,
                                pageBitmapCacheBudgetBytes(context),
                                (context.applicationContext as? PdfEditorApplication)?.pageRenderCache,
                            ),
                    )
                val state by model.uiState.collectAsState()
                SideEffect { beforeLeave = { model.flush() } }
                DisposableEffect(model) { onDispose { beforeLeave = {} } }
                // The export file and exact saved revision survive activity recreation in this VM.
                var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
                var pendingSnapshotPath by rememberSaveable { mutableStateOf<String?>(null) }
                val createCopy =
                    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { destination ->
                        val path = pendingPath
                        val snapshotPath = pendingSnapshotPath
                        pendingPath = null
                        pendingSnapshotPath = null
                        if (destination != null && path != null && snapshotPath != null) {
                            scope.launch {
                                try {
                                    val export =
                                        DocumentExport(
                                            File(path),
                                            kotlinx.serialization.json.Json.decodeFromString(
                                                withContext(Dispatchers.IO) { File(snapshotPath).readText() },
                                            ),
                                        )
                                    model.saveTo(destination, export)
                                    savedNotice = withContext(Dispatchers.IO) { savedMessage(context, destination) }
                                    // Keep the new file reopenable from Recents, then continue in it.
                                    runCatching {
                                        contentResolver.takePersistableUriPermission(
                                            destination,
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                                        )
                                    }
                                    open(destination, origin = route.origin)
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    model.report(e.message ?: "Save failed. Your draft is retained.")
                                }
                            }
                        }
                    }
                var appendPath by rememberSaveable { mutableStateOf<String?>(null) }
                var appendName by rememberSaveable { mutableStateOf("") }
                val importStatus by model.pageImportStatus.collectAsState()
                val appending = importStatus?.let { it.sourcePath == appendPath && it.running } == true
                LaunchedEffect(importStatus, appendPath) {
                    val completed = importStatus
                    if (completed?.succeeded == true && completed.sourcePath == appendPath) {
                        appendPath = null
                        withContext(Dispatchers.IO) { File(completed.sourcePath).delete() }
                    }
                }
                val append =
                    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        if (uri != null) {
                            scope.launch {
                                try {
                                    val (file, name) =
                                        withContext(Dispatchers.IO) {
                                            val target = File.createTempFile("append-", ".pdf", working)
                                            try {
                                                val input =
                                                    contentResolver.openInputStream(uri)
                                                        ?: error("Could not read the selected PDF.")
                                                input.use { from -> target.outputStream().use { from.copyTo(it) } }
                                                target to resolveDocumentMetadata(contentResolver, uri).displayName
                                            } catch (failure: Exception) {
                                                target.delete()
                                                throw failure
                                            }
                                        }
                                    appendName = name
                                    appendPath = file.absolutePath
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    model.report(e.message ?: "Could not open this PDF.")
                                }
                            }
                        }
                    }

                // The live name, so a rename is reflected in suggested save names.
                fun currentName() = (state as? com.pdfeditor.app.ui.reader.ReaderUiState.Ready)?.displayName ?: route.displayName

                suspend fun renameDocumentFile(
                    name: String,
                    file: File,
                ) {
                    val renamed = model.renameFile(name, file) ?: return
                    // The session, draft and Recents row follow the file to its new path.
                    withContext(Dispatchers.IO) {
                        migrateImportedDraft(route.uri, renamed, File(context.filesDir, "working_copies"))
                        MediaScannerConnection.scanFile(context, arrayOf(file.path, renamed.path), null, null)
                    }
                    runCatching { recentsRepository.updateUri(route.documentId, renamed.toString()) }
                    open(renamed, origin = route.origin)
                }

                // A rename waiting on the All files access settings page; finished on return.
                var pendingFileRename by rememberSaveable { mutableStateOf<String?>(null) }
                LifecycleResumeEffect(pendingFileRename) {
                    val name = pendingFileRename
                    if (name != null) {
                        pendingFileRename = null
                        scope.launch {
                            val file = withContext(Dispatchers.IO) { resolveDocumentFile(contentResolver, route.uri) }
                            if (hasAllFilesAccess() && file != null) {
                                renameDocumentFile(name, file)
                            } else {
                                model.report("Renamed in the app only. Allow All files access to rename the file itself.")
                            }
                        }
                    }
                    onPauseOrDispose {}
                }

                fun save(copy: Boolean) {
                    scope.launch {
                        try {
                            val export = model.export()
                            if (copy) {
                                pendingPath = export.file.absolutePath
                                val snapshotFile = File(export.file.parentFile, export.file.name + ".json")
                                withContext(Dispatchers.IO) {
                                    snapshotFile.writeText(
                                        kotlinx.serialization.json.Json.encodeToString(
                                            com.pdfeditor.app.domain.DocumentSnapshot.serializer(),
                                            export.snapshot,
                                        ),
                                    )
                                }
                                pendingSnapshotPath = snapshotFile.absolutePath
                                withContext(
                                    Dispatchers.Main.immediate,
                                ) { createCopy.launch("${currentName().removeSuffix(".pdf")}-edited.pdf") }
                            } else {
                                model.saveTo(route.uri, export)
                                savedNotice = withContext(Dispatchers.IO) { savedMessage(context, route.uri) }
                            }
                        } catch (e: Exception) {
                            model.report(e.message ?: "Could not save this PDF. Your draft is retained.")
                        }
                    }
                }
                val readerLayer = rememberGraphicsLayer()
                ReaderScreen(
                    uiState = state,
                    modifier =
                        Modifier.testTag("reader-document-${route.uri}").drawWithContent {
                            readerLayer.record { this@drawWithContent.drawContent() }
                            drawLayer(readerLayer)
                        },
                    onContentReady = {
                        documentSwitch?.takeIf { it.uri == route.uri.toString() && !it.ready }?.let {
                            documentSwitch = it.copy(ready = true)
                        }
                    },
                    adjacentDocument = { forward ->
                        val index = recents.indexOfFirst { it.uri == route.uri.toString() }
                        if (index < 0 || recents.size < 2) null else recents[(index + if (forward) 1 else -1).mod(recents.size)]
                    },
                    onSwitchDocument = { target ->
                        if (documentSwitch != null) {
                            false
                        } else {
                            val frame =
                                try {
                                    readerLayer.toImageBitmap()
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    null
                                }
                            documentSwitch = frame?.let { DocumentSwitchFrame(target.uri, it) }
                            val opened = open(Uri.parse(target.uri), existingRecent = target, origin = route.origin)
                            if (!opened) documentSwitch = null
                            opened
                        }
                    },
                    renderPage = model::renderPage,
                    loadText = model::text,
                    loadFillTargets = model::fillTargets,
                    cachedPage = model::cachedPage,
                    onPrefetchPreviews = model::prefetchPreviews,
                    renderRegion = model::renderRegion,
                    onPositionChanged = model::updateReadingPosition,
                    onClose = {
                        scope.launch {
                            model.flush()
                            withContext(Dispatchers.Main.immediate) {
                                // A PDF opened from another app returns there, not to Home.
                                when (route.origin) {
                                    OpenOrigin.LAUNCHED_APP -> activity?.finish() ?: nav.popBackStack()
                                    OpenOrigin.BROUGHT_FORWARD -> {
                                        nav.popBackStack()
                                        activity?.moveTaskToBack(true)
                                    }
                                    else -> nav.popBackStack()
                                }
                            }
                        }
                    },
                    onShare = {
                        scope.launch {
                            try {
                                val exported = model.export()
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", exported.file)
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "application/pdf"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            clipData = android.content.ClipData.newRawUri("PDF", uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        },
                                        null,
                                    ),
                                )
                            } catch (e: Exception) {
                                model.report(e.message ?: "Could not share this PDF.")
                            }
                        }
                    },
                    onSave = ::save,
                    onDiscardChanges = { model.discardChanges() },
                    onAddAnnotation = model::addAnnotation,
                    onEditText = model::editText,
                    onReplaceAnnotation = model::replaceAnnotation,
                    onRemoveAnnotation = model::removeAnnotation,
                    onRemoveAnnotations = model::removeAnnotations,
                    onUndo = model::undo, onRedo = model::redo,
                    onReorderPages = model::reorderPages,
                    onDeletePage = { model.deletePages(setOf(it)) },
                    onAppendRequested = { append.launch(arrayOf("application/pdf")) },
                    onToggleInvert = model::toggleInvert, onToggleLayout = model::toggleLayout,
                    onRename = { name ->
                        scope.launch {
                            val file = withContext(Dispatchers.IO) { resolveDocumentFile(contentResolver, route.uri) }
                            when {
                                file == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> model.rename(name)
                                hasAllFilesAccess() -> renameDocumentFile(name, file)
                                else -> {
                                    // Show the new name now; the file follows once access is granted.
                                    model.rename(name)
                                    pendingFileRename = name
                                    requestAllFilesAccess(context)
                                }
                            }
                        }
                    },
                    onCompress = {
                        scope.launch {
                            try {
                                val source = model.export()
                                val result =
                                    com.pdfeditor.app.data.pdf.PdfBoxCompressor().compress(
                                        source.file,
                                        com.pdfeditor.app.domain.CompressionPreset.MEDIUM,
                                    )
                                val snapshotFile = File(result.output.parentFile, result.output.name + ".json")
                                withContext(Dispatchers.IO) {
                                    snapshotFile.writeText(
                                        kotlinx.serialization.json.Json.encodeToString(
                                            com.pdfeditor.app.domain.DocumentSnapshot.serializer(),
                                            source.snapshot,
                                        ),
                                    )
                                }
                                pendingPath = result.output.absolutePath
                                pendingSnapshotPath = snapshotFile.absolutePath
                                withContext(
                                    Dispatchers.Main.immediate,
                                ) { createCopy.launch("${currentName().removeSuffix(".pdf")}-compressed.pdf") }
                            } catch (e: Exception) {
                                model.report(e.message ?: "Could not compress this PDF.")
                            }
                        }
                    },
                )
                appendPath?.let { path ->
                    SourcePageImportDialog(
                        file = File(path),
                        displayName = appendName,
                        busy = appending,
                        importError = importStatus?.takeIf { it.sourcePath == path && !it.running }?.error,
                        onCancel = {
                            appendPath = null
                            scope.launch(Dispatchers.IO) { File(path).delete() }
                        },
                        onConfirm = { indices -> model.appendPages(File(path), indices) },
                    )
                }
            }
        }
        documentSwitch?.let { frame ->
            DocumentSwitchOverlay(frame) {
                if (documentSwitch?.image === frame.image) documentSwitch = null
            }
        }
        savedNotice?.let { notice ->
            LaunchedEffect(notice) {
                kotlinx.coroutines.delay(SAVED_NOTICE_MILLIS)
                if (savedNotice == notice) savedNotice = null
            }
            SavedNotice(notice, onDismiss = { savedNotice = null }, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
    openError?.let {
            message ->
        AlertDialog(onDismissRequest = {
            openError = null
        }, title = {
            Text("Could not open PDF")
        }, text = { Text(message) }, confirmButton = { TextButton(onClick = { openError = null }) { Text("OK") } })
    }
}

private fun pageBitmapCacheBudgetBytes(context: Context): Int {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    return manager.memoryClass * 1024 * 1024 / 6
}

private const val SAVED_NOTICE_MILLIS = 6000L

/** "Saved “name.pdf” to Internal storage › Download", or just the name when the folder is unknown. */
private fun savedMessage(
    context: Context,
    uri: Uri,
): String {
    val name = resolveDocumentMetadata(context.contentResolver, uri).displayName
    val folder = describeDocumentLocation(context.packageManager, uri)
    return if (folder != null) "Saved “$name” to $folder" else "Saved “$name”"
}

@Composable
private fun SavedNotice(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 88.dp)
            .widthIn(max = 560.dp)
            .background(Color.Black, RoundedCornerShape(16.dp))
            .border(1.dp, Color.White.copy(alpha = .18f), RoundedCornerShape(16.dp))
            .clickable(onClick = onDismiss)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("saved-notice"),
    ) {
        Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Opens the system page where the user grants All files access to this app (Android 11+). */
internal fun requestAllFilesAccess(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val forApp =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(forApp) }.onFailure {
        // Some builds lack the per-app page; fall back to the full list.
        runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
