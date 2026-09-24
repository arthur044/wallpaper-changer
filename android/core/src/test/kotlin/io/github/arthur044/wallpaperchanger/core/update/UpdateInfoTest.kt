package io.github.arthur044.wallpaperchanger.core.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UpdateInfoTest {
    private val sha = "1655dfd23932d162660cc5db4756389ad1a870ddb6bb9ddfe366348bbd3b64d0"

    // Shaped like what android/ci/write-update-json.sh wrote on CI.
    private fun updateJson(
        versionCode: String = "92",
        sha256: String = "\"$sha\"",
        extra: String = "",
        drop: Set<String> = emptySet(),
    ): String {
        val fields = linkedMapOf(
            "channel" to "\"release\"",
            "package" to "\"io.github.arthur044.wallpaperchanger\"",
            "versionCode" to versionCode,
            "versionName" to "\"0.1.0 (9fb338d)\"",
            "commit" to "\"9fb338d00d6cf15b094cae9a8a0c38094b2bb6ea\"",
            "branch" to "\"main\"",
            "apk" to "\"wallpaper-changer-92.apk\"",
            "sha256" to sha256,
            "notes" to "\"ci: something\"",
            "builtAt" to "\"2026-09-24T15:29:36Z\"",
        ).filterKeys { it !in drop }
        return fields.entries.joinToString(",", "{", "$extra}") { (k, v) -> "\"$k\": $v" }
    }

    @Test
    fun `a complete update json is read`() {
        val info = parseUpdateInfo(updateJson())

        assertEquals(92, info.versionCode)
        assertEquals("0.1.0 (9fb338d)", info.versionName)
        assertEquals("io.github.arthur044.wallpaperchanger", info.`package`)
        assertEquals("main", info.branch)
        assertEquals("wallpaper-changer-92.apk", info.apk)
        assertEquals(sha, info.sha256)
    }

    @Test
    fun `fields added later are ignored`() {
        assertEquals(92, parseUpdateInfo(updateJson(extra = ", \"minSdk\": 26")).versionCode)
    }

    @Test
    fun `notes and build time are optional`() {
        val info = parseUpdateInfo(updateJson(drop = setOf("notes", "builtAt")))

        assertEquals("", info.notes)
        assertEquals("", info.builtAt)
    }

    @Test
    fun `a missing required field is an error`() {
        listOf("versionCode", "sha256", "apk", "package", "branch").forEach { field ->
            assertThrows<InvalidUpdateInfoException>(field) { parseUpdateInfo(updateJson(drop = setOf(field))) }
        }
    }

    @Test
    fun `malformed json is an error`() {
        assertThrows<InvalidUpdateInfoException> { parseUpdateInfo("{\"versionCode\": ") }
        assertThrows<InvalidUpdateInfoException> { parseUpdateInfo("<html>rate limited</html>") }
        assertThrows<InvalidUpdateInfoException> { parseUpdateInfo("") }
    }

    @Test
    fun `a wrong type is an error`() {
        assertThrows<InvalidUpdateInfoException> { parseUpdateInfo(updateJson(versionCode = "\"ninety\"")) }
    }

    @Test
    fun `values that can't be installed safely are errors`() {
        assertThrows<InvalidUpdateInfoException> { parseUpdateInfo(updateJson(versionCode = "0")) }
        assertThrows<InvalidUpdateInfoException> { parseUpdateInfo(updateJson(sha256 = "\"abc\"")) }
        assertThrows<InvalidUpdateInfoException> { parseUpdateInfo(updateJson(sha256 = "\"${sha.uppercase()}\"")) }
    }

    @Test
    fun `the apk must be a plain file name`() {
        // It becomes a path in the download directory.
        listOf("../escape.apk", "dir/app.apk", ".hidden.apk", "app.zip", "").forEach { name ->
            assertThrows<InvalidUpdateInfoException>(name) {
                parseUpdateInfo(updateJson().replace("wallpaper-changer-92.apk", name))
            }
        }
    }

    @Test
    fun `a published build is compared by versionCode`() {
        val available = parseUpdateInfo(updateJson(versionCode = "92"))

        assertEquals(VersionComparison.NEWER, compareVersion(installedVersionCode = 90, available))
        assertEquals(VersionComparison.SAME, compareVersion(installedVersionCode = 92, available))
        assertEquals(VersionComparison.OLDER, compareVersion(installedVersionCode = 93, available))
    }

    @Test
    fun `the first CI build is newer than the hand-built 1`() {
        // The phone's release came from build-apk.ps1 with versionCode 1.
        assertEquals(VersionComparison.NEWER, compareVersion(1, parseUpdateInfo(updateJson())))
    }
}
