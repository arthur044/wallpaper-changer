import collections
import contextlib
import logging
import threading
import time
from dataclasses import dataclass
from typing import Callable, Dict, List, Optional, Protocol

from src.os_integration.smtc import SmtcNowPlaying, TimelineSample

logger = logging.getLogger(__name__)

_MPRIS_PREFIX = "org.mpris.MediaPlayer2."
_MPRIS_PATH = "/org/mpris/MediaPlayer2"
_PLAYER_INTERFACE = "org.mpris.MediaPlayer2.Player"
# The official client is "spotify"; spotifast's name does not contain it.
# Same hints as SMTC's _SPOTIFY_AUMID_HINTS.
_SPOTIFY_NAME_HINTS = ("spotify", "spotifast")
# Events (PropertiesChanged, Seeked, a player appearing) wake the watcher
# early; this is the floor. 1 s, not SMTC's 2 s: spotifast never sends
# Seeked (measured 2026-10-01), so a seek there is only seen by reading
# Position, and the widget would wait this long to follow it.
_POLL_SECONDS = 1.0
_CALL_TIMEOUT_SECONDS = 2.0
# How far the player's Position may be from our own extrapolation before it
# is taken as a seek. spotifast's Position moves in steps of ~1.17 s
# (measured 2026-10-01), so a smaller gap is just where the step falls.
_SEEK_THRESHOLD_MS = 1500
# A signal from any MPRIS player wakes the watcher (a browser playing video
# can send many): at most one read of the bus per this long.
_MIN_REFRESH_SECONDS = 0.25
# A read that fails keeps the last snapshot, as SMTC does, so one slow reply
# doesn't send the poller to the Web API. Failing for longer than this, the
# bus is taken as gone and the snapshot is dropped.
_STALE_AFTER_SECONDS = 5.0


@dataclass(frozen=True)
class PlayerState:
    """One MPRIS player's Player properties, as read at [read_at] (monotonic)."""

    title: Optional[str]
    artist: Optional[str]
    album_title: Optional[str]
    album_artist: Optional[str]
    status: str  # "Playing", "Paused" or "Stopped"
    position_ms: int
    duration_ms: Optional[int]
    rate: float
    read_at: float

    @property
    def is_playing(self) -> bool:
        return self.status == "Playing"

    def snapshot(self) -> SmtcNowPlaying:
        return SmtcNowPlaying(
            title=self.title,
            artist=self.artist,
            album_title=self.album_title,
            album_artist=self.album_artist,
            is_playing=self.is_playing,
        )


def is_spotify_player(bus_name: str) -> bool:
    if not bus_name.startswith(_MPRIS_PREFIX):
        return False
    player = bus_name[len(_MPRIS_PREFIX):].lower()
    return any(hint in player for hint in _SPOTIFY_NAME_HINTS)


def _joined(value) -> Optional[str]:
    """xesam:artist and xesam:albumArtist are lists; SMTC gives one string.
    Anything else that isn't a string reads as unknown."""
    if isinstance(value, (list, tuple)):
        value = ", ".join(v for v in value if isinstance(v, str) and v)
    return value if isinstance(value, str) and value else None


