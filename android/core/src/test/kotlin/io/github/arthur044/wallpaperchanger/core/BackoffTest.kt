package io.github.arthur044.wallpaperchanger.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// Ported from tests/test_backoff.py.
class BackoffTest {
    @Test
    fun `starts at the base delay`() {
        assertEquals(5.seconds, nextBackoff(Duration.ZERO))
    }

    @Test
    fun `doubles on each failure`() {
        assertEquals(10.seconds, nextBackoff(5.seconds))
        assertEquals(20.seconds, nextBackoff(10.seconds))
    }

    @Test
    fun `caps at the maximum delay`() {
        assertEquals(300.seconds, nextBackoff(250.seconds))
        assertEquals(300.seconds, nextBackoff(300.seconds))
    }
}
