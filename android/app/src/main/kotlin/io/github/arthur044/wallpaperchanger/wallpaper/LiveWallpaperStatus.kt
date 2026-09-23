package io.github.arthur044.wallpaperchanger.wallpaper

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context

/** Whether the home screen currently shows this app's live wallpaper. */
fun interface LiveWallpaperStatus {
    fun isActive(): Boolean
}

class SystemLiveWallpaperStatus(private val context: Context) : LiveWallpaperStatus {
    private val ours = ComponentName(context, LiveWallpaperService::class.java)

    // A failed check reads as "not active": the static path still updates the screen.
    override fun isActive(): Boolean =
        runCatching { WallpaperManager.getInstance(context).wallpaperInfo?.component == ours }.getOrDefault(false)
}

/** The live wallpaper's two sides, as the updater needs them. */
class LiveWallpaper(val frames: LiveWallpaperFrames, val status: LiveWallpaperStatus)
