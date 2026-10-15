package com.pdfeditor.app.ui.reader

import android.app.Application
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.pdfeditor.app.data.cache.DocumentDraftStore
import com.pdfeditor.app.data.pdf.PdfBoxDocumentContent
import com.pdfeditor.app.domain.DrawStroke
import com.pdfeditor.app.domain.PdfPoint
import com.pdfeditor.app.domain.RecentDocument
import com.pdfeditor.app.domain.RecentsRepository
import com.pdfeditor.app.domain.TextBoxAnnotation
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@Config(application = Application::class, shadows = [SessionPdfRendererShadow::class, SessionPdfPageShadow::class])
@RunWith(RobolectricTestRunner::class)
class ReaderViewModelTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val content = PdfBoxDocumentContent()
    private val stores = mutableListOf<ViewModelStore>()
    private lateinit var application: Application
    private lateinit var workingCopies: File

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        application = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(application)
        workingCopies = temporary.newFolder("working")
    }

    @After
    fun tearDown() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    @Test
    fun `source annotations import on their own pages without changing original bytes`() =
        runTest {
            val source = annotatedFixture()
            val original = source.readBytes()
            val expected = content.readAnnotations(source)
            val vm = open(source)
            val state = ready(vm)

            assertEquals(3, state.pageCount)
            assertEquals(expected, state.annotations.filterValues { it.isNotEmpty() })
            assertFalse(state.dirty)
            assertFalse(state.canUndo)
            assertArrayEquals(original, source.readBytes())
        }

    @Test
    fun `annotations on separate pages save and reopen as editable objects`() =
        runTest {
            val source = fixture()
            val original = source.readBytes()
            val vm = open(source)
            var state = ready(vm)
            val ink = DrawStroke("ink", listOf(PdfPoint(20f, 30f), PdfPoint(70f, 60f)), -16777216, 2f)
            val text = TextBoxAnnotation("text", PdfPoint(40f, 90f), "Still editable", 14f, -16777216)
            state = changed(vm, state) { vm.addAnnotation(state.pageIds[0], ink) }
            state = changed(vm, state) { vm.addAnnotation(state.pageIds[2], text) }
            assertTrue(state.dirty)
            val exported = vm.export()
            val output = File(temporary.root, "saved.pdf")
            vm.saveTo(Uri.fromFile(output), exported)
            assertFalse(ready(vm).dirty)
            assertEquals(mapOf(0 to listOf(ink), 2 to listOf(text)), content.readAnnotations(output))
            assertArrayEquals(original, source.readBytes())
            val recoveredSource = ready(open(source))
            assertEquals(state.pageIds, recoveredSource.pageIds)
            assertEquals(state.annotations, recoveredSource.annotations)
            assertTrue(recoveredSource.canSaveOriginal)

            val reopened = open(output)
            val reopenedState = ready(reopened)
            assertEquals(mapOf(0 to listOf(ink), 2 to listOf(text)), reopenedState.annotations.filterValues { it.isNotEmpty() })
            assertFalse(reopenedState.dirty)
        }

    @Test
    fun `page reorder delete and undo preserve ids annotations and exported page order`() =
        runTest {
            val vm = open(fixture())
            var state = ready(vm)
            val originalIds = state.pageIds
            val ink = DrawStroke("ink", listOf(PdfPoint(20f, 30f), PdfPoint(70f, 60f)), -16777216, 2f)
            state = changed(vm, state) { vm.addAnnotation(originalIds[2], ink) }
            state = changed(vm, state) { vm.reorderPages(listOf(2, 0, 1)) }
            assertEquals(listOf(originalIds[2], originalIds[0], originalIds[1]), state.pageIds)
            assertEquals(listOf(ink), state.annotations[0])
            state = changed(vm, state) { vm.deletePages(setOf(1)) }
            assertEquals(listOf(originalIds[2], originalIds[1]), state.pageIds)
            val deletedExport = vm.export().file
            PDDocument.load(deletedExport).use { pdf ->
                assertEquals(listOf(500f, 400f), pdf.pages.map { it.mediaBox.width })
            }
            assertEquals(mapOf(0 to listOf(ink)), content.readAnnotations(deletedExport))

            state = changed(vm, state) { vm.undo() }
            assertEquals(listOf(originalIds[2], originalIds[0], originalIds[1]), state.pageIds)
            state = changed(vm, state) { vm.undo() }
            assertEquals(originalIds, state.pageIds)
            assertEquals(listOf(ink), state.annotations[2])
            PDDocument.load(vm.export().file).use { pdf ->
                assertEquals(listOf(300f, 400f, 500f), pdf.pages.map { it.mediaBox.width })
            }
        }

    @Test
    fun `a second session recovers dirty page structure and all annotations from draft`() =
        runTest {
            val source = fixture()
            val vm = open(source)
            var state = ready(vm)
            val ids = state.pageIds
            val ink = DrawStroke("ink", listOf(PdfPoint(20f, 30f), PdfPoint(70f, 60f)), -16777216, 2f)
            state = changed(vm, state) { vm.addAnnotation(ids[0], ink) }
            state = changed(vm, state) { vm.reorderPages(listOf(1, 2, 0)) }
            state = changed(vm, state) { vm.deletePages(setOf(0)) }
            val restored = ready(open(source))

            assertEquals(state.pageIds, restored.pageIds)
            assertEquals(state.annotations, restored.annotations)
            assertTrue(restored.dirty)
            assertEquals(2, restored.pageCount)
            assertFalse(restored.canUndo)
        }

    @Test
    fun `clean recovered session reimports an externally replaced original`() =
        runTest {
            val source = fixture()
            assertEquals(3, ready(open(source)).pageCount)
            replaceSource(source, listOf(250f, 350f))

            val reopened = ready(open(source))
            assertEquals(2, reopened.pageCount)
            assertEquals(listOf(250f, 350f), reopened.pageWidthsPoints)
            assertFalse(reopened.dirty)
        }

    @Test
    fun `dirty recovery retains draft when original changed and rejects replacement`() =
        runTest {
            val source = fixture()
            val vm = open(source)
            var state = ready(vm)
            state =
                changed(vm, state) {
                    vm.addAnnotation(state.pageIds[0], DrawStroke("draft", listOf(PdfPoint(10f, 20f), PdfPoint(30f, 40f)), -1, 2f))
                }
            replaceSource(source, listOf(250f, 350f))
            val changedOriginal = source.readBytes()

            val restored = open(source)
            val recovered = ready(restored)
            assertEquals(state.pageIds, recovered.pageIds)
            assertEquals(state.annotations, recovered.annotations)
            assertTrue(recovered.dirty)
            assertTrue(recovered.message.orEmpty().contains("original", ignoreCase = true))
            assertFalse(recovered.canSaveOriginal)
            try {
                restored.saveTo(Uri.fromFile(source), restored.export())
                fail("a changed original must not be overwritten")
            } catch (_: IllegalStateException) {
                assertArrayEquals(changedOriginal, source.readBytes())
                assertTrue(ready(restored).dirty)
            }
        }

    @Test
    fun `dirty draft recovers when original is no longer accessible`() =
        runTest {
            val source = fixture()
            val vm = open(source)
            var state = ready(vm)
            state = changed(vm, state) { vm.deletePages(setOf(1)) }
            assertTrue(source.delete())

            val recovered = ready(open(source))
            assertEquals(state.pageIds, recovered.pageIds)
            assertTrue(recovered.dirty)
            assertFalse(recovered.canSaveOriginal)
            assertTrue(recovered.message.orEmpty().contains("could not be read"))
        }

    @Test
    fun `legacy clean draft reimports source while legacy dirty draft remains recoverable`() =
        runTest {
            val source = fixture()
            val vm = open(source)
            val initial = ready(vm)
            val draftFile = workingCopies.listFiles()!!.single { it.isDirectory }.resolve("draft.json")
            val store = DocumentDraftStore(draftFile)
            store.save(store.load()!!.copy(sourceFingerprint = null))
            replaceSource(source, listOf(200f))
            val clean = ready(open(source))
            assertEquals(1, clean.pageCount)
            assertFalse(clean.dirty)

            changed(vm, initial) { vm.deletePages(setOf(0)) }
            store.save(store.load()!!.copy(sourceFingerprint = null))
            val recovered = ready(open(source))
            assertEquals(2, recovered.pageCount)
            assertTrue(recovered.dirty)
            assertFalse(recovered.canSaveOriginal)
        }

    @Test
    fun `replacement checks original again if it changes while session remains open`() =
        runTest {
            val source = fixture()
            val vm = open(source)
            var state = ready(vm)
            state = changed(vm, state) { vm.deletePages(setOf(1)) }
            replaceSource(source, listOf(220f))
            val external = source.readBytes()
            try {
                vm.saveTo(Uri.fromFile(source), vm.export())
                fail("conflicting original replacement must fail")
            } catch (_: IllegalStateException) {
                assertArrayEquals(external, source.readBytes())
                val failed = ready(vm)
                assertFalse(failed.canSaveOriginal)
                assertTrue(failed.dirty)
                assertEquals(state.pageIds, failed.pageIds)
            }
        }

    @Test
    fun `replacing original updates fingerprint and keeps each earlier backup`() =
        runTest {
            val source = fixture()
            val original = source.readBytes()
            val vm = open(source)
            var state = ready(vm)
            state = changed(vm, state) { vm.deletePages(setOf(1)) }
            vm.saveTo(Uri.fromFile(source), vm.export())
            val firstSave = source.readBytes()
            state = changed(vm, ready(vm)) { vm.deletePages(setOf(1)) }
            vm.saveTo(Uri.fromFile(source), vm.export())

            val recovered = ready(open(source))
            assertEquals(state.pageIds, recovered.pageIds)
            assertFalse(recovered.dirty)
            assertTrue(recovered.canSaveOriginal)
            val backups =
                workingCopies.listFiles()!!.single { it.isDirectory }.listFiles()!!.filter {
                    it.name.startsWith("before-replace-")
                }
            assertEquals(2, backups.size)
            assertTrue(backups.any { it.readBytes().contentEquals(original) })
            assertTrue(backups.any { it.readBytes().contentEquals(firstSave) })
        }

    @Test
    fun `failed explicit save leaves draft dirty and original unchanged`() =
        runTest {
            val source = fixture()
            val original = source.readBytes()
            val vm = open(source)
            var state = ready(vm)
            state =
                changed(vm, state) {
                    vm.addAnnotation(state.pageIds[0], DrawStroke("ink", listOf(PdfPoint(10f, 20f), PdfPoint(30f, 40f)), -1, 2f))
                }
            val exported = vm.export()
            val impossible = File(temporary.root, "missing/destination.pdf")
            try {
                vm.saveTo(Uri.fromFile(impossible), exported)
                fail("save failure must be surfaced")
            } catch (_: java.io.FileNotFoundException) {
                val failed = ready(vm)
                assertTrue(failed.dirty)
                assertFalse(failed.saving)
                assertNotNull(failed.message)
                assertFalse(impossible.exists())
                assertArrayEquals(original, source.readBytes())
                assertTrue(ready(open(source)).dirty)
            }
        }

    private fun open(source: File): ReaderViewModel {
        val vm =
            ReaderViewModel(
                EmptyRecents(),
                1L,
                Uri.fromFile(source),
                source.name,
                application.contentResolver,
                workingCopies,
                8 * 1024 * 1024,
            )
        stores += ViewModelStore().apply { put("reader", vm) }
        return vm
    }

    private suspend fun ready(
        vm: ReaderViewModel,
        before: ReaderUiState.Ready? = null,
    ): ReaderUiState.Ready =
        withContext(Dispatchers.Default) {
            val state =
                withTimeout(15_000L) {
                    vm.uiState.first { state ->
                        state is ReaderUiState.Error || state is ReaderUiState.Ready && state != before
                    }
                }
            check(state is ReaderUiState.Ready) { "Expected ready document; got $state" }
            state
        }

    private suspend fun changed(
        vm: ReaderViewModel,
        before: ReaderUiState.Ready,
        action: () -> Unit,
    ): ReaderUiState.Ready {
        action()
        return ready(vm, before)
    }

    private suspend fun annotatedFixture(): File {
        val target = File(temporary.root, "annotated.pdf")
        return content.writeAnnotations(
            fixture(),
            target,
            mapOf(
                0 to listOf(DrawStroke("imported-ink", listOf(PdfPoint(10f, 20f), PdfPoint(30f, 40f)), -1, 2f)),
                2 to listOf(TextBoxAnnotation("imported-text", PdfPoint(40f, 50f), "Imported", 14f, -16777216)),
            ),
        )
    }

    private fun fixture(): File {
        val file = File(temporary.root, "source-${System.nanoTime()}.pdf")
        PDDocument().use { pdf ->
            listOf(300f, 400f, 500f).forEach { width -> pdf.addPage(PDPage(PDRectangle(width, 600f))) }
            pdf.save(file)
        }
        return file
    }

    private fun replaceSource(
        source: File,
        widths: List<Float>,
    ) {
        PDDocument().use { pdf ->
            widths.forEach { width -> pdf.addPage(PDPage(PDRectangle(width, 600f))) }
            pdf.save(source)
        }
    }

    private class EmptyRecents : RecentsRepository {
        override fun observeRecents(): Flow<List<RecentDocument>> = flowOf(emptyList())

        override suspend fun getByUri(uri: String): RecentDocument? = null

        override suspend fun recordOpened(
            uri: String,
            displayName: String,
            fileSizeBytes: Long?,
        ): RecentDocument = error("Session must not create recents entries without a persisted SAF grant")

        override suspend fun updateReadingPosition(
            id: Long,
            lastPageIndex: Int,
            lastZoomScale: Float,
            lastScrollFraction: Float,
        ) = Unit

        override suspend fun updatePageCount(
            id: Long,
            pageCount: Int,
        ) = Unit

        override suspend fun rename(
            id: Long,
            displayName: String,
        ) = Unit

        override suspend fun remove(id: Long) = Unit

        override suspend fun setPinned(
            id: Long,
            isPinned: Boolean,
        ) = Unit
    }
}

