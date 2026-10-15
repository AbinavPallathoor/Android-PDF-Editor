package com.pdfeditor.app.data.pdf

import android.graphics.Bitmap
import android.graphics.Color
import com.pdfeditor.app.domain.CompressionPreset
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Robolectric provides a working `android.util.Log` and `Bitmap.compress`, which PdfBox-Android's image encoders call into. */
@RunWith(RobolectricTestRunner::class)
class PdfBoxCompressorTest {
    private lateinit var compressor: PdfBoxCompressor
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        compressor = PdfBoxCompressor()
        tempDir =
            File.createTempFile("pdfboxcompressor", "").apply {
                delete()
                mkdirs()
            }
    }

    /** A one-page PDF with a large, noisy (high-entropy, so JPEG can't cheat) embedded bitmap. */
    private fun buildPdfWithImage(name: String): File {
        val file = File(tempDir, name)
        val document = PDDocument()
        try {
            val page = PDPage(PDRectangle(600f, 800f))
            document.addPage(page)
            val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
            val random = java.util.Random(42)
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    bitmap.setPixel(x, y, Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)))
                }
            }
            val image = LosslessFactory.createFromImage(document, bitmap)
            PDPageContentStream(document, page).use { stream ->
                stream.drawImage(image, 0f, 0f, 600f, 800f)
            }
            document.save(file)
        } finally {
            document.close()
        }
        return file
    }

    @Test
    fun compress_shrinksFileAndStaysValid() =
        runTest {
            val source = buildPdfWithImage("photo.pdf")
            val originalSize = source.length()

            val result = compressor.compress(source, CompressionPreset.HIGH)

            assertTrue("compressed file should be smaller", result.compressedSizeBytes < originalSize)
            assertTrue("output file should actually exist on disk", result.output.exists())
            val reloaded = PDDocument.load(result.output)
            try {
                org.junit.Assert.assertEquals(1, reloaded.numberOfPages)
            } finally {
                reloaded.close()
            }
        }
}
