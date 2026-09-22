package io.github.arthur044.wallpaperchanger

import android.app.Application
import android.util.Log
import io.github.arthur044.wallpaperchanger.auth.EncryptedTokenStore
import io.github.arthur044.wallpaperchanger.auth.SpotifyAuth
import io.github.arthur044.wallpaperchanger.core.config.SettingsRepository
import io.github.arthur044.wallpaperchanger.core.spotify.ArtDownloader
import io.github.arthur044.wallpaperchanger.core.spotify.SpotifyApi
import io.github.arthur044.wallpaperchanger.render.WallpaperRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class WallpaperApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Manual DI: one instance of each long-lived dependency for the whole process. */
class AppContainer(app: Application) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: SettingsRepository = SettingsRepository.create(
        file = File(app.filesDir, "datastore/settings.json"),
        scope = appScope,
        onCorruption = { Log.e(TAG, "Settings file was unreadable; reset to defaults", it) },
    )

    val spotifyAuth = SpotifyAuth(app, EncryptedTokenStore(app))

    val spotifyApi = SpotifyApi(spotifyAuth)

    val artDownloader = ArtDownloader()

    val renderer = WallpaperRenderer()

    private companion object {
        const val TAG = "WallpaperApp"
    }
}
