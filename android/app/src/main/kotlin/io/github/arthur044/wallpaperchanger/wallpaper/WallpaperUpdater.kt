package io.github.arthur044.wallpaperchanger.wallpaper

import io.github.arthur044.wallpaperchanger.core.NowPlaying
import io.github.arthur044.wallpaperchanger.core.config.Settings
import io.github.arthur044.wallpaperchanger.core.render.CanvasSpec
import io.github.arthur044.wallpaperchanger.core.sync.WallpaperBlockedException
import io.github.arthur044.wallpaperchanger.core.sync.WallpaperSink
import io.github.arthur044.wallpaperchanger.render.WallpaperComposer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** The system rejected this image; the next track may succeed. */
class WallpaperNotAppliedException(cause: Exception) : Exception("Wallpaper not applied", cause)

/**
 * The app's [WallpaperSink]: draws the track for the current screen (measured
 * on every call, so rotation or a new display is picked up) and applies it.
 */
class WallpaperUpdater(
    private val composer: WallpaperComposer,
    private val applier: WallpaperApplier,
    private val settings: Flow<Settings>,
    private val canvas: () -> CanvasSpec,
) : WallpaperSink {
    override suspend fun show(nowPlaying: NowPlaying) {
        val current = settings.first()
        val composed = composer.compose(nowPlaying, canvas(), current)
        val result = try {
            applier.apply(composed.bitmap, includeLockScreen = current.syncLockScreen)
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
}
