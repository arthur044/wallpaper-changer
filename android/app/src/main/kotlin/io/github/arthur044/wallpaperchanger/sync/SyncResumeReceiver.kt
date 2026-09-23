package io.github.arthur044.wallpaperchanger.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.arthur044.wallpaperchanger.WallpaperApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Brings syncing back after a reboot or an app update, if the user left it on.
 * Both broadcasts are among the few that may still start a foreground service
 * from the background (Android 12+).
 */
class SyncResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RESUME_ACTIONS) return
        val container = (context.applicationContext as WallpaperApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                val resumed = container.syncController.resumeIfEnabled()
                Log.i(TAG, "${intent.action}: sync ${if (resumed) "resumed" else "left off"}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Could not resume sync after ${intent.action}", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SyncResumeReceiver"
        val RESUME_ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
