package io.github.arthur044.wallpaperchanger.core.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GithubReleasesTest {
    private fun release(tag: String, name: String?, prerelease: Boolean, withUpdateJson: Boolean = true): String {
        val nameField = name?.let { "\"$it\"" } ?: "null"
        val assets = buildList {
            add(asset("wallpaper-changer-93.apk", tag))
            if (withUpdateJson) add(asset("update.json", tag))
        }.joinToString(",")
        return """{"tag_name":"$tag","name":$nameField,"prerelease":$prerelease,"draft":false,
            "html_url":"https://github.com/arthur044/wallpaper-changer/releases/tag/$tag","assets":[$assets]}"""
    }

    private fun asset(file: String, tag: String) =
        """{"name":"$file","size":1,"browser_download_url":"https://github.com/arthur044/wallpaper-changer/releases/download/$tag/$file"}"""

    private fun list(vararg releases: String) = releases.joinToString(",", "[", "]")

    @Test
    fun `the latest release gives its update json and apk urls`() {
        val latest = parseRelease(release("r92", "0.1.0 (9fb338d)", prerelease = false))

        assertEquals("r92", latest.tagName)
        assertEquals("https://github.com/arthur044/wallpaper-changer/releases/download/r92/update.json", latest.updateJsonUrl)
        assertEquals(
            "https://github.com/arthur044/wallpaper-changer/releases/download/r92/wallpaper-changer-93.apk",
            latest.assetUrl("wallpaper-changer-93.apk"),
        )
        assertNull(latest.assetUrl("missing.apk"))
    }

    @Test
    fun `debug branches come from the titles, once each, newest first`() {
        val releases = parseReleases(
            list(
                release("debug-ci-android-spike", "ci/android-spike", prerelease = true),
                release("r92", "0.1.0 (9fb338d)", prerelease = false),
                release("debug-feat-foo", "feat/foo", prerelease = true),
                // Two tags titled with the same branch (a slug collision).
                release("debug-ci-android-spike-2", "ci/android-spike", prerelease = true),
            ),
        )

        val branches = debugBranches(releases)

        assertEquals(listOf("ci/android-spike", "feat/foo"), branches.map { it.branch })
        assertEquals("debug-ci-android-spike", branches.first().release.tagName)
    }

    @Test
    fun `a debug release without a title falls back to its tag`() {
        val releases = parseReleases(
            list(release("debug-feat-foo", null, prerelease = true), release("debug-fix-bar", "", prerelease = true)),
        )

        assertEquals(listOf("feat-foo", "fix-bar"), debugBranches(releases).map { it.branch })
    }

    @Test
    fun `releases that aren't debug builds, or can't be installed, are not branches`() {
        val releases = parseReleases(
            list(
                release("r92", "main", prerelease = false),
                // A debug tag published as a full release by hand.
                release("debug-oops", "oops", prerelease = false),
                release("v1-beta", "beta", prerelease = true),
                release("debug-no-json", "no-json", prerelease = true, withUpdateJson = false),
            ),
        )

        assertEquals(emptyList<DebugBranch>(), debugBranches(releases))
    }

    @Test
    fun `no releases at all means no branches`() {
        assertEquals(emptyList<DebugBranch>(), debugBranches(parseReleases("[]")))
        assertNull(preselectedBranch(emptyList(), installedBranch = "ci/android-spike"))
    }

    @Test
    fun `the installed build's branch is preselected while it has a build`() {
        val branches = debugBranches(
            parseReleases(
                list(
                    release("debug-feat-foo", "feat/foo", prerelease = true),
                    release("debug-ci-android-spike", "ci/android-spike", prerelease = true),
                ),
            ),
        )

        assertEquals("ci/android-spike", preselectedBranch(branches, installedBranch = "ci/android-spike")?.branch)
        assertEquals("feat/foo", preselectedBranch(branches, installedBranch = "deleted/branch")?.branch, "newest instead")
        assertEquals("feat/foo", preselectedBranch(branches, installedBranch = null)?.branch)
    }

    @Test
    fun `an unexpected response is an error`() {
        // What GitHub answers when rate limited.
        val rateLimited = """{"message":"API rate limit exceeded for 1.2.3.4.","documentation_url":"https://docs.github.com"}"""

        assertThrows<InvalidReleaseResponseException> { parseReleases(rateLimited) }
        assertThrows<InvalidReleaseResponseException> { parseRelease(rateLimited) }
        assertThrows<InvalidReleaseResponseException> { parseReleases("not json") }
    }
}
