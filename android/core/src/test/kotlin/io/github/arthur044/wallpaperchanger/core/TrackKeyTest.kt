package io.github.arthur044.wallpaperchanger.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class TrackKeyTest {
    @Test
    fun `joins normalized artist and title`() {
        assertEquals("radiohead::airbag", trackKey("Radiohead", "Airbag"))
    }

    @Test
    fun `ignores case and surrounding whitespace`() {
        // MediaSession metadata and the Web API disagree on casing/padding for
        // the same track; both must land on the same index entry.
        assertEquals(trackKey("Radiohead", "Airbag"), trackKey("  RADIOHEAD ", "airbag  "))
    }

    @Test
    fun `treats missing parts as empty`() {
        assertEquals("::airbag", trackKey(null, "Airbag"))
        assertEquals("radiohead::", trackKey("Radiohead", null))
    }

    @Test
    fun `different artists with the same title stay distinct`() {
        assertNotEquals(trackKey("Radiohead", "Creep"), trackKey("Stone Temple Pilots", "Creep"))
    }
}
