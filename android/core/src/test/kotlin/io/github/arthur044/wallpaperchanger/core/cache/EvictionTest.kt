package io.github.arthur044.wallpaperchanger.core.cache

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EvictionTest {
    @Test
    fun `nothing is evicted under budget`() {
        val entries = listOf(CacheEntry("a", 40, lastUsedMillis = 1), CacheEntry("b", 50, lastUsedMillis = 2))
        assertEquals(emptyList<String>(), evictionOrder(entries, maxBytes = 100))
    }

    @Test
    fun `evicts least recently used first until under budget`() {
        val entries = listOf(
            CacheEntry("newest", 40, lastUsedMillis = 30),
            CacheEntry("oldest", 40, lastUsedMillis = 10),
            CacheEntry("middle", 40, lastUsedMillis = 20),
        )
        // 120 bytes, budget 80: dropping just the oldest is enough.
        assertEquals(listOf("oldest"), evictionOrder(entries, maxBytes = 80))
        assertEquals(listOf("oldest", "middle"), evictionOrder(entries, maxBytes = 40))
    }

    @Test
    fun `an entry bigger than the whole budget is evicted too`() {
        assertEquals(listOf("huge"), evictionOrder(listOf(CacheEntry("huge", 500, lastUsedMillis = 1)), maxBytes = 100))
    }
}
