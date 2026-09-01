import logging
import socket
import webbrowser
from dataclasses import dataclass
from enum import Enum, auto
from typing import Optional
from urllib.parse import urlparse

from spotipy import Spotify

from src.config.settings import Settings, save_settings
from src.onboarding.state import normalize_client_id
from src.os_integration import lockscreen
from src.os_integration.autostart import install_autostart
from src.spotify.auth import build_auth_manager
from src.spotify.client import (
    AuthExpiredError,
    RateLimitedError,
    TransientNetworkError,
    fetch_now_playing,
)

logger = logging.getLogger(__name__)

DASHBOARD_URL = "https://developer.spotify.com/dashboard"

# Spotify reports an unregistered callback as "INVALID_CLIENT: Invalid
# redirect URI". Match that phrase rather than a bare "redirect", so an
# unrelated failure (requests' "Exceeded 30 redirects", a proxy loop) isn't
# misreported as a dashboard problem the user then wastes time "fixing".
_REDIRECT_MISMATCH_MARKERS = ("redirect uri", "redirect_uri")


class OnboardingError(Exception):
    """Base for failures the wizard knows how to explain to the user."""


class RedirectUriMismatchError(OnboardingError):
    """The redirect URI in config isn't registered on the Spotify app."""


class PortBusyError(OnboardingError):
    """Something else already holds the redirect URI's port, so the OAuth
    callback can't be received."""


class AuthenticationFailedError(OnboardingError):
    pass


class VerifyResult(Enum):
    PLAYING = auto()
    NOTHING_PLAYING = auto()


@dataclass(frozen=True)
class VerifyOutcome:
    result: VerifyResult
    track_name: Optional[str] = None
    artist_name: Optional[str] = None


@dataclass(frozen=True)
class ApplyOptionsResult:
    # str(exc) of a failed autostart install; the caller owns the wording.
    autostart_error: Optional[str] = None
    # The elevated scheduled task wasn't authorized. Everything else applied.
    lockscreen_declined: bool = False

    @property
    def finished(self) -> bool:
        return self.autostart_error is None and not self.lockscreen_declined


def open_dashboard() -> None:
    webbrowser.open(DASHBOARD_URL)


def looks_like_redirect_mismatch(message: str) -> bool:
    lowered = (message or "").lower()
    return any(marker in lowered for marker in _REDIRECT_MISMATCH_MARKERS)


def redirect_port(redirect_uri: str) -> Optional[int]:
    try:
        return urlparse(redirect_uri).port
    except ValueError:
        return None


def is_redirect_port_free(redirect_uri: str) -> bool:
    """The PKCE flow spins up a local server on this port to catch the
    callback, so a port already in use means the login would hang."""
    port = redirect_port(redirect_uri)
    if port is None:
        return True

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind(("127.0.0.1", port))
        except OSError:
            return False
    return True


def apply_options(settings: Settings, autostart: bool, sync_lock_screen: bool) -> ApplyOptionsResult:
    """Applies the wizard's final toggles.

    Mirrors the tray's own toggle (os_integration/tray.py) on purpose,
    including uninstalling the scheduled task when lock screen sync is
    switched off: leaving it registered would keep it firing at every logon
    while the config claims the feature is disabled.

    Blocks while the UAC prompt for the scheduled task is up, so callers must
    keep it off any UI thread.
    """
    if autostart:
        try:
            install_autostart()
        except OSError as exc:
            logger.error("Autostart install failed: %s", exc)
            return ApplyOptionsResult(autostart_error=str(exc))

    if sync_lock_screen and not settings.sync_lock_screen:
        if not (lockscreen.is_task_installed() or lockscreen.install_task()):
            logger.warning("Lock screen sync not enabled: task installation was declined or failed")
            return ApplyOptionsResult(lockscreen_declined=True)
    elif settings.sync_lock_screen and not sync_lock_screen:
        lockscreen.uninstall_task()

    settings.sync_lock_screen = sync_lock_screen
    save_settings(settings)
    return ApplyOptionsResult()


def save_client_id(settings: Settings, raw_client_id: str) -> None:
    settings.client_id = normalize_client_id(raw_client_id)
    save_settings(settings)
    logger.info("Client ID saved to config")


def authenticate(settings: Settings) -> Spotify:
    """Runs the interactive PKCE login (opens a browser) and returns a ready
    Spotify client. Raises the typed errors above so the wizard can explain
    exactly what went wrong instead of dumping a traceback."""
    if not is_redirect_port_free(settings.redirect_uri):
        raise PortBusyError(
            f"Port {redirect_port(settings.redirect_uri)} is already in use, "
            "so the Spotify login can't complete."
        )

    auth_manager = build_auth_manager(settings.client_id, settings.redirect_uri, settings.scope)
    try:
        auth_manager.get_access_token()
    except Exception as exc:  # noqa: BLE001 - spotipy raises a wide range here; all of it is user-facing
        message = str(exc)
        if looks_like_redirect_mismatch(message):
            # Keep the original text: the heuristic can still be wrong, and
            # nothing else ever prints the underlying error.
            raise RedirectUriMismatchError(
                f"Spotify rejected the redirect URI {settings.redirect_uri}. "
                f"Add it to your app's settings in the dashboard. ({message})"
            ) from exc
        raise AuthenticationFailedError(message) from exc

    return Spotify(auth_manager=auth_manager)


def verify_connection(client: Spotify) -> VerifyOutcome:
    """One call to prove the token actually works. Nothing playing is a
    success too - it just means Spotify is idle right now."""
    try:
        now_playing = fetch_now_playing(client)
    except AuthExpiredError as exc:
        raise AuthenticationFailedError(str(exc)) from exc
    except RateLimitedError as exc:
        raise OnboardingError(
            f"Spotify is rate limiting this account right now (retry in {exc.retry_after:.0f}s)."
        ) from exc
    except TransientNetworkError as exc:
        raise OnboardingError(f"Couldn't reach Spotify: {exc}") from exc

    if now_playing is None or not now_playing.is_playing:
        return VerifyOutcome(result=VerifyResult.NOTHING_PLAYING)

    return VerifyOutcome(
        result=VerifyResult.PLAYING,
        track_name=now_playing.track_name,
        artist_name=now_playing.artist_name,
    )
