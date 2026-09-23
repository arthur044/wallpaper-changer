package io.github.arthur044.wallpaperchanger.sync

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.arthur044.wallpaperchanger.MainActivity
import io.github.arthur044.wallpaperchanger.R
import io.github.arthur044.wallpaperchanger.WallpaperApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Quick Settings tile: turns syncing on and off, mirroring the saved choice. */
class SyncTileService : TileService() {
    private val container get() = (application as WallpaperApp).container
    private var listening: Job? = null

    override fun onStartListening() {
        listening = container.appScope.launch(Dispatchers.Main) {
            container.settings.settings.map { it.syncEnabled }.distinctUntilChanged().collect(::show)
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
    }

    override fun onClick() {
        val turnOn = qsTile?.state != Tile.STATE_ACTIVE
        container.appScope.launch {
            if (!turnOn) {
                container.syncController.disable()
                return@launch
            }
            // Signed out, or not allowed to start from the tile: the app can do both.
            val started = container.spotifyAuth.status().signedIn && container.syncController.enable()
            if (!started) withContext(Dispatchers.Main) { openApp() }
        }
    }

    private fun show(on: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (on) R.string.tile_on else R.string.tile_off)
        }
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            // The PendingIntent overload only exists from Android 14; below it this is the only way.
            @Suppress("DEPRECATION")
            @SuppressLint("StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
