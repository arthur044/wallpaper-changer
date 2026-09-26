package io.github.arthur044.wallpaperchanger.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.arthur044.wallpaperchanger.R
import io.github.arthur044.wallpaperchanger.share.SharePhase
import io.github.arthur044.wallpaperchanger.share.ShareUiState

class ShareCallbacks(
    val onBack: () -> Unit = {},
    val onTap: (Int) -> Unit = {},
    val onShare: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/** Choosing verses and sharing them as an image; stateless like [MainContent]. */
@Composable
fun LyricsShareScreen(state: ShareUiState, callbacks: ShareCallbacks, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.widthIn(max = MAX_CONTENT_WIDTH).fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = callbacks.onBack) { Text(stringResource(R.string.share_back)) }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.share_title), style = MaterialTheme.typography.titleLarge)
                    val playing = state.nowPlaying
                    if (playing != null) {
                        Text(
                            listOfNotNull(playing.trackName, playing.artistName).joinToString(" — "),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            when (state.phase) {
                SharePhase.LOADING -> Waiting(stringResource(R.string.share_loading))
                SharePhase.NO_LYRICS -> Message(stringResource(R.string.share_no_lyrics))
                SharePhase.INSTRUMENTAL -> Message(stringResource(R.string.share_instrumental))
                SharePhase.NO_NETWORK -> Message(stringResource(R.string.share_no_network), callbacks.onRetry)
                SharePhase.IMAGE_UNAVAILABLE -> Message(stringResource(R.string.share_image_unavailable))
                SharePhase.CHOOSING -> Choosing(state, callbacks, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Waiting(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.testTag(TAG_SHARE_LOADING))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Message(text: String, onRetry: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag(TAG_SHARE_MESSAGE))
        if (onRetry != null) OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.share_retry)) }
    }
}

@Composable
private fun Choosing(state: ShareUiState, callbacks: ShareCallbacks, modifier: Modifier) {
    val preview = state.preview
    if (preview != null) {
        val image = remember(preview) { preview.asImageBitmap() }
        Image(
            image,
            stringResource(R.string.share_preview_description),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = PREVIEW_MAX_HEIGHT).testTag(TAG_SHARE_PREVIEW),
        )
    } else if (!state.ready || state.selection != null) {
        Waiting(stringResource(R.string.share_preparing))
    }
    Text(
        stringResource(if (state.tooLong) R.string.share_too_long else R.string.share_hint),
        style = MaterialTheme.typography.bodySmall,
        color = if (state.tooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LazyColumn(modifier.fillMaxWidth().testTag(TAG_SHARE_LINES)) {
        itemsIndexed(state.lines) { index, line ->
            VerseLine(line, selected = state.selection?.range?.contains(index) == true, enabled = state.ready) {
                callbacks.onTap(index)
            }
        }
    }
    if (state.saveFailed) {
        Text(stringResource(R.string.share_save_failed), color = MaterialTheme.colorScheme.error)
    }
    Text(stringResource(R.string.share_source), style = MaterialTheme.typography.labelSmall)
    Button(
        onClick = callbacks.onShare,
        enabled = state.ready && state.selection != null && !state.saving,
        modifier = Modifier.fillMaxWidth().testTag(TAG_SHARE_BUTTON),
    ) { Text(stringResource(R.string.share_action)) }
}

@Composable
private fun VerseLine(line: String, selected: Boolean, enabled: Boolean, onTap: () -> Unit) {
    if (line.isBlank()) {
        Box(Modifier.fillMaxWidth().heightIn(min = 12.dp))
        return
    }
    val shape = RoundedCornerShape(8.dp)
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Text(
        line,
        style = MaterialTheme.typography.bodyLarge,
        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .clickable(enabled = enabled, onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

private val PREVIEW_MAX_HEIGHT = 280.dp
const val TAG_SHARE_LOADING = "share_loading"
const val TAG_SHARE_MESSAGE = "share_message"
const val TAG_SHARE_PREVIEW = "share_preview"
const val TAG_SHARE_LINES = "share_lines"
const val TAG_SHARE_BUTTON = "share_button"
