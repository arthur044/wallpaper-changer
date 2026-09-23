import logging
import time

from src.config.settings import Settings
from src.os_integration.smtc import SmtcNowPlaying
from src.spotify import poller as poller_module
from src.spotify.client import NowPlaying, RateLimitedError
from src.spotify.poller import Poller
from src.utils.app_state import AppState


class _StubSmtc:
    def __init__(self, snapshot=None):
        self._snapshot = snapshot

    def get_snapshot(self):
        return self._snapshot


def _smtc_snapshot(title="Mattel", is_playing=True):
    return SmtcNowPlaying(
        title=title,
        artist="Avenged Sevenfold",
        album_title="Life Is But a Dream",
        album_artist="Avenged Sevenfold",
        is_playing=is_playing,
    )


def _web_api_now_playing(album_id="real_album_1", track_id="real_track_1"):
    return NowPlaying(
        is_playing=True,
        track_id=track_id,
        album_id=album_id,
        art_url="http://cdn.example/hi-res.jpg",
        track_name="Mattel",
        artist_name="Avenged Sevenfold",
    )


def _make_poller(monkeypatch, fetch_result_or_exc=None, fallback_interval=25.0):
    def fake_fetch(client):
        if isinstance(fetch_result_or_exc, Exception):
            raise fetch_result_or_exc
        return fetch_result_or_exc

    monkeypatch.setattr(poller_module, "fetch_now_playing", fake_fetch)

    settings = Settings(poll_interval_seconds=4.0, fallback_poll_interval_seconds=fallback_interval)
    app_state = AppState()
    rendered = []

    poller = Poller(
        client=object(),
        settings=settings,
        app_state=app_state,
        render_fn=lambda now_playing: rendered.append(now_playing),
        reauth_fn=lambda: object(),
        smtc_watcher=_StubSmtc(_smtc_snapshot()),
        is_locked_fn=lambda: False,
    )
    return poller, rendered


def test_new_smtc_album_gets_resolved_to_high_res_art(monkeypatch):
    poller, rendered = _make_poller(monkeypatch, fetch_result_or_exc=_web_api_now_playing())

    poller._run_one_cycle()

    assert len(rendered) == 1
    assert rendered[0].album_id == "real_album_1"
    assert rendered[0].art_url == "http://cdn.example/hi-res.jpg"


def test_resolved_album_is_reused_without_calling_web_api_again(monkeypatch):
    calls = []

    def fake_fetch(client):
        calls.append(1)
        return _web_api_now_playing()

    poller, rendered = _make_poller(monkeypatch)
    monkeypatch.setattr(poller_module, "fetch_now_playing", fake_fetch)

    poller._run_one_cycle()  # resolves and caches
    poller._run_one_cycle()  # same track, should be a NOOP - no new render, no new call

    assert len(calls) == 1
    assert rendered[0].album_id == "real_album_1"


def test_throttled_resolution_skips_render_entirely(monkeypatch):
    # Only Spotify-sourced art is ever rendered: when a fresh album can't be
    # resolved yet (throttled here), nothing renders - the current wallpaper
    # is left untouched rather than showing a non-Spotify image.
    calls = []
    monkeypatch.setattr(poller_module, "fetch_now_playing", lambda client: calls.append(1))

    poller, rendered = _make_poller(monkeypatch, fallback_interval=9999.0)
    poller._last_web_api_at = time.monotonic()  # a Web API call just happened -> still throttled

    poller._run_one_cycle()

    assert calls == []
    assert rendered == []


def test_resolution_failure_skips_render_without_crashing(monkeypatch):
    poller, rendered = _make_poller(monkeypatch, fetch_result_or_exc=RateLimitedError(retry_after=5.0))

    poller._run_one_cycle()

    assert rendered == []


def test_unresolved_track_upgrades_to_good_image_once_resolution_succeeds(monkeypatch):
    # First cycle: resolution fails, so nothing renders (never the SMTC
    # thumbnail). Second cycle, same still-playing track: resolution now
    # succeeds and the good image renders - proving this isn't blocked by
    # the NOOP dedup, since the failed cycle never touched
    # _last_rendered_track_id in the first place.
    poller, rendered = _make_poller(monkeypatch, fetch_result_or_exc=RateLimitedError(retry_after=0.0))
    poller._run_one_cycle()
    assert rendered == []

    monkeypatch.setattr(poller_module, "fetch_now_playing", lambda client: _web_api_now_playing())
    poller._last_web_api_at = float("-inf")  # unblock the throttle for this test

    poller._run_one_cycle()

    assert len(rendered) == 1
    assert rendered[0].album_id == "real_album_1"


def test_unresolved_track_is_logged_once_per_track(monkeypatch, caplog):
    # The skip-without-render path used to be completely silent, so a frozen
    # wallpaper left no trace at all in the log. It must say so - but only
    # once per track, since this cycle repeats every poll_interval_seconds.
    monkeypatch.setattr(poller_module, "fetch_now_playing", lambda client: None)

    poller, rendered = _make_poller(monkeypatch, fallback_interval=9999.0)
    poller._last_web_api_at = 0.0  # still throttled -> nothing can resolve

    with caplog.at_level(logging.INFO, logger="src.spotify.poller"):
        poller._run_one_cycle()
        poller._run_one_cycle()

    assert rendered == []
    unresolved = [r for r in caplog.records if "no Spotify art resolved yet" in r.message]
    assert len(unresolved) == 1
    assert "Mattel" in unresolved[0].getMessage()


def test_each_unresolved_track_gets_its_own_log_line(monkeypatch, caplog):
    monkeypatch.setattr(poller_module, "fetch_now_playing", lambda client: None)

    poller, _ = _make_poller(monkeypatch, fallback_interval=9999.0)
    poller._last_web_api_at = 0.0

    with caplog.at_level(logging.INFO, logger="src.spotify.poller"):
        poller._run_one_cycle()
        poller._smtc._snapshot = _smtc_snapshot(title="Nobody")
        poller._run_one_cycle()

    unresolved = [r.getMessage() for r in caplog.records if "no Spotify art resolved yet" in r.message]
    assert len(unresolved) == 2
    assert "Mattel" in unresolved[0]
    assert "Nobody" in unresolved[1]


def test_successful_resolution_logs_nothing_about_being_unresolved(monkeypatch, caplog):
    poller, rendered = _make_poller(monkeypatch, fetch_result_or_exc=_web_api_now_playing())

    with caplog.at_level(logging.INFO, logger="src.spotify.poller"):
        poller._run_one_cycle()

    assert len(rendered) == 1
    assert [r for r in caplog.records if "no Spotify art resolved yet" in r.message] == []
