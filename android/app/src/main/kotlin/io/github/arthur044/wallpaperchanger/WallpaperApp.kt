package io.github.arthur044.wallpaperchanger

import android.app.Application
import android.content.Intent
import android.util.Log
import io.github.arthur044.wallpaperchanger.auth.EncryptedTokenStore
import io.github.arthur044.wallpaperchanger.auth.SpotifyAuth
import io.github.arthur044.wallpaperchanger.core.config.SettingsRepository
import io.github.arthur044.wallpaperchanger.core.lyrics.LrclibClient
import io.github.arthur044.wallpaperchanger.core.lyrics.LyricsPrefetch
import io.github.arthur044.wallpaperchanger.core.lyrics.LyricsSlot
import io.github.arthur044.wallpaperchanger.core.render.CanvasSpec
import io.github.arthur044.wallpaperchanger.share.LyricsShare
import io.github.arthur044.wallpaperchanger.share.ShareFiles
import io.github.arthur044.wallpaperchanger.share.ShareRenderer
import kotlinx.coroutines.flow.first
import io.github.arthur044.wallpaperchanger.core.spotify.ArtDownloader
import io.github.arthur044.wallpaperchanger.core.render.canvasSpec
import io.github.arthur044.wallpaperchanger.core.render.resizes
import io.github.arthur044.wallpaperchanger.core.spotify.SpotifyApi
import io.github.arthur044.wallpaperchanger.core.sync.FileRenderMemory
import io.github.arthur044.wallpaperchanger.core.sync.FileTrackIndexStore
import io.github.arthur044.wallpaperchanger.core.sync.SyncEngine
import io.github.arthur044.wallpaperchanger.core.update.InstalledBuild
import io.github.arthur044.wallpaperchanger.core.update.UpdateChannel
import io.github.arthur044.wallpaperchanger.core.update.UpdateClient
import io.github.arthur044.wallpaperchanger.core.update.UpdateController
import io.github.arthur044.wallpaperchanger.update.ApkInstaller
import io.github.arthur044.wallpaperchanger.sync.SyncController
import io.github.arthur044.wallpaperchanger.render.canvasSpecs
import io.github.arthur044.wallpaperchanger.render.defaultDisplayWindowContext
import io.github.arthur044.wallpaperchanger.render.screenMetrics
import io.github.arthur044.wallpaperchanger.wallpaper.LiveWallpaper
import io.github.arthur044.wallpaperchanger.wallpaper.LiveWallpaperFrames
import io.github.arthur044.wallpaperchanger.wallpaper.LiveWallpaperStatus
import io.github.arthur044.wallpaperchanger.wallpaper.SystemLiveWallpaperStatus
import io.github.arthur044.wallpaperchanger.wallpaper.WallpaperUpdater
import io.github.arthur044.wallpaperchanger.render.AlbumBaseCache
import io.github.arthur044.wallpaperchanger.render.WallpaperComposer
import io.github.arthur044.wallpaperchanger.render.WallpaperRenderer
import io.github.arthur044.wallpaperchanger.wallpaper.SystemWallpaperPort
import io.github.arthur044.wallpaperchanger.wallpaper.WallpaperApplier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
    private val screen = app.defaultDisplayWindowContext()

    /** The latest image, for the live wallpaper (the smooth-transition mode). */
    val liveFrames = LiveWallpaperFrames(File(app.filesDir, "live_wallpaper/frame.bin"))

    val liveWallpaperStatus: LiveWallpaperStatus = SystemLiveWallpaperStatus(app)

    val wallpaperUpdater = WallpaperUpdater(
        composer,
        applier,
        settings.settings,
        LiveWallpaper(liveFrames, liveWallpaperStatus),
        canvas = ::currentCanvas,
    )

    /** The wallpaper's canvas for this screen right now; the share image uses the same. */
    fun currentCanvas(): CanvasSpec = canvasSpec(screen.screenMetrics())

    private val renderMemory = FileRenderMemory.create(
        file = File(app.filesDir, "sync_state/last_track.txt"),
        scope = appScope,
        onError = { Log.w(TAG, "Could not read or save the last drawn track", it) },
    )

    private val trackIndexStore = FileTrackIndexStore.create(
        file = File(app.filesDir, "sync_state/track_index.json"),
        scope = appScope,
        onError = { Log.w(TAG, "Could not read or save the track index", it) },
    )

    val syncEngine = SyncEngine(
        source = { spotifyApi.currentlyPlaying() },
        sink = wallpaperUpdater,
        settings = settings.settings,
        memory = renderMemory,
        albumTracks = { albumId -> spotifyApi.albumTracks(albumId) },
        trackIndexStore = trackIndexStore,
    )

    val syncController = SyncController(app, settings, spotifyAuth, appScope)

    /**
     * Lyrics for the share screen, in memory only. Only the main screen asks,
     * while it is visible (LyricsPrefetch.follow); the sync never does.
     */
    val lyrics = LyricsPrefetch(LyricsSlot(LrclibClient(userAgent = "WallpaperChanger/${BuildConfig.VERSION_NAME} (Android)")))

    /** Lyrics share images, from the wallpaper's cached base; one temporary file at most. */
    val lyricsShare = LyricsShare(
        composer = composer,
        renderer = ShareRenderer(renderer),
        files = ShareFiles.forApp(app),
        canvas = ::currentCanvas,
        settings = { settings.settings.first() },
    )

    /**
     * The installer's confirmation screen while an update waits for it. Kept
     * so the update section can reopen it: starting it from the background is
     * blocked, and the user may leave it with Home.
     */
    val pendingInstallConfirmation = MutableStateFlow<Intent?>(null)

    val apkInstaller = ApkInstaller(app)

    /** In-app updates from the builds CI publishes on GitHub Releases. */
    val updates = UpdateController(
        source = UpdateClient(),
        installer = apkInstaller,
        installed = InstalledBuild(
            packageName = app.packageName,
            versionCode = BuildConfig.VERSION_CODE,
            versionName = BuildConfig.VERSION_NAME,
            branch = BuildConfig.GIT_BRANCH,
            channel = if (BuildConfig.DEBUG) UpdateChannel.DEBUG else UpdateChannel.RELEASE,
        ),
        downloadDir = File(app.cacheDir, "updates"),
        scope = appScope,
    )

    init {
        // A foldable opened or closed, or the display size (zoom) changed: redraw
        // the track on screen for the new canvas. No Web API call; if the sync is
        // stopped, it redraws on start.
        appScope.launch { screen.canvasSpecs().resizes().collect { syncEngine.redraw() } }
    }

    private companion object {
        const val TAG = "WallpaperApp"
    }
}
