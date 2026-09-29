package com.pdfeditor.app.ui.nav

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.pdfeditor.app.data.resolveDocumentMetadata
import com.pdfeditor.app.domain.RecentDocument
import com.pdfeditor.app.domain.RecentsRepository
import com.pdfeditor.app.ui.home.HomeScreen
import com.pdfeditor.app.ui.home.HomeViewModel
import com.pdfeditor.app.ui.reader.ReaderScreen
import com.pdfeditor.app.ui.reader.ReaderViewModel
import kotlinx.coroutines.launch

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
            val readerViewModel: ReaderViewModel =
                viewModel(
                    key = "reader-${route.documentId}",
                    factory =
                        ReaderViewModel.Factory(
                            recentsRepository = recentsRepository,
                            documentId = route.documentId,
                            uri = route.uri,
                            contentResolver = contentResolver,
                            cacheBudgetBytes = cacheBudgetBytes,
                        ),
                )
            val uiState by readerViewModel.uiState.collectAsState()

            ReaderScreen(
                uiState = uiState,
                renderPage = { pageIndex, widthPx, heightPx -> readerViewModel.renderPage(pageIndex, widthPx, heightPx) },
                onPositionChanged = { pageIndex, zoomScale, scrollFraction ->
                    readerViewModel.updateReadingPosition(pageIndex, zoomScale, scrollFraction)
                },
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
