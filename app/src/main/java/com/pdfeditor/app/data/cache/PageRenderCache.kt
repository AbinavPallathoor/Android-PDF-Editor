package com.pdfeditor.app.data.cache

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A rendered page of one immutable renderer base. A new base (any page edit) is a new key. */
data class PageRenderKey(
    val session: String,
    val base: String,
    val sourceIndex: Int,
    val widthPx: Int,
    val heightPx: Int,
) {
    internal val fileName get() = "$base-$sourceIndex-${widthPx}x$heightPx.img"
}

/**
 * Disk cache of rendered pages so reopening or switching documents shows pages without
 * re-rendering. Images are lossless, so a hit is pixel-identical to a fresh render. Entries expire
 * [maxAgeMillis] after their last use, and the oldest go first once [maxBytes] is exceeded. Lives in
 * the app's cache directory: the system may also clear it, which only costs a re-render.
 */
class PageRenderCache(
    private val directory: File,
    private val maxAgeMillis: Long = TimeUnit.DAYS.toMillis(10),
    private val maxBytes: Long = 256L * 1024 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val writing = ConcurrentHashMap.newKeySet<File>()

    suspend fun read(key: PageRenderKey): Bitmap? =
        withContext(Dispatchers.IO) {
            val file = fileFor(key)
            if (!file.isFile) return@withContext null
            if (clock() - file.lastModified() > maxAgeMillis) {
                file.delete()
                return@withContext null
            }
            val bitmap =
                runCatching {
                    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                }.getOrNull()
            if (bitmap == null || bitmap.width != key.widthPx || bitmap.height != key.heightPx) {
                bitmap?.recycle()
                file.delete()
                return@withContext null
            }
            file.setLastModified(clock())
            bitmap
        }

    /** [bitmap] must not be recycled or modified while this runs. */
    suspend fun write(
        key: PageRenderKey,
        bitmap: Bitmap,
    ) {
        withContext(Dispatchers.IO) {
            val file = fileFor(key)
            if (file.isFile || !writing.add(file)) return@withContext
            val temporary = File(file.parentFile, ".${file.name}.tmp")
            try {
                check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                val written = temporary.outputStream().use { bitmap.compress(losslessFormat(), 100, it) }
                if (written) temporary.renameTo(file)
            } catch (_: Exception) {
                // A failed write only means this page renders again next time.
            } finally {
                temporary.delete()
                writing.remove(file)
            }
        }
    }

    /** Drop expired entries, then the least recently used until the cache fits [maxBytes]. */
    suspend fun prune() {
        withContext(Dispatchers.IO) {
            val now = clock()
            val live = mutableListOf<File>()
            directory.listFiles()?.forEach { session ->
                session.listFiles()?.forEach { file ->
                    val age = now - file.lastModified()
                    when {
                        // A temporary file may belong to a write in progress; only abandoned ones go.
                        file.name.endsWith(".tmp") -> if (age > TimeUnit.HOURS.toMillis(1)) file.delete()
                        age > maxAgeMillis -> file.delete()
                        else -> live += file
                    }
                }
                if (session.isDirectory && session.list().isNullOrEmpty()) session.delete()
            }
            var total = live.sumOf { it.length() }
            for (file in live.sortedBy { it.lastModified() }) {
                if (total <= maxBytes) break
                total -= file.length()
                file.delete()
            }
        }
    }

    private fun fileFor(key: PageRenderKey) = File(File(directory, key.session), key.fileName)

    private fun losslessFormat() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.PNG
}
