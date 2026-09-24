from src.config.settings import Settings
from src.os_integration.smtc import SmtcNowPlaying
from src.spotify import poller as poller_module
from src.spotify.client import NowPlaying
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


def _web_api_now_playing(track_id="wt1", track_name="Web Track"):
    return NowPlaying(
        is_playing=True,
        track_id=track_id,
        album_id="wa1",
        art_url="http://x/y.jpg",
        track_name=track_name,
        artist_name="Web Artist",
    )


def _make_poller(monkeypatch, smtc_watcher, is_locked, fetch_result_or_exc=None, fallback_interval=25.0):
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
        smtc_watcher=smtc_watcher,
        is_locked_fn=lambda: is_locked,
    )
    return poller, app_state, rendered


def test_unlocked_with_smtc_active_resolves_art_via_web_api_then_renders(monkeypatch):
    # SMTC only ever supplies track/album identity for free; the art itself
    # always requires one Web API resolution call, since only Spotify-sourced
    # images are ever rendered.
    poller, app_state, rendered = _make_poller(
        monkeypatch,
        smtc_watcher=_StubSmtc(_smtc_snapshot()),
        is_locked=False,
        fetch_result_or_exc=_web_api_now_playing(track_name="Mattel"),
    )

    interval = poller._run_one_cycle()

    assert len(rendered) == 1
    assert rendered[0].track_name == "Mattel"  # identity from SMTC
    assert rendered[0].art_url == "http://x/y.jpg"  # art from the Web API resolution
    assert interval == 4.0  # cheap local tick, not the slow fallback interval


def test_unlocked_with_smtc_active_and_unresolved_art_skips_render(monkeypatch):
    # Web API call fails/returns nothing usable: never fall back to a
    # non-Spotify image, just skip rendering this cycle.
    poller, app_state, rendered = _make_poller(
        monkeypatch, smtc_watcher=_StubSmtc(_smtc_snapshot()), is_locked=False, fetch_result_or_exc=None
    )

    interval = poller._run_one_cycle()

    assert rendered == []
    assert interval == 4.0


def test_unlocked_without_smtc_falls_back_to_web_api(monkeypatch):
    poller, app_state, rendered = _make_poller(
        monkeypatch,
        smtc_watcher=_StubSmtc(None),
        is_locked=False,
        fetch_result_or_exc=_web_api_now_playing(),
        fallback_interval=25.0,
    )

    interval = poller._run_one_cycle()

    assert len(rendered) == 1
    assert interval == 25.0  # slower fallback interval, not the fast local tick


def test_locked_without_smtc_pauses_entirely(monkeypatch):
    calls = []
    monkeypatch.setattr(poller_module, "fetch_now_playing", lambda client: calls.append(1))

    poller, app_state, rendered = _make_poller(
        monkeypatch, smtc_watcher=_StubSmtc(None), is_locked=True
    )

    interval = poller._run_one_cycle()

    assert calls == []  # locked + Spotify desktop closed -> no API call at all
    assert rendered == []
    assert interval == 4.0


def test_locked_with_smtc_active_still_resolves_and_renders(monkeypatch):
    # Lock state doesn't gate the SMTC path's art resolution call - it's not
    # a "wasted" request in the sense the lock-state gate cares about, since
    # it only fires once per newly-seen album, not on a poll cadence.
    poller, app_state, rendered = _make_poller(
        monkeypatch,
        smtc_watcher=_StubSmtc(_smtc_snapshot()),
        is_locked=True,
        fetch_result_or_exc=_web_api_now_playing(track_name="Mattel"),
    )

    poller._run_one_cycle()

    assert len(rendered) == 1


def test_web_api_fallback_is_throttled_between_cycles(monkeypatch):
    calls = []

    def fake_fetch(client):
        calls.append(1)
        return _web_api_now_playing(track_id=f"wt{len(calls)}")

    poller, app_state, rendered = _make_poller(
        monkeypatch, smtc_watcher=_StubSmtc(None), is_locked=False, fallback_interval=9999.0
    )
    # _make_poller registers its own generic stub first; ours (which counts calls) must win.
    monkeypatch.setattr(poller_module, "fetch_now_playing", fake_fetch)

    first_interval = poller._run_one_cycle()
    second_interval = poller._run_one_cycle()

    assert len(calls) == 1  # second cycle is within the fallback interval, so it's skipped
    assert first_interval == 9999.0
    assert second_interval == 4.0  # falls back to the cheap local tick while throttled
