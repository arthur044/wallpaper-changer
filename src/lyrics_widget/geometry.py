from typing import FrozenSet, Iterable, Optional, Tuple

Rect = Tuple[int, int, int, int]  # x, y, width, height

DEFAULT_SIZE = (420, 190)
SCREEN_MARGIN = 24
# A saved rect is kept only while at least this share of it is on its monitor.
MIN_VISIBLE_SHARE = 0.5
# How close to the border, in px, a press resizes instead of moving.
RESIZE_MARGIN = 8

# SHQueryUserNotificationState answers meaning something owns the full screen:
# QUNS_BUSY (a full-screen app), QUNS_RUNNING_D3D_FULL_SCREEN (a game),
# QUNS_PRESENTATION_MODE.
_FULL_SCREEN_STATES = frozenset({2, 3, 4})


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


def _overlap(a: Rect, b: Rect) -> int:
    width = min(a[0] + a[2], b[0] + b[2]) - max(a[0], b[0])
    height = min(a[1] + a[3], b[1] + b[3]) - max(a[1], b[1])
    return max(0, width) * max(0, height)


def clamp_into(rect: Rect, area: Rect) -> Rect:
    """[rect] moved (and shrunk only if bigger than [area]) to lie inside [area]."""
    x, y, width, height = rect
    ax, ay, aw, ah = area
    width, height = min(width, aw), min(height, ah)
    x = min(max(x, ax), ax + aw - width)
    y = min(max(y, ay), ay + ah - height)
    return (x, y, width, height)


def restore_rect(saved: Optional[dict], screens: Iterable[Tuple[str, Rect]]) -> Optional[Rect]:
    """Where to put the widget from what the user saved, or None for the
    default position: when nothing was saved, when its monitor is gone, or
    when less than half of it would be on that monitor (its resolution or
    layout changed). What is kept is pulled fully inside the monitor."""
    if not saved:
        return None
    areas = dict(screens)
    area = areas.get(saved.get("monitor"))
    rect = saved.get("rect")
    if area is None or not rect or len(rect) != 4:
        return None
    rect = tuple(rect)
    if rect[2] <= 0 or rect[3] <= 0:
        return None
    if _overlap(rect, area) < MIN_VISIBLE_SHARE * rect[2] * rect[3]:
        return None
    return clamp_into(rect, area)


def edges_at(x: int, y: int, width: int, height: int, margin: int = RESIZE_MARGIN) -> FrozenSet[str]:
    """The window borders a press at (x, y) would drag: empty means move."""
    edges = set()
    if x < margin:
        edges.add("left")
    elif x >= width - margin:
        edges.add("right")
    if y < margin:
        edges.add("top")
    elif y >= height - margin:
        edges.add("bottom")
    return frozenset(edges)


def hide_for_full_screen(on_top: bool, notification_state: Optional[int]) -> bool:
    """Only an always-on-top widget would cover a game or a video; behind the
    windows it is already out of the way."""
    return on_top and notification_state in _FULL_SCREEN_STATES
