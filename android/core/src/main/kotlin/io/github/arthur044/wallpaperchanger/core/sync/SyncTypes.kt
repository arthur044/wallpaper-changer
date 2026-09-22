package io.github.arthur044.wallpaperchanger.core.sync

import io.github.arthur044.wallpaperchanger.core.NowPlaying
import kotlin.time.Duration

/** Where the engine learns what is playing (the Web API, in the base path). */
fun interface NowPlayingSource {
    /**
     * @return null when nothing is playing.
     * @throws io.github.arthur044.wallpaperchanger.core.spotify.AuthExpiredException
     * @throws io.github.arthur044.wallpaperchanger.core.spotify.RateLimitedException
     * @throws io.github.arthur044.wallpaperchanger.core.spotify.TransientNetworkException
     */
    suspend fun currentlyPlaying(): NowPlaying?
}

/** Draws [NowPlaying] and puts it on screen; throws if it didn't make it there. */
fun interface WallpaperSink {
    suspend fun show(nowPlaying: NowPlaying)
}

sealed interface SyncStatus {
    data object Starting : SyncStatus

    /** The user paused syncing; no API calls are made. */
    data object Paused : SyncStatus

    /** Nothing is playing; the wallpaper keeps its last image. */
    data object Idle : SyncStatus

    /** [nowPlaying] is on the wallpaper. */
    data class Showing(val nowPlaying: NowPlaying) : SyncStatus

    /** The track couldn't be drawn or applied; the next poll tries again. */
    data class RenderFailed(val nowPlaying: NowPlaying, val cause: Exception) : SyncStatus

    /** Spotify was unreachable or rate limited us; the next poll is in [retryIn]. */
    data class Retrying(val retryIn: Duration, val cause: Exception) : SyncStatus

    /** The session was revoked: syncing stopped until the user logs in again. */
    data object SignedOut : SyncStatus
}
