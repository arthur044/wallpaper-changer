from src.spotify.client import NowPlaying
from src.spotify.state_machine import PollDecision, decide


def _playing(track_id="t1", album_id="a1", is_playing=True):
    return NowPlaying(
        is_playing=is_playing,
        track_id=track_id,
        album_id=album_id,
        art_url="http://x/y.jpg",
        track_name="Some Track",
        artist_name="Some Artist",
    )


def test_decide_idle_when_nothing_playing():
    assert decide(None, last_track_id=None) is PollDecision.IDLE


def test_decide_idle_when_paused():
    assert decide(_playing(is_playing=False), last_track_id=None) is PollDecision.IDLE


def test_decide_render_on_new_album():
    assert decide(_playing(track_id="t2", album_id="a2"), last_track_id="t1") is PollDecision.RENDER


def test_decide_render_on_new_track_within_same_album():
    # Track text must update even when the album (and its cached base art) is unchanged.
    assert decide(_playing(track_id="t2", album_id="a1"), last_track_id="t1") is PollDecision.RENDER


def test_decide_noop_when_same_track_still_playing():
    assert decide(_playing(track_id="t1", album_id="a1"), last_track_id="t1") is PollDecision.NOOP


def test_decide_render_on_first_ever_track():
    assert decide(_playing(track_id="t1", album_id="a1"), last_track_id=None) is PollDecision.RENDER
