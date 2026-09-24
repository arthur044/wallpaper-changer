package io.github.arthur044.wallpaperchanger.core.update

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.time.Instant

class UpdateClientTest {
    private val server = MockWebServer()
    private lateinit var client: UpdateClient

    @TempDir
    lateinit var dir: File

    private val apkBytes = ByteArray(200_000) { (it % 251).toByte() }
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apkBytes).joinToString("") { "%02x".format(it) }

    @BeforeEach
    fun start() {
        server.start()
        client = UpdateClient(apiBase = server.url("/repos/o/r/"))
    }

    @AfterEach
    fun stop() = server.close()

    private fun asset(file: String) = """{"name":"$file","browser_download_url":"${server.url("/download/$file")}"}"""

    private fun release(tag: String, name: String, prerelease: Boolean) =
        """{"tag_name":"$tag","name":"$name","prerelease":$prerelease,
            "assets":[${asset("wallpaper-changer-92.apk")},${asset("update.json")}]}"""

    private fun updateJson(pkg: String = "io.github.arthur044.wallpaperchanger", versionCode: Int = 92, sha: String = apkSha) =
        """{"channel":"release","package":"$pkg","versionCode":$versionCode,"versionName":"0.1.0 (9fb338d)",
            "commit":"9fb338d","branch":"main","apk":"wallpaper-changer-92.apk","sha256":"$sha"}"""

    private fun ok(body: String) = MockResponse.Builder().body(body).build()

    private suspend fun foundRelease(sha: String = apkSha): UpdateCheck.Found {
        server.enqueue(ok(release("r92", "0.1.0", prerelease = false)))
        server.enqueue(ok(updateJson(sha = sha)))
        return client.checkRelease("io.github.arthur044.wallpaperchanger", installedVersionCode = 90) as UpdateCheck.Found
    }

    @Test
    fun `a newer release is found through its update json`() = runTest {
        val found = foundRelease()

        assertEquals(VersionComparison.NEWER, found.comparison)
        assertEquals(92, found.info.versionCode)
        assertEquals("/repos/o/r/releases/latest", server.takeRequest().target)
        assertEquals("/download/update.json", server.takeRequest().target)
    }

    @Test
    fun `no release yet is no build, not an error`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).body("""{"message":"Not Found"}""").build())

        assertEquals(UpdateCheck.NoBuild, client.checkRelease("io.github.arthur044.wallpaperchanger", 90))
    }

    @Test
    fun `a build for another app id is not offered`() = runTest {
        server.enqueue(ok(release("r92", "0.1.0", prerelease = false)))
        server.enqueue(ok(updateJson()))

        val check = client.checkRelease("io.github.arthur044.wallpaperchanger.debug", 90)

        assertTrue(check is UpdateCheck.WrongPackage, "got $check")
    }

    @Test
    fun `an exhausted rate limit says when it resets`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(403)
                .addHeader("x-ratelimit-remaining", "0")
                .addHeader("x-ratelimit-reset", "1790000000")
                .body("""{"message":"API rate limit exceeded"}""")
                .build(),
        )

        val e = assertThrows<UpdateRateLimitedException> { client.checkRelease("io.github.arthur044.wallpaperchanger", 90) }
        assertEquals(Instant.ofEpochSecond(1_790_000_000), e.resetsAt)
    }

    @Test
    fun `a 403 that isn't the rate limit is a plain failure`() = runTest {
        server.enqueue(MockResponse.Builder().code(403).addHeader("x-ratelimit-remaining", "12").build())

        assertThrows<UpdateNetworkException> { client.checkRelease("io.github.arthur044.wallpaperchanger", 90) }
    }

    @Test
    fun `no connection is a network failure`() = runTest {
        server.close()

        assertThrows<UpdateNetworkException> { client.checkRelease("io.github.arthur044.wallpaperchanger", 90) }
    }

    @Test
    fun `debug branches come from the release list`() = runTest {
        server.enqueue(
            ok(
                "[" + release("debug-ci-android-spike", "ci/android-spike", prerelease = true) + "," +
                    release("r92", "0.1.0", prerelease = false) + "]",
            ),
        )

        assertEquals(listOf("ci/android-spike"), client.debugBranches().map { it.branch })
        assertEquals("/repos/o/r/releases?per_page=100&page=1", server.takeRequest().target)
        assertEquals(1, server.requestCount, "a short page is the last one")
    }

    @Test
    fun `a debug build past the first 100 releases is still listed`() = runTest {
        // Every push to main adds a release; old branches fall off page one.
        val fullPage = (1..100).joinToString(",", "[", "]") { release("r$it", "0.1.0", prerelease = false) }
        server.enqueue(ok(fullPage))
        server.enqueue(ok("[" + release("debug-feat-old", "feat/old", prerelease = true) + "]"))

        assertEquals(listOf("feat/old"), client.debugBranches().map { it.branch })
        server.takeRequest()
        assertEquals("/repos/o/r/releases?per_page=100&page=2", server.takeRequest().target)
    }

    @Test
    fun `the downloaded apk is kept when its checksum matches`() = runTest {
        val found = foundRelease()
        File(dir, "wallpaper-changer-80.apk").writeText("an old download")
        server.enqueue(MockResponse.Builder().body(Buffer().write(apkBytes)).build())

        val apk = client.download(found, dir)

        assertArrayEquals(apkBytes, apk.readBytes())
        assertEquals(listOf("wallpaper-changer-92.apk"), dir.list()!!.toList(), "older downloads are cleared")
    }

    @Test
    fun `a download that doesn't match update json is deleted`() = runTest {
        val found = foundRelease(sha = "0".repeat(64))
        server.enqueue(MockResponse.Builder().body(Buffer().write(apkBytes)).build())

        assertThrows<ChecksumMismatchException> { client.download(found, dir) }
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun `a failed download leaves nothing behind`() = runTest {
        val found = foundRelease()
        server.enqueue(MockResponse.Builder().code(500).build())

        assertThrows<UpdateNetworkException> { client.download(found, dir) }
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }
}
