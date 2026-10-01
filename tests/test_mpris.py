import threading

import pytest

from src.lyrics.song_clock import SongClock
from src.os_integration.mpris import (
    MprisWatcher,
    PlayerPicker,
    is_spotify_player,
    next_timeline,
    player_state,
    unwrap_variants,
)
from src.os_integration.smtc import SmtcNowPlaying

SPOTIFY = "org.mpris.MediaPlayer2.spotify"
SPOTIFAST = "org.mpris.MediaPlayer2.spotifast"
VLC = "org.mpris.MediaPlayer2.vlc"


def _props(title="Black & Blue", artist=("Bring Me The Horizon",), album="Count Your Blessings",
           status="Playing", position_us=125_723_000, length_us=273_640_000, rate=1.0, album_artist=None):
    """A player's GetAll, variants unwrapped. Defaults: spotifast, as measured
    2026-10-01 (no xesam:albumArtist)."""
    metadata = {
        "mpris:trackid": "/rocks/spotifast/Track/track_01LmPUcfhSOGP2KtQVg58A",
        "mpris:length": length_us,
        "mpris:artUrl": "https://i.scdn.co/image/ab67616d0000b2735e324c795c568abd507e22e8",
        "xesam:title": title,
        "xesam:artist": list(artist),
        "xesam:album": album,
    }
    if album_artist is not None:
        metadata["xesam:albumArtist"] = list(album_artist)
    return {"PlaybackStatus": status, "Position": position_us, "Rate": rate, "Metadata": metadata}


class FakeBus:
    def __init__(self, players=None):
        self.players = dict(players or {})
        self.failing = set()
        self.closed = False
        self.waits = 0

    def player_names(self):
        return list(self.players)

    def player_properties(self, name):
        if name in self.failing:
            raise RuntimeError("org.freedesktop.DBus.Error.ServiceUnknown")
        return self.players[name]

    def wait_for_event(self, timeout):
        self.waits += 1
        threading.Event().wait(min(timeout, 0.01))
        return False

    def close(self):
        self.closed = True


class Clock:
    def __init__(self, start=100.0):
        self.t = start

    def __call__(self):
        return self.t


# --- which players ------------------------------------------------------------


@pytest.mark.parametrize(
    "name, expected",
    [
        (SPOTIFY, True),
        (SPOTIFAST, True),
        ("org.mpris.MediaPlayer2.spotify.instance4242", True),
        (VLC, False),
        ("org.mpris.MediaPlayer2.chromium.instance7", False),
        ("org.kde.StatusNotifierItem-1-spotify", False),
    ],
)
def test_only_spotify_clients_are_followed(name, expected):
    assert is_spotify_player(name) is expected


# --- reading a player ---------------------------------------------------------


def test_spotifast_properties_read_as_a_snapshot():
    state = player_state(_props(), read_at=5.0)

    assert state.snapshot() == SmtcNowPlaying(
        title="Black & Blue",
        artist="Bring Me The Horizon",
        album_title="Count Your Blessings",
        album_artist=None,  # spotifast leaves it out
        is_playing=True,
    )
    assert (state.position_ms, state.duration_ms, state.rate, state.read_at) == (125_723, 273_640, 1.0, 5.0)


def test_official_client_album_artist_and_several_artists_are_joined():
    props = _props(artist=("Artist A", "Artist B"), album_artist=("Dream Theater",))

    state = player_state(props, read_at=0.0)

    assert state.artist == "Artist A, Artist B"
    assert state.album_artist == "Dream Theater"


def test_a_player_with_nothing_loaded_reads_as_unknown_and_stopped():
    # spotifast right after it starts: Metadata {} and Stopped.
    state = player_state({"PlaybackStatus": "Stopped", "Position": 0, "Metadata": {}, "Rate": 1.0}, read_at=0.0)

    assert state.snapshot() == SmtcNowPlaying(None, None, None, None, False)
    assert state.duration_ms is None


def test_missing_or_odd_values_fall_back_instead_of_raising():
    state = player_state({"Metadata": "not a dict", "Position": "x", "Rate": 0.0}, read_at=0.0)

    assert (state.status, state.position_ms, state.rate, state.title) == ("Stopped", 0, 1.0, None)


def test_paused_is_not_playing():
    assert player_state(_props(status="Paused"), read_at=0.0).snapshot().is_playing is False


