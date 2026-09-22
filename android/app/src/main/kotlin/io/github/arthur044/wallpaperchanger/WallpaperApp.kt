package io.github.arthur044.wallpaperchanger

import android.app.Application
import android.util.Log
import io.github.arthur044.wallpaperchanger.auth.EncryptedTokenStore
import io.github.arthur044.wallpaperchanger.auth.SpotifyAuth
import io.github.arthur044.wallpaperchanger.core.config.SettingsRepository
import io.github.arthur044.wallpaperchanger.core.spotify.ArtDownloader
import io.github.arthur044.wallpaperchanger.core.render.canvasSpec
import io.github.arthur044.wallpaperchanger.core.spotify.SpotifyApi
import io.github.arthur044.wallpaperchanger.core.sync.FileRenderMemory
import io.github.arthur044.wallpaperchanger.core.sync.SyncEngine
import io.github.arthur044.wallpaperchanger.sync.SyncController
import io.github.arthur044.wallpaperchanger.render.defaultDisplayWindowContext
import io.github.arthur044.wallpaperchanger.render.screenMetrics
import io.github.arthur044.wallpaperchanger.wallpaper.WallpaperUpdater
import io.github.arthur044.wallpaperchanger.render.AlbumBaseCache
import io.github.arthur044.wallpaperchanger.render.WallpaperComposer
import io.github.arthur044.wallpaperchanger.render.WallpaperRenderer
import io.github.arthur044.wallpaperchanger.wallpaper.SystemWallpaperPort
import io.github.arthur044.wallpaperchanger.wallpaper.WallpaperApplier
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
    /** Lives as long as the process: work that must outlive a screen or a receiver. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: SettingsRepository = SettingsRepository.create(
        file = File(app.filesDir, "datastore/settings.json"),
        scope = appScope,
        onCorruption = { Log.e(TAG, "Settings file was unreadable; reset to defaults", it) },
    )

    val spotifyAuth = SpotifyAuth(app, EncryptedTokenStore(app))

    val spotifyApi = SpotifyApi(spotifyAuth)

    val artDownloader = ArtDownloader()

    val renderer = WallpaperRenderer()

    val composer = WallpaperComposer(artDownloader, renderer, AlbumBaseCache(File(app.cacheDir, "album_bases")))

    val applier = WallpaperApplier(SystemWallpaperPort(app))

    // Screen size is read from a window context: the service has no Activity.
    private val screen by lazy { app.defaultDisplayWindowContext() }

    val wallpaperUpdater = WallpaperUpdater(composer, applier, settings.settings) {
        canvasSpec(screen.screenMetrics())
    }

    private val renderMemory = FileRenderMemory.create(
        file = File(app.filesDir, "sync_state/last_track.txt"),
        scope = appScope,
        onError = { Log.w(TAG, "Could not read or save the last drawn track", it) },
    )

    val syncEngine = SyncEngine(
        source = { spotifyApi.currentlyPlaying() },
        sink = wallpaperUpdater,
        settings = settings.settings,
        memory = renderMemory,
        albumTracks = { albumId -> spotifyApi.albumTracks(albumId) },
    )

    val syncController = SyncController(app, settings, spotifyAuth, appScope)

    private companion object {
        const val TAG = "WallpaperApp"
    }
}
