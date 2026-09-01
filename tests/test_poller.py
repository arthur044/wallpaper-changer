from src.config.settings import Settings
from src.spotify import poller as poller_module
from src.spotify.client import AuthExpiredError, NowPlaying, RateLimitedError
from src.spotify.poller import Poller
from src.utils.app_state import AppState, AppStatus


def _now_playing(track_id="t1", album_id="a1", is_playing=True):
    return NowPlaying(
        is_playing=is_playing,
        track_id=track_id,
        album_id=album_id,
        art_url="http://x",
        track_name="Some Track",
        artist_name="Some Artist",
    )


def _make_poller(monkeypatch, fetch_result_or_exc):
    def fake_fetch(client):
        if isinstance(fetch_result_or_exc, Exception):
            raise fetch_result_or_exc
        return fetch_result_or_exc

    monkeypatch.setattr(poller_module, "fetch_now_playing", fake_fetch)

    settings = Settings(poll_interval_seconds=4.0, fallback_poll_interval_seconds=4.0)
    app_state = AppState()
    rendered = []

    poller = Poller(
        client=object(),
        settings=settings,
        app_state=app_state,
        render_fn=lambda now_playing: rendered.append(now_playing),
        reauth_fn=lambda: object(),
    )
    return poller, app_state, rendered


def test_cycle_renders_on_first_track(monkeypatch):
    now_playing = _now_playing(track_id="t1", album_id="a1")
    poller, app_state, rendered = _make_poller(monkeypatch, now_playing)

    interval = poller._run_one_cycle()

    assert interval == 4.0
    assert len(rendered) == 1
    assert app_state.snapshot().status == AppStatus.RUNNING


def test_cycle_skips_render_on_same_track(monkeypatch):
    now_playing = _now_playing(track_id="t1", album_id="a1")
    poller, app_state, rendered = _make_poller(monkeypatch, now_playing)
    poller._last_rendered_track_id = "t1"

    poller._run_one_cycle()

    assert rendered == []


def test_cycle_renders_on_new_track_within_same_album(monkeypatch):
    now_playing = _now_playing(track_id="t2", album_id="a1")
    poller, app_state, rendered = _make_poller(monkeypatch, now_playing)
    poller._last_rendered_track_id = "t1"

    poller._run_one_cycle()

    assert len(rendered) == 1


def test_cycle_backs_off_on_rate_limit(monkeypatch):
    poller, app_state, rendered = _make_poller(monkeypatch, RateLimitedError(retry_after=42.0))

    interval = poller._run_one_cycle()

    assert interval == 42.0
    assert rendered == []


def test_cycle_sets_error_then_recovers_on_auth_expiry(monkeypatch):
    poller, app_state, rendered = _make_poller(monkeypatch, AuthExpiredError("bad refresh token"))

    poller._run_one_cycle()

    # reauth_fn is a no-op stub that succeeds, so the error should be cleared afterward
    assert app_state.snapshot().status == AppStatus.RUNNING
    assert rendered == []


def test_cycle_pauses_without_calling_api(monkeypatch):
    calls = []

    def fake_fetch(client):
        calls.append(1)
        return None

    monkeypatch.setattr(poller_module, "fetch_now_playing", fake_fetch)
    settings = Settings()
    app_state = AppState()
    app_state.pause_event.set()

    poller = Poller(
        client=object(),
        settings=settings,
        app_state=app_state,
        render_fn=lambda now_playing: None,
        reauth_fn=lambda: object(),
    )

    poller._run_one_cycle()

    assert calls == []
