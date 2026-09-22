package io.github.arthur044.wallpaperchanger.auth

import io.github.arthur044.wallpaperchanger.core.spotify.AuthExpiredException
import io.github.arthur044.wallpaperchanger.core.spotify.TransientNetworkException
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationException.GeneralErrors
import net.openid.appauth.AuthorizationException.TokenRequestErrors
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthFailureClassifierTest {
    private inline fun <reified T : Exception> assertClassifiedAs(ex: AuthorizationException) {
        val classified = classifyAuthFailure(ex)
        // Not "$ex": AuthorizationException.toString() goes through org.json,
        // which is only a stub in JVM unit tests.
        assertTrue("code ${ex.code} classified as ${classified::class.simpleName}", classified is T)
        assertSame("original failure must be kept as the cause", ex, classified.cause)
    }

    @Test
    fun `a revoked refresh token needs a fresh login`() {
        // Spotify answers a revoked/rotated-away refresh token with 400 invalid_grant.
        assertClassifiedAs<AuthExpiredException>(TokenRequestErrors.INVALID_GRANT)
    }

    @Test
    fun `any other oauth token rejection also needs a fresh login`() {
        // Mirrors client.py mapping every SpotifyOauthError to AuthExpiredError.
        assertClassifiedAs<AuthExpiredException>(TokenRequestErrors.INVALID_CLIENT)
        assertClassifiedAs<AuthExpiredException>(TokenRequestErrors.UNAUTHORIZED_CLIENT)
    }

    @Test
    fun `network and server failures are retryable`() {
        assertClassifiedAs<TransientNetworkException>(GeneralErrors.NETWORK_ERROR)
        assertClassifiedAs<TransientNetworkException>(GeneralErrors.SERVER_ERROR)
        assertClassifiedAs<TransientNetworkException>(GeneralErrors.JSON_DESERIALIZATION_ERROR)
    }
}
