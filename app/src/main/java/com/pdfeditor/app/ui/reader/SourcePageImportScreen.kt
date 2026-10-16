package com.pdfeditor.app.ui.reader

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pdfeditor.app.data.pdf.PdfRendererEngine
import com.pdfeditor.app.domain.OpenPdfResult
import com.pdfeditor.app.domain.PdfDocumentRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** The source stays separate from the active document until the user confirms the selection. */
@Composable
internal fun SourcePageImportDialog(
    file: File,
    displayName: String,
    busy: Boolean,
    importError: String? = null,
    onCancel: () -> Unit,
    onConfirm: (Set<Int>) -> Unit,
) {
    var preview by remember(file) { mutableStateOf<ImportPreview?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        var opened: ImportPreview? = null
        try {
            // Transfer ownership before allowing cancellation at dispatcher boundaries.
            // Disposal during native open must still reach finally with a renderer to close.
            withContext(NonCancellable) { opened = ImportPreview.open(file) }
            currentCoroutineContext().ensureActive()
            preview = opened
            awaitCancellation()
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            error = failure.message ?: "Could not open this PDF."
        } finally {
            withContext(NonCancellable) { opened?.close() }
        }
    }
    Dialog(
        onDismissRequest = { if (!busy) onCancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val source = preview
        if (source != null) {
            val renderThumbnail = remember(source) { source::render }
            SourcePageImportContent(
                displayName = displayName,
                pageAspectRatios = source.aspectRatios,
                renderThumbnail = renderThumbnail,
                importError = importError,
                busy = busy,
                onCancel = onCancel,
                onConfirm = onConfirm,
            )
        } else {
            Column(
                Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Add PDF", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text(displayName, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(error ?: "Loading pages…", color = Color.White)
                TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel", color = Color.White) }
            }
        }
    }
}

@Composable
internal fun SourcePageImportContent(
    displayName: String,
    pageAspectRatios: List<Float>,
    renderThumbnail: suspend (Int, Int, Int) -> Bitmap?,
    onCancel: () -> Unit,
    onConfirm: (Set<Int>) -> Unit,
    busy: Boolean = false,
    importError: String? = null,
) {
    var choosing by rememberSaveable(pageAspectRatios.size) { mutableStateOf(false) }
    var selectedPages by rememberSaveable(pageAspectRatios.size) { mutableStateOf(pageAspectRatios.indices.toList()) }
    val selected = selectedPages.toSet()
    Column(
        Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(16.dp).testTag("source-page-import"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Add PDF", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text(displayName, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(
                enabled = !busy,
                onClick = {
                    choosing = false
                    selectedPages = pageAspectRatios.indices.toList()
                },
                modifier = if (!choosing) Modifier.border(1.dp, Color.White) else Modifier,
            ) { Text("All pages", color = Color.White) }
            TextButton(
                enabled = !busy,
                onClick = {
                    if (!choosing) selectedPages = emptyList()
                    choosing = true
                },
                modifier = if (choosing) Modifier.border(1.dp, Color.White) else Modifier,
            ) { Text("Select pages", color = Color.White) }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(pageAspectRatios.size, key = { it }) { index ->
                val ratio = pageAspectRatios[index]
                var bitmap by remember(index) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(index, renderThumbnail) {
                    bitmap = renderThumbnail(index, 240, (240 * ratio).roundToInt().coerceIn(1, 2048))
                }
                Column(
                    Modifier
                        .testTag("import-source-page-$index")
                        .selectable(
                            selected = index in selected,
                            enabled = !busy,
                            role = Role.Checkbox,
                            onClick = {
                                choosing = true
                                selectedPages = (if (index in selected) selected - index else selected + index).sorted()
                            },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(1f / ratio).background(Color.White)
                            .border(if (index in selected) 2.dp else 0.dp, Color.White),
                    ) {
                        bitmap?.let {
                            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        }
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(5.dp).size(24.dp).background(Color.Black)
                                .border(1.dp, Color.White),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (index in selected) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                    Text("${index + 1}", color = Color.White, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        importError?.let { Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel", color = Color.White) }
            Button(
                onClick = { onConfirm(selected.sorted().toSet()) },
                enabled = selected.isNotEmpty() && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                modifier = Modifier.testTag("import-confirm"),
            ) { Text(if (busy) "Adding…" else "Add ${selected.size} ${if (selected.size == 1) "page" else "pages"}") }
        }
    }
}

/** Preview owns a second file, so PDFBox can import the source while thumbnails are visible. */
private class ImportPreview(
    private val copy: File,
    private val renderer: PdfDocumentRenderer,
) {
    private val mutex = Mutex()
    val aspectRatios = (0 until renderer.pageCount).map { renderer.pageSize(it).let { size -> size.heightPoints / size.widthPoints } }

    suspend fun render(
        index: Int,
        width: Int,
        height: Int,
    ): Bitmap? =
        mutex.withLock {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                renderer.renderPage(index, bitmap)
                bitmap
            } catch (failure: Exception) {
                bitmap.recycle()
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                null
            }
        }

    suspend fun close() =
        mutex.withLock {
            renderer.close()
            withContext(Dispatchers.IO) { copy.delete() }
        }

    companion object {
        suspend fun open(source: File): ImportPreview {
            val copy =
                withContext(Dispatchers.IO) {
                    File.createTempFile("import-preview-", ".pdf", source.parentFile)
                }
            try {
                withContext(Dispatchers.IO) { source.copyTo(copy, overwrite = true) }
                val descriptor = withContext(Dispatchers.IO) { ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY) }
                return when (val result = PdfRendererEngine.open(descriptor)) {
                    is OpenPdfResult.Success -> {
                        if (result.renderer.pageCount == 0) {
                            result.renderer.close()
                            error("This PDF has no pages.")
                        }
                        ImportPreview(copy, result.renderer)
                    }
                    OpenPdfResult.PasswordProtected -> error("Password-protected PDFs are not supported yet.")
                    is OpenPdfResult.Failed -> error(result.message)
                }
            } catch (failure: Exception) {
                withContext(NonCancellable + Dispatchers.IO) { copy.delete() }
                throw failure
            }
        }
    }
}
