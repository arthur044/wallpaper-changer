package io.github.arthur044.wallpaperchanger.core.spotify

/** Source of a currently valid Spotify access token for Web API calls. */
interface AccessTokenProvider {
    /**
     * Returns a token that is valid right now, refreshing it first if needed.
     * [forceRefresh] skips the cached token even if it looks unexpired, for when
     * the Web API just answered 401 to it.
     *
     * @throws AuthExpiredException when not signed in or the session was revoked.
     * @throws TransientNetworkException when a refresh failed for a retryable reason.
     */
    suspend fun accessToken(forceRefresh: Boolean = false): String
}
