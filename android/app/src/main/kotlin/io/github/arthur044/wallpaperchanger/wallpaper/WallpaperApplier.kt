package io.github.arthur044.wallpaperchanger.wallpaper

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface ApplyResult {
    data object Applied : ApplyResult

    /** The device has no wallpaper (some TVs, kiosks). Retrying won't help. */
    data object Unsupported : ApplyResult

    /** A device policy forbids changing the wallpaper. Retrying won't help. */
    data object NotAllowed : ApplyResult

    /** The system rejected this attempt; the next track may succeed. */
    data class Failed(val cause: Exception) : ApplyResult
}

/**
 * Sets the home screen, and optionally the lock screen, to a rendered image.
 *
 * Home only leaves the lock screen as it is: when the two share a wallpaper,
 * the system first moves the current one to the lock screen.
 */
class WallpaperApplier(
    private val port: WallpaperPort,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun apply(bitmap: Bitmap, includeLockScreen: Boolean): ApplyResult {
        if (!port.isSupported) return ApplyResult.Unsupported
        if (!port.isAllowed) return ApplyResult.NotAllowed

        val which = if (includeLockScreen) HOME_AND_LOCK else HOME_ONLY
        // The image is already sized for this screen: show all of it, never a
        // zoomed-in part (some launchers want a wider wallpaper and would crop).
        val wholeImage = Rect(0, 0, bitmap.width, bitmap.height)
        return withContext(io) {
            try {
                val id = port.setBitmap(bitmap, wholeImage, which)
                if (id == 0) ApplyResult.Failed(IOException("WallpaperManager rejected the image"))
                else ApplyResult.Applied
            } catch (e: IOException) {
                failed(e)
            } catch (e: SecurityException) {
                failed(e)
            }
        }
    }

    private fun failed(e: Exception): ApplyResult {
        Log.w(TAG, "Could not set the wallpaper", e)
        return ApplyResult.Failed(e)
    }

    companion object {
        const val HOME_ONLY = WallpaperManager.FLAG_SYSTEM
        const val HOME_AND_LOCK = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        private const val TAG = "WallpaperApplier"
    }
}
