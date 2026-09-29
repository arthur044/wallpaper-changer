"""The widget's background, tinted by the album on the wallpaper."""

import threading
from typing import Optional, Tuple

RGB = Tuple[int, int, int]

DEFAULT_BACKGROUND: RGB = (18, 18, 18)
# WCAG AA for normal text: the white lyrics must stay this readable.
MIN_CONTRAST = 4.5
_WHITE_LUMINANCE = 1.0


def _channel(value: int) -> float:
    c = value / 255
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def relative_luminance(rgb: RGB) -> float:
    r, g, b = (_channel(v) for v in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast_with_white(rgb: RGB) -> float:
    return (_WHITE_LUMINANCE + 0.05) / (relative_luminance(rgb) + 0.05)


def widget_background(tint: Optional[RGB]) -> RGB:
    """The album's color, darkened just enough for white text to keep a 4.5:1
    contrast. A dark album keeps its own color; no tint yet, the default."""
    if tint is None:
        return DEFAULT_BACKGROUND
    color = tint
    factor = 1.0
    while contrast_with_white(color) < MIN_CONTRAST and factor > 0:
        factor = round(factor - 0.05, 2)
        color = tuple(int(v * factor) for v in tint)
    return color


class TintHolder:
    """The last album color the wallpaper render found, handed from the
    poller's thread to the widget's. In memory only."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._tint: Optional[RGB] = None

    def set(self, tint: Optional[RGB]) -> None:
        with self._lock:
            self._tint = tint

    def get(self) -> Optional[RGB]:
        with self._lock:
            return self._tint
