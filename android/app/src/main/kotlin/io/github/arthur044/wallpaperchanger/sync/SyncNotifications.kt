package io.github.arthur044.wallpaperchanger.sync

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.arthur044.wallpaperchanger.MainActivity
import io.github.arthur044.wallpaperchanger.R
import io.github.arthur044.wallpaperchanger.core.sync.SyncStatus

/** The service's ongoing notification, and the one left behind if the session expires. */
internal class SyncNotifications(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannel() {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.sync_channel_name))
            .setDescription(context.getString(R.string.sync_channel_description))
            .setShowBadge(false)
            .build()
        manager.createNotificationChannel(channel)
    }

    fun ongoing(status: SyncStatus): Notification {
        val stop = PendingIntent.getService(
            context, REQUEST_STOP, SyncService.stopIntent(context), PendingIntent.FLAG_IMMUTABLE,
        )
        return base(describe(status))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, context.getString(R.string.sync_action_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun update(status: SyncStatus) = post(ONGOING_ID, ongoing(status))

    /** Outlives the service, so the user learns why syncing stopped. */
    fun showStopped(status: SyncStatus) = post(STOPPED_ID, base(describe(status)).setAutoCancel(true).build())

    fun describe(status: SyncStatus): String = when (status) {
        SyncStatus.Starting -> context.getString(R.string.sync_status_starting)
        SyncStatus.Paused -> context.getString(R.string.sync_status_paused)
        SyncStatus.Idle -> context.getString(R.string.sync_status_idle)
        is SyncStatus.Showing -> context.getString(
            R.string.sync_status_showing,
            status.nowPlaying.trackName.orEmpty(),
            status.nowPlaying.artistName.orEmpty(),
        )
        is SyncStatus.RenderFailed ->
            context.getString(R.string.sync_status_render_failed, status.nowPlaying.trackName.orEmpty())
        is SyncStatus.Retrying ->
            context.getString(R.string.sync_status_retrying, status.retryIn.inWholeSeconds.toInt())
        SyncStatus.SignedOut -> context.getString(R.string.sync_status_signed_out)
        is SyncStatus.Blocked -> context.getString(R.string.sync_status_blocked)
    }

    private fun base(text: String): NotificationCompat.Builder {
        val open = PendingIntent.getActivity(
            context, REQUEST_OPEN, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setSilent(true)
            .setContentIntent(open)
    }

    // Without the permission (Android 13+) the service still runs, just unseen.
    private fun post(id: Int, notification: Notification) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) manager.notify(id, notification)
    }

    companion object {
        const val ONGOING_ID = 1
        private const val STOPPED_ID = 2
        private const val CHANNEL_ID = "sync"
        private const val REQUEST_OPEN = 0
        private const val REQUEST_STOP = 1
    }
}
