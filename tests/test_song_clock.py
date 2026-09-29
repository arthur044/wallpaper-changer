from src.lyrics.song_clock import SongClock
from src.os_integration.smtc import TimelineSample


class _FakeClock:
    def __init__(self, start: float = 100.0):
        self.now = start

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


def _sample(clock, position_ms, *, track="t1", playing=True, stamp=1.0, duration_ms=240_000, rate=1.0):
    return TimelineSample(
        track_key=track,
        position_ms=position_ms,
        observed_at=clock.now,
        duration_ms=duration_ms,
        is_playing=playing,
        rate=rate,
        stamp=stamp,
    )


def test_nothing_known_means_no_position():
    clock = SongClock(now=_FakeClock())
    assert clock.position_ms() is None
    assert clock.track_key is None
    assert clock.is_playing is False


def test_while_playing_the_position_moves_with_the_clock():
    fake = _FakeClock()
    clock = SongClock(now=fake)
    clock.update(_sample(fake, 10_000))

    fake.advance(2.5)

    assert clock.position_ms() == 12_500
    assert clock.is_playing is True


def test_the_playback_rate_scales_the_movement():
    fake = _FakeClock()
    clock = SongClock(now=fake)
    clock.update(_sample(fake, 10_000, rate=2.0))

    fake.advance(1.0)

    assert clock.position_ms() == 12_000


def test_paused_the_position_stays_put():
    fake = _FakeClock()
    clock = SongClock(now=fake)
    clock.update(_sample(fake, 10_000, playing=False))

    fake.advance(30.0)

    assert clock.position_ms() == 10_000
    assert clock.is_playing is False


def test_pausing_then_resuming_picks_up_where_it_stopped():
    fake = _FakeClock()
    clock = SongClock(now=fake)
    clock.update(_sample(fake, 10_000, stamp=1.0))
    fake.advance(3.0)
    clock.update(_sample(fake, 13_000, playing=False, stamp=2.0))  # paused at 0:13
    fake.advance(60.0)
    assert clock.position_ms() == 13_000

    clock.update(_sample(fake, 13_000, stamp=3.0))  # resumed
    fake.advance(1.0)

    assert clock.position_ms() == 14_000


def test_a_seek_forward_or_back_is_taken_from_the_next_sample():
    fake = _FakeClock()
    clock = SongClock(now=fake)
    clock.update(_sample(fake, 10_000, stamp=1.0))
    fake.advance(1.0)

    clock.update(_sample(fake, 90_000, stamp=2.0))
    assert clock.position_ms() == 90_000

    clock.update(_sample(fake, 5_000, stamp=3.0))
    fake.advance(0.5)
    assert clock.position_ms() == 5_500


def test_the_position_never_passes_the_end_of_the_track():
    fake = _FakeClock()
    clock = SongClock(now=fake)
    clock.update(_sample(fake, 238_000, duration_ms=240_000))

    fake.advance(10.0)

    assert clock.position_ms() == 240_000


def test_an_unknown_duration_puts_no_ceiling():
    fake = _FakeClock()
    clock = SongClock(now=fake)
    clock.update(_sample(fake, 238_000, duration_ms=None))

    fake.advance(10.0)

    assert clock.position_ms() == 248_000


def test_a_new_track_with_its_own_timeline_starts_where_it_says():
    fake = _FakeClock()
    clock = SongClock(now=fake)
    clock.update(_sample(fake, 200_000, track="t1", stamp=1.0))
    fake.advance(1.0)

    clock.update(_sample(fake, 300, track="t2", stamp=2.0))

    assert clock.track_key == "t2"
    assert clock.position_ms() == 300


def test_a_new_track_with_the_old_tracks_timeline_starts_from_zero():
    fake = _FakeClock()
    clock = SongClock(now=fake)
    clock.update(_sample(fake, 200_000, track="t1", stamp=1.0))
    fake.advance(1.0)

    # SMTC already names the next track, but its timeline is still t1's.
    clock.update(_sample(fake, 201_000, track="t2", stamp=1.0))
    assert clock.position_ms() == 0

    # Later reads of the same leftover timeline don't bring t1's position back.
    fake.advance(2.0)
    clock.update(_sample(fake, 203_000, track="t2", stamp=1.0))
    assert clock.position_ms() == 2_000

    # A fresh update for t2 is taken at its word again.
    fake.advance(1.0)
    clock.update(_sample(fake, 3_100, track="t2", stamp=4.0))
    assert clock.position_ms() == 3_100


def test_a_pause_on_the_leftover_timeline_still_freezes_the_clock():
    fake = _FakeClock()
    clock = SongClock(now=fake)
    clock.update(_sample(fake, 200_000, track="t1", stamp=1.0))
    clock.update(_sample(fake, 200_000, track="t2", stamp=1.0))
    fake.advance(2.0)

    clock.update(_sample(fake, 200_000, track="t2", playing=False, stamp=1.0))
    fake.advance(5.0)

    assert clock.position_ms() == 2_000


def test_without_stamps_a_new_track_is_taken_at_its_word():
    """Review #11: an unset SMTC stamp is the same on every sample, so it
    must not make every track change look like a leftover timeline."""
    fake = _FakeClock()
    clock = SongClock(now=fake)
    clock.update(_sample(fake, 200_000, track="t1", stamp=None))
    fake.advance(1.0)

    clock.update(_sample(fake, 1_500, track="t2", stamp=None))
    assert clock.position_ms() == 1_500
    assert clock.duration_ms == 240_000

    clock.update(_sample(fake, 90_000, track="t2", stamp=None))  # a seek
    assert clock.position_ms() == 90_000


def test_spotify_closing_clears_the_clock():
    fake = _FakeClock()
    clock = SongClock(now=fake)
    clock.update(_sample(fake, 10_000))

    clock.update(None)

    assert clock.position_ms() is None
    assert clock.track_key is None
