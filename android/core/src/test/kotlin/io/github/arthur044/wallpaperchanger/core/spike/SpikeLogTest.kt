package io.github.arthur044.wallpaperchanger.core.spike

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SpikeLogTest {
    private val log = SpikeLog()

    @Test
    fun `the head start is how late the API reports the change`() {
        log.onMediaSession(TrackSighting("Airbag", "Radiohead", atMillis = 1_000))
        log.onWebApi(TrackSighting("Airbag", "Radiohead", atMillis = 14_000))

        assertEquals(13.seconds, log.changes.single().headStart)
        assertEquals(13.seconds, log.summary().averageHeadStart)
        assertTrue(log.changes.single().keysMatch == true)
    }

    @Test
    fun `repeats of the same track are not new changes`() {
        log.onMediaSession(TrackSighting("Airbag", "Radiohead", 1_000))
        log.onMediaSession(TrackSighting("Airbag", "Radiohead", 2_000)) // pause/resume, position ticks
        log.onMediaSession(TrackSighting("Lucky", "Radiohead", 9_000))

        assertEquals(2, log.summary().trackChanges)
        assertEquals(3, log.summary().mediaSessionEvents)
    }

    @Test
    fun `a change with no API answer yet stays unpaired`() {
        log.onMediaSession(TrackSighting("Airbag", "Radiohead", 1_000))

        val summary = log.summary()
        assertEquals(1, summary.trackChanges)
        assertEquals(0, summary.pairedChanges)
        assertNull(summary.averageHeadStart)
        assertNull(log.changes.single().headStart)
    }

    @Test
    fun `polls that still report the old track don't close the change`() {
        log.onWebApi(TrackSighting("Airbag", "Radiohead", 0)) // what the API knew before
        log.onMediaSession(TrackSighting("Lucky", "Radiohead", 10_000))
        log.onWebApi(TrackSighting("Airbag", "Radiohead", 12_000)) // hasn't caught up
        assertEquals(0, log.summary().pairedChanges)

        log.onWebApi(TrackSighting("Lucky", "Radiohead", 37_000))
        assertEquals(1, log.summary().pairedChanges)
        assertEquals(27.seconds, log.changes.single().headStart)
    }

    @Test
    fun `a title that differs only by a suffix pairs and counts as a mismatch`() {
        log.onMediaSession(TrackSighting("Paranoid Android", "Radiohead", 1_000))
        log.onWebApi(TrackSighting("Paranoid Android - 2015 Remaster", "Radiohead", 5_000))

        val change = log.changes.single()
        assertEquals(4.seconds, change.headStart)
        assertTrue(change.keysMatch == false)
        assertEquals(1, log.summary().titleMismatches)
    }

    @Test
    fun `case and padding do not make a mismatch`() {
        log.onMediaSession(TrackSighting(" airbag ", "RADIOHEAD", 1_000))
        log.onWebApi(TrackSighting("Airbag", "Radiohead", 3_000))

        assertTrue(log.changes.single().keysMatch == true)
        assertEquals(0, log.summary().titleMismatches)
    }

    @Test
    fun `every API call is counted, answer or not`() {
        log.onMediaSession(TrackSighting("Airbag", "Radiohead", 1_000))
        log.onWebApi(TrackSighting("Airbag", "Radiohead", 3_000))
        log.onWebApiCallOnly() // nothing playing
        log.onWebApi(TrackSighting("Airbag", "Radiohead", 30_000)) // same track, still a call

        assertEquals(3, log.summary().webApiCalls)
        assertEquals(1, log.summary().pairedChanges)
    }

    @Test
    fun `the worst case is the longest head start`() {
        log.onMediaSession(TrackSighting("Airbag", "Radiohead", 0))
        log.onWebApi(TrackSighting("Airbag", "Radiohead", 5_000))
        log.onMediaSession(TrackSighting("Lucky", "Radiohead", 10_000))
        log.onWebApi(TrackSighting("Lucky", "Radiohead", 34_000))

        val summary = log.summary()
        assertEquals(24.seconds, summary.worstHeadStart)
        assertEquals(14_500.milliseconds, summary.averageHeadStart) // (5 + 24) / 2
        assertEquals(0, summary.titleMismatches)
    }
}
