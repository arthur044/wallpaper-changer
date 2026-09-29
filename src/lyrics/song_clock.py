import time
from dataclasses import replace
from typing import Callable, Optional

from src.os_integration.smtc import TimelineSample


def position_at(sample: TimelineSample, now: float) -> int:
    """[sample]'s position at monotonic time [now]: frozen while paused, moving
    at the playback rate while playing, never past the end of the track."""
    position = sample.position_ms
    if sample.is_playing:
        position += int(max(0.0, now - sample.observed_at) * 1000 * sample.rate)
    if sample.duration_ms is not None:
        position = min(position, sample.duration_ms)
    return max(0, position)


class SongClock:
    """Where the playing track is right now, from the SMTC timeline samples.
    Pure: time comes from [now], so tests drive it with a fake clock.

    Each sample replaces the last one, so a seek shows up with the first
    sample after it. The one sample not taken at its word is a timeline left
    over from the previous track (see update)."""

    def __init__(self, now: Callable[[], float] = time.monotonic):
        self._now = now
        self._sample: Optional[TimelineSample] = None
        # A timeline stamp known to belong to an earlier track.
        self._stale_stamp: Optional[float] = None

    @property
    def track_key(self) -> Optional[str]:
        return self._sample.track_key if self._sample is not None else None

    @property
    def is_playing(self) -> bool:
        return self._sample is not None and self._sample.is_playing

    def position_ms(self) -> Optional[int]:
        """None while nothing is known to be playing or paused."""
        if self._sample is None:
            return None
        return position_at(self._sample, self._now())

    def update(self, sample: Optional[TimelineSample]) -> None:
        if sample is None:
            self._sample = None
            self._stale_stamp = None
            return
        current = self._sample
        if current is not None and sample.track_key != current.track_key and sample.stamp == current.stamp:
            # The track changed but the timeline did not: its position is the
            # old track's. Not measured whether Spotify ever does this; if it
            # does, the new track starts from 0 until a fresh update arrives.
            self._stale_stamp = sample.stamp
            self._sample = replace(sample, position_ms=0)
            return
        if self._stale_stamp is not None and sample.stamp == self._stale_stamp and current is not None:
            # Still the leftover timeline: keep counting from our own 0, but
            # follow a pause or a rate change.
            self._sample = replace(
                sample,
                position_ms=position_at(current, sample.observed_at),
            )
            return
        self._stale_stamp = None
        self._sample = sample
