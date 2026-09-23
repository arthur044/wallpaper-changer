import hashlib
import logging
import os
import re
from pathlib import Path
from typing import Tuple

from src.config.settings import Settings

# Bump when the base drawing changes, so bases drawn by an older version are
# never reused.
#
# 1: key covers the pixel-affecting settings and the resolution, not just the
#    album id (bases used to survive a resolution or style change).
# 2: shadow blurred on a downscaled layer; art_glow draws a colored halo.
BASE_RENDER_VERSION = 2

logger = logging.getLogger(__name__)

# Same budget as the Android app: a few hundred albums at 1080p.
DEFAULT_MAX_BYTES = 150 * 1024 * 1024
# The file names base_cache_key produces. Anything else in the folder is a
# base from before the key covered resolution and style ("<album_id>.png"),
# which is never read again.
_CURRENT_NAME = re.compile(r"^[A-Za-z0-9]{1,64}_\d+x\d+_[0-9a-f]{12}\.png$")


def base_cache_key(album_id: str, canvas_size: Tuple[int, int], settings: Settings) -> str:
    """Cache key for an album's base image (fill + shadow + art, no text).

    Album id + resolution + the settings that change the base's pixels determine
    it fully. Settings drawn on top per track (show_track_info, text_card) or that
    never touch pixels (polling, client id...) are left out, so changing them
    doesn't throw the cache away. Mirrors the Android app's baseCacheKey."""
    width, height = canvas_size
    inputs = "|".join(
        str(v)
        for v in (
            BASE_RENDER_VERSION,
            width,
            height,
            settings.art_size_pct,
            settings.corner_radius,
            settings.shadow_blur_radius,
            settings.background_style,
            settings.art_glow,
            settings.art_frame,
        )
    )
    return f"{_file_safe_id(album_id)}_{width}x{height}_{_sha256_hex(inputs)[:12]}"


def _file_safe_id(album_id: str) -> str:
    # Spotify ids are base62; anything else is hashed so it can't form a path.
    if album_id and len(album_id) <= 64 and album_id.isascii() and album_id.isalnum():
        return album_id
    return "h" + _sha256_hex(album_id)[:22]


def _sha256_hex(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def prune_album_bases(directory: Path, keep: Path, max_bytes: int = DEFAULT_MAX_BYTES) -> None:
    """Deletes bases in the old naming, then the least recently used ones until
    the folder fits in [max_bytes]. [keep], the base in use, always stays.
    Recency is the file's mtime, refreshed whenever a base is reused. Best
    effort: a file that can't be deleted now is left for next time."""
    entries = []
    for path in directory.glob("*.png"):
        try:
            if path != keep and not _CURRENT_NAME.match(path.name):
                path.unlink()
                logger.info("Removed a base in the old naming: %s", path.name)
                continue
            stat = path.stat()
            entries.append((stat.st_mtime, stat.st_size, path))
        except OSError as exc:
            logger.warning("Could not check or remove cached base %s: %s", path.name, exc)

    total = sum(size for _, size, _ in entries)
    for _, size, path in sorted(entries, key=lambda entry: entry[0]):
        if total <= max_bytes:
            break
        if path == keep:
            continue
        try:
            path.unlink()
            total -= size
        except OSError as exc:
            logger.warning("Could not remove cached base %s: %s", path.name, exc)


def mark_used(path: Path) -> None:
    """Refreshes a base's recency for prune_album_bases."""
    try:
        os.utime(path, None)
    except OSError as exc:
        logger.warning("Could not refresh cached base %s: %s", path.name, exc)
