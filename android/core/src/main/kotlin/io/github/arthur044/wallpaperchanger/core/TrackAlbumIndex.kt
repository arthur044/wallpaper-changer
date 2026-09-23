package io.github.arthur044.wallpaperchanger.core

/** The real Spotify album a track belongs to, with its high-res art. */
data class ResolvedAlbum(val albumId: String, val artUrl: String?)

/**
 * Maps a [trackKey] to the album it belongs to. When an album is first resolved,
 * every track on it is recorded at once, so jumping straight to any later track
 * of that album is recognized without another Web API call.
 *
 * Bounded LRU (the desktop version grew without limit): ~40 albums' worth of
 * tracks at the default size. In-memory only; losing it on process death just
 * costs one re-resolution per album. Safe to call from multiple threads.
 */
class TrackAlbumIndex(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
    }

    private val entries = object : LinkedHashMap<String, ResolvedAlbum>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResolvedAlbum>): Boolean =
            size > maxEntries
    }

    @Synchronized
    operator fun get(trackKey: String): ResolvedAlbum? = entries[trackKey]

    @Synchronized
    fun record(album: ResolvedAlbum, trackKeys: Iterable<String>) {
        trackKeys.forEach { entries[it] = album }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 2_000
    }
}
