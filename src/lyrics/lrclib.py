"""Lyrics from LRCLIB (https://lrclib.net, no account or key).

Ported from the Android app's core/lyrics/LrclibClient.kt: an exact /get when
album and duration are known, then a ranked /search. It never goes through the
Spotify ApiThrottle: 429 and the backoff belong to the Spotify Web API.

The words live only in memory. Nothing here writes them to disk or to the log.
"""

import logging
from dataclasses import dataclass, field
from typing import Any, List, Optional, Tuple, Union

import requests

from src.lyrics.text import TimedLine, clean_artist, clean_title, loose_match, parse_lrc, split_lines, tidy

logger = logging.getLogger(__name__)

BASE_URL = "https://lrclib.net/api/"
USER_AGENT = "WallpaperChanger (Windows)"
_TIMEOUT = (10.0, 10.0)  # connect, read
_HTTP_BAD_REQUEST = 400
_HTTP_NOT_FOUND = 404

# A candidate this far from the playing track's length is another recording.
_MAX_DRIFT_SECS = 30.0


@dataclass(frozen=True)
class SyncedLyrics:
    """Lines with the time each one starts, in time order. At least one line has words."""

    lines: Tuple[TimedLine, ...]


@dataclass(frozen=True)
class TextLyrics:
    """Plain (unsynced) lines; a blank string separates stanzas. Never empty."""

    lines: Tuple[str, ...]


@dataclass(frozen=True)
class Instrumental:
    """The database knows the track has no words."""


@dataclass(frozen=True)
class NotFound:
    """Nobody transcribed it, or no candidate fits what is playing."""


Lyrics = Union[SyncedLyrics, TextLyrics, Instrumental, NotFound]


class LyricsUnavailableError(Exception):
    """LRCLIB could not be asked, or answered something other than lyrics or "not found"."""


@dataclass(frozen=True)
class LyricsQuery:
    """The track to look up. [album] and [duration_ms] may be unknown. [artists]
    are Spotify's names one by one, when known: LRCLIB files a track under its
    first artist, and [artist] joins them all ("A, B"), so it is never split."""

    artist: str
    title: str
    album: Optional[str]
    duration_ms: Optional[int]
    artists: Tuple[str, ...] = field(default=())

    @property
    def primary_artist(self) -> str:
        return next((name for name in self.artists if name.strip()), self.artist)


@dataclass(frozen=True)
class LrclibRecord:
    """One LRCLIB track, as /get returns it and /search lists it."""

    id: Optional[int] = None
    track_name: str = ""
    artist_name: str = ""
    duration: Optional[float] = None
    instrumental: bool = False
    plain_lyrics: Optional[str] = None
    synced_lyrics: Optional[str] = None

    @property
    def synced(self) -> Optional[str]:
        return self.synced_lyrics if self.synced_lyrics and self.synced_lyrics.strip() else None

    @property
    def plain(self) -> Optional[str]:
        return self.plain_lyrics if self.plain_lyrics and self.plain_lyrics.strip() else None

    def lyrics(self) -> Optional[Lyrics]:
        """None when the record holds no words and isn't marked instrumental.
        The widget follows the song, so the synced upload comes first; the
        plain one, or the synced one's words without times, is the fallback."""
        if self.instrumental:
            return Instrumental()
        if self.synced is not None:
            timed = parse_lrc(self.synced)
            if any(line.text for line in timed):
                return SyncedLyrics(tuple(timed))
        if self.plain is not None:
            lines = split_lines(self.plain)
        elif self.synced is not None:
            lines = [line.text for line in parse_lrc(self.synced)]
        else:
            return None
        tidied = tidy(lines)
        return TextLyrics(tuple(tidied)) if tidied else None


def _field(raw: dict, name: str, kinds: tuple, default: Any) -> Any:
    """kotlinx's coerceInputValues: a missing or null field takes its default;
    a field of the wrong type means the answer isn't LRCLIB's."""
    value = raw.get(name)
    if value is None:
        return default
    # bool is an int in Python; a flag is never a number here and vice versa.
    if not isinstance(value, kinds) or (isinstance(value, bool) and bool not in kinds):
        raise LyricsUnavailableError(f"Unexpected LRCLIB field {name!r}")
    return value


def parse_record(raw: Any) -> LrclibRecord:
    if not isinstance(raw, dict):
        raise LyricsUnavailableError("Unexpected LRCLIB response: not a track")
    duration = _field(raw, "duration", (int, float), None)
    return LrclibRecord(
        id=_field(raw, "id", (int,), None),
        track_name=_field(raw, "trackName", (str,), ""),
        artist_name=_field(raw, "artistName", (str,), ""),
        duration=float(duration) if duration is not None else None,
        instrumental=_field(raw, "instrumental", (bool,), False),
        plain_lyrics=_field(raw, "plainLyrics", (str,), None),
        synced_lyrics=_field(raw, "syncedLyrics", (str,), None),
    )


