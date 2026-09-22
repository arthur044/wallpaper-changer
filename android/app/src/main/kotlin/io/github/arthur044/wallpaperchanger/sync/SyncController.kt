package io.github.arthur044.wallpaperchanger.sync

import android.content.Context
import android.util.Log
import io.github.arthur044.wallpaperchanger.auth.SpotifyAuth
import io.github.arthur044.wallpaperchanger.core.config.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The one place that turns syncing on and off. The user's choice is saved
 * (Settings.syncEnabled), so the service can come back on its own after a
 * reboot, an app update or a new login.
 */
class SyncController(
    private val context: Context,
    private val settings: SettingsRepository,
    private val auth: SpotifyAuth,
    private val scope: CoroutineScope,
) {
    /**
     * @return false if the system refused to start the service from where we are
     * (Android 12+ background limits); the choice is saved anyway, so opening
     * the app starts it.
     */
    suspend fun enable(): Boolean {
        settings.update { it.copy(syncEnabled = true) }
        return startService()
    }

    suspend fun disable() {
        settings.update { it.copy(syncEnabled = false) }
        SyncService.stop(context)
    }

    /** For callers that can't suspend (the notification's Stop action). */
    fun disableInBackground() {
        scope.launch { disable() }
    }

    /**
     * Starts the service again if the user left syncing on and a session
     * exists; a signed-out app would only poll its way to "sign in again".
     */
    suspend fun resumeIfEnabled(): Boolean {
        if (!settings.settings.first().syncEnabled || !auth.status().signedIn) return false
        return startService()
    }

    private fun startService(): Boolean = try {
        SyncService.start(context)
        true
    } catch (e: IllegalStateException) { // ForegroundServiceStartNotAllowedException
        Log.w(TAG, "Not allowed to start syncing from here", e)
        false
    }

    private companion object {
        const val TAG = "SyncController"
    }
}
