package io.github.arthur044.wallpaperchanger.core.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SettingsRepositoryTest {
    @TempDir
    lateinit var dir: File

    private val file get() = File(dir, "settings.json")

    // DataStore allows one live instance per file; each call gets its own
    // scope and tears it down fully so the next instance can open the file.
    private suspend fun <T> withRepository(
        onCorruption: (Throwable) -> Unit = {},
        block: suspend (SettingsRepository) -> T,
    ): T {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        return try {
            block(SettingsRepository.create(file, scope, onCorruption))
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `no file yet means defaults`() = runTest {
        assertEquals(Settings(), withRepository { it.settings.first() })
    }

    @Test
    fun `updates survive a new instance on the same file`() = runTest {
        withRepository { repo -> repo.update { it.copy(clientId = "abc", paused = true) } }

        val reread = withRepository { it.settings.first() }
        assertEquals(Settings(clientId = "abc", paused = true), reread)
    }

    @Test
    fun `update stores the sanitized value and returns it`() = runTest {
        val returned = withRepository { repo -> repo.update { it.copy(artSizePct = 5.0) } }
        val stored = withRepository { it.settings.first() }

        assertEquals(Settings.ART_SIZE_PCT_RANGE.endInclusive, returned.artSizePct)
        assertEquals(returned, stored)
    }

    @Test
    fun `a corrupt file is reported and replaced with defaults`() = runTest {
        file.writeText("{ not json")
        val reported = mutableListOf<Throwable>()

        val settings = withRepository(onCorruption = { reported += it }) { it.settings.first() }

        assertEquals(Settings(), settings)
        assertEquals(1, reported.size)
        assertTrue(file.readText().contains("\"client_id\""), "corrupt file should be rewritten with defaults")
    }
}
