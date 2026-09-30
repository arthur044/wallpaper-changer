package io.github.arthur044.wallpaperchanger.core.cache

import java.io.File
import java.io.IOException

/**
 * The original cover of each album and its colors, on disk, so a look change
 * redraws an album without downloading the art or quantizing it again.
 *
 * One `<id>.art` and one `<id>.colors` per album (covers of an album never
 * change), named by the album id. Recency is the file's mtime, refreshed on a
 * hit; past [maxBytes] the least recently used go first, never the one just
 * written. Best effort throughout: anything missing, unreadable or damaged just
 * means downloading again. Blocking I/O: call from a background dispatcher.
 */
class AlbumArtStore(
    private val dir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun get(albumId: String): ByteArray? {
        val file = artFile(albumId)
        val bytes = try {
            file.readBytes()
        } catch (e: IOException) {
            return null
        }
        if (bytes.isEmpty()) return null
        file.setLastModified(clock())
        colorsFile(albumId).takeIf { it.exists() }?.setLastModified(clock())
        return bytes
    }

    @Synchronized
    fun put(albumId: String, bytes: ByteArray) {
        val target = artFile(albumId)
        if (!write(target, bytes)) return
        // Colors of an older cover would not describe these bytes.
        colorsFile(albumId).delete()
        trim(keep = target.name)
    }

    /** The stored art turned out not to be an image: forget it and its colors. */
    @Synchronized
    fun discard(albumId: String) {
        artFile(albumId).delete()
        colorsFile(albumId).delete()
    }

    /** The colors kept for [albumId], which work out (and keep) whatever is missing. */
    @Synchronized
    fun colors(albumId: String): AlbumColors {
        val stored = try {
            colorsFile(albumId).takeIf { it.exists() }?.readText()?.let(StoredColors::decode)
        } catch (e: IOException) {
            null
        }
        return AlbumColors(stored) { colors -> saveColors(albumId, colors) }
    }

    @Synchronized
    private fun saveColors(albumId: String, colors: StoredColors) {
        write(colorsFile(albumId), colors.encode().encodeToByteArray())
    }

    // Written aside and renamed in: never half a file.
    private fun write(target: File, bytes: ByteArray): Boolean {
        dir.mkdirs()
        val temp = File(dir, target.name + TEMP_EXTENSION)
        return try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw IOException("rename to ${target.name} failed")
            }
            target.setLastModified(clock())
            true
        } catch (e: IOException) {
            temp.delete()
            false
        }
    }

    private fun trim(keep: String) {
        val files = dir.listFiles { f -> f.name.endsWith(ART_EXTENSION) || f.name.endsWith(COLORS_EXTENSION) }.orEmpty()
        val keepColors = keep.removeSuffix(ART_EXTENSION) + COLORS_EXTENSION
        val entries = files.map { CacheEntry(it.name, it.length(), it.lastModified()) }
        val doomed = evictionOrder(entries, maxBytes).toSet() - setOf(keep, keepColors)
        files.filter { it.name in doomed }.forEach { it.delete() }
    }

    private fun artFile(albumId: String) = File(dir, fileSafeId(albumId) + ART_EXTENSION)

    private fun colorsFile(albumId: String) = File(dir, fileSafeId(albumId) + COLORS_EXTENSION)

    companion object {
        /** A few hundred covers; the album bases have their own, larger budget. */
        const val DEFAULT_MAX_BYTES: Long = 60L * 1024 * 1024
        private const val ART_EXTENSION = ".art"
        private const val COLORS_EXTENSION = ".colors"
        private const val TEMP_EXTENSION = ".tmp"
    }
}
