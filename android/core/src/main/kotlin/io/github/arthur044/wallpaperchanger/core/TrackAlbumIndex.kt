package io.github.arthur044.wallpaperchanger.core

/** The real Spotify album a track belongs to, with its high-res art. */
data class ResolvedAlbum(val albumId: String, val artUrl: String?)

/**
 * Maps a [trackKey] to the album it belongs to. When an album is first resolved,
 * every track on it is recorded at once, so jumping straight to any later track
 * of that album is recognized without another Web API call.
 *
 * Bounded LRU: a few hundred albums' worth of tracks at the default size.
 * Kept across restarts through a TrackIndexStore (snapshot/restore), so songs
 * of albums seen before need no lookup. Safe to call from multiple threads.
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

    /** Every entry, least recently used first: what a store saves. */
    @Synchronized
    fun snapshot(): List<Pair<String, ResolvedAlbum>> = entries.map { it.key to it.value }

    /** Puts saved entries back in order, so recency survives a restart. */
    @Synchronized
    fun restore(saved: List<Pair<String, ResolvedAlbum>>) {
        saved.forEach { (key, album) -> entries[key] = album }
    }

    /** @return whether [trackKey] was known. */
    @Synchronized
    fun forget(trackKey: String): Boolean = entries.remove(trackKey) != null

    companion object {
        const val DEFAULT_MAX_ENTRIES = 5_000
    }
}
