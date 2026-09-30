package io.github.arthur044.wallpaperchanger.core.lyrics

import io.github.arthur044.wallpaperchanger.core.NowPlaying
import io.github.arthur044.wallpaperchanger.core.sync.SyncStatus

/**
 * The track "share lyrics" refers to, or null for no button. It is the one on the
 * wallpaper: the one playing, or, with the music paused (Idle), the one the
 * wallpaper still shows. When the wallpaper isn't being kept up (paused sync,
 * signed out, blocked, a failed draw) the button stays away.
 */
fun shareTarget(status: SyncStatus, onScreen: NowPlaying?): NowPlaying? = when (status) {
    is SyncStatus.Showing -> status.nowPlaying
    SyncStatus.Idle -> onScreen
    else -> null
}
