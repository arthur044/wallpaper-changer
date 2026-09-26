package io.github.arthur044.wallpaperchanger.sync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.arthur044.wallpaperchanger.WallpaperApp
import io.github.arthur044.wallpaperchanger.media.MediaSessionProbe
import io.github.arthur044.wallpaperchanger.media.notificationAccessGranted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the wallpaper in sync while the screen is on: the engine runs when the
 * screen turns on (polling right away) and is cancelled when it turns off, so a
 * phone in a pocket makes no API calls.
 */
class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val container get() = (application as WallpaperApp).container
    private val engine get() = container.syncEngine
    private lateinit var notifications: SyncNotifications
    private var loop: Job? = null

    override fun onCreate() {
        super.onCreate()
        notifications = SyncNotifications(this).apply { ensureChannel() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The user's "off", same as in the app: don't come back after a reboot.
            container.syncController.disableInBackground()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, SyncNotifications.ONGOING_ID, notifications.ongoing(engine.status.value), foregroundType(),
            )
        } catch (e: IllegalStateException) {
            // Android 12+ may refuse a restart from the background (e.g. a sticky
            // restart after the process was killed). Give up quietly; the next
            // boot, update or app launch brings it back.
            Log.w(TAG, "Not allowed to run in the foreground now", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop == null) loop = scope.launch { syncWhileScreenOn() }
        return START_STICKY
    }

    private suspend fun syncWhileScreenOn() = coroutineScope {
        launch { engine.status.collect(notifications::update) }
        launch { internetAvailableFlow().collect { online -> if (online) engine.onNetworkAvailable() } }
        launch { followLocalSession() }
        screenOnFlow().collectLatest { on ->
            if (!on) return@collectLatest
            engine.run()
            // run() only returns when retrying can't help (session revoked, wallpaper
            // blocked): say why, then stop. The user's "on" stays saved.
            notifications.showStopped(engine.status.value)
            stopSelf()
        }
    }

    /**
     * Feeds the engine Spotify's own session while the option is on and the
     * user granted notification access. Turning either off tells the engine to
     * forget it, so it goes back to polling.
     */
    private suspend fun followLocalSession() {
        container.settings.settings
            .map { it.useMediaSession }
            .distinctUntilChanged()
            .collectLatest { wanted ->
                if (!wanted || !notificationAccessGranted()) {
                    engine.onLocalTrack(null)
                    return@collectLatest
                }
                MediaSessionProbe(this).snapshots().collect { snapshot ->
                    engine.onLocalTrack(snapshot.toLocalTrack())
                }
            }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SyncService"
        private const val ACTION_STOP = "io.github.arthur044.wallpaperchanger.action.STOP_SYNC"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SyncService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncService::class.java))
        }

        internal fun stopIntent(context: Context): Intent =
            Intent(context, SyncService::class.java).setAction(ACTION_STOP)

        private fun foregroundType(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
    }
}
