package io.github.arthur044.wallpaperchanger.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.arthur044.wallpaperchanger.core.spotify.AccessTokenProvider
import io.github.arthur044.wallpaperchanger.core.spotify.AuthExpiredException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import org.json.JSONException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface LoginResult {
    data object Success : LoginResult
    data object Cancelled : LoginResult
    data class Failed(val reason: String) : LoginResult
}

data class AuthStatus(val signedIn: Boolean, val accessTokenExpiresAtMillis: Long?)

/**
 * Spotify OAuth (authorization code + PKCE, no client secret) through AppAuth
 * and a Custom Tab. The only class that knows about AppAuth, so swapping the
 * library later touches this file alone.
 *
 * Every token read or refresh runs under one [Mutex]: Spotify rotates the
 * refresh token on each PKCE refresh, so two concurrent refreshes would leave
 * one of them holding an already-invalidated token.
 */
class SpotifyAuth(
    context: Context,
    private val tokenStore: EncryptedTokenStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AccessTokenProvider {
    private val service = AuthorizationService(context.applicationContext)
    private val mutex = Mutex()
    private var state: AuthState? = null // guarded by mutex; null until loaded

    /** Intent that opens Spotify's consent page; launch it for a result. */
    fun authorizationIntent(clientId: String): Intent {
        val request = AuthorizationRequest.Builder(SERVICE_CONFIG, clientId, ResponseTypeValues.CODE, REDIRECT_URI)
            .setScopes(SCOPES)
            .build() // AppAuth generates the PKCE verifier/challenge (S256) itself
        return service.getAuthorizationRequestIntent(request)
    }

    /** Finishes a login from the Custom Tab's result and stores the session. */
    suspend fun completeAuthorization(result: Intent?): LoginResult {
        val response = result?.let(AuthorizationResponse::fromIntent)
        val error = result?.let(AuthorizationException::fromIntent)
        if (response == null) {
            return when {
                error == null || error.code == AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.code ->
                    LoginResult.Cancelled
                else -> LoginResult.Failed(error.errorDescription ?: error.error ?: "code ${error.code}")
            }
        }

        val tokens = try {
            exchange(response.createTokenExchangeRequest())
        } catch (e: AuthorizationException) {
            return LoginResult.Failed(classifyAuthFailure(e).message.orEmpty())
        }

        val fresh = AuthState(response, null).apply { update(tokens, null) }
        mutex.withLock {
            persist(fresh)
            state = fresh
        }
        return LoginResult.Success
    }

    override suspend fun accessToken(forceRefresh: Boolean): String = mutex.withLock {
        val current = loadState() ?: throw AuthExpiredException("Not signed in to Spotify")
        if (forceRefresh) current.needsTokenRefresh = true
        try {
            freshToken(current)
        } catch (e: AuthExpiredException) {
            // The refresh token was rejected and will never work again: drop it,
            // so the app reads as signed out instead of retrying a dead session.
            forget()
            throw e
        }
    }

    /** Debug aid: refreshes now and returns the new expiry. */
    suspend fun forceRefresh(): Long? {
        accessToken(forceRefresh = true)
        return status().accessTokenExpiresAtMillis
    }

    suspend fun status(): AuthStatus = mutex.withLock {
        val current = loadState()
        AuthStatus(signedIn = current?.isAuthorized == true, accessTokenExpiresAtMillis = current?.accessTokenExpirationTime)
    }

    suspend fun signOut() = mutex.withLock { forget() }

    // Caller holds the mutex.
    private suspend fun forget() {
        state = null
        withContext(io) { tokenStore.clear() }
    }

    // Caller holds the mutex.
    private suspend fun freshToken(current: AuthState): String {
        val before = current.jsonSerializeString()
        val token = suspendCancellableCoroutine { cont ->
            current.performActionWithFreshTokens(service) { accessToken, _, ex ->
                when {
                    ex != null -> cont.resumeWithException(classifyAuthFailure(ex))
                    accessToken == null -> cont.resumeWithException(AuthExpiredException("Spotify returned no access token"))
                    else -> cont.resume(accessToken)
                }
            }
        }
        // Only write when a refresh actually changed something (e.g. a rotated
        // refresh token), not on every Web API call.
        if (current.jsonSerializeString() != before) persist(current)
        return token
    }

    // Caller holds the mutex.
    private suspend fun loadState(): AuthState? {
        state?.let { return it }
        val json = withContext(io) { tokenStore.load() } ?: return null
        return try {
            AuthState.jsonDeserialize(json).also { state = it }
        } catch (_: JSONException) {
            withContext(io) { tokenStore.clear() }
            null
        }
    }

    private suspend fun persist(value: AuthState) {
        val json = value.jsonSerializeString()
        withContext(io) { tokenStore.save(json) }
    }

    private suspend fun exchange(request: TokenRequest): TokenResponse = suspendCancellableCoroutine { cont ->
        service.performTokenRequest(request) { response, ex ->
            when {
                response != null -> cont.resume(response)
                else -> cont.resumeWithException(ex ?: AuthorizationException.GeneralErrors.SERVER_ERROR)
            }
        }
    }

    companion object {
        // Must match manifestPlaceholders["appAuthRedirectScheme"] in app/build.gradle.kts,
        // and be registered verbatim under Redirect URIs in the Spotify dashboard.
        val REDIRECT_URI: Uri = Uri.parse("io.github.arthur044.wallpaperchanger://callback")

        private val SCOPES = listOf("user-read-currently-playing", "user-read-playback-state")

        private val SERVICE_CONFIG = AuthorizationServiceConfiguration(
            Uri.parse("https://accounts.spotify.com/authorize"),
            Uri.parse("https://accounts.spotify.com/api/token"),
        )
    }
}
