package io.github.arthur044.wallpaperchanger.media

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import io.github.arthur044.wallpaperchanger.WallpaperApp
import io.github.arthur044.wallpaperchanger.sync.screenOnFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Enabled by the user under "Notification access". Its existence is what lets
 * MediaSessionManager hand out Spotify's session at all.
 *
 * In local-only mode (M15) it also *runs* the sync: the system keeps this
 * service bound while access is granted, so the wallpaper can follow the music
 * with no foreground service and no ongoing notification. It only ever sees
 * what plays on this phone, which is exactly what that mode promises.
 */
class SpotifyMediaListener : NotificationListenerService() {
    private val container get() = (application as WallpaperApp).container
    private var scope: CoroutineScope? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { this.scope = it }
        scope.launch { followSettings() }
    }

    override fun onListenerDisconnected() {
        scope?.cancel()
        scope = null
        super.onListenerDisconnected()
    }

    private suspend fun followSettings() {
        container.settings.settings
            .map { it.syncEnabled && it.useMediaSession && it.localOnly }
            .distinctUntilChanged()
            .collectLatest { localModeWanted ->
                if (!localModeWanted) return@collectLatest
                Log.i(TAG, "Local-only sync running from the notification listener")
                syncWhileScreenOn()
            }
    }

    private suspend fun syncWhileScreenOn() = coroutineScope {
        launch {
            MediaSessionProbe(this@SpotifyMediaListener).snapshots().collect { snapshot ->
                container.syncEngine.onLocalTrack(snapshot.toLocalTrack())
            }
        }
        screenOnFlow().collectLatest { on ->
            if (!on) return@collectLatest
            container.syncEngine.run()
        }
    }

    private companion object {
        const val TAG = "SpotifyMediaListener"
    }
}

/** Whether the user granted notification access, i.e. whether sessions are readable. */
fun Context.notificationAccessGranted(): Boolean =
    packageName in NotificationManagerCompat.getEnabledListenerPackages(this)

fun Context.mediaListenerComponent(): ComponentName = ComponentName(this, SpotifyMediaListener::class.java)
