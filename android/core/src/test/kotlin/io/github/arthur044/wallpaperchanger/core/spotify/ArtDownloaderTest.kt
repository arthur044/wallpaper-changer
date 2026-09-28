package io.github.arthur044.wallpaperchanger.core.spotify

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ArtDownloaderTest {
    private val server = MockWebServer()

    // MockWebServer is plain http on localhost, so these tests swap the host rule
    // for one on the path; isSpotifyArtUrl itself is tested on its own below.
    private val downloader = ArtDownloader(maxBytes = 1_000, isAllowed = { it.encodedPath.startsWith("/image/") })

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() = server.close()

    private fun url() = server.url("/image/abc").toString()

    @Test
    fun `returns the image bytes`() = runTest {
        val bytes = ByteArray(300) { it.toByte() }
        server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).build())
        assertArrayEquals(bytes, downloader.download(url()))
    }

    @Test
    fun `a non-2xx answer is transient`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())
        assertThrows<TransientNetworkException> { downloader.download(url()) }
    }

    @Test
    fun `an oversized image is refused`() = runTest {
        server.enqueue(MockResponse.Builder().body(Buffer().write(ByteArray(1_001))).build())
        assertThrows<TransientNetworkException> { downloader.download(url()) }
    }

    @Test
    fun `an unreachable host is transient`() = runTest {
        val dead = url()
        server.close()
        assertThrows<TransientNetworkException> { downloader.download(dead) }
    }

    @Test
    fun `a malformed url is transient rather than a crash`() = runTest {
        assertThrows<TransientNetworkException> { downloader.download("not a url") }
    }

    @Test
    fun `a url outside the allowed hosts is refused without any request`() = runTest {
        assertThrows<TransientNetworkException> { downloader.download(server.url("/elsewhere/abc").toString()) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a redirect out of the allowed hosts is refused`() = runTest {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/elsewhere/abc").build())
        server.enqueue(MockResponse.Builder().body(Buffer().write(ByteArray(300))).build())
        assertThrows<TransientNetworkException> { downloader.download(url()) }
        // Control: the redirect was followed, so it was the final-URL check that refused it.
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `the default rule takes https on Spotify's image CDN`() {
        assertTrue(isSpotifyArtUrl("https://i.scdn.co/image/ab67616d0000b273".toHttpUrl()))
        assertTrue(isSpotifyArtUrl("https://mosaic.scdn.co/640/abc".toHttpUrl()))
        assertTrue(isSpotifyArtUrl("https://image-cdn-ak.spotifycdn.com/image/abc".toHttpUrl()))
    }

    @Test
    fun `the default rule refuses plain http and look-alike hosts`() {
        assertFalse(isSpotifyArtUrl("http://i.scdn.co/image/abc".toHttpUrl()))
        assertFalse(isSpotifyArtUrl("https://evil.example/image/abc".toHttpUrl()))
        assertFalse(isSpotifyArtUrl("https://notscdn.co/image/abc".toHttpUrl()))
        assertFalse(isSpotifyArtUrl("https://i.scdn.co.evil.example/image/abc".toHttpUrl()))
    }
}
