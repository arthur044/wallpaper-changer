package io.github.arthur044.wallpaperchanger.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream

/** How the share image is encoded. JPEG by default: PNG costs hundreds of ms on a phone. */
enum class ShareFormat(val extension: String, val mimeType: String, val compress: Bitmap.CompressFormat, val quality: Int) {
    JPEG("jpg", "image/jpeg", Bitmap.CompressFormat.JPEG, 95),
    PNG("png", "image/png", Bitmap.CompressFormat.PNG, 100),
}

/**
 * The share image on disk: one file at most, in the app's temporary (cache)
 * folder, never the album cache. Writing the next one deletes the previous
 * one, and opening the app deletes whatever is left ([clear]).
 */
class ShareFiles(private val dir: File) {

    /** Deletes every share file. */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Replaces whatever is there with one new file written by [write]. A new
     * name each time, so a receiving app never shows a stale copy. A failed
     * write leaves nothing behind.
     */
    fun replace(extension: String, write: (OutputStream) -> Unit): File {
        clear()
        dir.mkdirs()
        val file = File(dir, "$NAME_PREFIX${System.currentTimeMillis()}.$extension")
        try {
            file.outputStream().use(write)
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        return file
    }

    /** [bitmap] encoded as [format] into the one share file. */
    fun write(bitmap: Bitmap, format: ShareFormat): File = replace(format.extension) { out ->
        check(bitmap.compress(format.compress, format.quality, out)) { "Could not encode the share image" }
    }

    companion object {
        private const val NAME_PREFIX = "letra-"

        fun forApp(context: Context) = ShareFiles(File(context.cacheDir, SHARE_DIR))
    }
}

/** The folder FileProvider serves (res/xml/share_paths.xml). */
const val SHARE_DIR = "share"

/** The system share sheet for [file], readable by the chosen app only. */
fun shareImageIntent(context: Context, file: File, format: ShareFormat, title: String): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(format.mimeType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The chooser shows a preview and passes the grant on only with a ClipData.
    send.clipData = ClipData.newRawUri(null, uri)
    return Intent.createChooser(send, title)
}
