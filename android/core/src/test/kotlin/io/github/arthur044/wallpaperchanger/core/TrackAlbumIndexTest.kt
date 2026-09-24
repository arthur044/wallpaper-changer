package io.github.arthur044.wallpaperchanger.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TrackAlbumIndexTest {
    private val okComputer = ResolvedAlbum(albumId = "a1", artUrl = "http://x/ok.jpg")

    @Test
    fun `unknown track resolves to nothing`() {
        assertNull(TrackAlbumIndex()["radiohead::airbag"])
    }

    @Test
    fun `every track of a recorded album resolves to it`() {
        // Mirrors Poller._resolve_and_cache_album: jumping straight to any later
        // track of an already-resolved album must not need another API call.
        val index = TrackAlbumIndex()
        index.record(okComputer, listOf("radiohead::airbag", "radiohead::paranoid android", "radiohead::lucky"))

        assertEquals(okComputer, index["radiohead::lucky"])
        assertEquals(okComputer, index["radiohead::airbag"])
    }

    @Test
    fun `recording again overwrites the previous album for a track`() {
        val index = TrackAlbumIndex()
        val deluxe = ResolvedAlbum(albumId = "a2", artUrl = null)
        index.record(okComputer, listOf("radiohead::airbag"))
        index.record(deluxe, listOf("radiohead::airbag"))

        assertEquals(deluxe, index["radiohead::airbag"])
    }

    @Test
    fun `evicts the least recently used track once full`() {
        val index = TrackAlbumIndex(maxEntries = 2)
        index.record(okComputer, listOf("k1", "k2"))
        index["k1"] // touch k1, so k2 becomes the eldest
        index.record(okComputer, listOf("k3"))

        assertEquals(okComputer, index["k1"])
        assertNull(index["k2"])
        assertEquals(okComputer, index["k3"])
    }

    @Test
    fun `rejects a non-positive capacity`() {
        assertThrows<IllegalArgumentException> { TrackAlbumIndex(maxEntries = 0) }
    }

    @org.junit.jupiter.api.Test
    fun `a snapshot restored into a new index keeps entries and their order`() {
        val first = TrackAlbumIndex(maxEntries = 3)
        first.record(ResolvedAlbum("a1", null), listOf("x", "y"))
        first.record(ResolvedAlbum("a2", null), listOf("z"))
        first["x"] // used again: now the most recent

        val restarted = TrackAlbumIndex(maxEntries = 3)
        restarted.restore(first.snapshot())
        restarted.record(ResolvedAlbum("a3", null), listOf("w")) // pushes out the least recent

        org.junit.jupiter.api.Assertions.assertEquals(listOf("z", "x", "w"), restarted.snapshot().map { it.first })
    }

    @org.junit.jupiter.api.Test
    fun `a forgotten track is gone`() {
        val index = TrackAlbumIndex()
        index.record(ResolvedAlbum("a1", null), listOf("x"))

        org.junit.jupiter.api.Assertions.assertTrue(index.forget("x"))
        org.junit.jupiter.api.Assertions.assertNull(index["x"])
        org.junit.jupiter.api.Assertions.assertFalse(index.forget("x"))
    }
}
