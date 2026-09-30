import json
import logging
import os
from pathlib import Path
from typing import Callable, List, Optional, Sequence, Tuple

from src.graphics.base_cache import _file_safe_id
from src.graphics.color_extractor import RGB, extract_accent_palette, extract_dominant_color

logger = logging.getLogger(__name__)

# The original art is ~100-200 KB a cover; this holds a few hundred albums.
DEFAULT_MAX_BYTES = 60 * 1024 * 1024
_COLORS_VERSION = 1
# What extract_dominant_color answers when it could not read the art: not a
# result to remember.
_FALLBACK_DOMINANT: RGB = (30, 30, 30)


def _rgb(value) -> Optional[RGB]:
    if (
        isinstance(value, list)
        and len(value) == 3
        and all(isinstance(c, int) and not isinstance(c, bool) and 0 <= c <= 255 for c in value)
    ):
        return (value[0], value[1], value[2])
    return None


class AlbumColors:
    """An album's dominant color and accent palette, worked out at most once:
    from what was stored, else from the art (ColorThief, ~200 ms for the
    accents). [remember] is called with the stored form whenever something new
    was worked out, so the next style change reads it instead."""

    def __init__(
        self,
        art_bytes: bytes,
        stored: Optional[dict] = None,
        remember: Callable[[dict], None] = lambda _stored: None,
    ) -> None:
        self._art_bytes = art_bytes
        self._remember = remember
        stored = stored or {}
        self._dominant: Optional[RGB] = _rgb(stored.get("dominant"))
        accents = stored.get("accents")
        parsed = [_rgb(c) for c in accents] if isinstance(accents, list) else None
        self._accents: Optional[List[RGB]] = parsed if parsed and None not in parsed else None

    def dominant(self) -> RGB:
        if self._dominant is None:
            found = extract_dominant_color(self._art_bytes)
            if found == _FALLBACK_DOMINANT:
                return found  # unreadable art: answer, but keep nothing
            self._dominant = found
            self._save()
        return self._dominant

    def accents(self) -> List[RGB]:
        if self._accents is None:
            found = extract_accent_palette(self._art_bytes)
            if not found:
                return []
            self._accents = found
            self._save()
        return list(self._accents)

    def _save(self) -> None:
        stored: dict = {"version": _COLORS_VERSION}
        if self._dominant is not None:
            stored["dominant"] = list(self._dominant)
        if self._accents is not None:
            stored["accents"] = [list(c) for c in self._accents]
        self._remember(stored)


class CachedArt:
    def __init__(self, art_bytes: bytes, colors: AlbumColors, from_disk: bool) -> None:
        self.bytes = art_bytes
        self.colors = colors
        self.from_disk = from_disk


class AlbumArtCache:
    """The original cover of each album and its colors, on disk, so a style
    change redraws an album without downloading the art or quantizing it again.

    One art file and one colors file per album, named by the album id (the
    covers of an album never change). Least recently used files go first
    when the folder outgrows [max_bytes]. Best effort throughout: a file that
    is missing, unreadable or damaged just means downloading again."""

    def __init__(self, directory: Path, max_bytes: int = DEFAULT_MAX_BYTES) -> None:
        self._directory = directory
        self._max_bytes = max_bytes

    def _art_path(self, album_id: str) -> Path:
        return self._directory / f"{_file_safe_id(album_id)}.art"

    def _colors_path(self, album_id: str) -> Path:
        return self._directory / f"{_file_safe_id(album_id)}.colors.json"

    def load(self, album_id: str, download: Callable[[], bytes]) -> CachedArt:
        art_path = self._art_path(album_id)
        try:
            art_bytes = art_path.read_bytes()
        except OSError:
            art_bytes = b""
        if art_bytes:
            self._touch(art_path, self._colors_path(album_id))
            stored = self._read_colors(self._colors_path(album_id))
            return CachedArt(art_bytes, self._colors(album_id, art_bytes, stored), from_disk=True)

        art_bytes = download()
        self._write(art_path, art_bytes)
        self._prune(keep=art_path)
        return CachedArt(art_bytes, self._colors(album_id, art_bytes, None), from_disk=False)

    def discard(self, album_id: str) -> None:
        """The stored art turned out not to be an image: forget it and its colors."""
        for path in (self._art_path(album_id), self._colors_path(album_id)):
            try:
                path.unlink()
            except OSError:
                pass

    def _colors(self, album_id: str, art_bytes: bytes, stored: Optional[dict]) -> AlbumColors:
        return AlbumColors(art_bytes, stored, remember=lambda data: self._write_colors(album_id, data))

    @staticmethod
    def _read_colors(path: Path) -> Optional[dict]:
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
        return data if isinstance(data, dict) and data.get("version") == _COLORS_VERSION else None

    def _write_colors(self, album_id: str, data: dict) -> None:
        self._write(self._colors_path(album_id), json.dumps(data).encode("utf-8"))

    def _write(self, path: Path, data: bytes) -> None:
        # Written aside and moved in: a crash never leaves half a file that
        # would be read as the whole art.
        temporary = path.with_name(path.name + ".tmp")
        try:
            self._directory.mkdir(parents=True, exist_ok=True)
            temporary.write_bytes(data)
            os.replace(temporary, path)
        except OSError as exc:
            logger.warning("Could not store %s: %s", path.name, exc)
            try:
                temporary.unlink()
            except OSError:
                pass

    @staticmethod
    def _touch(*paths: Path) -> None:
        for path in paths:
            try:
                os.utime(path, None)
            except OSError:
                pass

    def _prune(self, keep: Path) -> None:
        entries: List[Tuple[float, int, Path]] = []
        try:
            files: Sequence[Path] = [p for p in self._directory.iterdir() if p.is_file()]
        except OSError:
            return
        for path in files:
            try:
                stat = path.stat()
            except OSError:
                continue
            entries.append((stat.st_mtime, stat.st_size, path))
        total = sum(size for _, size, _ in entries)
        keep_colors = keep.with_name(keep.name[: -len(".art")] + ".colors.json")
        for _, size, path in sorted(entries, key=lambda entry: entry[0]):
            if total <= self._max_bytes:
                break
            if path in (keep, keep_colors):
                continue
            try:
                path.unlink()
                total -= size
            except OSError as exc:
                logger.warning("Could not remove cached art %s: %s", path.name, exc)