def test_variants_are_unwrapped_all_the_way_down():
    raw = {
        "PlaybackStatus": ("s", "Playing"),
        "Metadata": ("a{sv}", {"xesam:artist": ("as", ["A"]), "mpris:length": ("x", 5)}),
    }

    assert unwrap_variants(raw) == {"PlaybackStatus": "Playing", "Metadata": {"xesam:artist": ["A"], "mpris:length": 5}}


# --- timeline -----------------------------------------------------------------


def _state(position_ms, read_at, status="Playing", title="Black & Blue", rate=1.0):
    return player_state(_props(title=title, status=status, position_us=position_ms * 1000, rate=rate), read_at)


def test_the_first_read_is_the_timeline():
    sample = next_timeline(None, _state(30_000, read_at=10.0))

    assert (sample.position_ms, sample.observed_at, sample.duration_ms, sample.is_playing) == (30_000, 10.0, 273_640, True)
    assert sample.stamp is None  # MPRIS has no stamp: SongClock takes each sample at its word


def test_a_read_a_step_behind_keeps_the_anchor():
    # spotifast's Position moves in ~1.17 s steps: 2 s later it may still say +1.0 s.
    first = next_timeline(None, _state(30_000, read_at=10.0))

    assert next_timeline(first, _state(31_000, read_at=12.0)) is first


def test_a_read_ahead_of_the_extrapolation_moves_the_anchor_forward():
    first = next_timeline(None, _state(30_000, read_at=10.0))

    later = next_timeline(first, _state(32_400, read_at=12.0))

    assert (later.position_ms, later.observed_at) == (32_400, 12.0)


@pytest.mark.parametrize("position_ms", [10_000, 90_000])  # back and forward
def test_a_seek_is_followed_without_a_seeked_signal(position_ms):
    first = next_timeline(None, _state(30_000, read_at=10.0))

    after = next_timeline(first, _state(position_ms, read_at=11.0))

    assert (after.position_ms, after.observed_at) == (position_ms, 11.0)


def test_a_new_track_starts_a_new_timeline():
    first = next_timeline(None, _state(200_000, read_at=10.0))

    after = next_timeline(first, _state(1_000, read_at=11.0, title="Pyro"))

    assert after.position_ms == 1_000
    assert after.track_key != first.track_key


def test_pause_and_resume_take_the_read_position():
    playing = next_timeline(None, _state(30_000, read_at=10.0))
    paused = next_timeline(playing, _state(30_900, read_at=11.0, status="Paused"))
    resumed = next_timeline(paused, _state(30_900, read_at=20.0))

    assert (paused.is_playing, paused.position_ms) == (False, 30_900)
    assert (resumed.is_playing, resumed.position_ms, resumed.observed_at) == (True, 30_900, 20.0)


def test_a_rate_change_starts_a_new_anchor():
    first = next_timeline(None, _state(30_000, read_at=10.0))

    after = next_timeline(first, _state(30_500, read_at=11.0, rate=1.5))

    assert after.rate == 1.5 and after.observed_at == 11.0


def test_the_widget_clock_never_steps_back_on_spotifast_reads():
    # Reads as measured on spotifast: once a second, in ~1.17 s steps.
    reads = [(10.0, 30_000), (11.0, 31_168), (12.0, 31_168), (13.0, 33_504), (14.0, 33_504), (15.0, 35_840)]
    clock = SongClock(now=lambda: now[0])
    now = [0.0]
    sample, shown = None, []
    for read_at, position_ms in reads:
        sample = next_timeline(sample, _state(position_ms, read_at))
        now[0] = read_at
        clock.update(sample)
        shown.append(clock.position_ms())

    assert shown == sorted(shown)
    assert shown[-1] == 35_840


# --- which player when several are open ----------------------------------------


def test_the_playing_player_wins_over_a_paused_one():
    picker = PlayerPicker()
    states = {SPOTIFY: _state(0, 1.0, status="Paused"), SPOTIFAST: _state(0, 1.0)}

    assert picker.pick(states) == SPOTIFAST


def test_two_players_already_playing_at_start_always_pick_the_same_one():
    states = {SPOTIFY: _state(0, 1.0), SPOTIFAST: _state(0, 1.0)}

    assert PlayerPicker().pick(states) == PlayerPicker().pick(dict(reversed(states.items())))


