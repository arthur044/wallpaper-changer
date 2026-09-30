import asyncio
import hashlib
import logging
import threading
import time
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Optional

logger = logging.getLogger(__name__)

# "spotifast.exe" is a Spotify client whose id does not contain "spotify".
_SPOTIFY_AUMID_HINTS = ("spotify", "spotifast")
_PLAYBACK_STATUS_PLAYING = 4  # GlobalSystemMediaTransportControlsSessionPlaybackStatus.PLAYING
_SAFETY_POLL_SECONDS = 2.0  # events are the primary signal; this is just a floor in case one is missed
# An update stamped longer ago than this is no stamp at all (an unset SMTC
# DateTime reads as the year 1601): its position is taken as it stands.
_MAX_STAMP_AGE = timedelta(hours=12)


@dataclass(frozen=True)
class TimelineSample:
    """Where Spotify says the track is, anchored to time.monotonic() so the
    widget's clock ignores wall-clock changes.

    Spotify refreshes the SMTC timeline about every 4.5 s (measured
    2026-09-28); in between, the position is extrapolated from here."""

    track_key: str
    position_ms: int  # at [observed_at]
    observed_at: float  # time.monotonic() seconds
    duration_ms: Optional[int]
    is_playing: bool
    rate: float
    # SMTC's own last_updated_time, as epoch seconds: tells a fresh update
    # from one left over from the previous track. None when SMTC left it
    # unset, since every sample would then share it and look left over.
    stamp: Optional[float]


def sample_from_smtc(
    track_key: str,
    position: timedelta,
    end_time: timedelta,
    last_updated: datetime,
    is_playing: bool,
    rate: Optional[float],
    wall_now: datetime,
    mono_now: float,
) -> TimelineSample:
    """SMTC gives the position as of [last_updated] (wall clock); this moves it
    to [wall_now] and pins it to [mono_now]. Extrapolation, as the spike
    measured it: position + age x rate, only while playing."""
    if last_updated.tzinfo is None:
        last_updated = last_updated.replace(tzinfo=timezone.utc)
    rate = rate if rate and rate > 0 else 1.0
    age = wall_now - last_updated
    unset = age > _MAX_STAMP_AGE
    # A stamp from the future (clock skew) or from long ago (unset) adds nothing.
    if age < timedelta(0) or unset:
        age = timedelta(0)
    position_ms = position.total_seconds() * 1000
    if is_playing:
        position_ms += age.total_seconds() * 1000 * rate
    duration_ms = int(end_time.total_seconds() * 1000)
    return TimelineSample(
        track_key=track_key,
        position_ms=max(0, int(position_ms)),
        observed_at=mono_now,
        duration_ms=duration_ms if duration_ms > 0 else None,
        is_playing=is_playing,
        rate=rate,
        stamp=None if unset else last_updated.timestamp(),
    )


@dataclass(frozen=True)
class SmtcNowPlaying:
    title: Optional[str]
    artist: Optional[str]
    album_title: Optional[str]
    album_artist: Optional[str]
    is_playing: bool

    @property
    def track_key(self) -> str:
        return _stable_key(self.artist, self.title)


def _stable_key(primary: Optional[str], secondary: Optional[str]) -> str:
    """SMTC has no Spotify catalog id, so album/track identity is derived from
    metadata strings instead. Hashed (not slugified) so unicode titles never
    collide with filesystem-unsafe characters — see base_cache.base_cache_key,
    which today keys on the Spotify Web API's album_id."""
    raw = f"{(primary or '').strip().lower()}::{(secondary or '').strip().lower()}"
    if raw == "::":
        return "unknown"
    return hashlib.sha1(raw.encode("utf-8")).hexdigest()


def notify_loop(loop: asyncio.AbstractEventLoop, callback) -> None:
    """Hands an SMTC event (raised on a WinRT thread) to the watcher's loop.
    An event can still arrive while the watcher stops and its loop closes;
    there is nobody left to tell, so it is dropped instead of raising."""
    try:
        loop.call_soon_threadsafe(callback)
    except RuntimeError:  # "Event loop is closed"
        pass


