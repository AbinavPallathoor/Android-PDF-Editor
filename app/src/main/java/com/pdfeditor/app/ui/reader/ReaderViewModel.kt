package com.pdfeditor.app.ui.reader

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.collection.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pdfeditor.app.data.cache.DocumentDraft
import com.pdfeditor.app.data.cache.DocumentDraftStore
import com.pdfeditor.app.data.cache.documentSessionDirectory
import com.pdfeditor.app.data.fonts.FontRepository
import com.pdfeditor.app.data.pdf.PdfBoxCompressor
import com.pdfeditor.app.data.pdf.PdfBoxDocumentContent
import com.pdfeditor.app.data.pdf.PdfBoxFillTargetDetector
import com.pdfeditor.app.data.pdf.PdfRendererEngine
import com.pdfeditor.app.data.pdf.withPdfReadCopy
import com.pdfeditor.app.domain.Annotation
import com.pdfeditor.app.domain.CompressionPreset
import com.pdfeditor.app.domain.CompressionResult
import com.pdfeditor.app.domain.DocumentEdits
import com.pdfeditor.app.domain.DocumentPage
import com.pdfeditor.app.domain.DocumentSnapshot
import com.pdfeditor.app.domain.OpenPdfResult
import com.pdfeditor.app.domain.PageSize
import com.pdfeditor.app.domain.PdfDocumentRenderer
import com.pdfeditor.app.domain.PdfFillTarget
import com.pdfeditor.app.domain.PdfTextWord
import com.pdfeditor.app.domain.RecentsRepository
import com.pdfeditor.app.domain.TextBoxAnnotation
import com.pdfeditor.app.ui.nav.isImportedDocumentUri
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

sealed interface ReaderUiState {
    data object Loading : ReaderUiState

    data class Ready(
        val pageCount: Int,
        val pageAspectRatios: List<Float>,
        val pageWidthsPoints: List<Float>,
        val initialPage: Int,
        val initialZoom: Float,
        val invertColors: Boolean,
        val documentVersion: Int = 0,
        val displayName: String,
        val pageIds: List<String> = emptyList(),
        val annotations: Map<Int, List<Annotation>> = emptyMap(),
        val dirty: Boolean = false,
        val canUndo: Boolean = false,
        val canRedo: Boolean = false,
        val paged: Boolean = false,
        val saving: Boolean = false,
        val canSaveOriginal: Boolean = false,
        val message: String? = null,
        val initialScrollFraction: Float = 0f,
        val importedCopy: Boolean = false,
    ) : ReaderUiState

    data object PasswordProtected : ReaderUiState

    data class Error(val message: String) : ReaderUiState
}

@Serializable
private data class ReaderPreferences(val invert: Boolean = false, val paged: Boolean = false)

data class DocumentExport(val file: File, val snapshot: DocumentSnapshot)

data class PageImportStatus(val sourcePath: String, val running: Boolean, val succeeded: Boolean = false, val error: String? = null)

