package io.github.arthur044.wallpaperchanger.core.sync

import io.github.arthur044.wallpaperchanger.core.ApiThrottle
import io.github.arthur044.wallpaperchanger.core.DEFAULT_BACKOFF_BASE
import io.github.arthur044.wallpaperchanger.core.NowPlaying
import io.github.arthur044.wallpaperchanger.core.PollDecision
import io.github.arthur044.wallpaperchanger.core.ResolvedAlbum
import io.github.arthur044.wallpaperchanger.core.TrackAlbumIndex
import io.github.arthur044.wallpaperchanger.core.config.Settings
import io.github.arthur044.wallpaperchanger.core.decide
import io.github.arthur044.wallpaperchanger.core.nextBackoff
import io.github.arthur044.wallpaperchanger.core.trackKey
import io.github.arthur044.wallpaperchanger.core.spotify.AuthExpiredException
import io.github.arthur044.wallpaperchanger.core.spotify.RateLimitedException
import io.github.arthur044.wallpaperchanger.core.spotify.TransientNetworkException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * The polling loop (Poller, Web API path): ask what is playing, and redraw the
 * wallpaper only when the track changed.
 *
 * Network errors back off exponentially, a 429 waits out its Retry-After, and a
 * revoked session stops the loop (only an interactive login can fix it). When
 * to run at all (screen on, service alive) is the caller's business.
 */
