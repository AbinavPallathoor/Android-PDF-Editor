package com.pdfeditor.app.data.pdf

import android.graphics.Bitmap
import com.pdfeditor.app.domain.CompressionPreset
import com.pdfeditor.app.domain.CompressionResult
import com.pdfeditor.app.domain.PdfCompressor
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Re-encodes every embedded image at a lower quality/resolution - vector text and line art are
 * never touched, so this is always non-destructive to actual page content. Writes to a new file
 * next to [source] rather than overwriting it, so the caller can compare before/after sizes (or
 * discard the result) without having already lost the uncompressed working copy.
 */
class PdfBoxCompressor : PdfCompressor {
    override suspend fun compress(
        source: File,
        preset: CompressionPreset,
    ): CompressionResult =
        withContext(Dispatchers.IO) {
            val originalSize = source.length()
            val settings = settingsFor(preset)
            val output = File(source.parentFile, "${source.nameWithoutExtension}-compressed.pdf")
            val document = PDDocument.load(source)
            try {
                for (page in document.pages) {
                    val resources = page.resources ?: continue
                    val xobjectDict = resources.cosObject.getDictionaryObject(COSName.XOBJECT) as? COSDictionary ?: continue
                    for (name in resources.xObjectNames.toList()) {
                        val xobject = resources.getXObject(name)
                        if (xobject !is PDImageXObject) continue
                        val recompressed = recompress(document, xobject, settings) ?: continue
                        xobjectDict.setItem(name, recompressed.cosObject)
                    }
                }
                document.save(output)
            } finally {
                document.close()
            }
            CompressionResult(
                output = output,
                originalSizeBytes = originalSize,
                compressedSizeBytes = output.length(),
            )
        }

    private fun recompress(
        document: PDDocument,
        original: PDImageXObject,
        settings: CompressionSettings,
    ): PDImageXObject? {
        val bitmap = original.image ?: return null
        val scaled = downscale(bitmap, settings.maxDimensionPx)
        return JPEGFactory.createFromImage(document, scaled, settings.quality)
    }

    private fun downscale(
        bitmap: Bitmap,
        maxDimensionPx: Int,
    ): Bitmap {
        val longestSide = maxOf(bitmap.width, bitmap.height)
        if (longestSide <= maxDimensionPx) return bitmap
        val scale = maxDimensionPx.toFloat() / longestSide
        val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private data class CompressionSettings(val quality: Float, val maxDimensionPx: Int)

    private fun settingsFor(preset: CompressionPreset): CompressionSettings =
        when (preset) {
            // LOW compression: keep quality high, only downscale genuinely oversized images.
            CompressionPreset.LOW -> CompressionSettings(quality = 0.85f, maxDimensionPx = 2400)
            CompressionPreset.MEDIUM -> CompressionSettings(quality = 0.6f, maxDimensionPx = 1600)
            // HIGH compression: smallest file, most visible quality loss.
            CompressionPreset.HIGH -> CompressionSettings(quality = 0.35f, maxDimensionPx = 1000)
        }
}
