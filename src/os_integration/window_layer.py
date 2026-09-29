import ctypes
import logging

logger = logging.getLogger(__name__)

_HWND_TOPMOST = -1
_HWND_NOTOPMOST = -2
_HWND_BOTTOM = 1
_SWP_NOSIZE = 0x0001
_SWP_NOMOVE = 0x0002
_SWP_NOACTIVATE = 0x0010
_FLAGS = _SWP_NOSIZE | _SWP_NOMOVE | _SWP_NOACTIVATE


def set_window_layer(hwnd: int, on_top: bool) -> None:
    """Above every window, or at the bottom of the stack (above the desktop).

    Qt's WindowStaysOnTopHint does not reach an existing window that had
    WindowStaysOnBottomHint: its flag changes, but Windows never gets
    WS_EX_TOPMOST (seen 2026-09-28, PySide6 6.11.2). So the layer is set here."""
    try:
        set_pos = ctypes.windll.user32.SetWindowPos
        set_pos.argtypes = (ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_uint)
        if on_top:
            set_pos(hwnd, _HWND_TOPMOST, 0, 0, 0, 0, _FLAGS)
        else:
            # Leaving topmost first: HWND_BOTTOM alone keeps a topmost window topmost.
            set_pos(hwnd, _HWND_NOTOPMOST, 0, 0, 0, 0, _FLAGS)
            set_pos(hwnd, _HWND_BOTTOM, 0, 0, 0, 0, _FLAGS)
    except (AttributeError, OSError) as exc:
        logger.warning("Could not set the lyrics widget's layer: %s", exc)