def test_the_player_that_started_playing_last_wins_while_both_play():
    picker = PlayerPicker()
    picker.pick({SPOTIFAST: _state(0, 1.0), SPOTIFY: _state(0, 1.0, status="Paused")})

    assert picker.pick({SPOTIFAST: _state(0, 2.0), SPOTIFY: _state(0, 2.0)}) == SPOTIFY


def test_a_player_opened_after_the_start_counts_as_a_change():
    picker = PlayerPicker()
    picker.pick({SPOTIFAST: _state(0, 1.0)})

    assert picker.pick({SPOTIFAST: _state(0, 2.0), SPOTIFY: _state(0, 2.0)}) == SPOTIFY


def test_with_nothing_playing_the_one_paused_last_is_followed():
    picker = PlayerPicker()
    picker.pick({SPOTIFAST: _state(0, 1.0), SPOTIFY: _state(0, 1.0)})
    picker.pick({SPOTIFAST: _state(0, 2.0, status="Paused"), SPOTIFY: _state(0, 2.0)})

    assert picker.pick({SPOTIFAST: _state(0, 3.0, status="Paused"), SPOTIFY: _state(0, 3.0, status="Paused")}) == SPOTIFY


def test_no_players_is_none_and_a_closed_player_is_forgotten():
    picker = PlayerPicker()
    picker.pick({SPOTIFY: _state(0, 1.0)})

    assert picker.pick({}) is None
    assert picker.pick({SPOTIFAST: _state(0, 2.0, status="Paused")}) == SPOTIFAST


# --- the watcher --------------------------------------------------------------


def test_refresh_follows_spotify_and_ignores_other_players():
    bus = FakeBus({VLC: _props(title="Not Spotify"), SPOTIFAST: _props()})
    watcher = MprisWatcher(connect=lambda: bus, now=Clock(50.0))

    watcher.refresh(bus)

    assert watcher.get_snapshot().title == "Black & Blue"
    assert (watcher.get_timeline().position_ms, watcher.get_timeline().observed_at) == (125_723, 50.0)


def test_without_a_spotify_player_there_is_no_snapshot():
    # Spotify closed: None, so the poller falls back to the Web API (as with SMTC).
    bus = FakeBus({VLC: _props()})
    watcher = MprisWatcher(connect=lambda: bus)

    watcher.refresh(bus)

    assert watcher.get_snapshot() is None and watcher.get_timeline() is None


def test_a_player_that_vanishes_mid_read_is_skipped():
    bus = FakeBus({SPOTIFY: _props(title="Gone"), SPOTIFAST: _props()})
    bus.failing.add(SPOTIFY)
    watcher = MprisWatcher(connect=lambda: bus)

    watcher.refresh(bus)

    assert watcher.get_snapshot().title == "Black & Blue"


def test_closing_spotify_clears_the_snapshot():
    bus = FakeBus({SPOTIFAST: _props()})
    watcher = MprisWatcher(connect=lambda: bus)
    watcher.refresh(bus)

    del bus.players[SPOTIFAST]
    watcher.refresh(bus)

    assert watcher.get_snapshot() is None


def test_the_watcher_thread_is_ready_after_its_first_read_and_stops():
    bus = FakeBus({SPOTIFAST: _props()})
    watcher = MprisWatcher(connect=lambda: bus, poll_seconds=0.01)

    watcher.start()
    try:
        assert watcher.wait_ready(timeout=2.0)
        assert watcher.is_available()
        assert watcher.get_snapshot().artist == "Bring Me The Horizon"
    finally:
        watcher.stop()

    assert not watcher._thread.is_alive()
    assert bus.closed


def test_no_session_bus_is_ready_at_once_and_unavailable():
    def no_bus():
        raise ConnectionRefusedError("no session bus")

    watcher = MprisWatcher(connect=no_bus)
    watcher.start()

    assert watcher.wait_ready(timeout=2.0)
    assert not watcher.is_available()
    assert watcher.get_snapshot() is None
    watcher.stop()


def test_a_failed_read_does_not_end_the_watcher():
    bus = FakeBus({SPOTIFAST: _props()})
    calls = []
    real_names = bus.player_names

    def flaky_names():
        calls.append(1)
        if len(calls) == 1:
            raise TimeoutError("ListNames timed out")
        return real_names()

    bus.player_names = flaky_names
    watcher = MprisWatcher(connect=lambda: bus, poll_seconds=0.01)
    watcher.start()
    try:
        assert watcher.wait_ready(timeout=2.0)
        for _ in range(200):
            if watcher.get_snapshot() is not None:
                break
            threading.Event().wait(0.01)
        assert watcher.get_snapshot() is not None
    finally:
        watcher.stop()


