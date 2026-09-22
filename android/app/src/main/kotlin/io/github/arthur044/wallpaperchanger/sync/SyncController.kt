package io.github.arthur044.wallpaperchanger.sync

import android.content.Context
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
    suspend fun enable() {
        settings.update { it.copy(syncEnabled = true) }
        SyncService.start(context)
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
        SyncService.start(context)
        return true
    }
}
