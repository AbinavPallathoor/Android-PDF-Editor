package com.pdfeditor.app.data.cache

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit

@Config(application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class PageRenderCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val day = TimeUnit.DAYS.toMillis(1)
    private var now = System.currentTimeMillis()

    // Catches lossy encoding (blurred text) and handing back a page of the wrong size.
    @Test fun storedPageIsPixelIdentical() =
        runBlocking {
            val cache = PageRenderCache(temporary.root, clock = { now })
            val page = page(Color.RED)
            val key = PageRenderKey("session", "base-a", 2, 40, 60)
            cache.write(key, page)
            assertArrayEquals(pixels(page), pixels(cache.read(key)!!))
            assertNull(cache.read(key.copy(widthPx = 41)))
            assertNull(cache.read(key.copy(base = "base-b")))
        }

    // Catches keeping pages forever, or expiring pages that are still being used.
    @Test fun entriesExpireTenDaysAfterLastUse() =
        runBlocking {
            val cache = PageRenderCache(temporary.root, clock = { now })
            val key = PageRenderKey("session", "base", 0, 40, 60)
            cache.write(key, page(Color.BLUE))
            stamp(now)
            now += 9 * day
            assertNotNull(cache.read(key))
            now += 9 * day
            assertNotNull(cache.read(key))
            now += 10 * day + 1
            assertNull(cache.read(key))
            assertTrue(stored().isEmpty())
        }

    // Catches unbounded growth and evicting recently used pages before old ones.
    @Test fun pruneDropsExpiredThenLeastRecentlyUsed() =
        runBlocking {
            val keys = (0..2).map { PageRenderKey("session", "base", it, 40, 60) }
            val writer = PageRenderCache(temporary.root, clock = { now })
            keys.forEachIndexed { index, key -> writer.write(key, page(Color.GREEN + index)) }
            val files = keys.map { File(File(temporary.root, "session"), it.fileName) }
            files[0].setLastModified(now - 11 * day)
            files[1].setLastModified(now - 2 * day)
            files[2].setLastModified(now - day)
            PageRenderCache(temporary.root, maxBytes = files[2].length(), clock = { now }).prune()
            assertEquals(listOf(files[2]), stored())
            assertFalse(files[0].exists())
        }

    private fun stored() = temporary.root.walkTopDown().filter { it.isFile }.toList()

    private fun stamp(time: Long) = stored().forEach { it.setLastModified(time) }

    private fun page(color: Int) =
        Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            for (x in 5 until 30) setPixel(x, 10 + x % 7, color)
        }

    private fun pixels(bitmap: Bitmap) =
        IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
}
