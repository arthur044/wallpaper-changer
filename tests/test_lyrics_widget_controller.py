from src.lyrics.lrclib import Instrumental, NotFound, SyncedLyrics, TextLyrics
from src.lyrics.text import TimedLine
from src.lyrics_widget.controller import DURATION_WAIT_SECONDS, RETRY_SECONDS, LyricsWidgetController
from src.lyrics_widget.view_model import Phase
from src.os_integration.smtc import SmtcNowPlaying, TimelineSample

SYNCED = SyncedLyrics((TimedLine(1_000, "Line one"), TimedLine(4_000, "Line two"), TimedLine(8_000, "Line three")))


class _FakeClock:
    def __init__(self):
        self.now = 1_000.0

    def __call__(self):
        return self.now

    def advance(self, seconds):
        self.now += seconds


def _playing(title="Song", artist="Artist", album="Album", playing=True):
    return SmtcNowPlaying(title=title, artist=artist, album_title=album, album_artist=artist, is_playing=playing)


def _timeline(clock, snapshot, position_ms, *, playing=True, stamp=1.0, duration_ms=200_000):
    return TimelineSample(
        track_key=snapshot.track_key,
        position_ms=position_ms,
        observed_at=clock.now,
        duration_ms=duration_ms,
        is_playing=playing,
        rate=1.0,
        stamp=stamp,
    )


def _controller():
    clock = _FakeClock()
    return LyricsWidgetController(now=clock), clock


def test_without_smtc_the_widget_says_so_and_asks_nothing():
    controller = LyricsWidgetController(has_source=False)
    view, request = controller.tick(_playing(), None)
    assert view.phase == Phase.NO_SOURCE
    assert request is None


def test_nothing_playing_hides_the_widget():
    controller, _ = _controller()
    assert controller.tick(None, None)[0].phase == Phase.HIDDEN
    assert controller.tick(_playing(title=" "), None)[0].phase == Phase.HIDDEN


def test_a_new_track_asks_once_with_what_smtc_knows():
    controller, clock = _controller()
    song = _playing()

    view, request = controller.tick(song, _timeline(clock, song, 0))

    assert view.phase == Phase.LOADING
    assert request.track_key == song.track_key
    assert (request.query.artist, request.query.title, request.query.album, request.query.duration_ms) == (
        "Artist",
        "Song",
        "Album",
        200_000,
    )
    assert controller.tick(song, _timeline(clock, song, 0))[1] is None, "one lookup per track"


def test_without_a_timeline_the_lookup_waits_briefly_for_the_duration():
    controller, clock = _controller()
    song = _playing()

    assert controller.tick(song, None)[1] is None
    clock.advance(DURATION_WAIT_SECONDS)
    _, request = controller.tick(song, None)

    assert request is not None
    assert request.query.duration_ms is None


def test_the_old_tracks_duration_is_not_used_for_the_new_one():
    controller, clock = _controller()
    first, second = _playing(title="First"), _playing(title="Second")
    controller.tick(first, _timeline(clock, first, 150_000, stamp=1.0, duration_ms=300_000))

    # SMTC names the second track, but the timeline is still the first's.
    leftover = _timeline(clock, second, 151_000, stamp=1.0, duration_ms=300_000)
    assert controller.tick(second, leftover)[1] is None

    clock.advance(DURATION_WAIT_SECONDS)
    _, request = controller.tick(second, leftover)
    assert request.query.duration_ms is None


def test_synced_lyrics_follow_the_clock():
    controller, clock = _controller()
    song = _playing()
    timeline = _timeline(clock, song, 0)
    controller.tick(song, timeline)
    controller.on_lyrics(song.track_key, SYNCED)

    assert controller.tick(song, timeline)[0].current is None  # before the first line
    clock.advance(4.5)
    view, _ = controller.tick(song, timeline)

    assert view.phase == Phase.SYNCED
    assert view.lines == ("Line one", "Line two", "Line three")
    assert view.current == 1


def test_paused_the_current_line_stays():
    controller, clock = _controller()
    song = _playing(playing=False)
    timeline = _timeline(clock, song, 4_500, playing=False)
    controller.tick(song, timeline)
    controller.on_lyrics(song.track_key, SYNCED)

    clock.advance(60.0)

    assert controller.tick(song, timeline)[0].current == 1


def test_a_seek_moves_the_current_line():
    controller, clock = _controller()
    song = _playing()
    controller.tick(song, _timeline(clock, song, 1_500, stamp=1.0))
    controller.on_lyrics(song.track_key, SYNCED)

    view, _ = controller.tick(song, _timeline(clock, song, 9_000, stamp=2.0))

    assert view.current == 2


def test_unsynced_words_scroll_with_the_track():
    controller, clock = _controller()
    song = _playing()
    timeline = _timeline(clock, song, 50_000, duration_ms=200_000)
    controller.tick(song, timeline)
    controller.on_lyrics(song.track_key, TextLyrics(("Line one", "Line two")))

    view, _ = controller.tick(song, timeline)

    assert view.phase == Phase.TEXT
    assert view.progress == 0.25


def test_no_lyrics_and_instrumental_have_their_own_states():
    controller, clock = _controller()
    song = _playing()
    controller.tick(song, _timeline(clock, song, 0))

    controller.on_lyrics(song.track_key, NotFound())
    assert controller.tick(song, None)[0].phase == Phase.NO_LYRICS

    controller.on_lyrics(song.track_key, Instrumental())
    assert controller.tick(song, None)[0].phase == Phase.INSTRUMENTAL


def test_a_failed_lookup_shows_unavailable_and_retries_later():
    controller, clock = _controller()
    song = _playing()
    timeline = _timeline(clock, song, 0)
    controller.tick(song, timeline)

    controller.on_failure(song.track_key)
    view, request = controller.tick(song, timeline)
    assert view.phase == Phase.UNAVAILABLE
    assert request is None

    clock.advance(RETRY_SECONDS)
    assert controller.tick(song, timeline)[1] is not None


def test_an_answer_for_a_track_no_longer_playing_is_ignored():
    controller, clock = _controller()
    first, second = _playing(title="First"), _playing(title="Second")
    controller.tick(first, _timeline(clock, first, 0, stamp=1.0))
    controller.tick(second, _timeline(clock, second, 0, stamp=2.0))

    controller.on_lyrics(first.track_key, SYNCED)
    controller.on_failure(first.track_key)

    assert controller.tick(second, _timeline(clock, second, 0, stamp=2.0))[0].phase == Phase.LOADING


def test_a_new_track_drops_the_previous_lyrics_and_asks_again():
    controller, clock = _controller()
    first, second = _playing(title="First"), _playing(title="Second")
    controller.tick(first, _timeline(clock, first, 0, stamp=1.0))
    controller.on_lyrics(first.track_key, SYNCED)

    view, request = controller.tick(second, _timeline(clock, second, 0, stamp=2.0))

    assert view.phase == Phase.LOADING
    assert request.track_key == second.track_key
