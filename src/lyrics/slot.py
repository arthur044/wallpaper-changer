import threading
from dataclasses import dataclass
from typing import Callable, Optional

from src.lyrics.lrclib import Lyrics, LyricsQuery, NotFound

LyricsSource = Callable[[LyricsQuery], Lyrics]


@dataclass(frozen=True)
class _Held:
    track_id: str
    lyrics: Lyrics


class LyricsSlot:
    """The lyrics of one track, in memory only (ported from the Android
    LyricsSlot): the answer for the track showing, "not found" and
    "instrumental" included, so the widget never asks twice. Another track
    drops it. A failed lookup is not kept, so the next ask tries again.

    Lookups run one at a time: an ask while the lookup for the same track is
    still out waits for it instead of asking LRCLIB again."""

    def __init__(self, source: LyricsSource):
        self._source = source
        self._lookups = threading.Lock()
        # Both under _state: the track showing and the answer kept for it.
        self._state = threading.Lock()
        self._current: Optional[str] = None
        self._held: Optional[_Held] = None

    def peek(self, track_id: str) -> Optional[Lyrics]:
        """The kept answer for [track_id], without asking anyone."""
        with self._state:
            if self._held is not None and self._held.track_id == track_id:
                return self._held.lyrics
            return None

    def on_track(self, track_id: Optional[str]) -> None:
        """Another track is showing: the kept answer, if for another one, is gone."""
        with self._state:
            self._current = track_id
            if self._held is not None and self._held.track_id != track_id:
                self._held = None

    def lyrics_for(self, track_id: Optional[str], query: Optional[LyricsQuery]) -> Lyrics:
        """The kept answer for this track, or a lookup; this track becomes the
        one showing. NotFound when there is nothing to look up by. Raises
        LyricsUnavailableError when the lookup failed (nothing is kept)."""
        if track_id is None:
            return NotFound()
        self.on_track(track_id)
        with self._lookups:
            kept = self.peek(track_id)
            if kept is not None:
                return kept
            with self._state:
                superseded = self._current != track_id
            if superseded:
                # Another track started while this ask waited its turn: its
                # answer would be thrown away, so LRCLIB isn't asked at all.
                return NotFound()
            return self._look_up(track_id, query)

    def _look_up(self, track_id: str, query: Optional[LyricsQuery]) -> Lyrics:
        lyrics = NotFound() if query is None else self._source(query)
        # The track may have changed while LRCLIB answered: an answer for a
        # track no longer showing is returned to its caller but not kept.
        with self._state:
            if self._current == track_id:
                self._held = _Held(track_id, lyrics)
        return lyrics
