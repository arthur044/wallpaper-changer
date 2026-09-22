package io.github.arthur044.wallpaperchanger.core.spike

import io.github.arthur044.wallpaperchanger.core.trackKey
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * TEMPORARY (M13 spike): pairs what the local MediaSession says with what the
 * Web API reports, to answer three questions before deciding on MediaSession
 * (D4): how much sooner it notices a track change, how many API calls that
 * would save, and whether its titles still match (the "- 2015 Remaster"
 * suffix question from M6).
 */
data class TrackSighting(val title: String?, val artist: String?, val atMillis: Long)

data class PairedChange(
    val fromMediaSession: TrackSighting,
    val fromWebApi: TrackSighting?,
) {
    /** How much earlier the MediaSession knew; null while the API hasn't caught up. */
    val headStart: Duration?
        get() = fromWebApi?.let { (it.atMillis - fromMediaSession.atMillis).milliseconds }

    /** Same track by the key the hybrid path would use to look up the album. */
    val keysMatch: Boolean?
        get() = fromWebApi?.let {
            trackKey(fromMediaSession.artist, fromMediaSession.title) == trackKey(it.artist, it.title)
        }
}

/** Running record of one spike session. Not thread-safe: feed it from one place. */
class SpikeLog {
    private val pending = mutableListOf<PairedChange>()
    var webApiCalls = 0
        private set
    var mediaSessionEvents = 0
        private set
    private var lastWebApiKey: String? = null

    val changes: List<PairedChange> get() = pending.toList()

    /** A track change seen locally, ignoring repeats of the track already noted. */
    fun onMediaSession(sighting: TrackSighting) {
        mediaSessionEvents++
        val lastKey = pending.lastOrNull()?.fromMediaSession?.let { trackKey(it.artist, it.title) }
        if (lastKey == trackKey(sighting.artist, sighting.title)) return
        pending += PairedChange(sighting, null)
    }

    /**
     * A Web API answer. Counted always; it closes the open change when the API
     * first reports a different track than before. Pairing is by order in time,
     * not by title: pairing on equal titles would silently drop the very
     * mismatches this spike is looking for.
     */
    fun onWebApi(sighting: TrackSighting) {
        webApiCalls++
        val key = trackKey(sighting.artist, sighting.title)
        val apiNoticedAChange = key != lastWebApiKey
        lastWebApiKey = key
        if (!apiNoticedAChange) return
        val open = pending.lastOrNull() ?: return
        if (open.fromWebApi == null) pending[pending.lastIndex] = open.copy(fromWebApi = sighting)
    }

    /** Only the API call, e.g. a poll that found nothing playing. */
    fun onWebApiCallOnly() {
        webApiCalls++
    }

    fun summary(): SpikeSummary {
        val delays = pending.mapNotNull { it.headStart }
        return SpikeSummary(
            trackChanges = pending.size,
            pairedChanges = delays.size,
            mediaSessionEvents = mediaSessionEvents,
            webApiCalls = webApiCalls,
            averageHeadStart = delays.averageOrNull(),
            worstHeadStart = delays.maxOrNull(),
            titleMismatches = pending.count { it.keysMatch == false },
        )
    }
}

data class SpikeSummary(
    val trackChanges: Int,
    val pairedChanges: Int,
    val mediaSessionEvents: Int,
    val webApiCalls: Int,
    val averageHeadStart: Duration?,
    val worstHeadStart: Duration?,
    val titleMismatches: Int,
)

private fun List<Duration>.averageOrNull(): Duration? =
    if (isEmpty()) null else (sumOf { it.inWholeMilliseconds } / size).milliseconds
