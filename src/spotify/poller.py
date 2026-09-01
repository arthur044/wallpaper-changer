import logging
import time
from typing import Callable, Dict, Optional, Tuple

from spotipy import Spotify

from src.config.settings import Settings
from src.os_integration.smtc import SmtcNowPlaying, SmtcWatcher
from src.spotify.client import (
    AuthExpiredError,
    NowPlaying,
    RateLimitedError,
    TransientNetworkError,
    fetch_now_playing,
)
from src.spotify.state_machine import PollDecision, decide, next_backoff
from src.utils.app_state import AppState

logger = logging.getLogger(__name__)

RenderFn = Callable[[NowPlaying], None]
ReauthFn = Callable[[], Spotify]


def _smtc_to_now_playing(snapshot: SmtcNowPlaying, album_id: str, art_url: Optional[str]) -> NowPlaying:
    # Track/album identity (for change detection) always comes from SMTC -
    # it's free and near-instant. The art itself never does: only verified
    # Spotify art (album_id + art_url resolved via the Web API) is used.
    return NowPlaying(
        is_playing=snapshot.is_playing,
        track_id=snapshot.track_key,
        album_id=album_id,
        art_url=art_url,
        track_name=snapshot.title,
        artist_name=snapshot.artist,
    )


class Poller:
    def __init__(
        self,
        client: Spotify,
        settings: Settings,
        app_state: AppState,
        render_fn: RenderFn,
        reauth_fn: ReauthFn,
        smtc_watcher: Optional[SmtcWatcher] = None,
        is_locked_fn: Callable[[], bool] = lambda: False,
    ) -> None:
        self._client = client
        self._settings = settings
        self._app_state = app_state
        self._render_fn = render_fn
        self._reauth_fn = reauth_fn
        self._smtc = smtc_watcher
        self._is_locked_fn = is_locked_fn
        self._last_rendered_track_id: Optional[str] = None
        self._backoff_seconds = 0.0
        self._last_web_api_at = float("-inf")
        # Maps a SMTC-derived album key (hash of artist+title strings, see
        # os_integration/smtc.py) to the real (album_id, art_url) resolved
        # via one Web API call. Only Spotify-sourced art is ever rendered -
        # until an album is resolved, its entry is simply absent here and
        # nothing gets rendered for it. In-memory only, cleared on restart.
        self._smtc_album_resolution: Dict[str, Tuple[str, Optional[str]]] = {}

    def set_client(self, client: Spotify) -> None:
        self._client = client

    def run_forever(self) -> None:
        while not self._app_state.stop_event.is_set():
            interval = self._run_one_cycle()
            self._wait(interval)

    def _wait(self, interval: float) -> None:
        # Wakes early on force-sync or stop so "Force Sync" from the tray feels instant.
        self._app_state.force_sync_event.wait(timeout=interval)
        self._app_state.force_sync_event.clear()

    def _run_one_cycle(self) -> float:
        if self._app_state.is_paused():
            return self._settings.poll_interval_seconds

        locked = self._is_locked_fn()

        smtc_snapshot = self._smtc.get_snapshot() if self._smtc is not None else None
        if smtc_snapshot is not None:
            # Spotify desktop is open: SMTC drives this, no network call at all
            # for track-change detection, so lock state doesn't matter here.
            return self._handle_smtc_snapshot(smtc_snapshot)

        if locked:
            # Spotify desktop is closed and the workstation is locked: nothing
            # to show and nobody watching, so don't touch the Spotify API.
            return self._settings.poll_interval_seconds

        if not self._should_call_web_api():
            return self._settings.poll_interval_seconds

        try:
            now_playing = fetch_now_playing(self._client)
        except RateLimitedError as exc:
            logger.warning("Rate limited by Spotify, backing off %.0fs", exc.retry_after)
            return exc.retry_after
        except AuthExpiredError as exc:
            logger.error("Spotify auth expired, reauthenticating: %s", exc)
            self._app_state.set_error("Spotify session expired - please re-authenticate")
            try:
                self._client = self._reauth_fn()
                self._app_state.clear_error()
            except Exception as reauth_exc:  # noqa: BLE001 - auth failures must never kill the loop
                logger.error("Re-authentication failed: %s", reauth_exc)
            return self._bump_backoff()
        except TransientNetworkError as exc:
            logger.warning("Transient network error: %s", exc)
            return self._bump_backoff()

        self._backoff_seconds = 0.0
        return self._handle_now_playing(now_playing, success_interval=self._settings.fallback_poll_interval_seconds)

    def _handle_smtc_snapshot(self, snapshot: SmtcNowPlaying) -> float:
        resolved = self._smtc_album_resolution.get(snapshot.album_key)

        if resolved is None and snapshot.is_playing and self._should_call_web_api():
            fetched = self._resolve_high_res_art()
            if fetched is not None and fetched.album_id:
                resolved = (fetched.album_id, fetched.art_url)
                self._smtc_album_resolution[snapshot.album_key] = resolved

        if resolved is None:
            # No verified Spotify art for this album yet (throttled, failed,
            # or not playing): never fall back to the low-res local SMTC
            # thumbnail. Leave the current wallpaper untouched and retry on
            # a later cycle - _last_rendered_track_id is deliberately left
            # unset for this track, so once resolution does succeed it's
            # treated as a fresh RENDER (not a NOOP) and the good image
            # replaces whatever is currently showing.
            return self._settings.poll_interval_seconds

        album_id, art_url = resolved
        now_playing = _smtc_to_now_playing(snapshot, album_id=album_id, art_url=art_url)
        return self._handle_now_playing(now_playing)

    def _resolve_high_res_art(self) -> Optional[NowPlaying]:
        """One throttled Web API call to get the real album_id + art_url for
        a SMTC-detected album. Any failure just means this cycle skips
        rendering instead - never blocks or crashes the polling loop."""
        try:
            return fetch_now_playing(self._client)
        except RateLimitedError as exc:
            logger.warning("Rate limited while resolving high-res art, backing off %.0fs", exc.retry_after)
            self._last_web_api_at = time.monotonic() + max(
                0.0, exc.retry_after - self._settings.fallback_poll_interval_seconds
            )
        except AuthExpiredError as exc:
            logger.error("Spotify auth expired while resolving high-res art: %s", exc)
            self._app_state.set_error("Spotify session expired - please re-authenticate")
        except TransientNetworkError as exc:
            logger.warning("Transient network error while resolving high-res art: %s", exc)
        return None

    def _should_call_web_api(self) -> bool:
        now = time.monotonic()
        if now - self._last_web_api_at < self._settings.fallback_poll_interval_seconds:
            return False
        self._last_web_api_at = now
        return True

    def _handle_now_playing(self, now_playing: Optional[NowPlaying], success_interval: Optional[float] = None) -> float:
        interval = success_interval if success_interval is not None else self._settings.poll_interval_seconds
        decision = decide(now_playing, self._last_rendered_track_id)

        if decision is PollDecision.IDLE:
            self._app_state.set_idle()
            return interval

        if decision is PollDecision.NOOP:
            self._app_state.set_playing(now_playing.track_id, now_playing.album_id)
            return interval

        self._app_state.set_playing(now_playing.track_id, now_playing.album_id)
        try:
            self._render_fn(now_playing)
            self._last_rendered_track_id = now_playing.track_id
        except Exception as exc:  # noqa: BLE001 - a render failure must not kill the polling loop
            logger.exception("Render pipeline failed: %s", exc)

        return interval

    def _bump_backoff(self) -> float:
        self._backoff_seconds = next_backoff(self._backoff_seconds)
        return self._backoff_seconds
