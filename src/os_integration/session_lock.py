import ctypes
import functools
import logging
import subprocess
import sys
from ctypes import wintypes
from typing import Callable, Optional

logger = logging.getLogger(__name__)

_DESKTOP_SWITCHDESKTOP = 0x0100
_UOI_NAME = 2


@functools.cache
def _user32_api():
    # On first use, not at import: user32 only exists on Windows.
    user32 = ctypes.windll.user32
    user32.OpenInputDesktop.restype = wintypes.HANDLE
    user32.OpenInputDesktop.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    user32.GetUserObjectInformationW.restype = wintypes.BOOL
    user32.GetUserObjectInformationW.argtypes = [
        wintypes.HANDLE,
        ctypes.c_int,
        wintypes.LPVOID,
        wintypes.DWORD,
        ctypes.POINTER(wintypes.DWORD),
    ]
    user32.CloseDesktop.restype = wintypes.BOOL
    user32.CloseDesktop.argtypes = [wintypes.HANDLE]
    return user32


def _get_input_desktop_name() -> Optional[str]:
    """None means the input desktop couldn't even be opened, which happens
    when the secure desktop (Winlogon / UAC prompt) owns the session."""
    _user32 = _user32_api()
    handle = _user32.OpenInputDesktop(0, False, _DESKTOP_SWITCHDESKTOP)
    if not handle:
        return None

    try:
        buf = ctypes.create_unicode_buffer(256)
        length = wintypes.DWORD()
        ok = _user32.GetUserObjectInformationW(
            handle, _UOI_NAME, buf, ctypes.sizeof(buf), ctypes.byref(length)
        )
        return buf.value if ok else None
    finally:
        _user32.CloseDesktop(handle)


def _desktop_name_indicates_locked(name: Optional[str]) -> bool:
    # The normal interactive desktop is named "Default"; anything else
    # (Winlogon's secure desktop, or None because we couldn't even open it)
    # means the workstation is locked or showing a UAC/secure-desktop prompt.
    return name != "Default"


def is_workstation_locked() -> bool:
    if sys.platform == "win32":
        return _desktop_name_indicates_locked(_get_input_desktop_name())
    return is_omarchy_session_locked()


_OMARCHY_LOCK_CHECK = "omarchy-hyprland-session-locked"
_OMARCHY_TIMEOUT_S = 2.0
# Logged once, not on every poll, until a check works again.
_omarchy_check_failing = False


def is_omarchy_session_locked(run: Optional[Callable] = None) -> bool:
    """Omarchy's own check (Hyprland's ext-session-lock): exit 0 locked,
    1 unlocked, 2 undetermined. Anything but 0, a failure included, counts as
    unlocked, as Omarchy's own callers do: the cost of a wrong "unlocked" is
    one API call, of a wrong "locked" a wallpaper that never changes."""
    global _omarchy_check_failing
    run = run if run is not None else subprocess.run
    try:
        result = run([_OMARCHY_LOCK_CHECK], capture_output=True, timeout=_OMARCHY_TIMEOUT_S)
    except (OSError, subprocess.SubprocessError) as exc:
        if not _omarchy_check_failing:
            logger.warning("Could not check the session lock (%s); taking the session as unlocked", exc)
        _omarchy_check_failing = True
        return False
    _omarchy_check_failing = False
    return result.returncode == 0
