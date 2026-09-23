import json

from src.config.settings import Settings
from src.os_integration.smtc import SmtcNowPlaying
from src.spotify import poller as poller_module
from src.spotify.client import NowPlaying
from src.spotify.poller import Poller
from src.spotify.track_index import TrackAlbumStore
from src.utils.app_state import AppState


def test_a_saved_map_comes_back_after_a_restart(tmp_path):
    path = tmp_path / "track_index.json"
    store = TrackAlbumStore(path)
    store["angra::rebirth"] = ("rebirth", "http://cdn/rebirth.jpg")
    store.flush()

    again = TrackAlbumStore(path)

    assert again.get("angra::rebirth") == ("rebirth", "http://cdn/rebirth.jpg")


def test_nothing_is_written_until_something_changes(tmp_path):
    path = tmp_path / "track_index.json"
    TrackAlbumStore(path).flush()

    assert not path.exists()


def test_only_the_most_recently_used_songs_are_kept(tmp_path):
    store = TrackAlbumStore(tmp_path / "i.json", max_entries=3)
    for key in ("a", "b", "c"):
        store[key] = ("album", None)
    store.get("a")  # used again: now the most recent
    store["d"] = ("album", None)

    assert store.get("b") is None
    assert all(store.get(k) is not None for k in ("a", "c", "d"))


def test_an_unreadable_file_starts_an_empty_map(tmp_path):
    path = tmp_path / "track_index.json"
    path.write_text("{ not json", encoding="utf-8")

    assert TrackAlbumStore(path).get("angra::rebirth") is None


def test_a_file_from_an_unknown_version_is_ignored(tmp_path):
    path = tmp_path / "track_index.json"
    path.write_text(json.dumps({"version": 99, "tracks": [["k", "a", None]]}), encoding="utf-8")

    assert TrackAlbumStore(path).get("k") is None


def test_forgetting_an_entry_is_saved_too(tmp_path):
    path = tmp_path / "track_index.json"
    store = TrackAlbumStore(path)
    store["k"] = ("album", None)
    store.flush()
    store.pop("k")
    store.flush()

    assert TrackAlbumStore(path).get("k") is None


def test_without_a_path_it_is_memory_only(tmp_path):
    store = TrackAlbumStore(None)
    store["k"] = ("album", None)
    store.flush()

    assert store.get("k") == ("album", None)


# --- the poller with a saved map -------------------------------------------------------


class _Smtc:
    def __init__(self, title):
        self.snapshot = SmtcNowPlaying(title=title, artist="Angra", album_title="?", album_artist="Angra", is_playing=True)

    def get_snapshot(self):
        return self.snapshot


def _poller(monkeypatch, store, render):
    calls = []

    def fetch(client):
        calls.append(1)
        return NowPlaying(True, "id", "rebirth", "http://cdn/rebirth.jpg", "Rebirth", "Angra")

    monkeypatch.setattr(poller_module, "fetch_now_playing", fetch)
    monkeypatch.setattr(poller_module, "fetch_album_tracks", lambda client, album_id: [])
    poller = Poller(
        client=object(),
        settings=Settings(),
        app_state=AppState(),
        render_fn=render,
        reauth_fn=lambda: object(),
        smtc_watcher=_Smtc("Rebirth"),
        track_index=store,
    )
    return poller, calls


def test_after_a_restart_a_known_song_needs_no_lookup(monkeypatch, tmp_path):
    path = tmp_path / "track_index.json"
    first, first_calls = _poller(monkeypatch, TrackAlbumStore(path), lambda np: None)
    first._run_one_cycle()
    assert first_calls == [1]

    rendered = []
    restarted, calls = _poller(monkeypatch, TrackAlbumStore(path), rendered.append)
    restarted._run_one_cycle()

    assert calls == [], "the saved map already knows this song"
    assert [r.album_id for r in rendered] == ["rebirth"]


def test_a_song_that_fails_to_draw_is_looked_up_again(monkeypatch, tmp_path):
    # e.g. the saved art link stopped working: a fresh lookup brings a new one.
    store = TrackAlbumStore(tmp_path / "track_index.json")
    store["angra::rebirth"] = ("rebirth", "http://cdn/gone.jpg")

    def failing(np):
        raise OSError("404")

    poller, calls = _poller(monkeypatch, store, failing)
    poller._run_one_cycle()

    assert calls == []
    assert store.get("angra::rebirth") is None, "forgotten, so the next cycle asks Spotify"
