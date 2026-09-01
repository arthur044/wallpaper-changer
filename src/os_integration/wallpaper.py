import ctypes
import logging
import winreg
from pathlib import Path

from src.config.paths import cache_dir

logger = logging.getLogger(__name__)

_SPI_SETDESKWALLPAPER = 0x0014
_SPIF_UPDATEINIFILE = 0x01
_SPIF_SENDCHANGE = 0x02

_FILENAMES = ("wallpaper_a.png", "wallpaper_b.png")
_next_index = 0


def next_output_path() -> Path:
    """Alternates between two filenames so Explorer/DWM never holds a lock on the file
    we're about to write — the final (base + text) composite is rewritten on every
    track change, unlike the permanent per-album base cache."""
    global _next_index
    path = cache_dir() / _FILENAMES[_next_index % 2]
    _next_index += 1
    return path


def _ensure_fill_style() -> None:
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Control Panel\Desktop", 0, winreg.KEY_SET_VALUE) as key:
            winreg.SetValueEx(key, "WallpaperStyle", 0, winreg.REG_SZ, "10")
            winreg.SetValueEx(key, "TileWallpaper", 0, winreg.REG_SZ, "0")
    except OSError as exc:
        logger.warning("Could not set wallpaper fill style: %s", exc)


def set_wallpaper(path: Path) -> None:
    _ensure_fill_style()
    absolute = str(path.resolve())
    result = ctypes.windll.user32.SystemParametersInfoW(
        _SPI_SETDESKWALLPAPER, 0, absolute, _SPIF_UPDATEINIFILE | _SPIF_SENDCHANGE
    )
    if not result:
        raise OSError(f"SystemParametersInfoW failed to set wallpaper to {absolute}")
    logger.info("Wallpaper set to %s", absolute)
