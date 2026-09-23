package io.github.arthur044.wallpaperchanger.core.sync

import io.github.arthur044.wallpaperchanger.core.NowPlaying
import io.github.arthur044.wallpaperchanger.core.spotify.AlbumTrack
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

/**
 * Draws [NowPlaying] and puts it on screen; throws if it didn't make it there,
 * [WallpaperBlockedException] when no retry can ever succeed.
 */
fun interface WallpaperSink {
    suspend fun show(nowPlaying: NowPlaying)
}

/**
 * What Spotify's own session on this phone reports. It is free and instant, but
 * it only knows about playback on this device, so it is never the only source.
 */
data class LocalTrack(val title: String?, val artist: String?, val isPlaying: Boolean)

/** The tracks of an album, used to pre-warm the index after resolving one track. */
fun interface AlbumTracksSource {
    suspend fun albumTracks(albumId: String): List<AlbumTrack>
}

/** The wallpaper can't be changed on this device (unsupported, or a policy forbids it). */
class WallpaperBlockedException(message: String) : Exception(message)

/**
 * This track can never be drawn (no album art at all), unlike a download that
 * merely failed. The engine skips it instead of retrying it on every poll.
 */
class TrackNotDrawableException(message: String) : Exception(message)

/** Which track is on the wallpaper, kept across process restarts. */
interface RenderMemory {
    suspend fun lastRenderedTrackId(): String?

    suspend fun remember(trackId: String?)

    /** Forgets nothing and remembers nothing: every start redraws. */
    object None : RenderMemory {
        override suspend fun lastRenderedTrackId(): String? = null

        override suspend fun remember(trackId: String?) = Unit
    }
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

    /**
     * Something the loop doesn't model went wrong (a disk error reading the
     * settings, a bug). Syncing keeps going with backoff instead of dying
     * silently, and the status says so rather than blaming the network.
     */
    data class Failing(val retryIn: Duration, val cause: Throwable) : SyncStatus

    /** The session was revoked: syncing stopped until the user logs in again. */
    data object SignedOut : SyncStatus

    /** This device won't let the wallpaper change: syncing stopped for good. */
    data class Blocked(val reason: String) : SyncStatus
}