class SyncEngine(
    private val source: NowPlayingSource,
    private val sink: WallpaperSink,
    private val settings: Flow<Settings>,
    private val memory: RenderMemory = RenderMemory.None,
    /** Pre-warms the index for the whole album; without it only the played track is known. */
    private val albumTracks: AlbumTracksSource? = null,
    private val index: TrackAlbumIndex = TrackAlbumIndex(),
    timeSource: TimeSource = TimeSource.Monotonic,
) {
    // Guards the API against "sync now" spam; the regular cadence is the wait.
    private val throttle = ApiThrottle(MIN_CALL_SPACING, timeSource)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val mutableStatus = MutableStateFlow<SyncStatus>(SyncStatus.Starting)
    private var lastRenderedTrackId: String? = null
    private var undrawableTrackId: String? = null
    private var memoryLoaded = false
    private val redrawPending = AtomicBoolean(false)
    private val latestLocal = AtomicReference<LocalTrack?>(null)
    private var backoff = Duration.ZERO
    private var resolveBackoff = Duration.ZERO

    val status: StateFlow<SyncStatus> = mutableStatus.asStateFlow()

    /** Polls until cancelled, or until the session expires or the wallpaper is blocked. */
    suspend fun run() {
        while (true) {
            val wait = runOnce() ?: return
            withTimeoutOrNull(wait) { wake.receive() }
        }
    }

    /** Ends the current wait early (subject to the API throttle). */
    fun syncNow() {
        wake.trySend(Unit)
    }

    /**
     * A look setting changed: repaint the track on screen now. Uses what is
     * already known, so it costs no Web API call and isn't held by the throttle.
     */
    fun redraw() {
        redrawPending.set(true)
        wake.trySend(Unit)
    }

    /**
     * Spotify's session on this phone changed. Wakes the loop, so a track change
     * is picked up at once instead of at the next poll.
     */
    fun onLocalTrack(track: LocalTrack?) {
        latestLocal.set(track)
        wake.trySend(Unit)
    }

    /** Connectivity is back: a backoff wait for the network ends now. */
    fun onNetworkAvailable() {
        if (status.value is SyncStatus.Retrying) syncNow()
    }

    /**
     * One poll, with a net under it. @return how long to wait before the next,
     * or null to stop.
     *
     * Anything the cycle doesn't model (a disk error from the settings store, a
     * bug) would otherwise escape [run] and take the whole service down with it,
     * silently: the "why it stopped" notification only fires on a clean stop.
     */
    internal suspend fun runOnce(): Duration? = try {
        pollOnce()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        backoff = nextBackoff(backoff)
        mutableStatus.value = SyncStatus.Failing(backoff, e)
        backoff
    }

    private suspend fun pollOnce(): Duration? {
        if (!memoryLoaded) {
            lastRenderedTrackId = memory.lastRenderedTrackId()
            memoryLoaded = true
        }
        val current = settings.first()
        val interval = current.webApiPollInterval
        if (current.paused) {
            mutableStatus.value = SyncStatus.Paused
            return interval
        }
        if (redrawPending.getAndSet(false)) {
            val onScreen = (status.value as? SyncStatus.Showing)?.nowPlaying
            if (onScreen != null && !render(onScreen)) return null
            // Then poll as usual (if the throttle allows): the track may have changed.
        }
        // Playing on this phone and the option is on: no poll needed at all.
        val local = latestLocal.get().takeIf { current.useMediaSession && it?.isPlaying == true }
        if (local != null) return fromLocalSession(local, interval)
        if (current.useMediaSession && current.localOnly) {
            // Local-only: nothing plays here, so there is nothing to ask about.
            mutableStatus.value = SyncStatus.Idle
            return interval
        }

        if (!throttle.tryAcquire()) return interval

        val nowPlaying = try {
            source.currentlyPlaying()
        } catch (e: RateLimitedException) {
            throttle.deferFor(e.retryAfter)
            return retryIn(maxOf(e.retryAfter, MIN_CALL_SPACING), e)
        } catch (e: TransientNetworkException) {
            backoff = nextBackoff(backoff)
            return retryIn(backoff, e)
        } catch (e: AuthExpiredException) {
            mutableStatus.value = SyncStatus.SignedOut
            return null
        }
        backoff = Duration.ZERO

        when (decide(nowPlaying, lastRenderedTrackId)) {
            PollDecision.IDLE -> mutableStatus.value = SyncStatus.Idle
            PollDecision.NOOP -> mutableStatus.value = SyncStatus.Showing(checkNotNull(nowPlaying))
            PollDecision.RENDER -> if (!render(checkNotNull(nowPlaying))) return null
        }
        return interval
    }

    /**
     * The hybrid path (Poller._handle_smtc_snapshot): the local session says
     * what plays, the Web API is asked only to resolve an album we don't know.
     * Only verified Spotify art is ever drawn, so an unresolved track leaves the
     * wallpaper alone (and stays unmarked, to be drawn once resolution works).
     */
    private suspend fun fromLocalSession(local: LocalTrack, interval: Duration): Duration? {
        val key = trackKey(local.artist, local.title)
        val album = index[key] ?: run {
            if (!throttle.tryAcquire()) return interval
            when (val resolution = resolveAlbum(key)) {
                is Resolution.Found -> resolution.album
                is Resolution.RetryIn -> return resolution.wait // network trouble: retry soon, not in 25s
                Resolution.Unknown -> {
                    // A brand-new album while the API still reports the previous
                    // track: it will catch up, so try again soon and back off if
                    // it stays unknown (playing on another device, say).
                    resolveBackoff = nextBackoff(resolveBackoff, cap = interval)
                    return resolveBackoff
                }
                Resolution.SessionExpired -> {
                    mutableStatus.value = SyncStatus.SignedOut
                    return null
                }
            }
        }
        resolveBackoff = Duration.ZERO

        val nowPlaying = NowPlaying(
            isPlaying = true,
            trackId = key,
            albumId = album.albumId,
            artUrl = album.artUrl,
            trackName = local.title,
            artistName = local.artist,
        )
        when (decide(nowPlaying, lastRenderedTrackId)) {
            PollDecision.IDLE -> mutableStatus.value = SyncStatus.Idle
            PollDecision.NOOP -> mutableStatus.value = SyncStatus.Showing(nowPlaying)
            PollDecision.RENDER -> if (!render(nowPlaying)) return null
        }
        return interval
    }

    /**
     * One throttled Web API call for the album of what is playing, plus a
     * best-effort tracklist so every other track on it is free afterwards.
     */
    private suspend fun resolveAlbum(wantedKey: String): Resolution {
        val playing = try {
            source.currentlyPlaying()
        } catch (e: RateLimitedException) {
            throttle.deferFor(e.retryAfter)
            return Resolution.RetryIn(retryIn(maxOf(e.retryAfter, MIN_CALL_SPACING), e))
        } catch (e: TransientNetworkException) {
            backoff = nextBackoff(backoff)
            return Resolution.RetryIn(retryIn(backoff, e))
        } catch (e: AuthExpiredException) {
            return Resolution.SessionExpired
        }
        backoff = Duration.ZERO
        val albumId = playing?.albumId ?: return Resolution.Unknown
        val album = ResolvedAlbum(albumId, playing.artUrl)
        val keys = mutableListOf(trackKey(playing.artistName, playing.trackName))
        // Best-effort: a failed prefetch only costs one call on the next track.
        runCatching { albumTracks?.albumTracks(albumId).orEmpty() }
            .getOrDefault(emptyList())
            .mapTo(keys) { trackKey(it.artistName, it.name) }
        index.record(album, keys)
        // Unknown when the API is reporting another device: don't draw that track here.
        return index[wantedKey]?.let(Resolution::Found) ?: Resolution.Unknown
    }

    private sealed interface Resolution {
        data class Found(val album: ResolvedAlbum) : Resolution

        data class RetryIn(val wait: Duration) : Resolution

        /** The API couldn't place this track (nothing playing, or another device). */
        data object Unknown : Resolution

        data object SessionExpired : Resolution
    }

    /** @return false when the wallpaper can never be changed, so polling should stop. */
    private suspend fun render(nowPlaying: NowPlaying): Boolean {
        if (nowPlaying.trackId == undrawableTrackId) return true // already tried; the status says why
        try {
            sink.show(nowPlaying)
        } catch (e: CancellationException) {
            throw e
        } catch (e: WallpaperBlockedException) {
            mutableStatus.value = SyncStatus.Blocked(e.message.orEmpty())
            return false
        } catch (e: TrackNotDrawableException) {
            // Nothing to draw for this one, ever. Remembered as undrawable rather
            // than as rendered: the next polls skip it instead of failing on it
            // again, and the status keeps saying it couldn't be drawn instead of
            // claiming it is on the wallpaper.
            undrawableTrackId = nowPlaying.trackId
            mutableStatus.value = SyncStatus.RenderFailed(nowPlaying, e)
            return true
        } catch (e: Exception) {
            // Left unmarked, so the next poll sees a new track and tries again.
            mutableStatus.value = SyncStatus.RenderFailed(nowPlaying, e)
            return true
        }
        lastRenderedTrackId = nowPlaying.trackId
        memory.remember(nowPlaying.trackId)
        mutableStatus.value = SyncStatus.Showing(nowPlaying)
        return true
    }

    private fun retryIn(wait: Duration, cause: Exception): Duration {
        mutableStatus.value = SyncStatus.Retrying(wait, cause)
        return wait
    }

    private companion object {
        // Equal to the first backoff step, so a retry is never refused.
        val MIN_CALL_SPACING: Duration = DEFAULT_BACKOFF_BASE
    }
}
