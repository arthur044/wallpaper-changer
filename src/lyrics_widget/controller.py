import time
from dataclasses import dataclass
from typing import Callable, Optional, Tuple

from src.lyrics.lrclib import Instrumental, Lyrics, LyricsQuery, NotFound, SyncedLyrics, TextLyrics
from src.lyrics.song_clock import SongClock
from src.lyrics_widget.view_model import Phase, View, current_line, progress
from src.os_integration.smtc import SmtcNowPlaying, TimelineSample

# LRCLIB unreachable: ask again after this long, while the same track plays.
RETRY_SECONDS = 30.0
# A new track waits this long for its own timeline, whose duration makes the
# exact /get lookup possible and the search ranking sharper.
DURATION_WAIT_SECONDS = 2.0


@dataclass(frozen=True)
class FetchRequest:
    track_key: str
    query: LyricsQuery


class LyricsWidgetController:
    """Decides what the widget shows and when to ask for lyrics, from what
    SMTC reports. No Qt and no network: the Qt side calls tick() on a timer,
    runs the FetchRequest it gets off the Qt thread, and reports back through
    on_lyrics() or on_failure(). Only called while the widget is on, so
    nothing is looked up while it is off."""

    def __init__(self, has_source: bool = True, now: Callable[[], float] = time.monotonic):
        self._has_source = has_source
        self._now = now
        self._clock = SongClock(now)
        self._track: Optional[str] = None
        self._track_seen_at = 0.0
        self._lyrics: Optional[Lyrics] = None
        self._pending = False
        self._failed_at: Optional[float] = None

    def tick(
        self, snapshot: Optional[SmtcNowPlaying], timeline: Optional[TimelineSample]
    ) -> Tuple[View, Optional[FetchRequest]]:
        if not self._has_source:
            return View(Phase.NO_SOURCE), None
        if snapshot is None or not (snapshot.title or "").strip():
            self._clock.update(None)
            return View(Phase.HIDDEN), None

        key = snapshot.track_key
        self._clock.update(timeline if timeline is not None and timeline.track_key == key else None)
        if key != self._track:
            self._track = key
            self._track_seen_at = self._now()
            self._lyrics = None
            self._pending = False
            self._failed_at = None
        return self._view(), self._fetch_request(snapshot)

    def on_lyrics(self, track_key: str, lyrics: Lyrics) -> None:
        if track_key != self._track:
            return  # an answer for a track that is no longer playing
        self._lyrics = lyrics
        self._pending = False
        self._failed_at = None

    def on_failure(self, track_key: str) -> None:
        if track_key != self._track:
            return
        self._pending = False
        self._failed_at = self._now()

    def _fetch_request(self, snapshot: SmtcNowPlaying) -> Optional[FetchRequest]:
        if self._lyrics is not None or self._pending:
            return None
        now = self._now()
        if self._failed_at is not None and now - self._failed_at < RETRY_SECONDS:
            return None
        duration = self._clock.duration_ms
        if duration is None and now - self._track_seen_at < DURATION_WAIT_SECONDS:
            return None
        self._pending = True
        query = LyricsQuery(
            artist=snapshot.artist or "",
            title=snapshot.title or "",
            album=snapshot.album_title,
            duration_ms=duration,
        )
        return FetchRequest(snapshot.track_key, query)

    def _view(self) -> View:
        lyrics = self._lyrics
        if lyrics is None:
            return View(Phase.UNAVAILABLE if self._failed_at is not None else Phase.LOADING)
        if isinstance(lyrics, SyncedLyrics):
            position = self._clock.position_ms()
            index = current_line(lyrics.lines, position) if position is not None else None
            return View(Phase.SYNCED, tuple(line.text for line in lyrics.lines), current=index)
        if isinstance(lyrics, TextLyrics):
            return View(Phase.TEXT, lyrics.lines, progress=progress(self._clock.position_ms(), self._clock.duration_ms))
        if isinstance(lyrics, Instrumental):
            return View(Phase.INSTRUMENTAL)
        return View(Phase.NO_LYRICS)
