from typing import Tuple

Rect = Tuple[int, int, int, int]  # x, y, width, height

DEFAULT_SIZE = (360, 160)
SCREEN_MARGIN = 24


def default_position(available: Rect, size: Tuple[int, int], margin: int = SCREEN_MARGIN) -> Tuple[int, int]:
    """Bottom-right corner of the screen's available area (taskbar excluded),
    [margin] away from both edges. A window bigger than the area is pinned to
    its top-left instead of starting off screen."""
    x, y, width, height = available
    window_width, window_height = size
    return (
        max(x, x + width - window_width - margin),
        max(y, y + height - window_height - margin),
    )
