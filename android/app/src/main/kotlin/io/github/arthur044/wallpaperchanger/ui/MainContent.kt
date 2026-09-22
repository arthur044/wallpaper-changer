package io.github.arthur044.wallpaperchanger.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.arthur044.wallpaperchanger.R
import io.github.arthur044.wallpaperchanger.core.config.Settings
import io.github.arthur044.wallpaperchanger.core.sync.SyncStatus
import io.github.arthur044.wallpaperchanger.sync.describe
import kotlin.math.roundToInt

data class MainUiState(
    val syncEnabled: Boolean,
    val status: SyncStatus,
    val signedIn: Boolean,
    val settings: Settings,
    val art: ImageBitmap? = null,
    /** Whether the user granted notification access, which "react instantly" needs. */
    val notificationAccess: Boolean = false,
)

class MainCallbacks(
    val onSyncEnabledChange: (Boolean) -> Unit = {},
    val onSyncNow: () -> Unit = {},
    /** A change that alters the picture: saved, then the wallpaper is redrawn. */
    val onLookChange: ((Settings) -> Settings) -> Unit = {},
    /** A change that doesn't alter the picture: just saved. */
    val onSettingsChange: ((Settings) -> Settings) -> Unit = {},
    val onInstantChange: (Boolean) -> Unit = {},
    val onGrantNotificationAccess: () -> Unit = {},
    val onConnect: () -> Unit = {},
    val onOpenDebug: () -> Unit = {},
)

/** The main screen, stateless: everything comes in through [state] and goes out through [callbacks]. */
@Composable
fun MainContent(state: MainUiState, callbacks: MainCallbacks, modifier: Modifier = Modifier) {
    // Phones use the full width; tablets and unfolded foldables get a readable column.
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .widthIn(max = MAX_CONTENT_WIDTH)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            if (!state.signedIn) SignedOutCard(callbacks.onConnect)
            StatusCard(state, callbacks)
            LookSection(state.settings, callbacks.onLookChange)
            InstantSection(state, callbacks)
            AdvancedSection(state.settings, callbacks.onSettingsChange)
            TextButton(onClick = callbacks.onOpenDebug, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.main_debug_tools))
            }
        }
    }
}

@Composable
private fun SignedOutCard(onConnect: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Text(
                stringResource(R.string.main_signed_out_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onConnect) { Text(stringResource(R.string.main_signed_out_action)) }
        }
    }
}

@Composable
private fun StatusCard(state: MainUiState, callbacks: MainCallbacks) {
    val statusText = if (state.syncEnabled) state.status.describe(LocalContext.current) else stringResource(R.string.sync_status_off)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AlbumArt(state.art)
                Text(statusText, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag(TAG_STATUS))
            }
            SwitchRow(
                label = stringResource(R.string.main_sync_switch),
                checked = state.syncEnabled,
                onCheckedChange = callbacks.onSyncEnabledChange,
                modifier = Modifier.testTag(TAG_SYNC_SWITCH),
            )
            FilledTonalButton(
                onClick = callbacks.onSyncNow,
                enabled = state.syncEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.main_sync_now)) }
        }
    }
}

