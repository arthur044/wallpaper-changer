from enum import Enum, auto
from typing import Optional

from src.spotify.client import NowPlaying


class PollDecision(Enum):
    RENDER = auto()
    NOOP = auto()
    IDLE = auto()


def decide(now_playing: Optional[NowPlaying], last_track_id: Optional[str]) -> PollDecision:
    """RENDER covers both a new album (expensive: download + compose base) and a new
    track within the same album (cheap: reuse the cached base, just redraw the text
    overlay) — which of those two it is gets decided downstream by whether the
    per-album base file already exists on disk, not by this state machine."""
    if now_playing is None or not now_playing.is_playing or not now_playing.album_id:
        return PollDecision.IDLE
    if now_playing.track_id == last_track_id:
        return PollDecision.NOOP
    return PollDecision.RENDER


def next_backoff(current: float, base: float = 5.0, cap: float = 300.0) -> float:
    if current <= 0:
        return base
    return min(current * 2, cap)
