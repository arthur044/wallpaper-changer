package io.github.arthur044.wallpaperchanger.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import io.github.arthur044.wallpaperchanger.WallpaperApp
import io.github.arthur044.wallpaperchanger.core.update.InstallOutcome

/**
 * Where a PackageInstaller session reports back. The first report is usually
 * "needs the user": the system hands over its confirmation screen, shown here.
 * Success replaces the process, so it is rarely seen; failures go to the
 * update screen. Not exported: only the session's PendingIntent reaches it.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = confirmationIntent(intent)
            if (confirm != null) {
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            Log.w(TAG, "The installer asked for confirmation without a screen to show")
        }
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        (context.applicationContext as WallpaperApp).container.updates.onInstallOutcome(installOutcome(status), message)
    }

    private fun installOutcome(status: Int): InstallOutcome = when (status) {
        PackageInstaller.STATUS_SUCCESS -> InstallOutcome.SUCCESS
        PackageInstaller.STATUS_FAILURE_ABORTED -> InstallOutcome.CANCELLED
        PackageInstaller.STATUS_FAILURE_BLOCKED -> InstallOutcome.BLOCKED
        PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallOutcome.CONFLICT
        PackageInstaller.STATUS_FAILURE_STORAGE -> InstallOutcome.STORAGE
        PackageInstaller.STATUS_FAILURE_INVALID, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> InstallOutcome.INVALID
        else -> InstallOutcome.FAILED
    }

    private fun confirmationIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private companion object {
        const val TAG = "InstallResultReceiver"
    }
}