/**
 * SDK 36's native PdfRenderer calls Android-only FileDescriptor methods absent on the host JDK.
 * Only page metadata is shadowed: the session still imports, edits, saves and reopens real PDFs.
 * Rendering behavior belongs in device tests rather than this session persistence test.
 */
@Implements(PdfRenderer::class)
class SessionPdfRendererShadow {
    private var sizes = emptyList<Pair<Int, Int>>()

    @Implementation
    @Suppress("ktlint:standard:function-naming")
    fun __constructor__(descriptor: ParcelFileDescriptor) {
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { it.readBytes() }
        PDDocument.load(bytes).use { pdf ->
            sizes = pdf.pages.map { page -> page.cropBox.width.toInt() to page.cropBox.height.toInt() }
        }
    }

    @Implementation
    fun getPageCount(): Int = sizes.size

    @Implementation
    fun openPage(index: Int): PdfRenderer.Page =
        Shadow.newInstanceOf(PdfRenderer.Page::class.java).also { page ->
            val shadow = Shadow.extract<SessionPdfPageShadow>(page)
            shadow.pageWidth = sizes[index].first
            shadow.pageHeight = sizes[index].second
        }

    @Implementation
    fun close() = Unit
}

@Implements(PdfRenderer.Page::class)
class SessionPdfPageShadow {
    var pageWidth = 0
    var pageHeight = 0

    @Implementation
    fun getWidth(): Int = pageWidth

    @Implementation
    fun getHeight(): Int = pageHeight

    @Implementation
    fun close() = Unit
}
