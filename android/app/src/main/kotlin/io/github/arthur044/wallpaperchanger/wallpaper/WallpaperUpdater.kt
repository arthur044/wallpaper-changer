package io.github.arthur044.wallpaperchanger.wallpaper

import io.github.arthur044.wallpaperchanger.core.NowPlaying
import io.github.arthur044.wallpaperchanger.core.config.Settings
import io.github.arthur044.wallpaperchanger.core.render.CanvasSpec
import io.github.arthur044.wallpaperchanger.core.sync.WallpaperSink
import io.github.arthur044.wallpaperchanger.render.WallpaperComposer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class WallpaperNotAppliedException(val result: ApplyResult) :
    Exception("Wallpaper not applied: $result", (result as? ApplyResult.Failed)?.cause)

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
        if (result != ApplyResult.Applied) throw WallpaperNotAppliedException(result)
    }
}
