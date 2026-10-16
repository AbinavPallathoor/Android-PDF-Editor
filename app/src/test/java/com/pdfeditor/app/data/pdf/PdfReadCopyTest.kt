package com.pdfeditor.app.data.pdf

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class PdfReadCopyTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun source() = temporary.newFile("source.pdf").apply { writeText("Immutable source bytes") }

    @Test
    fun `cancelling a running reader removes its full document copy`() =
        runTest {
            val source = source()
            val bytes = source.readBytes()
            val copies = File(temporary.root, "copies")
            val acquired = CompletableDeferred<File>()
            val job =
                launch {
                    withPdfReadCopy(source, copies, Dispatchers.Unconfined) {
                        acquired.complete(it)
                        awaitCancellation()
                    }
                }
            val copy = acquired.await()
            assertTrue(copy.isFile)
            job.cancelAndJoin()
            assertFalse("Cancellation must delete the private PDF snapshot", copy.exists())
            assertArrayEquals(bytes, source.readBytes())
        }

    @Test
    fun `cancelling after IO copies but before caller resumes does not lose ownership`() =
        runTest {
            val source = source()
            val copies = File(temporary.root, "copies")
            val caller = QueueDispatcher()
            val io = QueueDispatcher()
            var read = false
            val job = launch(caller) { withPdfReadCopy(source, copies, io) { read = true } }
            caller.runOne()
            io.runOne()
            assertTrue("A real copy was acquired before cancellation", copies.listFiles().orEmpty().single().isFile)
            job.cancel()
            repeat(10) {
                caller.runOne()
                io.runOne()
            }
            assertTrue(job.isCompleted)
            assertFalse(read)
            assertTrue(
                "The acquired copy must be deleted even if its dispatcher result was canceled",
                copies.listFiles().orEmpty().isEmpty(),
            )
        }

    @Test
    fun `reader failures delete the copy and retain original bytes`() =
        runTest {
            val source = source()
            val bytes = source.readBytes()
            val copies = File(temporary.root, "copies")
            try {
                withPdfReadCopy(source, copies) { throw IOException("Read failed") }
                throw AssertionError("Expected read failure")
            } catch (_: IOException) {
                assertTrue(copies.listFiles().orEmpty().isEmpty())
                assertArrayEquals(bytes, source.readBytes())
            }
        }

    private class QueueDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            tasks.addLast(block)
        }

        fun runOne() {
            if (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }
}
