package io.github.arthur044.wallpaperchanger.core.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

enum class UpdateChannel { RELEASE, DEBUG }

/** The build running now. */
data class InstalledBuild(
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    /** The branch it was built from (BuildConfig.GIT_BRANCH). */
    val branch: String,
    val channel: UpdateChannel,
)

/** Installs an APK over this app; the Android side lives in :app. */
interface Installer {
    /** Whether the user allowed this app to install apps. */
    fun canInstall(): Boolean

    /** Hands [apk] to the system; the outcome comes back through [UpdateController.onInstallOutcome]. */
    suspend fun install(apk: File)
}

/** How the system's installer ended, mapped from PackageInstaller's status codes. */
enum class InstallOutcome {
    SUCCESS,

    /** The user declined the confirmation. */
    CANCELLED,

    /** Blocked by the device: Play Protect, a policy, or "install unknown apps" off. */
    BLOCKED,

    /** Signed with another key, or older than what is installed. */
    CONFLICT,

    /** Not enough space. */
    STORAGE,

    /** The APK is broken or doesn't fit this device. */
    INVALID,
    FAILED,
}

sealed interface UpdateFailure {
    data object NoNetwork : UpdateFailure

    /** GitHub's 60 calls an hour are used up until [resetsAt]. */
    data class RateLimited(val resetsAt: Instant?) : UpdateFailure

    /** update.json or GitHub's answer didn't make sense. */
    data object BadData : UpdateFailure

    /** The download wasn't the file update.json describes; it was deleted. */
    data object Checksum : UpdateFailure

    data class Install(val outcome: InstallOutcome, val systemMessage: String?) : UpdateFailure
}

sealed interface UpdatePhase {
    data object Idle : UpdatePhase

    data object Checking : UpdatePhase

    /** Nothing published for this channel or branch. */
    data object NoBuild : UpdatePhase

    data class UpToDate(val info: UpdateInfo) : UpdatePhase

    /** A debug build older than the installed one: Android refuses the downgrade. */
    data class Older(val info: UpdateInfo) : UpdatePhase

    /** What is published is for another app id. */
    data class WrongPackage(val info: UpdateInfo) : UpdatePhase

    /** "Install unknown apps" is off for this app; the user must allow it, then retry. */
    data class NeedsPermission(val info: UpdateInfo) : UpdatePhase

    data class Downloading(val info: UpdateInfo) : UpdatePhase

    /** Handed to the system, which asks the user to confirm. */
    data class Installing(val info: UpdateInfo) : UpdatePhase

    data class Failed(val failure: UpdateFailure) : UpdatePhase
}

data class UpdateState(
    val installed: InstalledBuild,
    /** Debug only: the branches with a build, newest first. */
    val branches: List<String> = emptyList(),
    val selectedBranch: String? = null,
    val branchesLoading: Boolean = false,
    val phase: UpdatePhase = UpdatePhase.Idle,
) {
    /**
     * An action is running. Installing is not one: the system owns that step,
     * and if its confirmation never shows (the app was in the background) or
     * is left with Home, no outcome ever comes back. The button must work again.
     */
    val busy: Boolean
        get() = branchesLoading || phase is UpdatePhase.Checking || phase is UpdatePhase.Downloading
}

/**
 * The update screen's logic. Release: one button, the latest release.
 * Debug: pick a branch (the installed build's own preselected), then fetch
 * its newest build. Either way a newer build is downloaded, checked against
 * its SHA-256 and handed to the system installer. One action at a time.
 */
class UpdateController(
    private val source: UpdateSource,
    private val installer: Installer,
    installed: InstalledBuild,
    private val downloadDir: File,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(UpdateState(installed))
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    private var debugBranches: List<DebugBranch> = emptyList()
    private var job: Job? = null

    /** Debug channel: (re)loads the branch list; keeps the selection while it still exists. */
    fun loadBranches() {
        if (state.value.installed.channel != UpdateChannel.DEBUG) return
        launchOnce { refreshBranches() }
    }

    fun selectBranch(branch: String) {
        if (state.value.busy) return
        mutableState.update { it.copy(selectedBranch = branch, phase = UpdatePhase.Idle) }
    }

    /** The button: check, and if a newer build exists, download and install it. */
    fun update() = launchOnce {
        val installed = state.value.installed
        setPhase(UpdatePhase.Checking)
        val check = when (installed.channel) {
            UpdateChannel.RELEASE -> source.checkRelease(installed.packageName, installed.versionCode)
            UpdateChannel.DEBUG -> {
                if (debugBranches.isEmpty()) refreshBranches()
                val branch = debugBranches.firstOrNull { it.branch == state.value.selectedBranch }
                    ?: return@launchOnce setPhase(UpdatePhase.NoBuild)
                source.checkDebug(branch, installed.packageName, installed.versionCode)
            }
        }
        val found = when (check) {
            UpdateCheck.NoBuild -> return@launchOnce setPhase(UpdatePhase.NoBuild)
            is UpdateCheck.WrongPackage -> return@launchOnce setPhase(UpdatePhase.WrongPackage(check.info))
            is UpdateCheck.Found -> check
        }
        when (found.comparison) {
            VersionComparison.SAME -> return@launchOnce setPhase(UpdatePhase.UpToDate(found.info))
            VersionComparison.OLDER -> return@launchOnce setPhase(UpdatePhase.Older(found.info))
            VersionComparison.NEWER -> Unit
        }
        if (!installer.canInstall()) return@launchOnce setPhase(UpdatePhase.NeedsPermission(found.info))
        setPhase(UpdatePhase.Downloading(found.info))
        val apk = source.download(found, downloadDir)
        setPhase(UpdatePhase.Installing(found.info))
        installer.install(apk)
    }

    /** What the system installer reported. Success replaces the process, so it rarely arrives. */
    fun onInstallOutcome(outcome: InstallOutcome, systemMessage: String?) {
        setPhase(
            when (outcome) {
                InstallOutcome.SUCCESS -> UpdatePhase.Idle
                // Declining is the user's choice, not an error.
                InstallOutcome.CANCELLED -> UpdatePhase.Idle
                else -> UpdatePhase.Failed(UpdateFailure.Install(outcome, systemMessage))
            },
        )
    }

    private suspend fun refreshBranches() {
        mutableState.update { it.copy(branchesLoading = true) }
        try {
            debugBranches = source.debugBranches()
        } finally {
            mutableState.update { it.copy(branchesLoading = false) }
        }
        mutableState.update { current ->
            val keep = current.selectedBranch?.takeIf { selected -> debugBranches.any { it.branch == selected } }
            current.copy(
                branches = debugBranches.map(DebugBranch::branch),
                selectedBranch = keep ?: preselectedBranch(debugBranches, current.installed.branch)?.branch,
            )
        }
    }

    private fun setPhase(phase: UpdatePhase) = mutableState.update { it.copy(phase = phase) }

    // One action at a time; a tap while one runs is ignored. Errors become the Failed phase.
    private fun launchOnce(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setPhase(UpdatePhase.Failed(e.toFailure()))
            }
        }
    }

    private fun Exception.toFailure(): UpdateFailure = when (this) {
        is UpdateRateLimitedException -> UpdateFailure.RateLimited(resetsAt)
        is UpdateNetworkException -> UpdateFailure.NoNetwork
        is ChecksumMismatchException -> UpdateFailure.Checksum
        is InvalidUpdateInfoException, is InvalidReleaseResponseException -> UpdateFailure.BadData
        else -> UpdateFailure.Install(InstallOutcome.FAILED, message)
    }
}
