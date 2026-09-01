import threading
from dataclasses import dataclass
from enum import Enum, auto
from typing import Optional


class AppStatus(Enum):
    IDLE = auto()
    RUNNING = auto()
    PAUSED = auto()
    ERROR = auto()


@dataclass
class SnapshotState:
    status: AppStatus
    current_track_id: Optional[str]
    current_album_id: Optional[str]
    last_error: Optional[str]


class AppState:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._status = AppStatus.RUNNING
        self._current_track_id: Optional[str] = None
        self._current_album_id: Optional[str] = None
        self._last_error: Optional[str] = None

        self.pause_event = threading.Event()
        self.force_sync_event = threading.Event()
        self.stop_event = threading.Event()

    def snapshot(self) -> SnapshotState:
        with self._lock:
            return SnapshotState(
                status=self._status,
                current_track_id=self._current_track_id,
                current_album_id=self._current_album_id,
                last_error=self._last_error,
            )

    def set_playing(self, track_id: Optional[str], album_id: Optional[str]) -> None:
        with self._lock:
            self._current_track_id = track_id
            self._current_album_id = album_id
            if self._status != AppStatus.PAUSED:
                self._status = AppStatus.RUNNING

    def set_idle(self) -> None:
        with self._lock:
            self._current_track_id = None
            self._current_album_id = None
            if self._status != AppStatus.PAUSED:
                self._status = AppStatus.IDLE

    def set_error(self, message: str) -> None:
        with self._lock:
            self._status = AppStatus.ERROR
            self._last_error = message

    def clear_error(self) -> None:
        with self._lock:
            if self._status == AppStatus.ERROR:
                self._status = AppStatus.RUNNING
            self._last_error = None

    def toggle_pause(self) -> bool:
        with self._lock:
            if self.pause_event.is_set():
                self.pause_event.clear()
                self._status = AppStatus.RUNNING
                paused = False
            else:
                self.pause_event.set()
                self._status = AppStatus.PAUSED
                paused = True
            return paused

    def is_paused(self) -> bool:
        return self.pause_event.is_set()