class SmtcWatcher:
    """Background watcher for the Spotify desktop app's Windows SMTC session.

    Keeps a thread-safe cached snapshot updated via SMTC change events (with a
    light poll as a safety net). Callers pull the snapshot synchronously via
    get_snapshot() — no asyncio leaks past this class's own thread.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._snapshot: Optional[SmtcNowPlaying] = None
        self._timeline: Optional[TimelineSample] = None
        self._available = False
        self._stop_event = threading.Event()
        self._first_refresh_done = threading.Event()
        self._thread: Optional[threading.Thread] = None

    def start(self) -> None:
        self._thread = threading.Thread(target=self._run, name="smtc-watcher", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._thread is not None:
            self._thread.join(timeout=5.0)

    def wait_ready(self, timeout: float = 3.0) -> bool:
        """Blocks until the first SMTC snapshot attempt has completed (or the
        timeout elapses). Call this after start() and before the poller loop
        begins, so the very first poll cycle already sees a live snapshot
        instead of racing the watcher thread's startup and briefly falling
        back to a plain (non-resolving) Web API call."""
        return self._first_refresh_done.wait(timeout)

    def get_snapshot(self) -> Optional[SmtcNowPlaying]:
        with self._lock:
            return self._snapshot

    def get_timeline(self) -> Optional[TimelineSample]:
        """Kept apart from get_snapshot(): the position changes every few
        seconds, and the poller only cares about which track is playing."""
        with self._lock:
            return self._timeline

    def is_available(self) -> bool:
        with self._lock:
            return self._available

    def _run(self) -> None:
        try:
            asyncio.run(self._run_async())
        except Exception:  # noqa: BLE001 - a watcher crash must not take down the poller loop
            logger.exception("SMTC watcher failed to start; falling back to Spotify Web API only")
        finally:
            # Unblocks any wait_ready() caller immediately on failure instead
            # of making it sit through the full timeout for nothing.
            self._first_refresh_done.set()

    async def _run_async(self) -> None:
        from winsdk.windows.media.control import (
            GlobalSystemMediaTransportControlsSessionManager as SessionManager,
        )

        manager = await SessionManager.request_async()
        with self._lock:
            self._available = True

        loop = asyncio.get_running_loop()
        changed = asyncio.Event()

        def _mark_changed(*_args) -> None:
            notify_loop(loop, changed.set)

        manager.add_sessions_changed(_mark_changed)
        registered_aumids: set = set()

        await self._refresh(manager)
        self._first_refresh_done.set()

        while not self._stop_event.is_set():
            self._register_new_sessions(manager, registered_aumids, _mark_changed)
            try:
                await asyncio.wait_for(changed.wait(), timeout=_SAFETY_POLL_SECONDS)
            except asyncio.TimeoutError:
                pass
            changed.clear()
            await self._refresh(manager)

    def _register_new_sessions(self, manager, registered_aumids: set, handler) -> None:
        for session in manager.get_sessions():
            aumid = session.source_app_user_model_id
            if aumid in registered_aumids:
                continue
            session.add_media_properties_changed(handler)
            session.add_playback_info_changed(handler)
            session.add_timeline_properties_changed(handler)
            registered_aumids.add(aumid)

    async def _refresh(self, manager) -> None:
        session = self._find_spotify_session(manager)
        if session is None:
            with self._lock:
                self._snapshot = None
                self._timeline = None
            return

        try:
            props = await session.try_get_media_properties_async()
            playback_info = session.get_playback_info()
        except Exception as exc:  # noqa: BLE001 - a single failed read must not kill the watcher loop
            logger.warning("Failed to read SMTC media properties: %s", exc)
            return

        snapshot = SmtcNowPlaying(
            title=props.title or None,
            artist=props.artist or None,
            album_title=props.album_title or None,
            album_artist=props.album_artist or None,
            is_playing=playback_info.playback_status == _PLAYBACK_STATUS_PLAYING,
        )
        timeline = self._read_timeline(session, snapshot, playback_info)
        with self._lock:
            self._snapshot = snapshot
            self._timeline = timeline

    @staticmethod
    def _read_timeline(session, snapshot: SmtcNowPlaying, playback_info) -> Optional[TimelineSample]:
        try:
            properties = session.get_timeline_properties()
            return sample_from_smtc(
                track_key=snapshot.track_key,
                position=properties.position,
                end_time=properties.end_time,
                last_updated=properties.last_updated_time,
                is_playing=snapshot.is_playing,
                rate=playback_info.playback_rate,
                wall_now=datetime.now(timezone.utc),
                mono_now=time.monotonic(),
            )
        except Exception as exc:  # noqa: BLE001 - no position must not cost the track snapshot
            logger.debug("Failed to read the SMTC timeline: %s", exc)
            return None

    @staticmethod
    def _find_spotify_session(manager):
        for session in manager.get_sessions():
            aumid = (session.source_app_user_model_id or "").lower()
            if any(hint in aumid for hint in _SPOTIFY_AUMID_HINTS):
                return session
        return None
