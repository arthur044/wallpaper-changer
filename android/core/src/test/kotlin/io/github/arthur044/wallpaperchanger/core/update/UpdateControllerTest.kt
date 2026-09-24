package io.github.arthur044.wallpaperchanger.core.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant

class UpdateControllerTest {
    private val dir = File("unused")

    private fun info(versionCode: Int, branch: String = "main", pkg: String = RELEASE_PKG) = UpdateInfo(
        channel = "release", `package` = pkg, versionCode = versionCode, versionName = "0.1.0 ($versionCode)",
        commit = "c$versionCode", branch = branch, apk = "wallpaper-changer-$versionCode.apk", sha256 = "0".repeat(64),
    )

    private fun release(tag: String, name: String) = GithubRelease(
        tagName = tag, name = name, prerelease = tag.startsWith("debug-"),
        assets = listOf(GithubAsset("update.json", "https://x/$tag/update.json")),
    )

    private class FakeSource : UpdateSource {
        var releaseCheck: suspend () -> UpdateCheck = { UpdateCheck.NoBuild }
        var branches: List<DebugBranch> = emptyList()
        val debugChecks = mutableMapOf<String, UpdateCheck>()
        val downloads = mutableListOf<UpdateInfo>()
        var downloadGate: CompletableDeferred<Unit>? = null

        override suspend fun checkRelease(installedPackage: String, installedVersionCode: Int) = releaseCheck()

        override suspend fun debugBranches() = branches

        override suspend fun checkDebug(branch: DebugBranch, installedPackage: String, installedVersionCode: Int) =
            debugChecks.getValue(branch.branch)

        override suspend fun download(found: UpdateCheck.Found, dir: File): File {
            downloadGate?.await()
            downloads += found.info
            return File(found.info.apk)
        }
    }

    private class FakeInstaller(var allowed: Boolean = true) : Installer {
        val installed = mutableListOf<String>()

        override fun canInstall() = allowed

        override suspend fun install(apk: File) {
            installed += apk.name
        }
    }

    private val source = FakeSource()
    private val installer = FakeInstaller()

    private fun TestScope.controller(
        channel: UpdateChannel = UpdateChannel.RELEASE,
        versionCode: Int = 90,
        branch: String = "main",
    ) = UpdateController(
        source,
        installer,
        InstalledBuild(if (channel == UpdateChannel.DEBUG) DEBUG_PKG else RELEASE_PKG, versionCode, "0.1.0", branch, channel),
        dir,
        backgroundScope,
    )

    private fun found(info: UpdateInfo, comparison: VersionComparison, tag: String = "r${info.versionCode}") =
        UpdateCheck.Found(info, release(tag, info.branch), comparison)

    @Test
    fun `a newer release is downloaded and handed to the installer`() = runTest {
        source.releaseCheck = { found(info(92), VersionComparison.NEWER) }
        val c = controller()

        c.update()
        runCurrent()

        assertEquals(listOf(92), source.downloads.map { it.versionCode })
        assertEquals(listOf("wallpaper-changer-92.apk"), installer.installed)
        assertEquals(UpdatePhase.Installing(info(92)), c.state.value.phase)
    }

    @Test
    fun `the installed version is up to date`() = runTest {
        source.releaseCheck = { found(info(90), VersionComparison.SAME) }
        val c = controller()

        c.update()
        runCurrent()

        assertEquals(UpdatePhase.UpToDate(info(90)), c.state.value.phase)
        assertTrue(source.downloads.isEmpty())
    }

    @Test
    fun `without the install permission nothing is downloaded`() = runTest {
        installer.allowed = false
        source.releaseCheck = { found(info(92), VersionComparison.NEWER) }
        val c = controller()

        c.update()
        runCurrent()

        assertEquals(UpdatePhase.NeedsPermission(info(92)), c.state.value.phase)
        assertTrue(source.downloads.isEmpty(), "don't spend 15 MB on something that can't be installed yet")
    }

    @Test
    fun `nothing published yet says so`() = runTest {
        val c = controller()

        c.update()
        runCurrent()

        assertEquals(UpdatePhase.NoBuild, c.state.value.phase)
    }

    @Test
    fun `errors become specific failures`() = runTest {
        val cases = listOf(
            UpdateRateLimitedException(Instant.ofEpochSecond(10)) to UpdateFailure.RateLimited(Instant.ofEpochSecond(10)),
            UpdateNetworkException("down") to UpdateFailure.NoNetwork,
            InvalidUpdateInfoException("bad") to UpdateFailure.BadData,
            ChecksumMismatchException("bad file") to UpdateFailure.Checksum,
        )
        val c = controller()
        cases.forEach { (error, failure) ->
            source.releaseCheck = { throw error }
            c.update()
            runCurrent()
            assertEquals(UpdatePhase.Failed(failure), c.state.value.phase, "$error")
        }
    }

