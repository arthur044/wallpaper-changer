package io.github.arthur044.wallpaperchanger.core

/**
 * Normalized "artist::title" identity for a track, used where no Spotify catalog
 * id is available (e.g. MediaSession metadata). Case- and padding-insensitive so
 * the same track reported by different sources lands on the same key.
 */
fun trackKey(artist: String?, title: String?): String =
    "${normalize(artist)}::${normalize(title)}"

private fun normalize(part: String?): String = part.orEmpty().trim().lowercase()
