package io.github.arthur044.wallpaperchanger.core.sync

import io.github.arthur044.wallpaperchanger.core.ApiThrottle
import io.github.arthur044.wallpaperchanger.core.DEFAULT_BACKOFF_BASE
import io.github.arthur044.wallpaperchanger.core.NowPlaying
import io.github.arthur044.wallpaperchanger.core.PollDecision
import io.github.arthur044.wallpaperchanger.core.config.Settings
import io.github.arthur044.wallpaperchanger.core.decide
import io.github.arthur044.wallpaperchanger.core.nextBackoff
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
    timeSource: TimeSource = TimeSource.Monotonic,
) {
    // Guards the API against "sync now" spam; the regular cadence is the wait.
    private val throttle = ApiThrottle(MIN_CALL_SPACING, timeSource)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val mutableStatus = MutableStateFlow<SyncStatus>(SyncStatus.Starting)
    private var lastRenderedTrackId: String? = null
    private var memoryLoaded = false
    private var backoff = Duration.ZERO

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

    /** Connectivity is back: a backoff wait for the network ends now. */
    fun onNetworkAvailable() {
        if (status.value is SyncStatus.Retrying) syncNow()
    }

    /** One poll. @return how long to wait before the next, or null to stop. */
    internal suspend fun runOnce(): Duration? {
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

    /** @return false when the wallpaper can never be changed, so polling should stop. */
    private suspend fun render(nowPlaying: NowPlaying): Boolean {
        try {
            sink.show(nowPlaying)
        } catch (e: CancellationException) {
            throw e
        } catch (e: WallpaperBlockedException) {
            mutableStatus.value = SyncStatus.Blocked(e.message.orEmpty())
            return false
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
