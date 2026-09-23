package io.github.arthur044.wallpaperchanger.ui

import android.Manifest
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.arthur044.wallpaperchanger.AppContainer
import io.github.arthur044.wallpaperchanger.BuildConfig
import io.github.arthur044.wallpaperchanger.core.spotify.ArtSource
import io.github.arthur044.wallpaperchanger.core.sync.SyncStatus
import io.github.arthur044.wallpaperchanger.media.notificationAccessGranted
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

    // Granted outside the app, so re-read it every time the screen comes back.
    var notificationAccess by remember { mutableStateOf(context.notificationAccessGranted()) }
    LifecycleResumeEffect(Unit) {
        notificationAccess = context.notificationAccessGranted()
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
            showDebugTools = BuildConfig.DEBUG,
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
            onConnect = onConnect,
            onOpenDebug = onOpenDebug,
        ),
        modifier = modifier,
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