@Composable
private fun AlbumArt(art: ImageBitmap?) {
    val shape = RoundedCornerShape(12.dp)
    val description = stringResource(R.string.main_art_description)
    if (art != null) {
        Image(art, description, contentScale = ContentScale.Crop, modifier = Modifier.size(ART_SIZE).clip(shape))
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(ART_SIZE).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Icon(painterResource(R.drawable.ic_stat_sync), description, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LookSection(settings: Settings, onLookChange: ((Settings) -> Settings) -> Unit) {
    Section(stringResource(R.string.main_section_look)) {
        SettingSlider(
            label = stringResource(R.string.main_art_size),
            value = (settings.artSizePct * 100).toFloat(),
            range = Settings.ART_SIZE_PCT_RANGE.toPercentRange(),
            display = { stringResource(R.string.value_percent, it.roundToInt()) },
            onCommit = { pct -> onLookChange { it.copy(artSizePct = pct.roundToInt() / 100.0) } },
            tag = TAG_ART_SIZE,
        )
        SettingSlider(
            label = stringResource(R.string.main_corner_radius),
            value = settings.cornerRadius.toFloat(),
            range = Settings.CORNER_RADIUS_RANGE.toFloatRange(),
            display = { stringResource(R.string.value_number, it.roundToInt()) },
            onCommit = { r -> onLookChange { it.copy(cornerRadius = r.roundToInt()) } },
            tag = TAG_CORNERS,
        )
        SettingSlider(
            label = stringResource(R.string.main_shadow),
            value = settings.shadowBlurRadius.toFloat(),
            range = Settings.BLUR_RADIUS_RANGE.toFloatRange(),
            display = { stringResource(R.string.value_number, it.roundToInt()) },
            onCommit = { b -> onLookChange { it.copy(shadowBlurRadius = b.roundToInt()) } },
            tag = TAG_SHADOW,
        )
        SettingSlider(
            label = stringResource(R.string.main_offset),
            value = (settings.artOffsetYPct * 100).toFloat(),
            range = Settings.ART_OFFSET_Y_PCT_RANGE.toPercentRange(),
            display = { stringResource(R.string.value_signed_percent, it.roundToInt()) },
            onCommit = { pct -> onLookChange { it.copy(artOffsetYPct = pct.roundToInt() / 100.0) } },
            tag = TAG_OFFSET,
        )
        SwitchRow(
            label = stringResource(R.string.main_show_track_info),
            checked = settings.showTrackInfo,
            onCheckedChange = { on -> onLookChange { it.copy(showTrackInfo = on) } },
            modifier = Modifier.testTag(TAG_TRACK_INFO),
        )
        SwitchRow(
            label = stringResource(R.string.main_sync_lock_screen),
            checked = settings.syncLockScreen,
            onCheckedChange = { on -> onLookChange { it.copy(syncLockScreen = on) } },
            modifier = Modifier.testTag(TAG_LOCK_SCREEN),
        )
    }
}

@Composable
private fun AdvancedSection(settings: Settings, onSettingsChange: ((Settings) -> Settings) -> Unit) {
    Section(stringResource(R.string.main_section_advanced)) {
        SettingSlider(
            label = stringResource(R.string.main_poll_interval),
            value = settings.webApiPollIntervalSeconds.toFloat(),
            range = Settings.POLL_INTERVAL_SECONDS_RANGE.let { it.start.toFloat()..it.endInclusive.toFloat() },
            display = { stringResource(R.string.value_seconds, it.roundToInt()) },
            onCommit = { s -> onSettingsChange { it.copy(webApiPollIntervalSeconds = s.roundToInt().toDouble()) } },
            tag = TAG_POLL,
        )
        Text(
            stringResource(R.string.main_poll_interval_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InstantSection(state: MainUiState, callbacks: MainCallbacks) {
    Section(stringResource(R.string.main_media_session)) {
        SwitchRow(
            label = stringResource(R.string.main_media_session),
            checked = state.settings.useMediaSession && state.notificationAccess,
            onCheckedChange = { on ->
                if (on && !state.notificationAccess) callbacks.onGrantNotificationAccess() else callbacks.onInstantChange(on)
            },
            modifier = Modifier.testTag(TAG_MEDIA_SESSION),
        )
        Text(
            stringResource(R.string.main_media_session_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.settings.useMediaSession && !state.notificationAccess) {
            TextButton(onClick = callbacks.onGrantNotificationAccess, modifier = Modifier.testTag(TAG_GRANT_ACCESS)) {
                Text(stringResource(R.string.main_media_session_grant))
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

private fun ClosedFloatingPointRange<Double>.toPercentRange() = (start * 100).toFloat()..(endInclusive * 100).toFloat()

private fun IntRange.toFloatRange() = first.toFloat()..last.toFloat()

private val MAX_CONTENT_WIDTH = 600.dp
private val ART_SIZE = 88.dp

internal const val TAG_STATUS = "status"
internal const val TAG_SYNC_SWITCH = "syncSwitch"
internal const val TAG_ART_SIZE = "artSize"
internal const val TAG_CORNERS = "corners"
internal const val TAG_SHADOW = "shadow"
internal const val TAG_OFFSET = "offset"
internal const val TAG_TRACK_INFO = "trackInfo"
internal const val TAG_LOCK_SCREEN = "lockScreen"
internal const val TAG_POLL = "pollInterval"
internal const val TAG_MEDIA_SESSION = "mediaSession"
internal const val TAG_GRANT_ACCESS = "grantAccess"
