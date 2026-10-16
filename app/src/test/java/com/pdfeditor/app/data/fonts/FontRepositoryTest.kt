package com.pdfeditor.app.data.fonts

import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

@org.robolectric.annotation.Config(application = android.app.Application::class)
@RunWith(RobolectricTestRunner::class)
class FontRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Before fun setup() = PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())

    @Test fun importIsStableDeduplicatedAndPersistsAcrossRepositoryInstances() {
        val repository = FontRepository(temporary.root)
        val bytes = FontRepository.openBuiltIn("droid-serif").use { it.readBytes() }
        val imported = repository.importFont(ByteArrayInputStream(bytes), "My serif")
        assertTrue(imported.id.matches(Regex("custom-[0-9a-f]{64}")))
        assertEquals(imported, repository.importFont(ByteArrayInputStream(bytes), "Renamed duplicate"))
        val reopened = FontRepository(temporary.root)
        assertEquals(4, reopened.list().size)
        assertEquals(imported, reopened.list().last())
        assertArrayEquals(bytes, reopened.openFont(imported.id).use { it.readBytes() })
        assertNotNull(reopened.typeface(imported.id))
        assertNotNull(reopened.typeface("droid-mono"))
    }

    @Test fun missingOrCorruptMetadataDoesNotPreventExactFontRecovery() {
        val repository = FontRepository(temporary.root)
        val bytes = FontRepository.openBuiltIn("droid-serif").use { it.readBytes() }
        val imported = bytes.inputStream().use { repository.importFont(it, "My serif") }
        val metadata = File(temporary.root, "fonts/${imported.id}.json")
        for (corruption in listOf<String?>(null, "partial json", "{\"id\":\"roboto\",\"displayName\":\"Wrong identity\"}")) {
            if (corruption == null) assertTrue(metadata.delete()) else metadata.writeText(corruption)
            val reopened = FontRepository(temporary.root)
            assertFalse("orphan font must remain eligible for PDF recovery", reopened.contains(imported.id))
            assertFalse(reopened.list().any { it.id == imported.id })
            val restored = bytes.inputStream().use { reopened.recoverFont(imported.id, it, imported.displayName) }
            assertEquals(imported, restored)
            assertTrue(reopened.contains(imported.id))
            assertEquals(imported, reopened.list().last())
            assertArrayEquals(bytes, reopened.openFont(imported.id).use { it.readBytes() })
        }
    }

    @Test fun corruptRegisteredFontIsUnavailableUntilItsExactBytesAreRecovered() {
        val repository = FontRepository(temporary.root)
        val bytes = FontRepository.openBuiltIn("droid-mono").use { it.readBytes() }
        val imported = bytes.inputStream().use { repository.importFont(it, "My mono") }
        assertTrue(repository.contains(imported.id))
        File(temporary.root, "fonts/${imported.id}.ttf").writeText("damaged")
        assertFalse(repository.contains(imported.id))
        bytes.inputStream().use { repository.recoverFont(imported.id, it, imported.displayName) }
        assertTrue(repository.contains(imported.id))
        assertArrayEquals(bytes, repository.openFont(imported.id).use { it.readBytes() })
    }

    @Test fun rejectsInvalidAndOversizeFontsWithoutRegisteringThem() {
        val repository = FontRepository(temporary.root)
        for (input in listOf(
            ByteArrayInputStream("not a font".toByteArray()),
            object : InputStream() {
                private var remaining = FontRepository.MAX_FONT_BYTES + 1

                override fun read(): Int = if (remaining-- > 0) 0 else -1

                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    if (remaining <= 0) return -1
                    val count = minOf(length, remaining)
                    buffer.fill(0, offset, offset + count)
                    remaining -= count
                    return count
                }
            },
        )) {
            try {
                input.use { repository.importFont(it, "Invalid") }
                fail("invalid fonts must not be registered")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().isNotBlank())
            }
        }
        assertEquals(FontRepository.BUILT_INS, repository.list())
    }

    @Test fun recoveryRequiresTheExactOriginalBytesAndMissingFontsFailClearly() {
        val repository = FontRepository(temporary.root)
        val missing = "custom-" + "0".repeat(64)
        try {
            repository.openFont(missing)
            fail("missing fonts must never be substituted")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("missing"))
        }
        try {
            FontRepository.openBuiltIn("roboto").use { repository.recoverFont(missing, it, "Forged") }
            fail("font identity must match its bytes")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("original"))
        }
        assertFalse(repository.contains(missing))
        assertEquals(FontRepository.BUILT_INS, repository.list())
    }
}
