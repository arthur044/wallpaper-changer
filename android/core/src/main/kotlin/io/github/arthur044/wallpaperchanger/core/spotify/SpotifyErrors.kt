package io.github.arthur044.wallpaperchanger.core.spotify

/**
 * The refresh token itself was rejected (revoked, or the app's access removed):
 * nothing but a fresh interactive login will fix it.
 */
class AuthExpiredException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A failure worth retrying later with backoff (network down, 5xx, timeouts). */
class TransientNetworkException(message: String, cause: Throwable? = null) : Exception(message, cause)
