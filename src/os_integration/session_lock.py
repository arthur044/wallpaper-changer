import ctypes
from ctypes import wintypes
from typing import Optional

_DESKTOP_SWITCHDESKTOP = 0x0100
_UOI_NAME = 2

_user32 = ctypes.windll.user32
_user32.OpenInputDesktop.restype = wintypes.HANDLE
_user32.OpenInputDesktop.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
_user32.GetUserObjectInformationW.restype = wintypes.BOOL
_user32.GetUserObjectInformationW.argtypes = [
    wintypes.HANDLE,
    ctypes.c_int,
    wintypes.LPVOID,
    wintypes.DWORD,
    ctypes.POINTER(wintypes.DWORD),
]
_user32.CloseDesktop.restype = wintypes.BOOL
_user32.CloseDesktop.argtypes = [wintypes.HANDLE]


def _get_input_desktop_name() -> Optional[str]:
    """None means the input desktop couldn't even be opened, which happens
    when the secure desktop (Winlogon / UAC prompt) owns the session."""
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
    return _desktop_name_indicates_locked(_get_input_desktop_name())
