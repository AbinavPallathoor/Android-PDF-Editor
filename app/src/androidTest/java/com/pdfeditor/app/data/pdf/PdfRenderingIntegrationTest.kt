package com.pdfeditor.app.data.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfeditor.app.domain.DrawStroke
import com.pdfeditor.app.domain.HighlightAnnotation
import com.pdfeditor.app.domain.OpenPdfResult
import com.pdfeditor.app.domain.PdfPoint
import com.pdfeditor.app.domain.PdfRect
import com.pdfeditor.app.domain.SignaturePlacement
import com.pdfeditor.app.domain.TextBoxAnnotation
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Checks base-page raster geometry through real Android PdfRenderer. Android's platform renderer
 * omits Ink annotations; editable app-owned annotations are drawn by the Compose overlay after
 * re-import. Raw AP color counts/images are retained as platform capability evidence, not treated
 * as annotation rendering support. Standard PDF AP interoperability is checked independently.
 */
@RunWith(AndroidJUnit4::class)
class PdfRenderingIntegrationTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(context)
        directory = File(context.cacheDir, "rendering-test-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    // An incorrect crop origin/rotation moves this native black square away from the crop center.
    @Test
    fun baseContentAlignsWithDisplayedCropAtEveryRotation() =
        runBlocking {
            val source = File(directory, "original.pdf")
            val rotations = listOf(0, 90, 180, 270)
            PDDocument().use { document ->
                for (rotation in rotations) {
                    val page =
                        PDPage(PDRectangle(500f, 600f)).apply {
                            cropBox = PDRectangle(20f, 30f, 300f, 400f)
                            this.rotation = rotation
                        }
                    document.addPage(page)
                    PDPageContentStream(document, page).use { stream ->
                        // Native crop center is (20+150,30+200)=(170,230), independent of rotation.
                        stream.setNonStrokingColor(0f)
                        stream.addRect(166f, 226f, 8f, 8f)
                        stream.fill()
                        stream.beginText()
                        stream.setFont(PDType1Font.HELVETICA, 12f)
                        stream.newLineAtOffset(120f, 210f)
                        stream.showText("Original PDF text")
                        stream.endText()
                    }
                }
                document.save(source)
            }
            val annotations =
                rotations.indices.associateWith { index ->
                    listOf(
                        DrawStroke("ink-$index", listOf(PdfPoint(20f, 30f), PdfPoint(70f, 60f)), Color.RED, 5f),
                        TextBoxAnnotation("text-$index", PdfPoint(90f, 100f), "Café\nEditable", 14f, Color.BLACK),
                        SignaturePlacement(
                            "sign-$index",
                            PdfPoint(150f, 160f),
                            listOf(listOf(PdfPoint(0f, 0f), PdfPoint(30f, 15f))),
                            1f,
                            Color.BLUE,
                        ),
                        HighlightAnnotation("highlight-$index", listOf(PdfRect(90f, 190f, 140f, 205f)), 0x66FFFF00),
                    )
                }
            val saved = PdfBoxDocumentContent().writeAnnotations(source, File(directory, "saved.pdf"), annotations)
            val diagnostics =
                File(
                    InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                    "render-diagnostics",
                ).apply { mkdirs() }
            saved.copyTo(File(diagnostics, "saved.pdf"), overwrite = true)
            val reports = mutableListOf<String>()
            val missingBaseContent = mutableListOf<Int>()
            val result = PdfRendererEngine.open(ParcelFileDescriptor.open(saved, ParcelFileDescriptor.MODE_READ_ONLY))
            assertTrue("Android must reopen the saved PDF: $result", result is OpenPdfResult.Success)
            val renderer = (result as OpenPdfResult.Success).renderer
            try {
                assertEquals("Annotation saving must retain all pages", 4, renderer.pageCount)
                for ((index, rotation) in rotations.withIndex()) {
                    val size = renderer.pageSize(index)
                    val expectedWidth = if (rotation == 90 || rotation == 270) 400f else 300f
                    val expectedHeight = if (rotation == 90 || rotation == 270) 300f else 400f
                    assertEquals("Displayed crop width at rotation $rotation", expectedWidth, size.widthPoints, .01f)
                    assertEquals("Displayed crop height at rotation $rotation", expectedHeight, size.heightPoints, .01f)
                    val bitmap = Bitmap.createBitmap((expectedWidth * 2).toInt(), (expectedHeight * 2).toInt(), Bitmap.Config.ARGB_8888)
                    try {
                        renderer.renderPage(index, bitmap)
                        val centerX = if (rotation == 90 || rotation == 270) 400 else 300
                        val centerY = if (rotation == 90 || rotation == 270) 300 else 400
                        val darkNearExpectedSquare =
                            ((centerX - 3)..(centerX + 3)).any { x ->
                                ((centerY - 3)..(centerY + 3)).any { y ->
                                    val pixel = bitmap.getPixel(x, y)
                                    Color.red(pixel) < 50 && Color.green(pixel) < 50 && Color.blue(pixel) < 50
                                }
                            }
                        // Capability diagnostic only: current Android hides native Ink APs.
                        // At x=40, the hand-derived line interpolation gives y=42, in displayed points.
                        val redNearExpectedLine =
                            (76..84).any { x ->
                                (80..88).any { y ->
                                    val pixel = bitmap.getPixel(x, y)
                                    Color.red(pixel) > 200 && Color.green(pixel) < 80 && Color.blue(pixel) < 80
                                }
                            }
                        var redCount = 0
                        var blackCount = 0
                        var blueCount = 0
                        var yellowCount = 0
                        var minRedX = bitmap.width
                        var minRedY = bitmap.height
                        var maxRedX = -1
                        var maxRedY = -1
                        val row = IntArray(bitmap.width)
                        for (y in 0 until bitmap.height) {
                            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                            row.forEachIndexed { x, pixel ->
                                val red = Color.red(pixel)
                                val green = Color.green(pixel)
                                val blue = Color.blue(pixel)
                                if (red > 200 && green < 80 && blue < 80) {
                                    redCount++
                                    minRedX = minOf(minRedX, x)
                                    minRedY = minOf(minRedY, y)
                                    maxRedX = maxOf(maxRedX, x)
                                    maxRedY = maxOf(maxRedY, y)
                                }
                                if (red < 80 && green < 80 && blue < 80) blackCount++
                                if (blue > 200 && red < 80 && green < 80) blueCount++
                                if (red > 200 && green > 200 && blue < 200) yellowCount++
                            }
                        }
                        val redBounds = if (redCount == 0) "none" else "[$minRedX,$minRedY,$maxRedX,$maxRedY]"
                        val report =
                            "rotation=$rotation crop=${size.widthPoints}x${size.heightPoints} " +
                                "red=$redCount redBoundsPx=$redBounds black=$blackCount blue=$blueCount " +
                                "yellow=$yellowCount expectedInk=$redNearExpectedLine expectedBase=$darkNearExpectedSquare"
                        reports += report
                        android.util.Log.i("PdfRenderingTest", report)
                        File(
                            diagnostics,
                            "rotation-$rotation.png",
                        ).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        File(diagnostics, "results.txt").writeText(reports.joinToString("\n"))
                        if (!darkNearExpectedSquare) missingBaseContent += rotation
                    } finally {
                        bitmap.recycle()
                    }
                }
                assertTrue(
                    "Base-page square missing at crop center for rotations $missingBaseContent; ${reports.joinToString(
                        "; ",
                    )}",
                    missingBaseContent.isEmpty(),
                )
            } finally {
                renderer.close()
            }
        }
}
