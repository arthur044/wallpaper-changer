package io.github.arthur044.wallpaperchanger.core.spotify

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ArtDownloaderTest {
    private val server = MockWebServer()
    private val downloader = ArtDownloader(maxBytes = 1_000)

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
}
