package io.github.arthur044.wallpaperchanger.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// Proves :core compiles and its tests run on the JUnit platform. Replaced by
// the real domain tests (decide, backoff, track key) in M1.
class ToolchainSmokeTest {
    @Test
    fun `core tests run on the JUnit platform`() {
        assertEquals(4, 2 + 2)
    }
}
