package com.pdfeditor.app

import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises real application startup, rather than initializing PDFBox inside the fixture. */
@Config(application = PdfEditorApplication::class)
@RunWith(RobolectricTestRunner::class)
class PdfEditorApplicationTest {
    // Without startup initialization, text-annotation export cannot open its bundled font.
    @Test
    fun applicationStartupMakesBundledAnnotationFontAvailable() {
        ApplicationProvider.getApplicationContext<PdfEditorApplication>()
        val header =
            PDFBoxResourceLoader.getStream("fonts/Roboto-Regular.ttf").use { stream ->
                ByteArray(4).also { bytes ->
                    var offset = 0
                    while (offset < bytes.size) {
                        val count = stream.read(bytes, offset, bytes.size - offset)
                        check(count > 0) { "Bundled annotation font is truncated" }
                        offset += count
                    }
                }
            }
        assertArrayEquals(byteArrayOf(0, 1, 0, 0), header)
    }
}
