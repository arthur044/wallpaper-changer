import io
import logging
from typing import Tuple

from colorthief import ColorThief

logger = logging.getLogger(__name__)

_FALLBACK_COLOR = (30, 30, 30)


def extract_dominant_color(image_bytes: bytes) -> Tuple[int, int, int]:
    try:
        thief = ColorThief(io.BytesIO(image_bytes))
        return thief.get_color(quality=4)
    except Exception as exc:  # noqa: BLE001 - never block a render over a color extraction glitch
        logger.error("Dominant color extraction failed (%s), using fallback color", exc)
        return _FALLBACK_COLOR


def to_hex(rgb: Tuple[int, int, int]) -> str:
    return "#{:02x}{:02x}{:02x}".format(*rgb)
