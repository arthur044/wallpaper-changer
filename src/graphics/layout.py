import ctypes
import logging
from dataclasses import dataclass
from typing import Tuple

from src.config.settings import Settings

logger = logging.getLogger(__name__)

_DPI_AWARENESS_SET = False


def _ensure_dpi_awareness() -> None:
    global _DPI_AWARENESS_SET
    if _DPI_AWARENESS_SET:
        return
    try:
        # PROCESS_PER_MONITOR_DPI_AWARE = 2 — without this, GetSystemMetrics
        # returns a DPI-virtualized (scaled) resolution on modern displays.
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except (AttributeError, OSError) as exc:
        logger.warning("Could not set DPI awareness, resolution detection may be scaled: %s", exc)
    _DPI_AWARENESS_SET = True


def get_primary_resolution(fallback: Tuple[int, int]) -> Tuple[int, int]:
    _ensure_dpi_awareness()
    try:
        user32 = ctypes.windll.user32
        width = user32.GetSystemMetrics(0)
        height = user32.GetSystemMetrics(1)
        if width <= 0 or height <= 0:
            raise ValueError(f"Invalid resolution reported: {width}x{height}")
        return width, height
    except Exception as exc:  # noqa: BLE001 - resolution detection must never crash the app
        logger.error("Resolution detection failed (%s), using fallback %s", exc, fallback)
        return fallback


@dataclass(frozen=True)
class ArtLayout:
    canvas_size: Tuple[int, int]
    art_size: int
    art_position: Tuple[int, int]


def compute_layout(settings: Settings) -> ArtLayout:
    fallback = (settings.fallback_resolution[0], settings.fallback_resolution[1])
    width, height = get_primary_resolution(fallback)

    art_size = int(height * settings.art_size_pct)
    art_x = (width - art_size) // 2
    art_y = (height - art_size) // 2

    return ArtLayout(
        canvas_size=(width, height),
        art_size=art_size,
        art_position=(art_x, art_y),
    )
