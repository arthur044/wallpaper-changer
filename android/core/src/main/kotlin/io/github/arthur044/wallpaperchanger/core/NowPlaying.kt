package io.github.arthur044.wallpaperchanger.core

/**
 * What is playing right now, as far as rendering is concerned. [trackId] is only
 * compared for equality (change detection); [albumId] keys the cached base art.
 * [albumName], [durationMs] and [artists] only feed the lyrics lookup and never
 * touch pixels; any may be unknown (the phone's session may not report them).
 * [artists] are the names one by one, as Spotify lists them ([artistName] joins
 * them for drawing); empty when only the joined text is known.
 */
data class NowPlaying(
    val isPlaying: Boolean,
    val trackId: String?,
    val albumId: String?,
    val artUrl: String?,
    val trackName: String?,
    val artistName: String?,
    val albumName: String? = null,
    val durationMs: Long? = null,
    val artists: List<String> = emptyList(),
)
