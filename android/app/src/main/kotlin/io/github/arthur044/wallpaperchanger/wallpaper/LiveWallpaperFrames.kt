package io.github.arthur044.wallpaperchanger.wallpaper

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * The latest wallpaper, handed from the sync (which draws it) to the live
 * wallpaper (which shows it), in the same process. Also kept on disk so the
 * live wallpaper has something to show right after a reboot, before the
 * first sync.
 *
 * Saved as raw pixels, not PNG: encoding a full phone screen costs hundreds
 * of milliseconds on every track, and the file is only read back here.
 * Blocking I/O: call [publish] and [current] off the main thread.
 */
class LiveWallpaperFrames(private val file: File) {
    private val latestFrame = MutableStateFlow<Bitmap?>(null)

    /** The newest frame this process has seen; null until the first publish or load. */
    val latest: StateFlow<Bitmap?> = latestFrame.asStateFlow()

    /** Keeps its own copy: the caller may recycle [bitmap] right after. */
    fun publish(bitmap: Bitmap) {
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        latestFrame.value = copy
        save(copy)
    }

    /** The last published frame, from memory or else from disk; null if there never was one. */
    fun current(): Bitmap? {
        latestFrame.value?.let { return it }
        val loaded = load() ?: return null
        latestFrame.compareAndSet(null, loaded)
        return latestFrame.value
    }

    private fun save(bitmap: Bitmap) {
        val pixels = ByteBuffer.allocate(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(pixels)
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            file.parentFile?.mkdirs()
            DataOutputStream(temp.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(bitmap.width)
                out.writeInt(bitmap.height)
                out.write(pixels.array())
            }
            if (!temp.renameTo(file)) throw IOException("rename to ${file.name} failed")
        } catch (e: IOException) {
            // Only costs the image shown right after a reboot; never break the sync.
            Log.w(TAG, "Could not save the live wallpaper frame", e)
            temp.delete()
        }
    }

    private fun load(): Bitmap? {
        if (!file.exists()) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) throw IOException("not a frame file")
                val width = input.readInt()
                val height = input.readInt()
                if (width !in 1..MAX_SIDE || height !in 1..MAX_SIDE) throw IOException("bad size ${width}x$height")
                val bytes = ByteArray(width * height * BYTES_PER_PIXEL)
                input.readFully(bytes)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    .apply { copyPixelsFromBuffer(ByteBuffer.wrap(bytes)) }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Discarding an unreadable live wallpaper frame", e)
            file.delete()
            null
        }
    }

    private companion object {
        const val TAG = "LiveWallpaperFrames"
        const val MAGIC = 0x57504631 // "WPF1"
        const val MAX_SIDE = 8192
        const val BYTES_PER_PIXEL = 4
    }
}