def score(record: LrclibRecord, query: LyricsQuery) -> Optional[int]:
    """How well a search result fits what is playing; None rules it out."""
    if not loose_match(record.track_name, clean_title(query.title)):
        return None
    total = 0
    # The joined names contain each artist, so a duet filed under its second
    # artist matches too (loose matching accepts containment).
    if loose_match(record.artist_name, clean_artist(query.artist)):
        total += 1000
    duration_ms = query.duration_ms or 0
    duration = record.duration if record.duration and record.duration > 0 else None
    if duration_ms > 0 and duration is not None:
        drift = abs(duration - duration_ms / 1000.0)
        if drift > _MAX_DRIFT_SECS:
            return None
        total += int((_MAX_DRIFT_SECS - drift) * 10)
    # A synced upload is usually the more careful one, so it wins a tie.
    if record.synced is not None:
        total += 200
    elif record.plain is not None:
        total += 50
    return total


def pick(records: List[LrclibRecord], query: LyricsQuery) -> Optional[LrclibRecord]:
    """The best-scoring candidate; the first one wins a tie."""
    best: Optional[Tuple[int, LrclibRecord]] = None
    for record in records:
        points = score(record, query)
        if points is None:
            continue
        if best is None or points > best[0]:
            best = (points, record)
    return best[1] if best is not None else None


class LrclibClient:
    def __init__(self, user_agent: str = USER_AGENT, base_url: str = BASE_URL, http: Optional[requests.Session] = None):
        self._user_agent = user_agent
        self._base_url = base_url if base_url.endswith("/") else base_url + "/"
        self._http = http if http is not None else requests.Session()

    def lyrics(self, query: LyricsQuery) -> Lyrics:
        """Raises LyricsUnavailableError when the answer could not be had (no
        network, server trouble); "nobody has it" is NotFound, not an error."""
        title = clean_title(query.title)
        artist = clean_artist(query.primary_artist)
        if not title.strip() or not artist.strip():
            return NotFound()
        album = (query.album or "").strip()
        seconds = (query.duration_ms or 0) // 1000
        exact_text: Optional[TextLyrics] = None
        # /get wants all four; without album or duration it can only say 400.
        if album and seconds > 0:
            exact = self._get("get", {"artist_name": artist, "track_name": title, "album_name": album, "duration": str(seconds)})
            if exact is not None:
                found = parse_record(exact).lyrics()
                if isinstance(found, TextLyrics):
                    # Words without times can't follow the song. Unlike the
                    # Android share (plain text either way), the widget looks
                    # for a synced upload of the same recording first.
                    exact_text = found
                elif found is not None:
                    return found
        if exact_text is not None:
            try:
                # Only synced candidates compete here: the plain upload of the
                # same recording, usually in the list too with no drift, would
                # otherwise outrank a synced one a few seconds off.
                searched = self._search(query, artist, title, synced_only=True)
            except LyricsUnavailableError:
                return exact_text  # the exact answer stands
            return searched if isinstance(searched, SyncedLyrics) else exact_text
        return self._search(query, artist, title)

    def _search(self, query: LyricsQuery, artist: str, title: str, synced_only: bool = False) -> Lyrics:
        listed = self._get("search", {"artist_name": artist, "track_name": title})
        if listed is None:
            listed = []
        if not isinstance(listed, list):
            raise LyricsUnavailableError("Unexpected LRCLIB response: not a list")
        records = [parse_record(raw) for raw in listed]
        if synced_only:
            records = [record for record in records if record.synced is not None]
        chosen = pick(records, query)
        found = chosen.lyrics() if chosen is not None else None
        return found if found is not None else NotFound()

    def _get(self, path: str, params: dict) -> Any:
        # A 404 is "nobody has transcribed this" and a 400 "not a question I
        # can answer"; neither is a fault worth showing.
        try:
            response = self._http.get(
                self._base_url + path,
                params=params,
                headers={"User-Agent": self._user_agent, "Accept": "application/json"},
                timeout=_TIMEOUT,
            )
        except requests.RequestException as exc:
            raise LyricsUnavailableError(f"LRCLIB unreachable: {exc}") from exc
        if response.status_code in (_HTTP_NOT_FOUND, _HTTP_BAD_REQUEST):
            return None
        if not 200 <= response.status_code < 300:
            raise LyricsUnavailableError(f"LRCLIB answered HTTP {response.status_code}")
        try:
            return response.json()
        except ValueError as exc:  # requests' JSONDecodeError is a ValueError
            raise LyricsUnavailableError("Unexpected LRCLIB response: not JSON") from exc
