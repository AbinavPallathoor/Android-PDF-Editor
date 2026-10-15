package com.pdfeditor.app.ui.nav

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.pdfeditor.app.data.resolveDocumentMetadata
import com.pdfeditor.app.domain.RecentsRepository
import com.pdfeditor.app.ui.home.HomeScreen
import com.pdfeditor.app.ui.home.HomeViewModel
import com.pdfeditor.app.ui.reader.DocumentExport
import com.pdfeditor.app.ui.reader.ReaderScreen
import com.pdfeditor.app.ui.reader.ReaderViewModel
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
    val home: HomeViewModel = viewModel(factory = HomeViewModel.Factory(recentsRepository))
    val scope = rememberCoroutineScope()
    var beforeLeave by remember { mutableStateOf<suspend () -> Unit>({}) }
    var openError by remember { mutableStateOf<String?>(null) }

    suspend fun open(
        uri: Uri,
        existingRecent: com.pdfeditor.app.domain.RecentDocument? = null,
    ) {
        try {
            beforeLeave()
            val metadata = withContext(Dispatchers.IO) { resolveDocumentMetadata(contentResolver, uri) }
            val durable = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            val doc = existingRecent ?: if (durable) home.recordOpened(uri.toString(), metadata.displayName, metadata.sizeBytes) else null
            val id = doc?.id ?: -uri.toString().hashCode().toLong().let { if (it == 0L) 1 else kotlin.math.abs(it) }
            withContext(Dispatchers.Main.immediate) {
                nav.navigate(
                    ReaderRoute.forDocument(id, uri, doc?.displayName ?: metadata.displayName),
                ) { popUpTo<HomeRoute> { inclusive = false } }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            withContext(Dispatchers.Main.immediate) { openError = e.message ?: "Could not open this PDF." }
        }
    }

    LaunchedEffect(openRequests) { openRequests.collect { request -> open(request.uri) } }
    NavHost(navController = nav, startDestination = HomeRoute) {
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
        composable<ReaderRoute> { entry ->
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
                            } catch (e: Exception) {
                                model.report(e.message ?: "Save failed. Your draft is retained.")
                            }
                        }
                    }
                }
            val append =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        scope.launch {
                            try {
                                val file =
                                    withContext(Dispatchers.IO) {
                                        val target = File.createTempFile("append-", ".pdf", working)
                                        val input = contentResolver.openInputStream(uri) ?: error("Could not read the selected PDF.")
                                        input.use { from -> target.outputStream().use { from.copyTo(it) } }
                                        target
                                    }
                                model.appendPages(listOf(file))
                            } catch (e: Exception) {
                                model.report(e.message ?: "Could not append this PDF.")
                            }
                        }
                    }
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
                            ) { createCopy.launch("${route.displayName.removeSuffix(".pdf")}-edited.pdf") }
                        } else {
                            model.saveTo(route.uri, export)
                        }
                    } catch (e: Exception) {
                        model.report(e.message ?: "Could not save this PDF. Your draft is retained.")
                    }
                }
            }
            ReaderScreen(
                uiState = state,
                adjacentDocument = { forward ->
                    val index = recents.indexOfFirst { it.uri == route.uri.toString() }
                    if (index < 0 || recents.size < 2) null else recents[(index + if (forward) 1 else -1).mod(recents.size)]
                },
                onSwitchDocument = { forward ->
                    val index = recents.indexOfFirst { it.uri == route.uri.toString() }
                    val target = if (index < 0 || recents.size < 2) null else recents[(index + if (forward) 1 else -1).mod(recents.size)]
                    target?.let { scope.launch { open(Uri.parse(it.uri), existingRecent = it) } }
                },
                renderPage = model::renderPage,
                loadText = model::text,
                onPositionChanged = model::updateReadingPosition,
                onClose = {
                    scope.launch {
                        model.flush()
                        withContext(Dispatchers.Main.immediate) { nav.popBackStack() }
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
                onAddAnnotation = model::addAnnotation,
                onEditText = model::editText,
                onReplaceAnnotation = model::replaceAnnotation,
                onRemoveAnnotation = model::removeAnnotation,
                onUndo = model::undo, onRedo = model::redo,
                onReorderPages = model::reorderPages,
                onDeletePage = { model.deletePages(setOf(it)) },
                onAppendRequested = { append.launch(arrayOf("application/pdf")) },
                onToggleInvert = model::toggleInvert, onToggleLayout = model::toggleLayout,
                onRename = model::rename,
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
                            ) { createCopy.launch("${route.displayName.removeSuffix(".pdf")}-compressed.pdf") }
                        } catch (e: Exception) {
                            model.report(e.message ?: "Could not compress this PDF.")
                        }
                    }
                },
            )
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
