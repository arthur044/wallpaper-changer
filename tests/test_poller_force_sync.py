from src.config.settings import Settings
from src.os_integration.smtc import SmtcNowPlaying
from src.spotify import poller as poller_module
from src.spotify.client import NowPlaying, RateLimitedError
from src.spotify.poller import Poller
from src.utils.app_state import AppState


class _StubSmtc:
    def __init__(self, snapshot):
        self.snapshot = snapshot

    def get_snapshot(self):
        return self.snapshot


def _snapshot(title="Home"):
    return SmtcNowPlaying(
        title=title, artist="Dream Theater", album_title="Metropolis", album_artist="Dream Theater", is_playing=True
    )


def _resolved():
    return NowPlaying(
        is_playing=True,
        track_id="w1",
        album_id="a1",
        art_url="http://x/y.jpg",
        track_name="Home",
        artist_name="Dream Theater",
    )


def _poller(monkeypatch, fetch):
    calls = []

    def fake_fetch(client):
        calls.append(1)
        return fetch(len(calls))

    monkeypatch.setattr(poller_module, "fetch_now_playing", fake_fetch)
    monkeypatch.setattr(poller_module, "fetch_album_tracks", lambda client, album_id: [])
    app_state = AppState()
    rendered = []
    poller = Poller(
        client=object(),
        settings=Settings(poll_interval_seconds=4.0, fallback_poll_interval_seconds=25.0),
        app_state=app_state,
        render_fn=rendered.append,
        reauth_fn=lambda: object(),
        smtc_watcher=_StubSmtc(_snapshot()),
    )
    return poller, app_state, rendered, calls


def _force(poller, app_state):
    app_state.force_sync_event.set()
    poller._wait(0)


def test_force_sync_redraws_the_track_already_on_screen(monkeypatch):
    poller, app_state, rendered, _ = _poller(monkeypatch, lambda n: _resolved())
    poller._run_one_cycle()
    poller._run_one_cycle()
    assert len(rendered) == 1, "same track: no redraw without a force"

    _force(poller, app_state)
    poller._run_one_cycle()

    assert len(rendered) == 2


def test_force_applies_to_one_cycle_only(monkeypatch):
    poller, app_state, rendered, _ = _poller(monkeypatch, lambda n: _resolved())
    poller._run_one_cycle()
    _force(poller, app_state)
    poller._run_one_cycle()
    poller._run_one_cycle()

    assert len(rendered) == 2


def test_plain_timeout_is_not_a_force(monkeypatch):
    poller, app_state, rendered, _ = _poller(monkeypatch, lambda n: _resolved())
    poller._run_one_cycle()
    poller._wait(0)
    poller._run_one_cycle()

    assert len(rendered) == 1


def test_force_sync_retries_an_unresolved_track_inside_the_throttle_window(monkeypatch):
    # First resolution attempt returns nothing; a normal cycle would now wait
    # out fallback_poll_interval_seconds (25 s) before asking again.
    poller, app_state, rendered, calls = _poller(monkeypatch, lambda n: None if n == 1 else _resolved())
    poller._run_one_cycle()
    poller._run_one_cycle()
    assert calls == [1] and rendered == []

    _force(poller, app_state)
    poller._run_one_cycle()

    assert calls == [1, 1]
    assert len(rendered) == 1


def test_force_sync_still_respects_a_rate_limit(monkeypatch):
    def fetch(n):
        if n == 1:
            raise RateLimitedError(120.0)
        return _resolved()

    poller, app_state, rendered, calls = _poller(monkeypatch, fetch)
    poller._run_one_cycle()

    _force(poller, app_state)
    poller._run_one_cycle()

    assert calls == [1], "a 429 must not be answered with another request"
    assert rendered == []
