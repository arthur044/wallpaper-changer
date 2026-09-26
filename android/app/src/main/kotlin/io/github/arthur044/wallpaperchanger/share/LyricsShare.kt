package io.github.arthur044.wallpaperchanger.share

import android.graphics.Bitmap
import android.util.Log
import io.github.arthur044.wallpaperchanger.core.NowPlaying
import io.github.arthur044.wallpaperchanger.core.config.Settings
import io.github.arthur044.wallpaperchanger.core.render.CanvasSpec
import io.github.arthur044.wallpaperchanger.core.share.ShareLayout
import io.github.arthur044.wallpaperchanger.core.share.shareLayout
import io.github.arthur044.wallpaperchanger.core.share.versesToDraw
import io.github.arthur044.wallpaperchanger.render.CachedBase
import io.github.arthur044.wallpaperchanger.render.WallpaperComposer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Makes lyrics share images from the wallpaper's cached base: no download in
 * the normal case, and the composer's own get-or-draw right after a zoom or
 * style change. Nothing here touches the sync, the screen frames or the
 * wallpaper's cache keys.
 */
class LyricsShare(
    private val composer: WallpaperComposer,
    private val renderer: ShareRenderer,
    private val files: ShareFiles,
    private val canvas: () -> CanvasSpec,
    private val settings: suspend () -> Settings,
    val format: ShareFormat = ShareFormat.JPEG,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * A session for [nowPlaying] on this screen and look: holds its base until
     * closed, so choosing verses and redrawing the preview cost no disk read.
     *
     * @throws io.github.arthur044.wallpaperchanger.core.sync.TrackNotDrawableException if no base can be had.
     * @throws io.github.arthur044.wallpaperchanger.core.spotify.TransientNetworkException if the art download fails.
     */
    suspend fun open(nowPlaying: NowPlaying): ShareSession {
        val started = System.nanoTime()
        val spec = canvas()
        val current = settings()
        val obtained = composer.obtainBase(nowPlaying, spec, current)
        val layout = shareLayout(spec, current, obtained.base.sourceArtSidePx)
        Log.d(TAG, "base ${if (obtained.fromCache) "from cache" else "drawn"} in ${msSince(started)} ms")
        return ShareSession(obtained.base, layout, nowPlaying, current, this)
    }

    /** Deletes a share file left over (opening the app). */
    suspend fun clearFiles() = withContext(io) { files.clear() }

    internal suspend fun draw(session: ShareSession, verses: List<String>): Bitmap = withContext(cpu) {
        val started = System.nanoTime()
        renderer.draw(session.base, session.layout, session.nowPlaying, versesToDraw(verses), session.settings.textCard)
            .also { Log.d(TAG, "drawn in ${msSince(started)} ms") }
    }

    internal fun fits(session: ShareSession, verses: List<String>): Boolean =
        renderer.versesFit(session.layout, versesToDraw(verses))

    internal suspend fun save(image: Bitmap): File = withContext(io) {
        val started = System.nanoTime()
        files.write(image, format).also { Log.d(TAG, "${format.name} ${it.length() / 1024} KB in ${msSince(started)} ms") }
    }

    private fun msSince(started: Long) = (System.nanoTime() - started) / 1_000_000

    private companion object {
        const val TAG = "LyricsShare"
    }
}

/**
 * One track's share: its base, its layout, and the image drawn from the
 * chosen verses. [close] releases the base; drawing after that fails.
 */
class ShareSession internal constructor(
    internal val base: CachedBase,
    val layout: ShareLayout,
    val nowPlaying: NowPlaying,
    internal val settings: Settings,
    private val share: LyricsShare,
) {
    private val lock = Mutex()
    private var closed = false

    /** Whether these consecutive lines fit the card at some size. */
    fun fits(verses: List<String>): Boolean = share.fits(this, verses)

    /**
     * The image for [verses] (the caller recycles it).
     *
     * @throws IllegalArgumentException if they are empty or don't fit.
     */
    suspend fun draw(verses: List<String>): Bitmap = lock.withLock {
        check(!closed) { "Share session closed" }
        share.draw(this, verses)
    }

    /** [image] as the one share file (the previous one is deleted). */
    suspend fun save(image: Bitmap): File = share.save(image)

    /** Waits for a draw in progress, then releases the base. */
    suspend fun close() = lock.withLock {
        if (closed) return@withLock
        closed = true
        base.base.bitmap.recycle()
    }
}
