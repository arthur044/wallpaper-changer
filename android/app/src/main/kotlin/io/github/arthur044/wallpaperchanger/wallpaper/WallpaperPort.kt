package io.github.arthur044.wallpaperchanger.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect

/** The slice of [WallpaperManager] that [WallpaperApplier] uses; faked in tests. */
interface WallpaperPort {
    val isSupported: Boolean
    val isAllowed: Boolean

    /** Blocking. @return the new wallpaper's id, or 0 if the system refused it. */
    fun setBitmap(bitmap: Bitmap, visibleCropHint: Rect, which: Int): Int
}

class SystemWallpaperPort(context: Context) : WallpaperPort {
    private val manager = WallpaperManager.getInstance(context)

    override val isSupported: Boolean get() = manager.isWallpaperSupported
    override val isAllowed: Boolean get() = manager.isSetWallpaperAllowed

    // allowBackup = false: a wallpaper of whatever was playing is not worth restoring.
    override fun setBitmap(bitmap: Bitmap, visibleCropHint: Rect, which: Int): Int =
        manager.setBitmap(bitmap, visibleCropHint, false, which)
}
