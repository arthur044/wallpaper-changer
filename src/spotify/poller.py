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
    fetch_album_tracks,
    fetch_now_playing,
)
from src.spotify.state_machine import PollDecision, decide, next_backoff
from src.spotify.track_index import TrackAlbumStore
from src.utils.app_state import AppState

logger = logging.getLogger(__name__)

RenderFn = Callable[[NowPlaying], None]
ReauthFn = Callable[[], Spotify]

# A new album seen through SMTC is looked up at most this often: quick enough
# for skipping between albums, far below what trips Spotify's rate limit. The
# background poll (Spotify desktop closed) keeps fallback_poll_interval_seconds.
_ALBUM_LOOKUP_SPACING_SECONDS = 5.0
# The Web API trails the desktop app: asked right as a song changes, it can
# still describe the previous one. Its answer is only used for the song that
# SMTC reports once the titles match, or after this many tries (the two can
# spell a title differently, and never drawing is worse).
_MAX_TITLE_MISMATCHES = 3


def _same_title(a: Optional[str], b: Optional[str]) -> bool:
    return (a or "").strip().casefold() == (b or "").strip().casefold()


def _track_key(artist: Optional[str], title: Optional[str]) -> str:
    return f"{(artist or '').strip().lower()}::{(title or '').strip().lower()}"


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
        track_index: Optional[TrackAlbumStore] = None,
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
        # Maps a normalized "artist::title" track key to the real (album_id,
        # art_url) it belongs to. Populated for every track of an album the
        # moment that album is first resolved (see _resolve_and_cache_album),
        # not just the one track that triggered the resolution - so jumping
        # straight to track 7 of an already-seen album is recognized without
        # any further API call. Only Spotify-sourced art is ever rendered:
        # until a track's entry appears here, nothing gets rendered for it.
        # Saved to disk when the app has a path for it (see TrackAlbumStore),
        # so songs of albums seen before need no lookup after a restart.
        self._track_to_album = track_index if track_index is not None else TrackAlbumStore(None)
        # Track keys already forgotten once after failing to draw (see below).
        self._forgotten_after_failure: set = set()
        # Albums used although the Web API reported another title (see
        # _resolve_and_cache_album): a guess, so memory-only, never saved.
        self._unverified_albums: Dict[str, Tuple[str, Optional[str]]] = {}
        self._last_render_failed = False
        # Track key whose "still unresolved" state has already been logged.
        # Without it the message below would repeat every poll_interval_seconds
        # for as long as the track plays; with it, a frozen wallpaper leaves
        # exactly one line per track in the log.
        self._unresolved_logged_key: Optional[str] = None
        # Set when the tray's Force Sync woke the loop; consumed by the next cycle.
        self._force_pending = False
        # What is on screen, so a forced cycle with nothing playing can redraw
        # it (a style change from the tray while the music is paused).
        self._last_rendered: Optional[NowPlaying] = None
        self._render_attempts = 0
        # A 429 from any path blocks every Web API call until this (monotonic),
        # Force Sync included: answering a rate limit with another request
        # only extends it.
        self._rate_limited_until = float("-inf")
        # Lookups of the playing song whose answer described another one, per track key.
        self._title_mismatches: Dict[str, int] = {}
        # Albums whose tracklist was already fetched (each costs one call, once),
        # and the one waiting to be fetched right after the current render.
        self._tracklists_fetched: set = set()
        self._pending_tracklist: Optional[Tuple[str, Tuple[str, Optional[str]]]] = None

    def set_client(self, client: Spotify) -> None:
        self._client = client

    def run_forever(self) -> None:
        while not self._app_state.stop_event.is_set():
            interval = self._run_one_cycle()
            self._wait(interval)

    def _wait(self, interval: float) -> None:
        # Wakes early on force-sync or stop so "Force Sync" from the tray feels instant.
        woken = self._app_state.force_sync_event.wait(timeout=interval)
        self._app_state.force_sync_event.clear()
        if woken and not self._app_state.stop_event.is_set():
            self._force_pending = True

    def _run_one_cycle(self) -> float:
        if self._app_state.is_paused():
            return self._settings.poll_interval_seconds

        forced, self._force_pending = self._force_pending, False
        try:
            return self._cycle(forced)
        finally:
            # Written once per change (a new album), never per song.
            self._track_to_album.flush()

    def _cycle(self, forced: bool) -> float:
        if not forced:
            return self._poll_once(forced=False)

        # Force Sync means "redraw now": the same track counts as new (also how
        # a style change from the tray reaches the screen), and the Web API
        # throttle is skipped once so a pending art lookup retries.
        logger.info("Force sync: redrawing the current track")
        self._last_rendered_track_id = None
        attempts_before = self._render_attempts
        interval = self._poll_once(forced=True)
        if self._render_attempts == attempts_before:
            self._redraw_last()
        return interval

    def _redraw_last(self) -> None:
        if self._last_rendered is None:
            logger.info("Force sync: nothing playing and nothing drawn yet, nothing to redraw")
            return
        logger.info("Force sync: nothing playing, redrawing the wallpaper on screen")
        self._render(self._last_rendered)

    def _poll_once(self, forced: bool) -> float:
        locked = self._is_locked_fn()

        smtc_snapshot = self._smtc.get_snapshot() if self._smtc is not None else None
        if smtc_snapshot is not None:
            # Spotify desktop is open: SMTC drives this, no network call at all
            # for track-change detection, so lock state doesn't matter here.
            return self._handle_smtc_snapshot(smtc_snapshot, forced)

        if locked:
            # Spotify desktop is closed and the workstation is locked: nothing
            # to show and nobody watching, so don't touch the Spotify API.
            return self._settings.poll_interval_seconds

        if not self._should_call_web_api(forced):
            return self._settings.poll_interval_seconds

        try:
            now_playing = fetch_now_playing(self._client)
        except RateLimitedError as exc:
            logger.warning("Rate limited by Spotify, backing off %.0fs", exc.retry_after)
            self._rate_limited_until = time.monotonic() + exc.retry_after
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

    def _handle_smtc_snapshot(self, snapshot: SmtcNowPlaying, forced: bool = False) -> float:
        track_key = _track_key(snapshot.artist, snapshot.title)
        resolved = self._track_to_album.get(track_key) or self._unverified_albums.get(track_key)

        spacing = min(_ALBUM_LOOKUP_SPACING_SECONDS, self._settings.fallback_poll_interval_seconds)
        if resolved is None and snapshot.is_playing and self._should_call_web_api(forced, spacing):
            resolved = self._resolve_and_cache_album(track_key, snapshot)

        if resolved is None:
            # No verified Spotify art for this album yet (throttled, failed,
            # or not playing): never fall back to the low-res local SMTC
            # thumbnail. Leave the current wallpaper untouched and retry on
            # a later cycle - _last_rendered_track_id is deliberately left
            # unset for this track, so once resolution does succeed it's
            # treated as a fresh RENDER (not a NOOP) and the good image
            # replaces whatever is currently showing.
            #
            # This is the only path that leaves the wallpaper stale, so it
            # says so: silently skipping here makes a frozen wallpaper
            # indistinguishable from an idle one in the log.
            self._log_unresolved(track_key, snapshot)
            self._fetch_pending_tracklist()
            return self._settings.poll_interval_seconds

        self._unresolved_logged_key = None
        album_id, art_url = resolved
        now_playing = _smtc_to_now_playing(snapshot, album_id=album_id, art_url=art_url)
        interval = self._handle_now_playing(now_playing)
        if not self._last_render_failed:
            # Drawn fine: if its link expires some day, it may be forgotten again.
            self._forgotten_after_failure.discard(track_key)
        elif track_key not in self._forgotten_after_failure:
            # Maybe the saved art link stopped working: forget it once, so the
            # next cycle asks Spotify again instead of failing on it forever.
            self._forgotten_after_failure.add(track_key)
            self._track_to_album.pop(track_key)
            self._unverified_albums.pop(track_key, None)
        # After the render, not before: the wallpaper shouldn't wait on a call
        # that only speeds up the album's other songs.
        self._fetch_pending_tracklist()
        return interval

    def _log_unresolved(self, track_key: str, snapshot: SmtcNowPlaying) -> None:
        if self._unresolved_logged_key == track_key:
            return
        self._unresolved_logged_key = track_key
        logger.info(
            "Wallpaper left unchanged: no Spotify art resolved yet for %s - %s (playing=%s)",
            snapshot.artist or "?",
            snapshot.title or "?",
            snapshot.is_playing,
        )

    def _resolve_and_cache_album(
        self, track_key: str, snapshot: SmtcNowPlaying
    ) -> Optional[Tuple[str, Optional[str]]]:
        """One throttled Web API call for the album of what is playing. Its
        tracklist is queued for right after the render (once per album), so
        every other song on it needs no call of its own afterwards."""
        fetched = self._resolve_high_res_art()
        if fetched is None or not fetched.album_id:
            return None

        resolved = (fetched.album_id, fetched.art_url)
        self._track_to_album[_track_key(fetched.artist_name, fetched.track_name)] = resolved
        if fetched.album_id not in self._tracklists_fetched:
            self._pending_tracklist = (fetched.album_id, resolved)

        if not _same_title(fetched.track_name, snapshot.title):
            misses = self._title_mismatches.get(track_key, 0) + 1
            self._title_mismatches[track_key] = misses
            if misses < _MAX_TITLE_MISMATCHES:
                logger.info(
                    "Web API still reports %r while %r plays; asking again shortly",
                    fetched.track_name,
                    snapshot.title,
                )
                return None
            logger.warning(
                "Web API keeps reporting %r for %r; using its album anyway", fetched.track_name, snapshot.title
            )
            self._title_mismatches.pop(track_key, None)
            self._unverified_albums[track_key] = resolved
            return resolved

        self._title_mismatches.pop(track_key, None)
        # Also under SMTC's own spelling of the artist, which can differ.
        self._track_to_album[track_key] = resolved
        return resolved

    def _fetch_pending_tracklist(self) -> None:
        """Best-effort: maps every song of the queued album. A failure only
        means those songs are looked up one by one later."""
        if self._pending_tracklist is None or time.monotonic() < self._rate_limited_until:
            return
        album_id, resolved = self._pending_tracklist
        self._pending_tracklist = None
        self._tracklists_fetched.add(album_id)
        try:
            for name, artist in fetch_album_tracks(self._client, album_id):
                self._track_to_album[_track_key(artist, name)] = resolved
        except RateLimitedError as exc:
            logger.warning("Rate limited while prefetching the tracklist, backing off %.0fs", exc.retry_after)
            self._rate_limited_until = time.monotonic() + exc.retry_after
            self._tracklists_fetched.discard(album_id)
            self._pending_tracklist = (album_id, resolved)
        except Exception as exc:  # noqa: BLE001 - a best-effort prefetch must never crash the render pipeline
            logger.warning("Failed to prefetch tracklist for album %s: %s", album_id, exc)

    def _resolve_high_res_art(self) -> Optional[NowPlaying]:
        """One throttled Web API call to get the real album_id + art_url for
        a SMTC-detected album. Any failure just means this cycle skips
        rendering instead - never blocks or crashes the polling loop."""
        try:
            return fetch_now_playing(self._client)
        except RateLimitedError as exc:
            logger.warning("Rate limited while resolving high-res art, backing off %.0fs", exc.retry_after)
            self._rate_limited_until = time.monotonic() + exc.retry_after
            self._last_web_api_at = time.monotonic() + max(
                0.0, exc.retry_after - self._settings.fallback_poll_interval_seconds
            )
        except AuthExpiredError as exc:
            logger.error("Spotify auth expired while resolving high-res art: %s", exc)
            self._app_state.set_error("Spotify session expired - please re-authenticate")
        except TransientNetworkError as exc:
            logger.warning("Transient network error while resolving high-res art: %s", exc)
        return None

    def _should_call_web_api(self, forced: bool = False, spacing: Optional[float] = None) -> bool:
        now = time.monotonic()
        if now < self._rate_limited_until:
            return False
        min_spacing = self._settings.fallback_poll_interval_seconds if spacing is None else spacing
        if not forced and now - self._last_web_api_at < min_spacing:
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
        self._render(now_playing)
        return interval

    def _render(self, now_playing: NowPlaying) -> None:
        self._render_attempts += 1
        self._last_render_failed = False
        try:
            self._render_fn(now_playing)
            self._last_rendered_track_id = now_playing.track_id
            self._last_rendered = now_playing
        except Exception as exc:  # noqa: BLE001 - a render failure must not kill the polling loop
            self._last_render_failed = True
            logger.exception("Render pipeline failed: %s", exc)

    def _bump_backoff(self) -> float:
        self._backoff_seconds = next_backoff(self._backoff_seconds)
        return self._backoff_seconds
