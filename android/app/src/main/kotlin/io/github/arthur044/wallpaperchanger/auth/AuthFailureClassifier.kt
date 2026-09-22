package io.github.arthur044.wallpaperchanger.auth

import io.github.arthur044.wallpaperchanger.core.spotify.AuthExpiredException
import io.github.arthur044.wallpaperchanger.core.spotify.TransientNetworkException
import net.openid.appauth.AuthorizationException

/**
 * Any OAuth-level rejection of a token request (revoked or rotated-away refresh
 * token, removed app access) means only a new login helps, as client.py treated
 * every SpotifyOauthError. Everything else (network, 5xx, garbled response) is
 * worth retrying.
 */
internal fun classifyAuthFailure(ex: AuthorizationException): Exception =
    if (ex.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR) {
        AuthExpiredException("Spotify rejected the session (${ex.error ?: ex.code})", ex)
    } else {
        TransientNetworkException("Spotify auth request failed (${ex.errorDescription ?: ex.error ?: ex.code})", ex)
    }
