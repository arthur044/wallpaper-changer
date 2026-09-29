"""What the lyrics widget shows, and where, as pure functions (no Qt), so the
layout can be tested without a window."""

from bisect import bisect_right
from dataclasses import dataclass
from enum import Enum
from typing import List, Optional, Sequence, Tuple

from src.lyrics.text import TimedLine


class Phase(Enum):
    HIDDEN = "hidden"  # nothing playing: the window hides
    LOADING = "loading"
    SYNCED = "synced"
    TEXT = "text"  # words without times
    NO_LYRICS = "no_lyrics"
    INSTRUMENTAL = "instrumental"
    UNAVAILABLE = "unavailable"  # LRCLIB unreachable; retried later
    NO_SOURCE = "no_source"  # SMTC off: no track or position to follow


MESSAGES = {
    Phase.LOADING: "Looking for lyrics…",
    Phase.NO_LYRICS: "No lyrics for this track",
    Phase.INSTRUMENTAL: "Instrumental",
    Phase.UNAVAILABLE: "Lyrics unavailable right now",
    Phase.NO_SOURCE: "Turn on use_smtc to follow the song",
}


@dataclass(frozen=True)
class View:
    phase: Phase
    lines: Tuple[str, ...] = ()
    # SYNCED: the line being sung (None before the first one starts).
    current: Optional[int] = None
    # TEXT: how far into the track, 0..1, to scroll unsynced words along.
    progress: float = 0.0

    @property
    def message(self) -> Optional[str]:
        return MESSAGES.get(self.phase)


def current_line(lines: Sequence[TimedLine], position_ms: int) -> Optional[int]:
    """The last line that has started by [position_ms]; None before the first."""
    index = bisect_right([line.time_ms for line in lines], position_ms) - 1
    return index if index >= 0 else None


def progress(position_ms: Optional[int], duration_ms: Optional[int]) -> float:
    if not position_ms or not duration_ms or duration_ms <= 0:
        return 0.0
    return min(1.0, max(0.0, position_ms / duration_ms))


# --- layout --------------------------------------------------------------------

MIN_FONT_PX = 13
MAX_FONT_PX = 34
WIDTH_PER_FONT_PX = 20  # the font grows 1 px for every 20 px of width
PADDING_PX = 20
# The narrowest the window gets: this many average characters of the
# smallest font on one line, plus the padding.
MIN_WIDTH_CHARS = 24
LINE_SPACING = 0.45  # extra space between lines, in font heights
BREAK_HEIGHT = 0.5  # an empty line (stanza break), in font heights
ANCHOR = 0.4  # the current line's middle sits at 40% of the height


def font_px_for_width(width: int) -> int:
    """The text grows with the window, within readable bounds."""
    return max(MIN_FONT_PX, min(MAX_FONT_PX, round(width / WIDTH_PER_FONT_PX)))


def min_width(average_char_px: float, chars: int = MIN_WIDTH_CHARS, padding: int = PADDING_PX) -> int:
    """[average_char_px] comes from the font metrics of the smallest font."""
    return int(round(average_char_px * chars)) + 2 * padding


def column_tops(heights: Sequence[float], spacing: float) -> List[float]:
    """Top of each line when the lines are stacked with [spacing] between."""
    tops = []
    y = 0.0
    for height in heights:
        tops.append(y)
        y += height + spacing
    return tops


def scroll_to_line(tops: Sequence[float], heights: Sequence[float], index: Optional[int], view_height: float) -> float:
    """How far to scroll the column so line [index]'s middle sits at the anchor.
    Before the first line starts, the first line waits at the anchor."""
    if not tops:
        return 0.0
    i = 0 if index is None else max(0, min(index, len(tops) - 1))
    return tops[i] + heights[i] / 2 - view_height * ANCHOR


def scroll_for_progress(total_height: float, view_height: float, fraction: float) -> float:
    """Unsynced words scroll along with the track: top at 0, bottom at the end."""
    room = max(0.0, total_height - view_height)
    return room * min(1.0, max(0.0, fraction))
