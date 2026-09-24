package io.github.arthur044.wallpaperchanger.core.sync

import io.github.arthur044.wallpaperchanger.core.ResolvedAlbum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileTrackIndexStoreTest {
    @TempDir
    lateinit var dir: File

    private val file get() = File(dir, "track_index.json")

    // One DataStore per file at a time: each "process" opens and fully closes its own.
    private suspend fun <T> withStore(onError: (Throwable) -> Unit = {}, block: suspend (TrackIndexStore) -> T): T {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        return try {
            block(FileTrackIndexStore.create(file, scope, onError))
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `nothing saved means nothing to load`() = runTest {
        assertTrue(withStore { it.load() }.isEmpty())
    }

    @Test
    fun `saved entries survive a restart, in order`() = runTest {
        val entries = listOf(
            "radiohead::airbag" to ResolvedAlbum("a1", "https://i.scdn.co/image/a1"),
            "radiohead::lucky" to ResolvedAlbum("a1", null),
        )
        withStore { it.save(entries) }

        assertEquals(entries, withStore { it.load() })
    }

    @Test
    fun `an unreadable file is reported and starts empty`() = runTest {
        file.writeText("{ not json")
        val errors = mutableListOf<Throwable>()

        val loaded = withStore(onError = { errors += it }) { it.load() }

        assertTrue(loaded.isEmpty())
        assertTrue(errors.isNotEmpty())
    }

    @Test
    fun `a file of the wrong shape starts empty`() = runTest {
        for (content in listOf("[]", "42", "null", """{"version":1,"tracks":[{"key":"k"}]}""")) {
            file.writeText(content)

            assertTrue(withStore { it.load() }.isEmpty(), content)
        }
    }

    @Test
    fun `a file from an unknown version is ignored`() = runTest {
        file.writeText("""{"version":99,"tracks":[{"key":"k","albumId":"a"}]}""")

        assertTrue(withStore { it.load() }.isEmpty())
    }
}
