package io.github.arthur044.wallpaperchanger.core.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileRenderMemoryTest {
    @TempDir
    lateinit var dir: File

    private val file get() = File(dir, "last_track.txt")

    // One DataStore per file at a time: each "process" opens and fully closes its own.
    private suspend fun <T> withMemory(block: suspend (RenderMemory) -> T): T {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        return try {
            block(FileRenderMemory.create(file, scope))
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `nothing remembered on first start`() = runTest {
        assertNull(withMemory { it.lastRenderedTrackId() })
    }

    @Test
    fun `a remembered track survives a restart`() = runTest {
        withMemory { it.remember("4uLU6hMCjMI75M1A2tKUQC") }
        assertEquals("4uLU6hMCjMI75M1A2tKUQC", withMemory { it.lastRenderedTrackId() })
    }

    @Test
    fun `remembering null forgets`() = runTest {
        withMemory { it.remember("t1") }
        withMemory { it.remember(null) }
        assertNull(withMemory { it.lastRenderedTrackId() })
    }
}
