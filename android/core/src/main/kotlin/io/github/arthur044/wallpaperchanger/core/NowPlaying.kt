package io.github.arthur044.wallpaperchanger.core

/**
 * What is playing right now, as far as rendering is concerned. [trackId] is only
 * compared for equality (change detection); [albumId] keys the cached base art.
 */
data class NowPlaying(
    val isPlaying: Boolean,
    val trackId: String?,
    val albumId: String?,
    val artUrl: String?,
    val trackName: String?,
    val artistName: String?,
)
