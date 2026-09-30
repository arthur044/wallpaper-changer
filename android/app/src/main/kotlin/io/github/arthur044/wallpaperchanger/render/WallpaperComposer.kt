package io.github.arthur044.wallpaperchanger.render

import android.graphics.Bitmap
import android.util.Log
import io.github.arthur044.wallpaperchanger.core.cache.AlbumArtStore
import io.github.arthur044.wallpaperchanger.core.cache.AlbumColors
import io.github.arthur044.wallpaperchanger.core.NowPlaying
import io.github.arthur044.wallpaperchanger.core.cache.baseCacheKey
import io.github.arthur044.wallpaperchanger.core.config.Settings
import io.github.arthur044.wallpaperchanger.core.render.CanvasSpec
import io.github.arthur044.wallpaperchanger.core.render.computeLayout
import io.github.arthur044.wallpaperchanger.core.spotify.ArtSource
import io.github.arthur044.wallpaperchanger.core.sync.TrackNotDrawableException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ComposedWallpaper(val bitmap: Bitmap, val reusedBase: Boolean)

/** An album's base; [fromCache] is false when it was just drawn (and cached). */
class ObtainedBase(val base: CachedBase, val fromCache: Boolean)

/**
 * The full render path (renderer.render_for_now_playing): a new album is
 * downloaded and its base drawn and cached; another track on a cached album
 * reuses that base and only redraws the text.
 */
class WallpaperComposer(
    private val art: ArtSource,
    private val renderer: WallpaperRenderer,
    private val cache: AlbumBaseCache,
    /** The original cover and its colors, kept so a look change downloads and quantizes nothing. */
    private val artStore: AlbumArtStore? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * @throws TrackNotDrawableException if it has no album, or no art and no base is cached.
     * @throws io.github.arthur044.wallpaperchanger.core.spotify.TransientNetworkException if the art download fails.
     */
    suspend fun compose(nowPlaying: NowPlaying, canvas: CanvasSpec, settings: Settings): ComposedWallpaper {
        val obtained = obtainBase(nowPlaying, canvas, settings)
        val base = obtained.base
        return try {
            val layout = computeLayout(canvas, settings, base.sourceArtSidePx)
            val final = withContext(cpu) {
                renderer.drawFinal(base.base, layout, nowPlaying.trackName, nowPlaying.artistName, settings.textCard)
            }
            ComposedWallpaper(final, reusedBase = obtained.fromCache)
        } finally {
            // Only the final image outlives this call; the base is on disk now.
            base.base.bitmap.recycle()
        }
    }

    /**
     * The album's base for this screen and look, without the track text: the
     * cached one, or (just after a zoom or style change) drawn from a fresh
     * download and cached, exactly as [compose] would. The caller owns the
     * bitmap and must recycle it.
     *
     * @throws TrackNotDrawableException if it has no album, or no art and no base is cached.
     * @throws io.github.arthur044.wallpaperchanger.core.spotify.TransientNetworkException if the art download fails.
     */
    suspend fun obtainBase(nowPlaying: NowPlaying, canvas: CanvasSpec, settings: Settings): ObtainedBase {
        // decide() keeps album-less tracks out of here, but say it the same way
        // as missing art if one ever arrives: retrying it would change nothing.
        val albumId = nowPlaying.albumId
            ?: throw TrackNotDrawableException("Track ${nowPlaying.trackId} has no album")
        val key = baseCacheKey(albumId, canvas, settings)

        val cached = withContext(io) { cache.get(key) }
        return ObtainedBase(cached ?: drawAndCacheBase(nowPlaying, albumId, key, canvas, settings), fromCache = cached != null)
    }

    private suspend fun drawAndCacheBase(
        nowPlaying: NowPlaying,
        albumId: String,
        key: String,
        canvas: CanvasSpec,
        settings: Settings,
    ): CachedBase {
        val stored = artStore?.let { withContext(io) { it.get(albumId) } }
        val base = if (stored != null) {
            try {
                drawBase(stored, albumId, canvas, settings)
            } catch (e: IllegalArgumentException) {
                // Stored bytes that are no image (damaged): once more from the network.
                Log.w(TAG, "Stored art for album $albumId is unreadable, downloading it again", e)
                withContext(io) { artStore?.discard(albumId) }
                downloadAndDraw(nowPlaying, albumId, canvas, settings)
            }
        } else {
            downloadAndDraw(nowPlaying, albumId, canvas, settings)
        }
        withContext(io) { cache.put(key, base.base, base.sourceArtSidePx) }
        return base
    }

    private suspend fun downloadAndDraw(
        nowPlaying: NowPlaying,
        albumId: String,
        canvas: CanvasSpec,
        settings: Settings,
    ): CachedBase {
        // Spotify does return albums with no images at all. Nothing to draw now
        // and nothing to retry, so say which it is instead of failing like a
        // download would: the engine skips the track rather than looping on it.
        val url = nowPlaying.artUrl
            ?: throw TrackNotDrawableException("Album $albumId has no art")
        val bytes = art.download(url)
        artStore?.let { withContext(io) { it.put(albumId, bytes) } }
        return try {
            drawBase(bytes, albumId, canvas, settings)
        } catch (e: IllegalArgumentException) {
            withContext(io) { artStore?.discard(albumId) } // not an image: keep nothing
            throw e
        }
    }

    private suspend fun drawBase(bytes: ByteArray, albumId: String, canvas: CanvasSpec, settings: Settings): CachedBase {
        val colors = artStore?.let { withContext(io) { it.colors(albumId) } } ?: AlbumColors()
        return withContext(cpu) {
            val image = renderer.decodeArt(bytes)
            try {
                CachedBase(
                    renderer.renderBase(image, computeLayout(canvas, settings, image.width), settings, colors),
                    image.width,
                )
            } finally {
                image.recycle()
            }
        }
    }

    private companion object {
        const val TAG = "WallpaperComposer"
    }
}
