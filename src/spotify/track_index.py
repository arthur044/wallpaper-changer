import json
import logging
import os
from collections import OrderedDict
from pathlib import Path
from typing import Optional, Tuple

logger = logging.getLogger(__name__)

Resolved = Tuple[str, Optional[str]]  # (album_id, art_url)

_VERSION = 1
# A few hundred albums' worth of songs; a few hundred KB on disk.
DEFAULT_MAX_ENTRIES = 5000


class TrackAlbumStore:
    """The "artist::title" -> (album_id, art_url) map, kept across restarts.

    Least recently used songs go first once [max_entries] is reached. Changes
    are written by flush(), once per change set (a new album), atomically. An
    unreadable file just means an empty map: it only costs a lookup per album.
    With no path it is memory-only."""

    def __init__(self, path: Optional[Path], max_entries: int = DEFAULT_MAX_ENTRIES) -> None:
        self._path = path
        self._max = max_entries
        self._entries: "OrderedDict[str, Resolved]" = OrderedDict()
        self._dirty = False
        if path is not None:
            self._load(path)

    def get(self, key: str) -> Optional[Resolved]:
        # Reordering alone does not mark the map dirty: the recency order on
        # disk is refreshed with the next real change, which is good enough.
        value = self._entries.get(key)
        if value is not None:
            self._entries.move_to_end(key)
        return value

    def __setitem__(self, key: str, value: Resolved) -> None:
        if self._entries.get(key) != value:
            self._dirty = True
        self._entries[key] = value
        self._entries.move_to_end(key)
        while len(self._entries) > self._max:
            self._entries.popitem(last=False)

    def pop(self, key: str) -> None:
        if self._entries.pop(key, None) is not None:
            self._dirty = True

    def flush(self) -> None:
        if not self._dirty or self._path is None:
            return
        data = {"version": _VERSION, "tracks": [[k, a, u] for k, (a, u) in self._entries.items()]}
        temp = self._path.with_name(self._path.name + ".tmp")
        try:
            temp.write_text(json.dumps(data), encoding="utf-8")
            os.replace(temp, self._path)
            self._dirty = False
        except OSError as exc:
            logger.warning("Could not save the track index: %s", exc)

    def _load(self, path: Path) -> None:
        if not path.exists():
            return
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(data, dict):
                raise ValueError(f"expected an object, got {type(data).__name__}")
            if data.get("version") != _VERSION:
                logger.warning("Ignoring a track index of version %r", data.get("version"))
                return
            for key, album_id, art_url in data["tracks"][-self._max:]:
                self._entries[str(key)] = (str(album_id), art_url)
        except (OSError, ValueError, KeyError, TypeError) as exc:
            logger.warning("Ignoring an unreadable track index (%s)", exc)
            self._entries.clear()