def player_state(props: Dict[str, object], read_at: float) -> PlayerState:
    """[props] is the player's GetAll, with the D-Bus variants already
    unwrapped. Anything missing or of the wrong type reads as unknown."""
    metadata = props.get("Metadata")
    metadata = metadata if isinstance(metadata, dict) else {}
    length = metadata.get("mpris:length")
    position = props.get("Position")
    rate = props.get("Rate")
    status = props.get("PlaybackStatus")
    return PlayerState(
        title=_joined(metadata.get("xesam:title")),
        artist=_joined(metadata.get("xesam:artist")),
        album_title=_joined(metadata.get("xesam:album")),
        # spotifast leaves it out (measured 2026-10-01).
        album_artist=_joined(metadata.get("xesam:albumArtist")),
        status=status if isinstance(status, str) else "Stopped",
        # MPRIS times are microseconds.
        position_ms=max(0, position // 1000) if isinstance(position, int) else 0,
        duration_ms=length // 1000 if isinstance(length, int) and length > 0 else None,
        rate=float(rate) if isinstance(rate, (int, float)) and rate > 0 else 1.0,
        read_at=read_at,
    )


def _as_sample(state: PlayerState) -> TimelineSample:
    return TimelineSample(
        track_key=state.snapshot().track_key,
        position_ms=state.position_ms,
        observed_at=state.read_at,
        duration_ms=state.duration_ms,
        is_playing=state.is_playing,
        rate=state.rate,
        # MPRIS has no update stamp; Position is read live. None tells
        # SongClock to take every sample at its word.
        stamp=None,
    )


def next_timeline(current: Optional[TimelineSample], state: PlayerState) -> TimelineSample:
    """The sample the widget should count from after reading [state].

    Kept as it is while the player agrees with it, so the lyrics don't jitter
    with every read. spotifast's Position lags behind by up to one ~1.17 s
    step and never runs ahead, so a read a little ahead of our extrapolation
    is the truer one and is taken; one a little behind is the step and is
    not. A gap past _SEEK_THRESHOLD_MS either way is a seek."""
    fresh = _as_sample(state)
    if (
        current is None
        or current.track_key != fresh.track_key
        or current.is_playing != fresh.is_playing
        or current.rate != fresh.rate
        or current.duration_ms != fresh.duration_ms
        or not fresh.is_playing
    ):
        return fresh
    expected = current.position_ms + (fresh.observed_at - current.observed_at) * 1000 * current.rate
    gap = fresh.position_ms - expected
    if gap > 0 or abs(gap) > _SEEK_THRESHOLD_MS:
        return fresh
    return current


class PlayerPicker:
    """Which Spotify player to follow when more than one is open: the one
    playing; among several playing, or none, the one that changed last
    (started, paused, skipped). Both clients can play at once (seen
    2026-10-01 with spotifast and the official client)."""

    def __init__(self) -> None:
        self._last_seen: Dict[str, tuple] = {}
        self._changed_at: Dict[str, float] = {}
        self._first_pick = True

    def pick(self, states: Dict[str, PlayerState]) -> Optional[str]:
        for name in list(self._last_seen):
            if name not in states:
                del self._last_seen[name]
                self._changed_at.pop(name, None)
        for name, state in states.items():
            seen = (state.status, state.title, state.artist)
            if self._last_seen.get(name) != seen:
                self._last_seen[name] = seen
                # A player already open when the watcher starts counts as
                # changed long ago, so it never outranks a real change.
                self._changed_at[name] = float("-inf") if self._first_pick else state.read_at
        self._first_pick = False
        if not states:
            return None
        playing = [name for name, state in states.items() if state.is_playing]
        candidates = playing or list(states)
        # sorted first: ties go to the same player every time.
        return max(sorted(candidates), key=lambda name: self._changed_at[name])


class MprisBus(Protocol):
    """The few D-Bus calls the watcher makes; a fake in the tests."""

    def player_names(self) -> List[str]: ...

    def player_properties(self, name: str) -> Dict[str, object]: ...

    def wait_for_event(self, timeout: float) -> bool: ...

    def close(self) -> None: ...


class MprisWatcher:
    """Linux counterpart of SmtcWatcher, with the same contract: a thread
    that follows the Spotify client's MPRIS player on the session bus and
    keeps a snapshot (which track) and a timeline (where in it) for callers
    to pull. Accepts the official client and spotifast."""

    def __init__(
        self,
        connect: Optional[Callable[[], MprisBus]] = None,
        now: Callable[[], float] = time.monotonic,
        poll_seconds: float = _POLL_SECONDS,
    ) -> None:
        self._connect = connect if connect is not None else JeepneyBus
        self._now = now
        self._poll_seconds = poll_seconds
        self._lock = threading.Lock()
        self._snapshot: Optional[SmtcNowPlaying] = None
        self._timeline: Optional[TimelineSample] = None
        self._available = False
        self._picker = PlayerPicker()
        # Each player's last good read, reused while a read of it fails.
        self._states: Dict[str, PlayerState] = {}
        self._stop_event = threading.Event()
        self._first_refresh_done = threading.Event()
        self._thread: Optional[threading.Thread] = None

    def start(self) -> None:
        self._thread = threading.Thread(target=self._run, name="mpris-watcher", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._thread is not None:
            self._thread.join(timeout=5.0)

    def wait_ready(self, timeout: float = 3.0) -> bool:
        """Blocks until the first read of the bus has completed (or failed),
        so the poller's first cycle already sees the playing track."""
        return self._first_refresh_done.wait(timeout)

    def get_snapshot(self) -> Optional[SmtcNowPlaying]:
        with self._lock:
            return self._snapshot

    def get_timeline(self) -> Optional[TimelineSample]:
        with self._lock:
            return self._timeline

    def is_available(self) -> bool:
        with self._lock:
            return self._available

    def _run(self) -> None:
        try:
            bus = self._connect()
        except Exception:  # noqa: BLE001 - no session bus must not take down the poller loop
            logger.exception("MPRIS watcher could not reach the session bus; falling back to Spotify Web API only")
            self._first_refresh_done.set()
            return
        with self._lock:
            self._available = True
        failing_since: Optional[float] = None
        try:
            while True:
                started = self._now()
                try:
                    self.refresh(bus)
                    failing_since = None
                except Exception as exc:  # noqa: BLE001 - one failed read must not end the watcher
                    if failing_since is None:
                        logger.warning("Reading the MPRIS players failed: %s", exc)
                        failing_since = started
                    elif started - failing_since > _STALE_AFTER_SECONDS:
                        self._forget_everything()
                self._first_refresh_done.set()
                if self._stop_event.is_set():
                    break
                try:
                    bus.wait_for_event(self._poll_seconds)
                except Exception:  # noqa: BLE001 - waiting failed: poll at the floor instead
                    self._stop_event.wait(self._poll_seconds)
                self._stop_event.wait(max(0.0, _MIN_REFRESH_SECONDS - (self._now() - started)))
        finally:
            with contextlib.suppress(Exception):
                bus.close()

    def _forget_everything(self) -> None:
        self._states = {}
        with self._lock:
            self._snapshot = None
            self._timeline = None

    def refresh(self, bus: MprisBus) -> None:
        """One read of every Spotify player on the bus. Public for the tests.
        A player is dropped only once it leaves the bus; while a read of it
        fails, its last good read stands in."""
        states: Dict[str, PlayerState] = {}
        for name in bus.player_names():
            if not is_spotify_player(name):
                continue
            try:
                props = bus.player_properties(name)
            except Exception as exc:  # noqa: BLE001 - a slow or closing player must not cost the others
                logger.debug("Could not read MPRIS player %s: %s", name, exc)
                if name in self._states:
                    states[name] = self._states[name]
                continue
            states[name] = player_state(props, self._now())
        self._states = states
        chosen = self._picker.pick(states)
        with self._lock:
            if chosen is None:
                self._snapshot = None
                self._timeline = None
                return
            state = states[chosen]
            self._snapshot = state.snapshot()
            self._timeline = next_timeline(self._timeline, state)


def unwrap_variants(value):
    """jeepney gives a variant as (signature, value); MPRIS nests them in
    Metadata. Plain Python values all the way down."""
    if isinstance(value, tuple) and len(value) == 2 and isinstance(value[0], str):
        return unwrap_variants(value[1])
    if isinstance(value, dict):
        return {key: unwrap_variants(item) for key, item in value.items()}
    if isinstance(value, list):
        return [unwrap_variants(item) for item in value]
    return value


class JeepneyBus:
    """The session bus through jeepney (pure Python, no GLib). Used from the
    watcher's thread only: a blocking jeepney connection is not thread-safe."""

    def __init__(self) -> None:
        from jeepney import MatchRule, message_bus
        from jeepney.io.blocking import Proxy, open_dbus_connection

        self._conn = open_dbus_connection(bus="SESSION")
        try:
            self._bus_proxy = Proxy(message_bus, self._conn, timeout=_CALL_TIMEOUT_SECONDS)
            # Any of these just wakes the watcher, which then reads everything:
            # a bounded queue is enough, and a burst of signals is one refresh.
            self._events: collections.deque = collections.deque(maxlen=64)
            self._filters = contextlib.ExitStack()
            owner_changed = MatchRule(
                type="signal", interface="org.freedesktop.DBus", member="NameOwnerChanged", path="/org/freedesktop/DBus"
            )
            owner_changed.add_arg_condition(0, "org.mpris.MediaPlayer2", kind="namespace")
            rules = (
                MatchRule(
                    type="signal",
                    interface="org.freedesktop.DBus.Properties",
                    member="PropertiesChanged",
                    path=_MPRIS_PATH,
                ),
                # Only the official client sends it; spotifast is caught by the poll.
                MatchRule(type="signal", interface=_PLAYER_INTERFACE, member="Seeked", path=_MPRIS_PATH),
                owner_changed,
            )
            for rule in rules:
                self._bus_proxy.AddMatch(rule)
                self._filters.enter_context(self._conn.filter(rule, queue=self._events))
        except BaseException:
            # The watcher never gets this object to close it.
            self._conn.close()
            raise

    def player_names(self) -> List[str]: ...

    def player_properties(self, name: str) -> Dict[str, object]: ...

    def wait_for_event(self, timeout: float) -> bool: ...

    def close(self) -> None: ...


class MprisWatcher:
    """Linux counterpart of SmtcWatcher, with the same contract: a thread
    that follows the Spotify client's MPRIS player on the session bus and
    keeps a snapshot (which track) and a timeline (where in it) for callers
    to pull. Accepts the official client and spotifast."""

    def __init__(
        self,
        connect: Optional[Callable[[], MprisBus]] = None,
        now: Callable[[], float] = time.monotonic,
        poll_seconds: float = _POLL_SECONDS,
    ) -> None:
        self._connect = connect if connect is not None else JeepneyBus
        self._now = now
        self._poll_seconds = poll_seconds
        self._lock = threading.Lock()
        self._snapshot: Optional[SmtcNowPlaying] = None
        self._timeline: Optional[TimelineSample] = None
        self._available = False
        self._picker = PlayerPicker()
        # Each player's last good read, reused while a read of it fails.
        self._states: Dict[str, PlayerState] = {}
        self._stop_event = threading.Event()
        self._first_refresh_done = threading.Event()
        self._thread: Optional[threading.Thread] = None

    def start(self) -> None:
        self._thread = threading.Thread(target=self._run, name="mpris-watcher", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._thread is not None:
            self._thread.join(timeout=5.0)

    def wait_ready(self, timeout: float = 3.0) -> bool:
        """Blocks until the first read of the bus has completed (or failed),
        so the poller's first cycle already sees the playing track."""
        return self._first_refresh_done.wait(timeout)

    def get_snapshot(self) -> Optional[SmtcNowPlaying]:
        with self._lock:
            return self._snapshot

    def get_timeline(self) -> Optional[TimelineSample]:
        with self._lock:
            return self._timeline

    def is_available(self) -> bool:
        with self._lock:
            return self._available

    def _run(self) -> None:
        try:
            bus = self._connect()
        except Exception:  # noqa: BLE001 - no session bus must not take down the poller loop
            logger.exception("MPRIS watcher could not reach the session bus; falling back to Spotify Web API only")
            self._first_refresh_done.set()
            return
        with self._lock:
            self._available = True
        failing_since: Optional[float] = None
        try:
            while True:
                started = self._now()
                try:
                    self.refresh(bus)
                    failing_since = None
                except Exception as exc:  # noqa: BLE001 - one failed read must not end the watcher
                    if failing_since is None:
                        logger.warning("Reading the MPRIS players failed: %s", exc)
                        failing_since = started
                    elif started - failing_since > _STALE_AFTER_SECONDS:
                        self._forget_everything()
                self._first_refresh_done.set()
                if self._stop_event.is_set():
                    break
                try:
                    bus.wait_for_event(self._poll_seconds)
                except Exception:  # noqa: BLE001 - waiting failed: poll at the floor instead
                    self._stop_event.wait(self._poll_seconds)
                self._stop_event.wait(max(0.0, _MIN_REFRESH_SECONDS - (self._now() - started)))
        finally:
            with contextlib.suppress(Exception):
                bus.close()

    def _forget_everything(self) -> None:
        self._states = {}
        with self._lock:
            self._snapshot = None
            self._timeline = None

    def refresh(self, bus: MprisBus) -> None:
        """One read of every Spotify player on the bus. Public for the tests.
        A player is dropped only once it leaves the bus; while a read of it
        fails, its last good read stands in."""
        states: Dict[str, PlayerState] = {}
        for name in bus.player_names():
            if not is_spotify_player(name):
                continue
            try:
                props = bus.player_properties(name)
            except Exception as exc:  # noqa: BLE001 - a slow or closing player must not cost the others
                logger.debug("Could not read MPRIS player %s: %s", name, exc)
                if name in self._states:
                    states[name] = self._states[name]
                continue
            states[name] = player_state(props, self._now())
        self._states = states
        chosen = self._picker.pick(states)
        with self._lock:
            if chosen is None:
                self._snapshot = None
                self._timeline = None
                return
            state = states[chosen]
            self._snapshot = state.snapshot()
            self._timeline = next_timeline(self._timeline, state)


def unwrap_variants(value):
    """jeepney gives a variant as (signature, value); MPRIS nests them in
    Metadata. Plain Python values all the way down."""
    if isinstance(value, tuple) and len(value) == 2 and isinstance(value[0], str):
        return unwrap_variants(value[1])
    if isinstance(value, dict):
        return {key: unwrap_variants(item) for key, item in value.items()}
    if isinstance(value, list):
        return [unwrap_variants(item) for item in value]
    return value


class JeepneyBus:
    """The session bus through jeepney (pure Python, no GLib). Used from the
    watcher's thread only: a blocking jeepney connection is not thread-safe."""

    def __init__(self) -> None:
        from jeepney import MatchRule, message_bus
        from jeepney.io.blocking import Proxy, open_dbus_connection

        self._conn = open_dbus_connection(bus="SESSION")
        try:
            self._subscribe(Proxy, MatchRule, message_bus)
        except BaseException:
            # The watcher never gets this object to close it.
            self._conn.close()
            raise

    def _subscribe(self, Proxy, MatchRule, message_bus) -> None:  # noqa: N803 - jeepney's own names
        self._bus_proxy = Proxy(message_bus, self._conn, timeout=_CALL_TIMEOUT_SECONDS)
        # Any of these just wakes the watcher, which then reads everything:
        # a bounded queue is enough, and a burst of signals is one refresh.
        self._events: collections.deque = collections.deque(maxlen=64)
        self._filters = contextlib.ExitStack()
        owner_changed = MatchRule(
            type="signal", interface="org.freedesktop.DBus", member="NameOwnerChanged", path="/org/freedesktop/DBus"
        )
        owner_changed.add_arg_condition(0, "org.mpris.MediaPlayer2", kind="namespace")
        rules = (
            MatchRule(
                type="signal", interface="org.freedesktop.DBus.Properties", member="PropertiesChanged", path=_MPRIS_PATH
            ),
            # Only the official client sends it; spotifast is caught by the poll.
            MatchRule(type="signal", interface=_PLAYER_INTERFACE, member="Seeked", path=_MPRIS_PATH),
            owner_changed,
        )
        for rule in rules:
            self._bus_proxy.AddMatch(rule)
            self._filters.enter_context(self._conn.filter(rule, queue=self._events))

    def player_names(self) -> List[str]:
        (names,) = self._bus_proxy.ListNames()
        return [name for name in names if name.startswith(_MPRIS_PREFIX)]

    def player_properties(self, name: str) -> Dict[str, object]:
        from jeepney import DBusAddress, Properties
        from jeepney.wrappers import unwrap_msg

        address = DBusAddress(_MPRIS_PATH, bus_name=name, interface=_PLAYER_INTERFACE)
        reply = self._conn.send_and_get_reply(Properties(address).get_all(), timeout=_CALL_TIMEOUT_SECONDS)
        (props,) = unwrap_msg(reply)
        return unwrap_variants(props)

    def wait_for_event(self, timeout: float) -> bool:
        if not self._events:
            try:
                self._conn.recv_until_filtered(self._events, timeout=timeout)
            except TimeoutError:
                return False
        self._events.clear()
        return True

    def close(self) -> None:
        self._filters.close()
        self._conn.close()
