package io.github.arthur044.wallpaperchanger.ui

import android.Manifest
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.arthur044.wallpaperchanger.AppContainer
import io.github.arthur044.wallpaperchanger.BuildConfig
import io.github.arthur044.wallpaperchanger.core.spotify.ArtSource
import io.github.arthur044.wallpaperchanger.core.sync.SyncStatus
import io.github.arthur044.wallpaperchanger.core.update.InstallOutcome
import io.github.arthur044.wallpaperchanger.media.notificationAccessGranted
import io.github.arthur044.wallpaperchanger.wallpaper.LiveWallpaperService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Wires [MainContent] to the app: settings, the engine's status, the controller. */
@Composable
fun MainScreen(
    container: AppContainer,
    onConnect: () -> Unit,
    onOpenDebug: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Writes go to the app scope so they finish even if the screen goes away.
    val scope = container.appScope
    val settings by container.settings.settings.collectAsState(initial = null)
    val status by container.syncEngine.status.collectAsState()
    val update by container.updates.state.collectAsState()
    val pendingConfirmation by container.pendingInstallConfirmation.collectAsState()
    LaunchedEffect(Unit) { container.updates.loadBranches() }
    var signedIn by remember { mutableStateOf(true) }
    LaunchedEffect(status) { signedIn = container.spotifyAuth.status().signedIn }

    val artUrl = (status as? SyncStatus.Showing)?.nowPlaying?.artUrl
    val art by produceState<ImageBitmap?>(null, artUrl) {
        value = artUrl?.let { loadThumbnail(container.artDownloader, it) }
    }

    // The service runs without it; the permission only makes the notification visible.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        scope.launch { container.syncController.enable() }
    }
    val enableSync = {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            scope.launch { container.syncController.enable() }
        }
    }

    // Both are granted or picked outside the app, so re-read them every time the screen comes back.
    var notificationAccess by remember { mutableStateOf(context.notificationAccessGranted()) }
    var liveWallpaperActive by remember { mutableStateOf(container.liveWallpaperStatus.isActive()) }
    LifecycleResumeEffect(Unit) {
        notificationAccess = context.notificationAccessGranted()
        liveWallpaperActive = container.liveWallpaperStatus.isActive()
        onPauseOrDispose { }
    }

    val current = settings ?: return // first read of the settings file
    MainContent(
        state = MainUiState(
            syncEnabled = current.syncEnabled,
            status = status,
            signedIn = signedIn,
            settings = current,
            art = art,
            notificationAccess = notificationAccess,
            liveWallpaperActive = liveWallpaperActive,
            showDebugTools = BuildConfig.DEBUG,
            update = update,
            updateConfirmationPending = pendingConfirmation != null,
        ),
        callbacks = MainCallbacks(
            onSyncEnabledChange = { on ->
                if (on) enableSync() else scope.launch { container.syncController.disable() }
            },
            onSyncNow = container.syncEngine::syncNow,
            onLookChange = { change ->
                scope.launch {
                    container.settings.update(change)
                    container.syncEngine.redraw()
                }
            },
            onSettingsChange = { change -> scope.launch { container.settings.update(change) } },
            onInstantChange = { on ->
                scope.launch {
                    container.settings.update { it.copy(useMediaSession = on, localOnly = it.localOnly && on) }
                    container.syncController.applyRunMode()
                }
            },
            onLocalOnlyChange = { on ->
                scope.launch {
                    container.settings.update { it.copy(localOnly = on) }
                    container.syncController.applyRunMode()
                }
            },
            onGrantNotificationAccess = {
                scope.launch { container.settings.update { it.copy(useMediaSession = true) } }
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            onSmoothTransitionChange = { on ->
                scope.launch {
                    container.settings.update { it.copy(smoothTransition = on) }
                    // On: hands the current image to the live wallpaper before it is
                    // picked. Off: sets it statically, which replaces the live one.
                    container.syncEngine.redraw()
                }
                if (on && !liveWallpaperActive) context.pickLiveWallpaper()
            },
            onPickLiveWallpaper = { context.pickLiveWallpaper() },
            onConnect = onConnect,
            onOpenDebug = onOpenDebug,
            update = UpdateCallbacks(
                onUpdate = container.updates::update,
                onSelectBranch = container.updates::selectBranch,
                onRefreshBranches = { container.updates.loadBranches(force = true) },
                onAllowInstalls = { context.allowInstallingApps() },
                onConfirmInstall = {
                    pendingConfirmation?.let { confirm ->
                        runCatching { context.startActivity(confirm) }.onFailure { e ->
                            // The session is gone (expired, or already answered): say so.
                            container.pendingInstallConfirmation.value = null
                            container.updates.onInstallOutcome(InstallOutcome.FAILED, e.message)
                        }
                    }
                },
            ),
        ),
        modifier = modifier,
    )
}

// The system picker, opened on this app's live wallpaper; the generic
// chooser where a launcher lacks the direct one.
private fun Context.pickLiveWallpaper() {
    val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
        WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
        ComponentName(this, LiveWallpaperService::class.java),
    )
    try {
        startActivity(direct)
    } catch (e: ActivityNotFoundException) {
        runCatching { startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }
    }
}

// The system page where the user lets this app install apps (needed for updates).
private fun Context.allowInstallingApps() {
    startActivity(
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

// A small copy of the art for the status card; null if it can't be fetched now.
private suspend fun loadThumbnail(art: ArtSource, url: String): ImageBitmap? = try {
    val bytes = art.download(url)
    withContext(Dispatchers.Default) {
        val options = BitmapFactory.Options().apply { inSampleSize = THUMBNAIL_SAMPLE }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

private const val THUMBNAIL_SAMPLE = 2
