import hashlib
from typing import Tuple

from src.config.settings import Settings

# Bump when the base drawing changes, so bases drawn by an older version are
# never reused.
#
# 1: key covers the pixel-affecting settings and the resolution, not just the
#    album id (bases used to survive a resolution or style change).
# 2: shadow blurred on a downscaled layer; art_glow draws a colored halo.
BASE_RENDER_VERSION = 2


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