def test_a_broken_wait_falls_back_to_the_poll_instead_of_spinning():
    bus = FakeBus({SPOTIFAST: _props()})

    def broken_wait(timeout):
        bus.waits += 1
        raise ConnectionResetError("bus gone")

    bus.wait_for_event = broken_wait
    watcher = MprisWatcher(connect=lambda: bus, poll_seconds=0.05)
    watcher.start()
    threading.Event().wait(0.3)
    watcher.stop()

    assert 1 <= bus.waits <= 10  # ~6 at a 0.05 s floor, not thousands


# --- review of #22: failures keep what was known ---------------------------------


def test_a_failed_read_of_the_only_player_keeps_the_snapshot_and_the_timeline():
    # As SMTC does: one slow reply must not send the poller to the Web API.
    bus = FakeBus({SPOTIFAST: _props()})
    watcher = MprisWatcher(connect=lambda: bus)
    watcher.refresh(bus)
    before = (watcher.get_snapshot(), watcher.get_timeline())

    bus.failing.add(SPOTIFAST)
    watcher.refresh(bus)

    assert (watcher.get_snapshot(), watcher.get_timeline()) == before


def test_a_failed_read_does_not_switch_between_two_playing_players():
    bus = FakeBus({SPOTIFAST: _props(), SPOTIFY: _props(album_artist=("Bring Me The Horizon",))})
    clock = Clock(0.0)
    watcher = MprisWatcher(connect=lambda: bus, now=clock)
    watcher.refresh(bus)
    followed = watcher.get_snapshot().album_artist

    for failing in (SPOTIFAST, SPOTIFY):
        bus.failing = {failing}
        clock.t += 1
        watcher.refresh(bus)
        bus.failing = set()
        clock.t += 1
        watcher.refresh(bus)

    assert watcher.get_snapshot().album_artist == followed


def test_reads_failing_for_long_drop_the_snapshot():
    bus = FakeBus({SPOTIFAST: _props()})
    clock = Clock(0.0)
    names = bus.player_names
    calls = []

    def failing_after_the_first():
        calls.append(1)
        clock.t += 1.0  # each cycle, a second of fake time
        if len(calls) > 1:
            raise TimeoutError("ListNames timed out")
        return names()

    bus.player_names = failing_after_the_first
    watcher = MprisWatcher(connect=lambda: bus, now=clock, poll_seconds=0.001)
    watcher.start()
    try:
        assert watcher.wait_ready(timeout=2.0)
        for _ in range(500):
            if watcher.get_snapshot() is None:
                break
            threading.Event().wait(0.005)
        assert watcher.get_snapshot() is None
        assert clock.t > 5.0  # held through the first seconds of failures
    finally:
        watcher.stop()


def test_a_value_of_the_wrong_type_reads_as_unknown_instead_of_failing_the_read():
    bus = FakeBus({SPOTIFAST: _props(title=5, artist=("Ok", 7))})
    watcher = MprisWatcher(connect=lambda: bus)

    watcher.refresh(bus)

    assert (watcher.get_snapshot().title, watcher.get_snapshot().artist) == (None, "Ok")


def test_a_storm_of_signals_is_read_at_most_four_times_a_second():
    bus = FakeBus({SPOTIFAST: _props()})
    reads = []
    names = bus.player_names
    bus.player_names = lambda: reads.append(1) or names()
    bus.wait_for_event = lambda timeout: True  # a browser sending signals nonstop
    watcher = MprisWatcher(connect=lambda: bus)
    watcher.start()
    threading.Event().wait(0.6)
    watcher.stop()

    assert len(reads) <= 4  # 0, 0.25, 0.5 s (+1 for the stop)


def test_the_connection_is_closed_when_subscribing_fails(monkeypatch):
    pytest.importorskip("jeepney")
    from jeepney.io import blocking

    from src.os_integration import mpris

    class Conn:
        closed = False

        def send_and_get_reply(self, message, timeout=None):
            raise PermissionError("AddMatch refused")

        def close(self):
            Conn.closed = True

    monkeypatch.setattr(blocking, "open_dbus_connection", lambda bus="SESSION": Conn())

    with pytest.raises(PermissionError):
        mpris.JeepneyBus()
    assert Conn.closed
