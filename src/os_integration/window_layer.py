import ctypes
import logging
from ctypes import wintypes

logger = logging.getLogger(__name__)

_HWND_TOPMOST = wintypes.HWND(-1)
_HWND_NOTOPMOST = wintypes.HWND(-2)
_HWND_BOTTOM = wintypes.HWND(1)
_SWP_NOSIZE = 0x0001
_SWP_NOMOVE = 0x0002
_SWP_NOACTIVATE = 0x0010
_FLAGS = _SWP_NOSIZE | _SWP_NOMOVE | _SWP_NOACTIVATE


def _set_window_pos():
    # A private user32: argtypes set on ctypes.windll.user32's shared function
    # would change it for every other caller in the process.
    set_pos = ctypes.WinDLL("user32", use_last_error=True).SetWindowPos
    set_pos.argtypes = (wintypes.HWND, wintypes.HWND, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, wintypes.UINT)
    set_pos.restype = wintypes.BOOL
    return set_pos


def set_window_layer(hwnd: int, on_top: bool) -> None:
    """Above every window, or at the bottom of the stack (above the desktop).

    Qt's WindowStaysOnTopHint does not reach an existing window that had
    WindowStaysOnBottomHint: its flag changes, but Windows never gets
    WS_EX_TOPMOST (seen 2026-09-28, PySide6 6.11.2). So the layer is set here."""
    try:
        set_pos = _set_window_pos()
        # Leaving topmost first: HWND_BOTTOM alone keeps a topmost window topmost.
        steps = (_HWND_TOPMOST,) if on_top else (_HWND_NOTOPMOST, _HWND_BOTTOM)
        for insert_after in steps:
            if not set_pos(hwnd, insert_after, 0, 0, 0, 0, _FLAGS):
                logger.warning("Could not set the lyrics widget's layer: %s", ctypes.WinError(ctypes.get_last_error()))
                return
    except (AttributeError, OSError) as exc:
        logger.warning("Could not set the lyrics widget's layer: %s", exc)
