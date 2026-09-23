package io.github.arthur044.wallpaperchanger.core

import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Spaces out Spotify Web API calls: at most one per [minInterval]. This is the
 * call that can trip Spotify's rate limit (429), so every Web API request made
 * by the sync loop goes through [tryAcquire] first.
 */
class ApiThrottle(
    private val minInterval: Duration,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    // Mark of the last granted call; may sit in the future after deferFor().
    private var lastGranted: TimeMark? = null

    /** True (and starts a new interval) if a call may be made now. */
    @Synchronized
    fun tryAcquire(): Boolean {
        val last = lastGranted
        if (last != null && last.elapsedNow() < minInterval) return false
        lastGranted = timeSource.markNow()
        return true
    }

    /**
     * Honors a 429 Retry-After: the next call is allowed no sooner than
     * [retryAfter] from now, and never sooner than one full [minInterval].
     */
    @Synchronized
    fun deferFor(retryAfter: Duration) {
        val extra = (retryAfter - minInterval).coerceAtLeast(Duration.ZERO)
        lastGranted = timeSource.markNow() + extra
    }
}
