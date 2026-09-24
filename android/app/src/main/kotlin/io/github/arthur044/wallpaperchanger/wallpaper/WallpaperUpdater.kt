package io.github.arthur044.wallpaperchanger.wallpaper

import android.graphics.Bitmap
import io.github.arthur044.wallpaperchanger.core.NowPlaying
import io.github.arthur044.wallpaperchanger.core.config.Settings
import io.github.arthur044.wallpaperchanger.core.render.CanvasSpec
import io.github.arthur044.wallpaperchanger.core.sync.WallpaperBlockedException
import io.github.arthur044.wallpaperchanger.core.sync.WallpaperSink
import io.github.arthur044.wallpaperchanger.render.WallpaperComposer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** The system rejected this image; the next track may succeed. */
class WallpaperNotAppliedException(cause: Exception) : Exception("Wallpaper not applied", cause)

/**
 * The app's [WallpaperSink]: draws the track for the current screen (measured
 * on every call, so rotation or a new display is picked up) and applies it.
 *
 * With smoothTransition on, the image goes to the app's live wallpaper, which
 * fades into it; the lock screen, if synced, still gets it as a static image.
 * Until the live wallpaper is picked, it is applied statically as well, so
 * the screen never stops following the music.
 */
class WallpaperUpdater(
    private val composer: WallpaperComposer,
    private val applier: WallpaperApplier,
    private val settings: Flow<Settings>,
    private val live: LiveWallpaper? = null,
    private val canvas: () -> CanvasSpec,
) : WallpaperSink {
    override suspend fun show(nowPlaying: NowPlaying) {
        val current = settings.first()
        val composed = composer.compose(nowPlaying, canvas(), current)
        val result = try {
            if (current.smoothTransition && live != null) {
                showLive(live, composed.bitmap, current)
            } else {
                applier.apply(composed.bitmap, includeLockScreen = current.syncLockScreen)
            }
        } finally {
            composed.bitmap.recycle()
        }
        when (result) {
            ApplyResult.Applied -> Unit
            ApplyResult.Unsupported -> throw WallpaperBlockedException("This device has no wallpaper")
            ApplyResult.NotAllowed -> throw WallpaperBlockedException("A device policy forbids changing the wallpaper")
            is ApplyResult.Failed -> throw WallpaperNotAppliedException(result.cause)
        }
    }

    private suspend fun showLive(live: LiveWallpaper, bitmap: Bitmap, current: Settings): ApplyResult {
        withContext(Dispatchers.IO) { live.frames.publish(bitmap) }
        return when {
            !live.status.isActive() -> applier.apply(bitmap, includeLockScreen = current.syncLockScreen)
            current.syncLockScreen -> applier.applyToLockScreenOnly(bitmap)
            else -> ApplyResult.Applied
        }
    }
}
