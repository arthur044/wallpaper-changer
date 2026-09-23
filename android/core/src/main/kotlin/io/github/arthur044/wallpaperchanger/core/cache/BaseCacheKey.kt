package io.github.arthur044.wallpaperchanger.core.cache

import io.github.arthur044.wallpaperchanger.core.config.Settings
import io.github.arthur044.wallpaperchanger.core.render.CanvasSpec
import java.security.MessageDigest

/**
 * Bump when the renderer's drawing changes, so bases drawn by an older version
 * are never reused.
 *
 * 2: background color now matches the desktop (ColorThief port, not Palette).
 * 3: the cache file no longer carries the background color; the text color
 *    is sampled from the base itself.
 */
const val BASE_RENDER_VERSION = 3

/**
 * Cache key for an album's base image (fill + shadow + art, no text).
 *
 * Keyed on the layout's *inputs* rather than the layout itself: the layout
 * also depends on the source art's size, which is only known after the
 * download the cache exists to avoid. The source art is fixed per album, so
 * album id + canvas + the pixel-affecting settings determine the base fully.
 * Settings that never touch pixels (client id, polling, pause...) are left out
 * so changing them doesn't throw the cache away. Neither is textCard: the
 * card is drawn over the base on every track.
 */
fun baseCacheKey(albumId: String, canvas: CanvasSpec, settings: Settings): String {
    val inputs = listOf(
        BASE_RENDER_VERSION,
        canvas.canvasWidth, canvas.canvasHeight,
        canvas.safeArea.left, canvas.safeArea.top, canvas.safeArea.right, canvas.safeArea.bottom,
        canvas.density,
        settings.artSizePct, settings.cornerRadius, settings.shadowBlurRadius,
        settings.artOffsetYPct, settings.showTrackInfo,
        settings.backgroundStyle, settings.artGlow, settings.artFrame,
    ).joinToString("|")
    return "${fileSafeId(albumId)}_${canvas.canvasWidth}x${canvas.canvasHeight}_${sha256Hex(inputs).take(12)}"
}

// Spotify ids are base62; anything else is hashed so it can't form a path.
private fun fileSafeId(albumId: String): String =
    if (albumId.matches(SPOTIFY_ID)) albumId else "h" + sha256Hex(albumId).take(22)

private fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }

private val SPOTIFY_ID = Regex("[A-Za-z0-9]{1,64}")
