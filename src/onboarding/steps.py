import logging
import socket
import webbrowser
from dataclasses import dataclass
from enum import Enum, auto
from typing import Optional
from urllib.parse import urlparse

from src.config.settings import Settings, save_settings
from src.onboarding.state import normalize_client_id
from src.spotify.auth import build_auth_manager
from src.spotify.client import (
    AuthExpiredError,
    RateLimitedError,
    TransientNetworkError,
    fetch_now_playing,
)

logger = logging.getLogger(__name__)

DASHBOARD_URL = "https://developer.spotify.com/dashboard"


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


def open_dashboard() -> None:
    webbrowser.open(DASHBOARD_URL)


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


def save_client_id(settings: Settings, raw_client_id: str) -> None:
    settings.client_id = normalize_client_id(raw_client_id)
    save_settings(settings)
    logger.info("Client ID saved to config")


def authenticate(settings: Settings):
    """Runs the interactive PKCE login (opens a browser) and returns a ready
    Spotify client. Raises the typed errors above so the wizard can explain
    exactly what went wrong instead of dumping a traceback."""
    from spotipy import Spotify

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
        if "redirect" in message.lower():
            raise RedirectUriMismatchError(
                f"Spotify rejected the redirect URI {settings.redirect_uri}. "
                "Add it to your app's settings in the dashboard."
            ) from exc
        raise AuthenticationFailedError(message) from exc

    return Spotify(auth_manager=auth_manager)


def verify_connection(client) -> VerifyOutcome:
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
