package io.github.arthur044.wallpaperchanger.core.spotify

/** Source of a currently valid Spotify access token for Web API calls. */
fun interface AccessTokenProvider {
    /**
     * Returns a token that is valid right now, refreshing it first if needed.
     *
     * @throws AuthExpiredException when not signed in or the session was revoked.
     * @throws TransientNetworkException when a refresh failed for a retryable reason.
     */
    suspend fun accessToken(): String
}
