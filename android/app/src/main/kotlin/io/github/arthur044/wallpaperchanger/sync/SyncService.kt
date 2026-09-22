package io.github.arthur044.wallpaperchanger.sync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.arthur044.wallpaperchanger.WallpaperApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the wallpaper in sync while the screen is on: the engine runs when the
 * screen turns on (polling right away) and is cancelled when it turns off, so a
 * phone in a pocket makes no API calls.
 */
class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine get() = (application as WallpaperApp).container.syncEngine
    private lateinit var notifications: SyncNotifications
    private var loop: Job? = null

    override fun onCreate() {
        super.onCreate()
        notifications = SyncNotifications(this).apply { ensureChannel() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, SyncNotifications.ONGOING_ID, notifications.ongoing(engine.status.value), foregroundType(),
        )
        if (loop == null) loop = scope.launch { syncWhileScreenOn() }
        return START_STICKY
    }

    private suspend fun syncWhileScreenOn() = coroutineScope {
        launch { engine.status.collect(notifications::update) }
        screenOnFlow().collectLatest { on ->
            if (!on) return@collectLatest
            engine.run()
            // run() only returns when the session was revoked: nothing to do until a new login.
            notifications.showSignedOut()
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
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
