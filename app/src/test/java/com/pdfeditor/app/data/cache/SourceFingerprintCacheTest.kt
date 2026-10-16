package com.pdfeditor.app.data.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class SourceFingerprintCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private var now = System.currentTimeMillis()
    private val file get() = File(temporary.root, "source-stamp.json")
    private val cache get() = SourceFingerprintCache(file, clock = { now })

    // Catches trusting a stale hash after the original changed, which would hide outside edits.
    @Test fun reusesHashOnlyForUnchangedMetadata() {
        cache.write(100L, 5_000L, "abc")
        assertEquals("abc", cache.read(100L, 5_000L))
        assertNull(cache.read(101L, 5_000L))
        assertNull(cache.read(100L, 5_001L))
        assertNull(cache.read(null, 5_000L))
    }

    // Catches skipping the hash for providers that don't report a modification time.
    @Test fun missingMetadataIsNeverStamped() {
        cache.write(100L, 5_000L, "abc")
        cache.write(100L, null, "def")
        assertFalse(file.exists())
        assertNull(cache.read(100L, 5_000L))
    }

    // Catches keeping a stamp beyond the ten-day window.
    @Test fun stampsUnusedForTenDaysExpire() {
        cache.write(100L, 5_000L, "abc")
        file.setLastModified(now)
        now += TimeUnit.DAYS.toMillis(9)
        assertEquals("abc", cache.read(100L, 5_000L))
        now += TimeUnit.DAYS.toMillis(10) + 1
        assertNull(cache.read(100L, 5_000L))
    }
}
