package io.github.arthur044.wallpaperchanger.sync

import android.content.Context
import io.github.arthur044.wallpaperchanger.R
import io.github.arthur044.wallpaperchanger.core.sync.SyncStatus

/** One line for the user, shared by the notification and the main screen. */
fun SyncStatus.describe(context: Context): String = when (this) {
    SyncStatus.Starting -> context.getString(R.string.sync_status_starting)
    SyncStatus.Paused -> context.getString(R.string.sync_status_paused)
    SyncStatus.Idle -> context.getString(R.string.sync_status_idle)
    is SyncStatus.Showing -> context.getString(
        R.string.sync_status_showing,
        nowPlaying.trackName.orEmpty(),
        nowPlaying.artistName.orEmpty(),
    )
    is SyncStatus.RenderFailed ->
        context.getString(R.string.sync_status_render_failed, nowPlaying.trackName.orEmpty())
    is SyncStatus.Retrying -> context.getString(R.string.sync_status_retrying, retryIn.inWholeSeconds.toInt())
    SyncStatus.SignedOut -> context.getString(R.string.sync_status_signed_out)
    is SyncStatus.Blocked -> context.getString(R.string.sync_status_blocked)
}
