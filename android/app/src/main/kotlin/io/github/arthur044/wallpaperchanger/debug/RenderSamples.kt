package io.github.arthur044.wallpaperchanger.debug

import android.content.Context
import android.graphics.Bitmap
import io.github.arthur044.wallpaperchanger.AppContainer
import io.github.arthur044.wallpaperchanger.core.render.Insets
import io.github.arthur044.wallpaperchanger.core.render.ScreenMetrics
import io.github.arthur044.wallpaperchanger.core.render.canvasSpec
import io.github.arthur044.wallpaperchanger.core.render.computeLayout
import io.github.arthur044.wallpaperchanger.render.screenMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/**
 * TEMPORARY (M5): renders the current track's art for several screen shapes so
 * the layout can be judged by eye. For square (tablet/foldable) canvases it
 * saves what the system would show in each orientation, not the raw square.
 */
suspend fun renderSamples(context: Context, container: AppContainer): List<File> {
    val playing = checkNotNull(container.spotifyApi.currentlyPlaying()) { "Nothing is playing" }
    val artUrl = checkNotNull(playing.artUrl) { "The current track has no album art" }
    val settings = container.settings.settings.first()
    val screens = listOf(
        "este-aparelho" to context.screenMetrics(),
        "compacto-720x1600" to ScreenMetrics(720, 1600, 2f, 360, Insets(0, 48, 0, 96)),
        "tablet-2560x1600" to ScreenMetrics(2560, 1600, 2f, 800, Insets(0, 48, 0, 96)),
        "dobravel-2208x1840" to ScreenMetrics(2208, 1840, 2.625f, 673, Insets(0, 70, 0, 70)),
    )
    val bytes = container.artDownloader.download(artUrl)

    return withContext(Dispatchers.Default) {
        val renderer = container.renderer
        val art = renderer.decodeArt(bytes)
        val dir = File(context.cacheDir, "render_samples").apply { deleteRecursively(); mkdirs() }
        screens.flatMap { (name, screen) ->
            val spec = canvasSpec(screen)
            val layout = computeLayout(spec, settings, sourceArtSidePx = art.width)
            val final = renderer.drawFinal(renderer.renderBase(art, layout), layout, playing.trackName, playing.artistName)
            viewsOf(final, screen).map { (suffix, view) -> save(view, File(dir, "$name$suffix.png")) }
        }
    }
}

// Phones show the bitmap as-is; a square canvas is center-cropped to the real
// screen in each orientation, which is what the system displays.
private fun viewsOf(bitmap: Bitmap, screen: ScreenMetrics): List<Pair<String, Bitmap>> {
    if (bitmap.width != bitmap.height) return listOf("" to bitmap)
    val side = bitmap.width
    val short = min(screen.widthPx, screen.heightPx)
    val offset = (side - short) / 2
    return listOf(
        "-paisagem" to Bitmap.createBitmap(bitmap, 0, offset, side, short),
        "-retrato" to Bitmap.createBitmap(bitmap, offset, 0, short, side),
    )
}

private fun save(bitmap: Bitmap, file: File): File {
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return file
}
