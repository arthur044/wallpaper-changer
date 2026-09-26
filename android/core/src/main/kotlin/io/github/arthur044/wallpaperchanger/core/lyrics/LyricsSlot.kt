package io.github.arthur044.wallpaperchanger.core.lyrics

import io.github.arthur044.wallpaperchanger.core.NowPlaying
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The lyrics of one track, in memory only: the answer for the track on screen,
 * "not found" and "instrumental" included, so the share button never asks
 * twice. Another track drops it. A failed lookup is not kept, so the next ask
 * tries again.
 *
 * Lookups run one at a time: the share button, pressed while the early lookup
 * for the same track is still out, waits for it instead of asking again.
 */
class LyricsSlot(private val source: LyricsSource) {
    private data class Held(val trackId: String, val lyrics: Lyrics)

    private val lookups = Mutex()

    // Both under `state`: the track showing and the answer kept for it.
    private val state = Any()
    private var current: String? = null
    private var held: Held? = null

    /** The kept answer for [trackId], without asking anyone. */
    fun peek(trackId: String): Lyrics? = synchronized(state) { held?.takeIf { it.trackId == trackId }?.lyrics }

    /** Another track is showing: the kept answer, if for another one, is gone. */
    fun onTrack(trackId: String?) = synchronized(state) {
        current = trackId
        if (held?.trackId != trackId) held = null
    }

    /**
     * The kept answer for this track, or a lookup. [Lyrics.NotFound] when the
     * track has nothing to look up by. With [claim], this track becomes the one
     * showing (the early lookup); without it (a screen asking about the track it
     * was opened for), the track showing and its kept answer are left alone, and
     * the answer is kept only if it is for the track showing.
     *
     * @throws LyricsUnavailableException when the lookup failed (nothing is kept).
     */
    suspend fun lyricsFor(nowPlaying: NowPlaying, claim: Boolean = true): Lyrics {
        val trackId = nowPlaying.trackId ?: return Lyrics.NotFound
        if (claim) onTrack(trackId)
        return lookups.withLock {
            peek(trackId) ?: lookUp(trackId, nowPlaying)
        }
    }

    private suspend fun lookUp(trackId: String, nowPlaying: NowPlaying): Lyrics {
        val query = LyricsQuery.of(nowPlaying)
        val lyrics = if (query == null) Lyrics.NotFound else source.lyrics(query)
        // The track may have changed while LRCLIB answered: an answer for a
        // track no longer showing is returned to its caller but not kept.
        synchronized(state) {
            if (current == trackId) held = Held(trackId, lyrics)
        }
        return lyrics
    }
}