    @Test
    fun `a tap while downloading is ignored`() = runTest {
        source.releaseCheck = { found(info(92), VersionComparison.NEWER) }
        source.downloadGate = CompletableDeferred()
        val c = controller()

        c.update()
        runCurrent()
        assertTrue(c.state.value.busy)
        c.update()
        source.downloadGate!!.complete(Unit)
        runCurrent()

        assertEquals(1, source.downloads.size)
    }

    @Test
    fun `an install the system never answers doesn't lock the button`() = runTest {
        // The confirmation can be blocked (app in the background) or left with
        // Home: no outcome arrives, and the user must be able to try again.
        source.releaseCheck = { found(info(92), VersionComparison.NEWER) }
        val c = controller()
        c.update()
        runCurrent()
        assertEquals(UpdatePhase.Installing(info(92)), c.state.value.phase)

        assertTrue(!c.state.value.busy)
        c.update()
        runCurrent()

        assertEquals(2, installer.installed.size)
    }

    @Test
    fun `the installer's answer is shown, and declining is not an error`() = runTest {
        val c = controller()

        c.onInstallOutcome(InstallOutcome.BLOCKED, "blocked by Play Protect")
        assertEquals(
            UpdatePhase.Failed(UpdateFailure.Install(InstallOutcome.BLOCKED, "blocked by Play Protect")),
            c.state.value.phase,
        )

        c.onInstallOutcome(InstallOutcome.CANCELLED, null)
        assertEquals(UpdatePhase.Idle, c.state.value.phase)
    }

    @Test
    fun `the release channel has no branch list`() = runTest {
        source.branches = listOf(DebugBranch("feat/foo", release("debug-feat-foo", "feat/foo")))
        val c = controller()

        c.loadBranches()
        runCurrent()

        assertEquals(emptyList<String>(), c.state.value.branches)
    }

    @Test
    fun `debug preselects the installed build's branch`() = runTest {
        source.branches = listOf(
            DebugBranch("feat/foo", release("debug-feat-foo", "feat/foo")),
            DebugBranch("ci/android-spike", release("debug-ci-android-spike", "ci/android-spike")),
        )
        val c = controller(UpdateChannel.DEBUG, branch = "ci/android-spike")

        c.loadBranches()
        runCurrent()

        assertEquals(listOf("feat/foo", "ci/android-spike"), c.state.value.branches)
        assertEquals("ci/android-spike", c.state.value.selectedBranch)
    }

    @Test
    fun `debug installs the chosen branch's newer build`() = runTest {
        source.branches = listOf(
            DebugBranch("feat/foo", release("debug-feat-foo", "feat/foo")),
            DebugBranch("ci/android-spike", release("debug-ci-android-spike", "ci/android-spike")),
        )
        val fooBuild = info(95, branch = "feat/foo", pkg = DEBUG_PKG)
        source.debugChecks["feat/foo"] = found(fooBuild, VersionComparison.NEWER, "debug-feat-foo")
        val c = controller(UpdateChannel.DEBUG, branch = "ci/android-spike")
        c.loadBranches()
        runCurrent()

        c.selectBranch("feat/foo")
        c.update()
        runCurrent()

        assertEquals(listOf("wallpaper-changer-95.apk"), installer.installed)
    }

    @Test
    fun `an older build on another branch is explained, not attempted`() = runTest {
        source.branches = listOf(DebugBranch("feat/old", release("debug-feat-old", "feat/old")))
        val old = info(80, branch = "feat/old", pkg = DEBUG_PKG)
        source.debugChecks["feat/old"] = found(old, VersionComparison.OLDER, "debug-feat-old")
        val c = controller(UpdateChannel.DEBUG, branch = "ci/android-spike", versionCode = 94)

        c.update() // loads the branches first
        runCurrent()

        assertEquals(UpdatePhase.Older(old), c.state.value.phase)
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun `debug with no branch builds at all says there's nothing`() = runTest {
        val c = controller(UpdateChannel.DEBUG, branch = "ci/android-spike")

        c.update()
        runCurrent()

        assertEquals(UpdatePhase.NoBuild, c.state.value.phase)
        assertEquals(null, c.state.value.selectedBranch)
    }

    private companion object {
        const val RELEASE_PKG = "io.github.arthur044.wallpaperchanger"
        const val DEBUG_PKG = "io.github.arthur044.wallpaperchanger.debug"
    }
}
