package io.github.arthur044.wallpaperchanger.core.spotify

import kotlin.time.Duration

/** Spotify answered 429: no Web API call should be made for [retryAfter]. */
class RateLimitedException(val retryAfter: Duration) : Exception("Rate limited, retry after $retryAfter")

/**
 * The refresh token itself was rejected (revoked, or the app's access removed):
 * nothing but a fresh interactive login will fix it.
 */
class AuthExpiredException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A failure worth retrying later with backoff (network down, 5xx, timeouts). */
class TransientNetworkException(message: String, cause: Throwable? = null) : Exception(message, cause)
