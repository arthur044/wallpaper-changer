package io.github.arthur044.wallpaperchanger.share

import android.graphics.Bitmap
import android.util.Log
import io.github.arthur044.wallpaperchanger.core.NowPlaying
import io.github.arthur044.wallpaperchanger.core.config.Settings
import io.github.arthur044.wallpaperchanger.core.render.CanvasSpec
import io.github.arthur044.wallpaperchanger.core.share.shareLayout
import io.github.arthur044.wallpaperchanger.core.share.versesToDraw
import io.github.arthur044.wallpaperchanger.render.WallpaperComposer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** One track's share image, from choosing verses to the saved file. */
interface ShareDrawing {
    /** Whether these consecutive lines fit the card at some size. */
    fun fits(verses: List<String>): Boolean

    /**
     * The image for [verses] (the caller owns it).
     *
     * @throws IllegalArgumentException if they are empty or don't fit.
     * @throws IllegalStateException after [close].
     */
    suspend fun draw(verses: List<String>): Bitmap

    /** [image] as the one share file (the previous one is deleted). */
    suspend fun save(image: Bitmap): File

    /** Waits for a draw in progress, then releases what the drawing holds. */
    suspend fun close()
}

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
     * A drawing for [nowPlaying] on this screen and look: holds its base until
     * closed, so choosing verses and redrawing the preview cost no disk read.
     *
     * @throws io.github.arthur044.wallpaperchanger.core.sync.TrackNotDrawableException if no base can be had.
     * @throws io.github.arthur044.wallpaperchanger.core.spotify.TransientNetworkException if the art download fails.
     */
    suspend fun open(nowPlaying: NowPlaying): ShareDrawing {
        val started = System.nanoTime()
        val spec = canvas()
        val current = settings()
        val base = composer.obtainBase(nowPlaying, spec, current)
        val layout = shareLayout(spec, current, base.base.sourceArtSidePx)
        Log.d(TAG, "base ${if (base.fromCache) "from cache" else "drawn"} in ${msSince(started)} ms")
        return ShareSession(
            base = base.base.base.bitmap,
            fitsCheck = { renderer.versesFit(layout, versesToDraw(it)) },
            render = { verses ->
                withContext(cpu) {
                    val drawStarted = System.nanoTime()
                    renderer.draw(base.base, layout, nowPlaying, versesToDraw(verses), current.textCard)
                        .also { Log.d(TAG, "drawn in ${msSince(drawStarted)} ms") }
                }
            },
            store = ::save,
        )
    }

    /** Deletes a share file left over (opening the app). */
    suspend fun clearFiles() = withContext(io) { files.clear() }

    private suspend fun save(image: Bitmap): File = withContext(io) {
        val started = System.nanoTime()
        files.write(image, format).also { Log.d(TAG, "${format.name} ${it.length() / 1024} KB in ${msSince(started)} ms") }
    }

    private fun msSince(started: Long) = (System.nanoTime() - started) / 1_000_000

    private companion object {
        const val TAG = "LyricsShare"
    }
}

/**
 * A [ShareDrawing] over a base bitmap it owns: [close] recycles [base], after
 * a draw in progress (the mutex), and drawing after that fails.
 */
class ShareSession internal constructor(
    private val base: Bitmap,
    private val fitsCheck: (List<String>) -> Boolean,
    private val render: suspend (List<String>) -> Bitmap,
    private val store: suspend (Bitmap) -> File,
) : ShareDrawing {
    private val lock = Mutex()
    private var closed = false

    override fun fits(verses: List<String>): Boolean = fitsCheck(verses)

    override suspend fun draw(verses: List<String>): Bitmap = lock.withLock {
        check(!closed) { "Share session closed" }
        render(verses)
    }

    override suspend fun save(image: Bitmap): File = store(image)

    override suspend fun close() = lock.withLock {
        if (closed) return@withLock
        closed = true
        base.recycle()
    }
}