/** One recoverable session: the renderer reads an immutable base; edits refer to stable pages. */
class ReaderViewModel(
    private val recentsRepository: RecentsRepository,
    private val documentId: Long,
    private val uri: Uri,
    initialDisplayName: String,
    private val contentResolver: ContentResolver,
    private val workingCopiesDir: File,
    cacheBudgetBytes: Int,
) : ViewModel() {
    private val _uiState = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val uiState: StateFlow<ReaderUiState> = _uiState
    private val _pageImportStatus = MutableStateFlow<PageImportStatus?>(null)
    val pageImportStatus: StateFlow<PageImportStatus?> = _pageImportStatus
    private var pageImportJob: Job? = null
    private val operation = Mutex()
    private val fonts = FontRepository(requireNotNull(workingCopiesDir.parentFile))
    private val content = PdfBoxDocumentContent(fonts)
    private val sessionDir = documentSessionDirectory(workingCopiesDir, uri)
    private val drafts = DocumentDraftStore(File(sessionDir, "draft.json"))
    private var edits: DocumentEdits? = null
    private var base: File? = null
    private var engine: PdfDocumentRenderer? = null
    private var sizes = emptyList<PageSize>()
    private var displayName = initialDisplayName
    private var preferences = ReaderPreferences()
    private var page = 0
    private var zoom = 1f
    private var scrollFraction = 0f
    private var version = 0
    private var saving = false
    private var message: String? = null
    private var writable = false
    private var sourceFingerprint: String? = null
    private var sourceWarning: String? = null
    private var importedCopy = false
    private val cache =
        object : LruCache<String, Bitmap>(cacheBudgetBytes) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ) = value.byteCount
        }
    private val textCache = object : LruCache<String, List<PdfTextWord>>(12) {}
    private val fillCache = object : LruCache<String, List<PdfFillTarget>>(12) {}

    init {
        launchOperation {
            withContext(Dispatchers.IO) {
                sessionDir.mkdirs()
                importedCopy = isImportedDocumentUri(uri, File(workingCopiesDir.parentFile, "imported_documents"))
            }
            preferences =
                withContext(Dispatchers.IO) {
                    File(sessionDir, "preferences.json").takeIf {
                        it.exists()
                    }?.let { runCatching { Json.decodeFromString<ReaderPreferences>(it.readText()) }.getOrNull() } ?: ReaderPreferences()
                }
            val recovered = drafts.load()
            val observedFingerprint =
                withContext(Dispatchers.IO) {
                    runCatching { contentResolver.openInputStream(uri)?.use(::fingerprint) }.getOrNull()
                }
            val recoveredEdits = recovered?.let { DocumentEdits(it.current, it.saved) }
            val reimport =
                recoveredEdits == null ||
                    (!recoveredEdits.isDirty && observedFingerprint != null && observedFingerprint != recovered.sourceFingerprint)
            if (!reimport) {
                base =
                    File(sessionDir, recovered.baseFileName).also {
                        require(it.isFile) { "The saved draft's PDF is missing. The original has not been changed." }
                    }
                edits = recoveredEdits
                sourceFingerprint = recovered.sourceFingerprint
                sourceWarning =
                    when {
                        observedFingerprint == null -> "The original PDF could not be read. Your draft is recovered; save a copy."
                        sourceFingerprint == null ->
                            "The original PDF could not be compared with this older draft. Save a copy to preserve both."
                        observedFingerprint != sourceFingerprint ->
                            "The original PDF changed outside this app. Your draft is recovered; save a copy to preserve both."
                        else -> null
                    }
                message = sourceWarning ?: if (recoveredEdits?.isDirty == true) {
                    "Unsaved draft restored. The PDF has not been changed."
                } else {
                    null
                }
            } else {
                val incoming = File(sessionDir, "import-${UUID.randomUUID()}.pdf")
                withContext(Dispatchers.IO) {
                    val input = contentResolver.openInputStream(uri) ?: error("The file provider could not open this PDF.")
                    input.use { from -> incoming.outputStream().use { from.copyTo(it) } }
                    sourceFingerprint = incoming.inputStream().use(::fingerprint)
                }
                val annotations = content.readAnnotations(incoming)
                val cleanBase = File(sessionDir, "base-${UUID.randomUUID()}.pdf")
                content.writeAnnotations(incoming, cleanBase, emptyMap())
                base = cleanBase
                val count = withContext(Dispatchers.IO) { PDDocument.load(cleanBase).use { it.numberOfPages } }
                require(count > 0) { "This PDF has no pages." }
                val pages = List(count) { DocumentPage(UUID.randomUUID().toString(), it) }
                edits = DocumentEdits(DocumentSnapshot(pages, annotations.mapKeys { pages[it.key].id }))
                withContext(Dispatchers.IO) { incoming.delete() }
            }
            val referencedFonts =
                edits!!.current.annotations.values.flatten().filterIsInstance<TextBoxAnnotation>().map { it.fontId }.toSet()
            val needsFonts = withContext(Dispatchers.IO) { referencedFonts.any { !fonts.contains(it) } }
            if (needsFonts) {
                // Draft bases deliberately omit owned appearances; restore original embedded fonts
                // from an independent source snapshot, without replacing any recovered edits.
                val fontSource = File(sessionDir, "font-recovery-${UUID.randomUUID()}.pdf")
                try {
                    withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(uri)?.use { input -> fontSource.outputStream().use { input.copyTo(it) } }
                    }
                    if (fontSource.isFile) content.readAnnotations(fontSource)
                } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    // The editor keeps the draft and offers font import when recovery is impossible.
                } finally {
                    withContext(Dispatchers.IO) { fontSource.delete() }
                }
            }
            openRenderer()
            val recent = recentsRepository.getByUri(uri.toString())
            page = (recent?.lastPageIndex ?: 0).coerceIn(0, edits!!.current.pages.lastIndex)
            zoom = recent?.lastZoomScale?.coerceIn(0.25f, 5f) ?: 1f
            scrollFraction = recent?.lastScrollFraction ?: 0f
            displayName = recent?.displayName ?: displayName
            writable = sourceWarning == null && sourceFingerprint != null && canWriteOriginal()
            persist()
            publish()
        }
    }

    private fun launchOperation(block: suspend () -> Unit) =
        viewModelScope.launch {
            operation.withLock {
                try {
                    block()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    message = e.message ?: "The operation could not be completed. Your draft is retained."
                    if (edits != null && engine != null) {
                        saving = false
                        publish()
                    } else {
                        _uiState.value = ReaderUiState.Error(message!!)
                    }
                }
            }
        }

    private suspend fun openRenderer() {
        engine?.close()
        engine = null
        val pfd = withContext(Dispatchers.IO) { ParcelFileDescriptor.open(base!!, ParcelFileDescriptor.MODE_READ_ONLY) }
        when (val result = PdfRendererEngine.open(pfd)) {
            is OpenPdfResult.Success -> {
                engine = result.renderer
                sizes = List(result.renderer.pageCount) { result.renderer.pageSize(it) }
            }
            OpenPdfResult.PasswordProtected -> error("Password-protected PDFs are not supported yet.")
            is OpenPdfResult.Failed -> error(result.message)
        }
        cache.evictAll()
        textCache.evictAll()
        fillCache.evictAll()
        version++
    }

    private fun publish() {
        val doc = edits ?: return
        val orderedSizes = doc.current.pages.map { sizes[it.sourceIndex] }
        _uiState.value =
            ReaderUiState.Ready(
                pageCount = orderedSizes.size,
                pageAspectRatios = orderedSizes.map { it.heightPoints / it.widthPoints },
                pageWidthsPoints = orderedSizes.map { it.widthPoints },
                initialPage = page.coerceIn(0, orderedSizes.lastIndex), initialZoom = zoom,
                invertColors = preferences.invert, documentVersion = version, displayName = displayName,
                pageIds = doc.current.pages.map { it.id }, annotations = doc.current.pages.indices.associateWith { doc.annotationsAt(it) },
                dirty = doc.isDirty, canUndo = doc.canUndo, canRedo = doc.canRedo, paged = preferences.paged,
                saving = saving, canSaveOriginal = writable, message = message, initialScrollFraction = scrollFraction,
                importedCopy = importedCopy,
            )
    }

    private suspend fun persist() {
        val doc = edits ?: return
        drafts.save(DocumentDraft(base!!.name, doc.current, doc.saved, sourceFingerprint))
    }

    suspend fun renderPage(
        pageIndex: Int,
        widthPx: Int,
        heightPx: Int,
    ): Bitmap? =
        operation.withLock {
            val sourceIndex = edits?.current?.pages?.getOrNull(pageIndex)?.sourceIndex ?: return@withLock null
            if (widthPx <= 0 || heightPx <= 0) return@withLock null
            val key = "$version:$sourceIndex:$widthPx:$heightPx"
            cache.get(key)?.let { return@withLock it }
            val target = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            try {
                engine?.renderPage(sourceIndex, target) ?: return@withLock null
                cache.put(key, target)
                target
            } catch (
                e: Exception,
            ) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                target.recycle()
                null
            }
        }

    suspend fun text(pageIndex: Int): List<PdfTextWord> =
        operation.withLock {
            val sourceIndex = edits?.current?.pages?.getOrNull(pageIndex)?.sourceIndex ?: return@withLock emptyList()
            val key = "$version:$sourceIndex"
            textCache.get(key) ?: run {
                // Text extraction reads a separate immutable snapshot, never the renderer's open file.
                val copy =
                    withContext(Dispatchers.IO) {
                        File.createTempFile("text-", ".pdf", sessionDir).also { base!!.copyTo(it, overwrite = true) }
                    }
                try {
                    content.extractText(copy, sourceIndex).also { textCache.put(key, it) }
                } finally {
                    withContext(Dispatchers.IO) { copy.delete() }
                }
            }
        }

    suspend fun fillTargets(pageIndex: Int): List<PdfFillTarget> =
        operation.withLock {
            val sourceIndex = edits?.current?.pages?.getOrNull(pageIndex)?.sourceIndex ?: return@withLock emptyList()
            val key = "$version:$sourceIndex"
            fillCache.get(key) ?: withPdfReadCopy(base!!, sessionDir) { copy ->
                PdfBoxFillTargetDetector().detect(copy, sourceIndex).also { fillCache.put(key, it) }
            }
        }

    private fun edit(action: (DocumentEdits) -> Unit) =
        launchOperation {
            val doc = edits ?: return@launchOperation
            val oldPages = doc.current.pages
            action(doc)
            message = sourceWarning
            if (doc.current.pages != oldPages) version++
            persist()
            publish()
        }

    fun addAnnotation(
        pageId: String,
        annotation: Annotation,
    ) = edit { it.add(pageId, annotation) }

    fun editText(
        pageId: String,
        annotation: TextBoxAnnotation,
        session: String,
    ) = edit { it.editText(pageId, annotation, session) }

    fun replaceAnnotation(
        pageId: String,
        annotation: Annotation,
    ) = edit { it.replace(pageId, annotation) }

    fun removeAnnotation(
        pageId: String,
        id: String,
    ) = edit { it.remove(pageId, id) }

    fun discardChanges() = edit { it.discardChanges() }

    fun undo() = edit { it.undo() }

    fun redo() = edit { it.redo() }

    fun reorderPages(order: List<Int>) = edit { it.reorder(order) }

    fun deletePages(indices: Set<Int>) = edit { doc -> doc.delete(indices) }

    fun appendPages(files: List<File>) = appendSelections(files.map { it to null })

    /** Source indices are zero based; chosen pages always enter in their original PDF order. */
    fun appendPages(
        file: File,
        selectedPageIndices: Set<Int>,
    ): Job {
        if (_pageImportStatus.value?.running == true) return requireNotNull(pageImportJob)
        val selection = selectedPageIndices.toSet()
        _pageImportStatus.value = PageImportStatus(file.absolutePath, running = true)
        return viewModelScope.launch {
            val succeeded = appendSelections(listOf(file to selection)).await()
            _pageImportStatus.value =
                PageImportStatus(file.absolutePath, running = false, succeeded = succeeded, error = if (succeeded) null else message)
        }.also { pageImportJob = it }
    }

    private fun appendSelections(requests: List<Pair<File, Set<Int>?>>) =
        viewModelScope.async {
            operation.withLock {
                try {
                    appendSelectionsLocked(requests)
                    true
                } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    message = failure.message ?: "Could not add these pages. Your draft is retained."
                    if (edits != null && engine != null) publish() else _uiState.value = ReaderUiState.Error(message!!)
                    false
                }
            }
        }

    private suspend fun appendSelectionsLocked(requests: List<Pair<File, Set<Int>?>>) {
        val doc = edits ?: error("Document is not ready.")
        require(requests.isNotEmpty()) { "Choose a PDF to add." }
        // Validate every source before creating a replacement base or changing history.
        val sources =
            withContext(Dispatchers.IO) {
                requests.map { (file, selection) ->
                    val count = PDDocument.load(file).use { it.numberOfPages }
                    val indices = selection?.sorted() ?: (0 until count).toList()
                    require(indices.isNotEmpty()) { "Select at least one source page." }
                    val invalid = indices.filter { it !in 0 until count }
                    require(invalid.isEmpty()) { "Source page indices are out of range: $invalid." }
                    file to indices
                }
            }
        val oldBase = base!!
        val oldCount = sizes.size
        val imported = mutableMapOf<Int, List<Annotation>>()
        var count = oldCount
        for ((file, indices) in sources) {
            val annotations = content.readAnnotations(file)
            for (index in indices) imported[count++] = annotations[index].orEmpty()
        }
        val next = File(sessionDir, "merge-${UUID.randomUUID()}.pdf")
        val stripped = File(sessionDir, "base-${UUID.randomUUID()}.pdf")
        try {
            withContext(Dispatchers.IO) {
                // PDFBox reads this copy, never the immutable file held by PdfRenderer.
                val readCopy = File.createTempFile("append-base-", ".pdf", sessionDir)
                try {
                    oldBase.copyTo(readCopy, overwrite = true)
                    PDDocument.load(readCopy).use { target ->
                        val merger = PDFMergerUtility()
                        for ((file, indices) in sources) {
                            PDDocument.load(file).use { source ->
                                val selected = indices.toSet()
                                for (index in source.numberOfPages - 1 downTo 0) {
                                    if (index !in selected) source.removePage(index)
                                }
                                merger.appendDocument(target, source)
                            }
                        }
                        target.save(next)
                    }
                } finally {
                    readCopy.delete()
                }
            }
            content.writeAnnotations(next, stripped, emptyMap())
            base = stripped
            try {
                openRenderer()
            } catch (failure: Exception) {
                base = oldBase
                openRenderer()
                throw failure
            }
            val pages = List(count - oldCount) { DocumentPage(UUID.randomUUID().toString(), oldCount + it) }
            doc.append(pages, pages.associate { it.id to imported[it.sourceIndex].orEmpty() })
            message = sourceWarning
            persist()
            publish()
        } finally {
            withContext(Dispatchers.IO) {
                next.delete()
                if (base != stripped) stripped.delete()
            }
        }
    }

    suspend fun export(): DocumentExport =
        operation.withLock {
            val snapshot = edits?.current ?: error("Document is not ready.")
            val reordered =
                withContext(Dispatchers.IO) {
                    val readCopy = File.createTempFile("export-base-", ".pdf", sessionDir)
                    base!!.copyTo(readCopy, overwrite = true)
                    val ordered = File.createTempFile("ordered-", ".pdf", workingCopiesDir)
                    PDDocument.load(readCopy).use { document ->
                        val pages = snapshot.pages.map { document.getPage(it.sourceIndex) }
                        while (document.numberOfPages > 0) document.removePage(0)
                        pages.forEach { document.addPage(it) }
                        document.save(ordered)
                    }
                    readCopy.delete()
                    ordered
                }
            val output = File(workingCopiesDir, "share-${UUID.randomUUID()}.pdf")
            try {
                content.writeAnnotations(
                    reordered,
                    output,
                    snapshot.pages.mapIndexed {
                            index,
                            p,
                        ->
                        index to snapshot.annotations[p.id].orEmpty()
                    }.toMap(),
                )
                withContext(Dispatchers.IO) { PDDocument.load(output).use { require(it.numberOfPages == snapshot.pages.size) } }
                DocumentExport(output, snapshot)
            } finally {
                withContext(Dispatchers.IO) { reordered.delete() }
            }
        }

    suspend fun saveTo(
        destination: Uri,
        export: DocumentExport,
    ) = operation.withLock {
        saving = true
        message = null
        publish()
        try {
            withContext(Dispatchers.IO) {
                // Keep a recoverable original before explicit replacement. SAF replacement is provider-dependent.
                if (destination == uri) {
                    check(writable) { sourceWarning ?: "The original PDF cannot be replaced. Save a copy instead." }
                    val backup = File(sessionDir, "before-replace-${UUID.randomUUID()}.pdf")
                    val input = contentResolver.openInputStream(uri) ?: error("Could not back up the original PDF.")
                    input.use { from -> backup.outputStream().use { from.copyTo(it) } }
                    if (backup.inputStream().use(::fingerprint) != sourceFingerprint) {
                        writable = false
                        sourceWarning = "The original PDF changed outside this app. Your draft is retained; save a copy to preserve both."
                        error(sourceWarning!!)
                    }
                }
                val output = contentResolver.openOutputStream(destination, "wt") ?: error("The file provider did not open a writable file.")
                output.use { to -> export.file.inputStream().use { it.copyTo(to) } }
                val check = File.createTempFile("verify-", ".pdf", sessionDir)
                try {
                    val input =
                        contentResolver.openInputStream(destination)
                            ?: error("The saved PDF could not be reopened. The draft is retained.")
                    input.use { from -> check.outputStream().use { from.copyTo(it) } }
                    PDDocument.load(
                        check,
                    ).use { require(it.numberOfPages == export.snapshot.pages.size) { "The saved PDF could not be verified." } }
                    require(
                        check.inputStream().use(::fingerprint) == export.file.inputStream().use(::fingerprint),
                    ) { "The file provider did not retain the complete saved PDF. Your draft is retained." }
                } finally {
                    check.delete()
                }
                if (destination == uri) sourceFingerprint = export.file.inputStream().use(::fingerprint)
            }
            edits!!.markSaved(export.snapshot)
            persist()
            message = "Saved"
        } catch (e: Exception) {
            message = e.message ?: "Save failed. Your draft is retained."
            throw e
        } finally {
            saving = false
            publish()
        }
    }

    suspend fun flush() = operation.withLock { persist() }

    fun report(message: String) {
        this.message = message
        publish()
    }

    fun toggleInvert() {
        preferences = preferences.copy(invert = !preferences.invert)
        savePreferences()
    }

    fun toggleLayout() {
        preferences = preferences.copy(paged = !preferences.paged)
        savePreferences()
    }

    private fun savePreferences() =
        launchOperation {
            withContext(Dispatchers.IO) {
                val temp = File(sessionDir, "preferences.tmp")
                temp.writeText(Json.encodeToString(preferences))
                check(temp.renameTo(File(sessionDir, "preferences.json"))) { "Could not save view preferences." }
            }
            publish()
        }

    fun updateReadingPosition(
        pageIndex: Int,
        zoomScale: Float,
        fraction: Float,
    ) {
        page = pageIndex
        zoom = zoomScale
        scrollFraction = fraction
        viewModelScope.launch { recentsRepository.updateReadingPosition(documentId, pageIndex, zoomScale, fraction) }
    }

    fun rename(newName: String) =
        launchOperation {
            if (newName.isNotBlank()) {
                displayName = newName.trim()
                recentsRepository.rename(documentId, displayName)
                publish()
            }
        }

    suspend fun compress(preset: CompressionPreset): CompressionResult {
        val exported = export()
        return PdfBoxCompressor().compress(exported.file, preset)
    }

    private fun canWriteOriginal(): Boolean =
        runCatching {
            if (uri.scheme == "file") return@runCatching File(uri.path!!).canWrite()
            val hasGrant = contentResolver.openFileDescriptor(uri, "rw")?.use { true } == true
            if (!hasGrant) return@runCatching false
            // Some non-SAF providers omit document flags; actual write access is still authoritative.
            runCatching {
                contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use {
                    !it.moveToFirst() || it.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0
                } ?: true
            }.getOrDefault(true)
        }.getOrDefault(false)

    private fun fingerprint(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var count = stream.read(buffer)
        while (count >= 0) {
            if (count > 0) digest.update(buffer, 0, count)
            count = stream.read(buffer)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun onCleared() {
        // Render work is serialized and owned by the VM scope; do not race a native in-flight render.
        val current = engine
        engine = null
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            operation.withLock {
                current?.close()
                cache.evictAll()
            }
        }
    }

    class Factory(
        private val recentsRepository: RecentsRepository,
        private val documentId: Long,
        private val uri: Uri,
        private val initialDisplayName: String,
        private val contentResolver: ContentResolver,
        private val workingCopiesDir: File,
        private val cacheBudgetBytes: Int,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ReaderViewModel(
                recentsRepository,
                documentId,
                uri,
                initialDisplayName,
                contentResolver,
                workingCopiesDir,
                cacheBudgetBytes,
            ) as T
    }
}
