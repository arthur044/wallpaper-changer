package io.github.arthur044.wallpaperchanger.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import io.github.arthur044.wallpaperchanger.core.cache.CacheEntry
import io.github.arthur044.wallpaperchanger.core.cache.evictionOrder
import io.github.arthur044.wallpaperchanger.core.render.Rgb
import java.io.File
import java.io.IOException

class CachedBase(val base: RenderedBase, val sourceArtSidePx: Int)

/**
 * Per-album base images on disk, keyed by
 * [io.github.arthur044.wallpaperchanger.core.cache.baseCacheKey].
 *
 * One PNG per entry, its metadata in the name: `<key>.<RRGGBB>.<artSide>.png`
 * (background color and source art size, needed to redraw the text). Writes go
 * to a temp file and are renamed into place, so an entry is either complete or
 * absent. Recency is the file's mtime, refreshed on every hit, and the
 * directory is trimmed to [maxBytes] after each write.
 *
 * Blocking I/O: call from a background dispatcher.
 */
class AlbumBaseCache(
    private val dir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun get(key: String): CachedBase? {
        val file = entriesFor(key).firstOrNull() ?: return null
        val meta = parseName(file.name)
        val bitmap = meta?.let { BitmapFactory.decodeFile(file.path) }
        if (meta == null || bitmap == null) {
            Log.w(TAG, "Discarding unreadable cached base ${file.name}")
            file.delete()
            return null
        }
        file.setLastModified(clock())
        return CachedBase(RenderedBase(bitmap, meta.background), meta.sourceArtSidePx)
    }

    @Synchronized
    fun put(key: String, base: RenderedBase, sourceArtSidePx: Int) {
        dir.mkdirs()
        val target = File(dir, "$key.${hex(base.background)}.$sourceArtSidePx$EXTENSION")
        val temp = File(dir, "$key$TEMP_EXTENSION")
        try {
            temp.outputStream().use { out ->
                if (!base.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("PNG encode failed")
            }
            entriesFor(key).forEach { it.delete() }
            if (!temp.renameTo(target)) throw IOException("rename to ${target.name} failed")
            target.setLastModified(clock())
        } catch (e: IOException) {
            // A failed write only costs a re-render next time; never break the render.
            Log.w(TAG, "Could not cache base for $key", e)
            temp.delete()
            return
        }
        trim()
    }

    private fun trim() {
        val files = dir.listFiles { f -> f.name.endsWith(EXTENSION) }.orEmpty()
        val entries = files.map { CacheEntry(it.name, it.length(), it.lastModified()) }
        val doomed = evictionOrder(entries, maxBytes).toSet()
        files.filter { it.name in doomed }.forEach { it.delete() }
    }

    private fun entriesFor(key: String): List<File> =
        dir.listFiles { f -> f.name.startsWith("$key.") && f.name.endsWith(EXTENSION) }.orEmpty().toList()

    private data class Meta(val background: Rgb, val sourceArtSidePx: Int)

    // "<key>.<RRGGBB>.<side>.png" -> Meta; keys never contain dots.
    private fun parseName(name: String): Meta? {
        val parts = name.removeSuffix(EXTENSION).split('.')
        if (parts.size != 3) return null
        val color = parts[1].toIntOrNull(16) ?: return null
        val side = parts[2].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return Meta(Rgb.fromArgb(color), side)
    }

    private fun hex(rgb: Rgb) = "%02x%02x%02x".format(rgb.r, rgb.g, rgb.b)

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 150L * 1024 * 1024
        private const val TAG = "AlbumBaseCache"
        private const val EXTENSION = ".png"
        private const val TEMP_EXTENSION = ".tmp"
    }
}
