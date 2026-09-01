import logging
from dataclasses import dataclass
from typing import List, Optional, Tuple

import requests
from spotipy import Spotify, SpotifyException
from spotipy.oauth2 import SpotifyOauthError

logger = logging.getLogger(__name__)


class AuthExpiredError(Exception):
    """Raised when the refresh token itself has been revoked and a fresh login is required."""


class RateLimitedError(Exception):
    def __init__(self, retry_after: float):
        super().__init__(f"Rate limited, retry after {retry_after}s")
        self.retry_after = retry_after


class TransientNetworkError(Exception):
    pass


@dataclass
class NowPlaying:
    is_playing: bool
    track_id: Optional[str]
    album_id: Optional[str]
    art_url: Optional[str]
    track_name: Optional[str]
    artist_name: Optional[str]


def _raise_for_spotify_exception(exc: SpotifyException) -> None:
    if exc.http_status == 429:
        retry_after = float(exc.headers.get("Retry-After", 5)) if exc.headers else 5.0
        raise RateLimitedError(retry_after) from exc
    if exc.http_status == 401:
        raise AuthExpiredError(str(exc)) from exc
    raise TransientNetworkError(str(exc)) from exc


def fetch_now_playing(client: Spotify) -> Optional[NowPlaying]:
    """Returns None when nothing is playing / no active device. Raises the typed errors above otherwise."""
    try:
        payload = client.current_user_playing_track()
    except SpotifyException as exc:
        _raise_for_spotify_exception(exc)
    except SpotifyOauthError as exc:
        raise AuthExpiredError(str(exc)) from exc
    except (requests.ConnectionError, requests.Timeout) as exc:
        raise TransientNetworkError(str(exc)) from exc

    if not payload or not payload.get("item"):
        return None

    item = payload["item"]
    album = item.get("album") or {}
    images = album.get("images") or []
    art_url = images[0]["url"] if images else None
    artists = item.get("artists") or []
    artist_name = ", ".join(a["name"] for a in artists if a.get("name")) or None

    return NowPlaying(
        is_playing=bool(payload.get("is_playing")),
        track_id=item.get("id"),
        album_id=album.get("id"),
        art_url=art_url,
        track_name=item.get("name"),
        artist_name=artist_name,
    )


def fetch_album_tracks(client: Spotify, album_id: str) -> List[Tuple[str, str]]:
    """(track_name, artist_name) pairs for every track in the album. Used to
    recognize a later SMTC-reported track as belonging to an already-resolved
    album without any further API call - see Poller._track_to_album.
    Only the first page (up to 50 tracks) is fetched; box sets beyond that
    just fall back to a fresh resolution for their later tracks."""
    try:
        results = client.album_tracks(album_id)
    except SpotifyException as exc:
        _raise_for_spotify_exception(exc)
    except SpotifyOauthError as exc:
        raise AuthExpiredError(str(exc)) from exc
    except (requests.ConnectionError, requests.Timeout) as exc:
        raise TransientNetworkError(str(exc)) from exc

    items = (results or {}).get("items") or []
    pairs = []
    for item in items:
        name = item.get("name")
        if not name:
            continue
        artists = item.get("artists") or []
        artist_name = ", ".join(a["name"] for a in artists if a.get("name"))
        pairs.append((name, artist_name))
    return pairs
