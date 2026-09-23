import ctypes
import logging
import winreg
from pathlib import Path

from src.config.paths import cache_dir

logger = logging.getLogger(__name__)

_SPI_SETDESKWALLPAPER = 0x0014
_SPIF_UPDATEINIFILE = 0x01
_SPIF_SENDCHANGE = 0x02
# Undocumented Progman message that makes Explorer create the WorkerW window
# behind the desktop icons, which is what animates a wallpaper change. Used by
# Windows' own slideshow; widely used by wallpaper tools.
_PROGMAN_SPAWN_WORKERW = 0x052C
_SEND_TIMEOUT_MS = 1000

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


def set_wallpaper(path: Path, smooth: bool = False) -> None:
    """Sets the desktop wallpaper. With [smooth], through Windows' own fading
    path (IActiveDesktop), which cross-fades where the system's animations are
    on; any failure there falls back to the direct, instant change."""
    _ensure_fill_style()
    absolute = str(path.resolve())
    if smooth:
        try:
            _set_with_fade(absolute)
            logger.info("Wallpaper set (fading) to %s", absolute)
            return
        except Exception as exc:  # noqa: BLE001 - the direct path below still sets it
            logger.warning("Fading wallpaper change failed (%s), setting it directly", exc)
    _set_directly(absolute)
    logger.info("Wallpaper set to %s", absolute)


def _set_directly(absolute: str) -> None:
    result = ctypes.windll.user32.SystemParametersInfoW(
        _SPI_SETDESKWALLPAPER, 0, absolute, _SPIF_UPDATEINIFILE | _SPIF_SENDCHANGE
    )
    if not result:
        raise OSError(f"SystemParametersInfoW failed to set wallpaper to {absolute}")


def _set_with_fade(absolute: str) -> None:
    # Imported here: only this optional path needs COM.
    import pythoncom
    import win32con
    import win32gui
    from win32com.shell import shell, shellcon

    progman = win32gui.FindWindow("Progman", None)
    if progman:
        win32gui.SendMessageTimeout(
            progman, _PROGMAN_SPAWN_WORKERW, 0, 0, win32con.SMTO_NORMAL, _SEND_TIMEOUT_MS
        )
    # Called from the poller thread: COM must be initialized on it.
    pythoncom.CoInitialize()
    try:
        desktop = pythoncom.CoCreateInstance(
            shell.CLSID_ActiveDesktop, None, pythoncom.CLSCTX_INPROC_SERVER, shell.IID_IActiveDesktop
        )
        desktop.SetWallpaper(absolute, 0)
        # Not AD_APPLY_FORCE: it regenerates the old HTML Active Desktop, which
        # Windows 10 no longer has, and fails the whole call with E_FAIL.
        desktop.ApplyChanges(shellcon.AD_APPLY_ALL)
    finally:
        pythoncom.CoUninitialize()
