package com.pdfeditor.app.ui.nav

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.pdfeditor.app.data.pdf.PdfBoxAnnotationFlattener
import com.pdfeditor.app.data.resolveDocumentMetadata
import com.pdfeditor.app.domain.RecentDocument
import com.pdfeditor.app.domain.RecentsRepository
import com.pdfeditor.app.ui.home.HomeScreen
import com.pdfeditor.app.ui.home.HomeViewModel
import com.pdfeditor.app.ui.reader.ReaderScreen
import com.pdfeditor.app.ui.reader.ReaderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun PdfEditorNavGraph(
    recentsRepository: RecentsRepository,
    contentResolver: ContentResolver,
    onRequestOpenDocument: (onPicked: (Uri) -> Unit) -> Unit,
) {
    val navController = rememberNavController()
    val homeViewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory(recentsRepository))
    val scope = rememberCoroutineScope()

    NavHost(navController = navController, startDestination = HomeRoute) {
        composable<HomeRoute> {
            val recents by homeViewModel.recents.collectAsState()

            HomeScreen(
                recents = recents,
                onOpenDocument = {
                    onRequestOpenDocument { uri ->
                        scope.launch {
                            val metadata = resolveDocumentMetadata(contentResolver, uri)
                            val doc = homeViewModel.recordOpened(uri.toString(), metadata.displayName, metadata.sizeBytes)
                            navController.navigate(ReaderRoute.forDocument(doc.id, uri, doc.displayName))
                        }
                    }
                },
                onOpenRecent = { doc: RecentDocument ->
                    navController.navigate(ReaderRoute.forDocument(doc.id, Uri.parse(doc.uri), doc.displayName))
                },
                onRemoveRecent = { doc: RecentDocument -> homeViewModel.removeFromRecents(doc.id) },
            )
        }

        composable<ReaderRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ReaderRoute>()
            val context = LocalContext.current
            val cacheBudgetBytes = remember { pageBitmapCacheBudgetBytes(context) }
            val workingCopiesDir = remember { File(context.filesDir, "working_copies") }
            val readerViewModel: ReaderViewModel =
                viewModel(
                    key = "reader-${route.documentId}",
                    factory =
                        ReaderViewModel.Factory(
                            recentsRepository = recentsRepository,
                            documentId = route.documentId,
                            uri = route.uri,
                            contentResolver = contentResolver,
                            workingCopiesDir = workingCopiesDir,
                            cacheBudgetBytes = cacheBudgetBytes,
                        ),
                )
            val uiState by readerViewModel.uiState.collectAsState()

            val flattener = remember { PdfBoxAnnotationFlattener() }
            var pendingExportFile by remember { mutableStateOf<File?>(null) }
            val createDocumentLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { destUri ->
                    val exportFile = pendingExportFile
                    pendingExportFile = null
                    if (destUri != null && exportFile != null) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                context.contentResolver.openOutputStream(destUri)?.use { out ->
                                    exportFile.inputStream().use { it.copyTo(out) }
                                }
                            }
                        }
                    }
                }

            val appendLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { pickedUri ->
                    if (pickedUri != null) {
                        scope.launch {
                            val copied =
                                withContext(Dispatchers.IO) {
                                    val file = File(workingCopiesDir.apply { mkdirs() }, "append-${System.currentTimeMillis()}.pdf")
                                    context.contentResolver.openInputStream(pickedUri)?.use { input ->
                                        file.outputStream().use { output -> input.copyTo(output) }
                                    }
                                    file
                                }
                            readerViewModel.appendPages(listOf(copied))
                        }
                    }
                }

            ReaderScreen(
                uiState = uiState,
                renderPage = { pageIndex, widthPx, heightPx -> readerViewModel.renderPage(pageIndex, widthPx, heightPx) },
                onPositionChanged = { pageIndex, zoomScale, scrollFraction ->
                    readerViewModel.updateReadingPosition(pageIndex, zoomScale, scrollFraction)
                },
                onClose = { navController.popBackStack() },
                onShare = {
                    // If structural edits exist, share the edited working copy instead of the
                    // untouched original - it needs a FileProvider content:// uri since it's a
                    // private-storage file that can't be handed to another app directly.
                    val workingFile = readerViewModel.currentWorkingFile()
                    val shareUri =
                        if (workingFile != null) {
                            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", workingFile)
                        } else {
                            route.uri
                        }
                    val sendIntent =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, shareUri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    context.startActivity(Intent.createChooser(sendIntent, null))
                },
                onSaveCopy = { pageIndex, annotations ->
                    scope.launch {
                        // Never overwrite the original: always flatten into a fresh, dedicated
                        // export file - copied from the edited working copy if structural edits
                        // exist (reorder/delete/append), else from the untouched original - then
                        // let the user pick a brand-new destination via SAF.
                        val exportFile =
                            withContext(Dispatchers.IO) {
                                val source = File(workingCopiesDir.apply { mkdirs() }, "${route.documentId}-export.pdf")
                                val workingFile = readerViewModel.currentWorkingFile()
                                if (workingFile != null) {
                                    workingFile.inputStream().use { input -> source.outputStream().use { output -> input.copyTo(output) } }
                                } else {
                                    context.contentResolver.openInputStream(route.uri)?.use { input ->
                                        source.outputStream().use { output -> input.copyTo(output) }
                                    }
                                }
                                flattener.flattenAnnotations(source, mapOf(pageIndex to annotations))
                            }
                        pendingExportFile = exportFile
                        val baseName = route.displayName.removeSuffix(".pdf")
                        createDocumentLauncher.launch("$baseName-edited.pdf")
                    }
                },
                onReorderPages = { newOrder -> readerViewModel.reorderPages(newOrder) },
                onDeletePage = { pageIndex -> readerViewModel.deletePages(setOf(pageIndex)) },
                onAppendRequested = { appendLauncher.launch(arrayOf("application/pdf")) },
            )
        }
    }
}

/** Roughly a sixth of the app's memory class, per CLAUDE.md's PdfRendererEngine memory-bounds note. */
private fun pageBitmapCacheBudgetBytes(context: Context): Int {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memoryClassMb = activityManager.memoryClass
    return (memoryClassMb * 1024 * 1024) / 6
}
