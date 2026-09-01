from src.os_integration.smtc import SmtcNowPlaying, SmtcWatcher, _stable_key


def test_wait_ready_returns_false_before_first_refresh():
    watcher = SmtcWatcher()
    assert watcher.wait_ready(timeout=0.05) is False


def test_wait_ready_returns_true_after_first_refresh():
    watcher = SmtcWatcher()
    watcher._first_refresh_done.set()
    assert watcher.wait_ready(timeout=0.05) is True


def _snapshot(title="T", artist="A", album_title="Alb", album_artist="AlbArtist", is_playing=True):
    return SmtcNowPlaying(
        title=title,
        artist=artist,
        album_title=album_title,
        album_artist=album_artist,
        is_playing=is_playing,
    )


def test_stable_key_is_deterministic():
    assert _stable_key("A", "B") == _stable_key("A", "B")


def test_stable_key_is_case_and_whitespace_insensitive():
    assert _stable_key("Some Artist", "Some Album") == _stable_key(" some artist ", " some album ")


def test_stable_key_differs_for_different_input():
    assert _stable_key("A", "B") != _stable_key("A", "C")


def test_stable_key_handles_missing_fields():
    assert _stable_key(None, None) == "unknown"


def test_album_key_matches_artist_and_album_title():
    snapshot = _snapshot(album_artist="Avenged Sevenfold", album_title="Life Is But a Dream")
    assert snapshot.album_key == _stable_key("Avenged Sevenfold", "Life Is But a Dream")


def test_track_key_matches_artist_and_title():
    snapshot = _snapshot(artist="Avenged Sevenfold", title="Mattel")
    assert snapshot.track_key == _stable_key("Avenged Sevenfold", "Mattel")


def test_different_albums_get_different_keys():
    a = _snapshot(album_title="Album A")
    b = _snapshot(album_title="Album B")
    assert a.album_key != b.album_key
