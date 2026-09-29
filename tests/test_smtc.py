from datetime import datetime, timedelta, timezone

from src.os_integration.smtc import SmtcNowPlaying, SmtcWatcher, _stable_key, sample_from_smtc

_WALL = datetime(2026, 9, 28, 20, 0, 0, tzinfo=timezone.utc)


def _timeline(position_s=30.0, end_s=240.0, age_s=2.0, playing=True, rate=1.0, last_updated=None):
    return sample_from_smtc(
        track_key="k",
        position=timedelta(seconds=position_s),
        end_time=timedelta(seconds=end_s),
        last_updated=last_updated if last_updated is not None else _WALL - timedelta(seconds=age_s),
        is_playing=playing,
        rate=rate,
        wall_now=_WALL,
        mono_now=500.0,
    )


def test_a_playing_timeline_is_moved_to_when_it_was_read():
    sample = _timeline(position_s=30.0, age_s=2.0)

    assert sample.position_ms == 32_000
    assert sample.observed_at == 500.0
    assert sample.duration_ms == 240_000
    assert sample.stamp == (_WALL - timedelta(seconds=2)).timestamp()


def test_a_paused_timeline_is_taken_as_it_stands():
    assert _timeline(position_s=30.0, age_s=20.0, playing=False).position_ms == 30_000


def test_a_missing_or_zero_rate_counts_as_normal_speed():
    assert _timeline(age_s=1.0, rate=None).rate == 1.0
    assert _timeline(age_s=1.0, rate=0.0).position_ms == 31_000


def test_a_naive_update_time_is_read_as_utc():
    naive = (_WALL - timedelta(seconds=2)).replace(tzinfo=None)
    assert _timeline(last_updated=naive).position_ms == 32_000


def test_an_update_time_from_the_future_adds_nothing():
    assert _timeline(age_s=-5.0).position_ms == 30_000


def test_an_unset_update_time_adds_nothing():
    unset = datetime(1601, 1, 1, tzinfo=timezone.utc)
    sample = _timeline(last_updated=unset)
    assert sample.position_ms == 30_000
    assert sample.stamp is None, "an unset stamp can't tell one update from another"


def test_no_end_time_means_an_unknown_duration():
    assert _timeline(end_s=0.0).duration_ms is None


def test_get_timeline_is_none_before_any_read():
    assert SmtcWatcher().get_timeline() is None


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


def test_track_key_matches_artist_and_title():
    snapshot = _snapshot(artist="Avenged Sevenfold", title="Mattel")
    assert snapshot.track_key == _stable_key("Avenged Sevenfold", "Mattel")
