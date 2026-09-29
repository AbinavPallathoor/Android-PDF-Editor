package com.pdfeditor.app.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomRecentsRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RoomRecentsRepository
    private var clock = 1_000L

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = RoomRecentsRepository(database.recentDocumentDao(), clockMillis = { clock })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `recordOpened inserts a new row on first open`() =
        runTest {
            val doc = repository.recordOpened("content://a", "a.pdf", 1234L)

            assertEquals("a.pdf", doc.displayName)
            assertEquals(0, doc.lastPageIndex)
            assertEquals(1_000L, doc.lastOpenedAtEpochMs)
            assertEquals(1, repository.observeRecents().first().size)
        }

    @Test
    fun `recordOpened updates lastOpenedAt without creating a duplicate row`() =
        runTest {
            repository.recordOpened("content://a", "a.pdf", 1234L)
            clock = 2_000L
            repository.recordOpened("content://a", "a-renamed.pdf", 5678L)

            val all = repository.observeRecents().first()
            assertEquals(1, all.size)
            assertEquals("a-renamed.pdf", all.single().displayName)
            assertEquals(2_000L, all.single().lastOpenedAtEpochMs)
        }

    @Test
    fun `most recently opened document sorts first`() =
        runTest {
            repository.recordOpened("content://a", "a.pdf", null)
            clock = 2_000L
            repository.recordOpened("content://b", "b.pdf", null)

            val all = repository.observeRecents().first()
            assertEquals("b.pdf", all.first().displayName)
        }

    @Test
    fun `pinned documents sort before unpinned regardless of recency`() =
        runTest {
            val a = repository.recordOpened("content://a", "a.pdf", null)
            clock = 2_000L
            repository.recordOpened("content://b", "b.pdf", null)

            repository.setPinned(a.id, true)

            val all = repository.observeRecents().first()
            assertEquals("a.pdf", all.first().displayName)
            assertTrue(all.first().isPinned)
        }

    @Test
    fun `updateReadingPosition persists normalized page state`() =
        runTest {
            val doc = repository.recordOpened("content://a", "a.pdf", null)

            repository.updateReadingPosition(doc.id, lastPageIndex = 12, lastZoomScale = 2f, lastScrollFraction = 0.4f)

            val updated = checkNotNull(repository.getByUri("content://a"))
            assertEquals(12, updated.lastPageIndex)
            assertEquals(2f, updated.lastZoomScale)
            assertEquals(0.4f, updated.lastScrollFraction)
        }

    @Test
    fun `remove deletes the row and never touches the source file`() =
        runTest {
            val doc = repository.recordOpened("content://a", "a.pdf", null)

            repository.remove(doc.id)

            assertNull(repository.getByUri("content://a"))
            assertEquals(0, repository.observeRecents().first().size)
        }
}
