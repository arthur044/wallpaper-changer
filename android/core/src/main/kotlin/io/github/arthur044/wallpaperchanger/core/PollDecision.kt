package io.github.arthur044.wallpaperchanger.core

enum class PollDecision { RENDER, NOOP, IDLE }

/**
 * RENDER covers both a new album (expensive: download + compose the base) and a
 * new track within the same album (cheap: reuse the cached base, redraw only the
 * text). Which of the two it is gets decided downstream by whether the per-album
 * base is already cached, not here.
 */
fun decide(nowPlaying: NowPlaying?, lastRenderedTrackId: String?): PollDecision = when {
    nowPlaying == null || !nowPlaying.isPlaying || nowPlaying.albumId == null -> PollDecision.IDLE
    nowPlaying.trackId == lastRenderedTrackId -> PollDecision.NOOP
    else -> PollDecision.RENDER
}
