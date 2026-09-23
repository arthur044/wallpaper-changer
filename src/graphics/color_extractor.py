import colorsys
import io
import logging
from typing import List, Sequence, Tuple

from colorthief import ColorThief

logger = logging.getLogger(__name__)

_FALLBACK_COLOR = (30, 30, 30)

RGB = Tuple[int, int, int]

# Accent palette: more colors than get_color's 5 so a small vivid detail gets
# its own entry, at a coarser sampling than the dominant color. Quantizing is
# the costly part (~200 ms per album on an old i5), so only effects that need
# accents ask for it.
_ACCENT_COLOR_COUNT = 10
_ACCENT_QUALITY = 10

# How far (RGB distance) the glow must sit from the fill to read as light.
_MIN_GLOW_DISTANCE = 80
_LIFT_STEP = 0.15


def extract_dominant_color(image_bytes: bytes) -> Tuple[int, int, int]:
    try:
        thief = ColorThief(io.BytesIO(image_bytes))
        return thief.get_color(quality=4)
    except Exception as exc:  # noqa: BLE001 - never block a render over a color extraction glitch
        logger.error("Dominant color extraction failed (%s), using fallback color", exc)
        return _FALLBACK_COLOR


def extract_accent_palette(image_bytes: bytes) -> List[RGB]:
    """Up to 10 representative colors, most populous first; [] if the art can't be read."""
    try:
        thief = ColorThief(io.BytesIO(image_bytes))
        return list(thief.get_palette(color_count=_ACCENT_COLOR_COUNT, quality=_ACCENT_QUALITY))
    except Exception as exc:  # noqa: BLE001 - effects degrade, the render goes on
        logger.error("Accent palette extraction failed (%s), effects will use fallbacks", exc)
        return []


def _vividness(rgb: RGB) -> float:
    _, saturation, value = colorsys.rgb_to_hsv(*(c / 255 for c in rgb))
    return saturation * value


def _distance(a: RGB, b: RGB) -> float:
    return sum((x - y) ** 2 for x, y in zip(a, b)) ** 0.5


def _luminance(rgb: RGB) -> float:
    r, g, b = rgb
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def _mix(a: RGB, b: RGB, t: float) -> RGB:
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def pick_glow_color(palette: Sequence[RGB], background: RGB) -> RGB:
    """The art's most vivid color, pushed away from the fill until it reads
    against it: toward white on a dark fill, toward black on a light one."""
    # max() keeps the first of equals, so ties go to the more populous color.
    color = max(palette, key=_vividness) if palette else background
    target = (255, 255, 255) if _luminance(background) < 140 else (0, 0, 0)
    t = 0.0
    lifted = color
    while _distance(lifted, background) < _MIN_GLOW_DISTANCE and t < 1.0:
        t = min(1.0, t + _LIFT_STEP)
        lifted = _mix(color, target, t)
    return lifted


def to_hex(rgb: Tuple[int, int, int]) -> str:
    return "#{:02x}{:02x}{:02x}".format(*rgb)
