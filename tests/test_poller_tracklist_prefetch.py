from src.config.settings import Settings
from src.os_integration.smtc import SmtcNowPlaying
from src.spotify import poller as poller_module
from src.spotify.client import NowPlaying
from src.spotify.poller import Poller
from src.utils.app_state import AppState


class _StubSmtc:
    def __init__(self, snapshot):
        self._snapshot = snapshot

    def set(self, snapshot):
        self._snapshot = snapshot

    def get_snapshot(self):
        return self._snapshot


def _smtc_snapshot(title, artist="Avenged Sevenfold"):
    return SmtcNowPlaying(
        title=title,
        artist=artist,
        album_title="Life Is But a Dream",
        album_artist=artist,
        is_playing=True,
    )


def _resolved_now_playing():
    return NowPlaying(
        is_playing=True,
        track_id="track_2",
        album_id="album_1",
        art_url="http://cdn.example/hi-res.jpg",
        track_name="Mattel",
        artist_name="Avenged Sevenfold",
    )


def _make_poller(monkeypatch, smtc_snapshot, fallback_interval=25.0):
    smtc = _StubSmtc(smtc_snapshot)
    settings = Settings(poll_interval_seconds=4.0, fallback_poll_interval_seconds=fallback_interval)
    app_state = AppState()
    rendered = []

    poller = Poller(
        client=object(),
        settings=settings,
        app_state=app_state,
        render_fn=lambda now_playing: rendered.append(now_playing),
        reauth_fn=lambda: object(),
        smtc_watcher=smtc,
        is_locked_fn=lambda: False,
    )
    return poller, smtc, rendered


def test_prefetched_tracklist_lets_other_tracks_skip_resolution(monkeypatch):
    now_playing_calls = []
    monkeypatch.setattr(
        poller_module, "fetch_now_playing", lambda client: now_playing_calls.append(1) or _resolved_now_playing()
    )
    album_tracks_calls = []

    def fake_album_tracks(client, album_id):
        album_tracks_calls.append(album_id)
        return [
            ("Mattel", "Avenged Sevenfold"),
            ("Nobody", "Avenged Sevenfold"),
            ("Easier", "Avenged Sevenfold"),
        ]

    monkeypatch.setattr(poller_module, "fetch_album_tracks", fake_album_tracks)

    poller, smtc, rendered = _make_poller(monkeypatch, _smtc_snapshot("Mattel"))
    poller._run_one_cycle()  # resolves "Mattel" + prefetches the whole tracklist

    assert len(now_playing_calls) == 1
    assert album_tracks_calls == ["album_1"]
    assert len(rendered) == 1

    # Jump straight to "Easier" (never seen before, but on the same prefetched album).
    smtc.set(_smtc_snapshot("Easier"))
    poller._run_one_cycle()

    assert len(now_playing_calls) == 1  # no new resolution call needed
    assert len(rendered) == 2
    assert rendered[1].album_id == "album_1"
    assert rendered[1].track_name == "Easier"


def test_tracklist_prefetch_failure_does_not_block_current_track(monkeypatch):
    monkeypatch.setattr(poller_module, "fetch_now_playing", lambda client: _resolved_now_playing())

    def failing_album_tracks(client, album_id):
        raise RuntimeError("boom")

    monkeypatch.setattr(poller_module, "fetch_album_tracks", failing_album_tracks)

    poller, smtc, rendered = _make_poller(monkeypatch, _smtc_snapshot("Mattel"))

    poller._run_one_cycle()

    assert len(rendered) == 1
    assert rendered[0].album_id == "album_1"


def test_track_not_in_any_prefetched_album_requires_fresh_resolution(monkeypatch):
    resolve_calls = []

    def fake_now_playing(client):
        resolve_calls.append(1)
        return _resolved_now_playing()

    monkeypatch.setattr(poller_module, "fetch_now_playing", fake_now_playing)
    monkeypatch.setattr(poller_module, "fetch_album_tracks", lambda client, album_id: [("Mattel", "Avenged Sevenfold")])

    poller, smtc, rendered = _make_poller(monkeypatch, _smtc_snapshot("Mattel"), fallback_interval=0.0)
    poller._run_one_cycle()
    assert len(resolve_calls) == 1

    # A totally different track/album, never resolved or prefetched before.
    smtc.set(_smtc_snapshot("Some Other Song", artist="Some Other Band"))
    poller._run_one_cycle()

    assert len(resolve_calls) == 2  # had to hit the Web API again for this unknown track
