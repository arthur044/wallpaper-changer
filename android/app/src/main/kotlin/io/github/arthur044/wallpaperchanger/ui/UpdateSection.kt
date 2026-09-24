package io.github.arthur044.wallpaperchanger.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.arthur044.wallpaperchanger.R
import io.github.arthur044.wallpaperchanger.core.update.InstallOutcome
import io.github.arthur044.wallpaperchanger.core.update.UpdateChannel
import io.github.arthur044.wallpaperchanger.core.update.UpdateFailure
import io.github.arthur044.wallpaperchanger.core.update.UpdatePhase
import io.github.arthur044.wallpaperchanger.core.update.UpdateState
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class UpdateCallbacks(
    val onUpdate: () -> Unit = {},
    val onSelectBranch: (String) -> Unit = {},
    val onRefreshBranches: () -> Unit = {},
    /** Opens the system's "install unknown apps" page for this app. */
    val onAllowInstalls: () -> Unit = {},
    /** Reopens the installer's confirmation screen. */
    val onConfirmInstall: () -> Unit = {},
)

/**
 * Updating the app from the builds CI publishes. Release: one button, the
 * latest release. Debug: a branch picker, then the newest build of that branch.
 */
@Composable
internal fun UpdateSection(state: UpdateState, callbacks: UpdateCallbacks, confirmationPending: Boolean = false) {
    Section(stringResource(R.string.update_section)) {
        Text(stringResource(R.string.update_installed, state.installed.versionName))
        val debug = state.installed.channel == UpdateChannel.DEBUG
        Text(
            stringResource(if (debug) R.string.update_channel_debug else R.string.update_channel_release),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (debug) BranchPicker(state, callbacks)
        FilledTonalButton(
            onClick = callbacks.onUpdate,
            enabled = !state.busy && (!debug || state.selectedBranch != null),
            modifier = Modifier.testTag(TAG_UPDATE_BUTTON),
        ) {
            Text(stringResource(if (debug) R.string.update_button_debug else R.string.update_button_release))
        }
        PhaseMessage(state.phase, callbacks, confirmationPending, debug)
    }
}

@Composable
private fun BranchPicker(state: UpdateState, callbacks: UpdateCallbacks) {
    var expanded by remember { mutableStateOf(false) }
    val label = when {
        state.branchesLoading -> stringResource(R.string.update_branch_loading)
        state.branches.isEmpty() -> stringResource(R.string.update_branch_none)
        else -> state.selectedBranch ?: stringResource(R.string.update_branch_label)
    }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = !state.busy && state.branches.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().testTag(TAG_BRANCH_PICKER),
        ) { Text("${stringResource(R.string.update_branch_label)}: $label") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.branches.forEach { branch ->
                DropdownMenuItem(
                    text = { Text(branch) },
                    onClick = {
                        expanded = false
                        callbacks.onSelectBranch(branch)
                    },
                )
            }
        }
    }
    TextButton(onClick = callbacks.onRefreshBranches, enabled = !state.busy) {
        Text(stringResource(R.string.update_branch_refresh))
    }
}

@Composable
private fun PhaseMessage(phase: UpdatePhase, callbacks: UpdateCallbacks, confirmationPending: Boolean, debug: Boolean) {
    val text = when (phase) {
        UpdatePhase.Idle -> null
        UpdatePhase.Checking -> stringResource(R.string.update_checking)
        UpdatePhase.NoBuild -> stringResource(R.string.update_no_build)
        is UpdatePhase.UpToDate -> stringResource(R.string.update_up_to_date, phase.info.versionName)
        is UpdatePhase.Older -> stringResource(
            if (debug) R.string.update_older else R.string.update_older_release,
            phase.info.versionName,
        )
        is UpdatePhase.WrongPackage -> stringResource(R.string.update_wrong_package)
        is UpdatePhase.NeedsPermission -> stringResource(R.string.update_needs_permission, phase.info.versionName)
        is UpdatePhase.Downloading -> stringResource(R.string.update_downloading, phase.info.versionName)
        is UpdatePhase.Installing -> stringResource(R.string.update_installing, phase.info.versionName)
        is UpdatePhase.Failed -> failureText(phase.failure)
    }
    if (phase is UpdatePhase.Checking || phase is UpdatePhase.Downloading) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    text?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = if (phase is UpdatePhase.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(TAG_UPDATE_MESSAGE),
        )
    }
    if (phase is UpdatePhase.NeedsPermission) {
        TextButton(onClick = callbacks.onAllowInstalls) { Text(stringResource(R.string.update_allow)) }
    }
    if (phase is UpdatePhase.Installing && confirmationPending) {
        TextButton(onClick = callbacks.onConfirmInstall, modifier = Modifier.testTag(TAG_CONFIRM_INSTALL)) {
            Text(stringResource(R.string.update_confirm))
        }
    }
}

@Composable
private fun failureText(failure: UpdateFailure): String = when (failure) {
    UpdateFailure.NoNetwork -> stringResource(R.string.update_failed_network)
    is UpdateFailure.RateLimited -> failure.resetsAt?.let {
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(it)
        stringResource(R.string.update_failed_rate_limited, time)
    } ?: stringResource(R.string.update_failed_rate_limited_later)
    UpdateFailure.BadData -> stringResource(R.string.update_failed_bad_data)
    UpdateFailure.Checksum -> stringResource(R.string.update_failed_checksum)
    is UpdateFailure.Install -> when (failure.outcome) {
        InstallOutcome.BLOCKED -> stringResource(R.string.update_failed_blocked)
        InstallOutcome.CONFLICT -> stringResource(R.string.update_failed_conflict)
        InstallOutcome.STORAGE -> stringResource(R.string.update_failed_storage)
        InstallOutcome.INVALID -> stringResource(R.string.update_failed_invalid)
        else -> stringResource(R.string.update_failed_other, failure.systemMessage.orEmpty())
    }
}

internal const val TAG_UPDATE_BUTTON = "updateButton"
internal const val TAG_BRANCH_PICKER = "branchPicker"
internal const val TAG_UPDATE_MESSAGE = "updateMessage"
internal const val TAG_CONFIRM_INSTALL = "confirmInstall"
