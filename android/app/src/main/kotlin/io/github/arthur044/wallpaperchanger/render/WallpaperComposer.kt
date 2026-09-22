package io.github.arthur044.wallpaperchanger.render

import android.graphics.Bitmap
import io.github.arthur044.wallpaperchanger.core.NowPlaying
import io.github.arthur044.wallpaperchanger.core.cache.baseCacheKey
import io.github.arthur044.wallpaperchanger.core.config.Settings
import io.github.arthur044.wallpaperchanger.core.render.CanvasSpec
import io.github.arthur044.wallpaperchanger.core.render.computeLayout
import io.github.arthur044.wallpaperchanger.core.spotify.ArtSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ComposedWallpaper(val bitmap: Bitmap, val reusedBase: Boolean)

/**
 * The full render path (renderer.render_for_now_playing): a new album is
 * downloaded and its base drawn and cached; another track on a cached album
 * reuses that base and only redraws the text.
 */
class WallpaperComposer(
    private val art: ArtSource,
    private val renderer: WallpaperRenderer,
    private val cache: AlbumBaseCache,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * @throws IllegalArgumentException if [nowPlaying] has no album, or no art on a cache miss.
     * @throws io.github.arthur044.wallpaperchanger.core.spotify.TransientNetworkException if the art download fails.
     */
    suspend fun compose(nowPlaying: NowPlaying, canvas: CanvasSpec, settings: Settings): ComposedWallpaper {
        val albumId = requireNotNull(nowPlaying.albumId) { "Can't render a track without an album" }
        val key = baseCacheKey(albumId, canvas, settings)

        val cached = withContext(io) { cache.get(key) }
        val base = cached ?: drawAndCacheBase(nowPlaying, key, canvas, settings)
        return try {
            val layout = computeLayout(canvas, settings, base.sourceArtSidePx)
            val final = withContext(cpu) {
                renderer.drawFinal(base.base, layout, nowPlaying.trackName, nowPlaying.artistName)
            }
            ComposedWallpaper(final, reusedBase = cached != null)
        } finally {
            // Only the final image outlives this call; the base is on disk now.
            base.base.bitmap.recycle()
        }
    }

    private suspend fun drawAndCacheBase(
        nowPlaying: NowPlaying,
        key: String,
        canvas: CanvasSpec,
        settings: Settings,
    ): CachedBase {
        val url = requireNotNull(nowPlaying.artUrl) { "No album art for album ${nowPlaying.albumId}" }
        val bytes = art.download(url)
        val base = withContext(cpu) {
            val image = renderer.decodeArt(bytes)
            try {
                CachedBase(renderer.renderBase(image, computeLayout(canvas, settings, image.width)), image.width)
            } finally {
                image.recycle()
            }
        }
        withContext(io) { cache.put(key, base.base, base.sourceArtSidePx) }
        return base
    }
}
