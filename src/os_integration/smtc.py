import asyncio
import hashlib
import logging
import threading
from dataclasses import dataclass
from typing import Optional

logger = logging.getLogger(__name__)

_SPOTIFY_AUMID_HINT = "spotify"
_PLAYBACK_STATUS_PLAYING = 4  # GlobalSystemMediaTransportControlsSessionPlaybackStatus.PLAYING
_SAFETY_POLL_SECONDS = 2.0  # events are the primary signal; this is just a floor in case one is missed


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


class SmtcWatcher:
    """Background watcher for the Spotify desktop app's Windows SMTC session.

    Keeps a thread-safe cached snapshot updated via SMTC change events (with a
    light poll as a safety net). Callers pull the snapshot synchronously via
    get_snapshot() — no asyncio leaks past this class's own thread.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._snapshot: Optional[SmtcNowPlaying] = None
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
            loop.call_soon_threadsafe(changed.set)

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
            registered_aumids.add(aumid)

    async def _refresh(self, manager) -> None:
        session = self._find_spotify_session(manager)
        if session is None:
            with self._lock:
                self._snapshot = None
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
        with self._lock:
            self._snapshot = snapshot

    @staticmethod
    def _find_spotify_session(manager):
        for session in manager.get_sessions():
            aumid = (session.source_app_user_model_id or "").lower()
            if _SPOTIFY_AUMID_HINT in aumid:
                return session
        return None
