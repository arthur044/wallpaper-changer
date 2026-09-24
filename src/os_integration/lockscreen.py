import ctypes
import json
import logging
import subprocess
import sys
import winreg
from ctypes import wintypes
from pathlib import Path
from typing import Optional

from src.config.paths import data_dir

logger = logging.getLogger(__name__)

_TASK_NAME = "SpotifyWallpaperEngine_LockScreen"
_CSP_KEY = r"SOFTWARE\Microsoft\Windows\CurrentVersion\PersonalizationCSP"
_SEE_MASK_NOCLOSEPROCESS = 0x00000040
_SW_HIDE = 0
_WAIT_TIMEOUT_MS = 30_000
# schtasks.exe is a console program. The app runs under pythonw, which has no
# console to share, so without this Windows opens one per call: a terminal
# flashing on screen on every track change.
_NO_WINDOW = subprocess.CREATE_NO_WINDOW


def _pending_path_file() -> Path:
    return data_dir() / "lockscreen_pending.json"


def _launch_command() -> str:
    main_script = Path(__file__).resolve().parents[2] / "main.py"
    pythonw = Path(sys.executable).with_name("pythonw.exe")
    interpreter = str(pythonw) if pythonw.exists() else sys.executable
    return f'"{interpreter}" "{main_script}" --apply-lockscreen'


class _ShellExecuteInfo(ctypes.Structure):
    _fields_ = [
        ("cbSize", wintypes.DWORD),
        ("fMask", ctypes.c_ulong),
        ("hwnd", wintypes.HWND),
        ("lpVerb", wintypes.LPCWSTR),
        ("lpFile", wintypes.LPCWSTR),
        ("lpParameters", wintypes.LPCWSTR),
        ("lpDirectory", wintypes.LPCWSTR),
        ("nShow", ctypes.c_int),
        ("hInstApp", wintypes.HINSTANCE),
        ("lpIDList", ctypes.c_void_p),
        ("lpClass", wintypes.LPCWSTR),
        ("hKeyClass", wintypes.HKEY),
        ("dwHotKey", wintypes.DWORD),
        ("hIcon", wintypes.HANDLE),
        ("hProcess", wintypes.HANDLE),
    ]


def _run_elevated(exe: str, params: str) -> bool:
    """Launches exe with the 'runas' verb (triggers one UAC prompt) and waits
    for it to finish. Returns False on a clean decline/failure instead of
    raising, since the caller must be able to continue running unelevated."""
    info = _ShellExecuteInfo()
    info.cbSize = ctypes.sizeof(info)
    info.fMask = _SEE_MASK_NOCLOSEPROCESS
    info.lpVerb = "runas"
    info.lpFile = exe
    info.lpParameters = params
    info.nShow = _SW_HIDE

    if not ctypes.windll.shell32.ShellExecuteExW(ctypes.byref(info)):
        error = ctypes.GetLastError()
        logger.warning("Elevated launch of %s failed or was declined (error %s)", exe, error)
        return False

    ctypes.windll.kernel32.WaitForSingleObject(info.hProcess, _WAIT_TIMEOUT_MS)
    exit_code = wintypes.DWORD()
    ctypes.windll.kernel32.GetExitCodeProcess(info.hProcess, ctypes.byref(exit_code))
    ctypes.windll.kernel32.CloseHandle(info.hProcess)
    return exit_code.value == 0


def is_task_installed() -> bool:
    result = subprocess.run(["schtasks", "/query", "/tn", _TASK_NAME], capture_output=True, creationflags=_NO_WINDOW)
    return result.returncode == 0


def install_task() -> bool:
    """Elevates once (UAC) to register a Task Scheduler task with 'highest'
    privileges. Windows treats that consent as durable for the task, so
    subsequent /run calls execute elevated without prompting again."""
    command = _launch_command()
    escaped_command = command.replace('"', '\\"')
    params = f'/create /tn "{_TASK_NAME}" /tr "{escaped_command}" /sc onlogon /rl highest /f'
    success = _run_elevated("schtasks.exe", params)
    if success:
        logger.info("Lock screen scheduled task installed")
    else:
        logger.warning("Lock screen scheduled task installation failed or was declined")
    return success


def uninstall_task() -> None:
    subprocess.run(["schtasks", "/delete", "/tn", _TASK_NAME, "/f"], capture_output=True, creationflags=_NO_WINDOW)
    logger.info("Lock screen scheduled task removed")


def request_update(path: Path) -> None:
    """Fire-and-forget: records the target path, then triggers the
    pre-elevated scheduled task. No UAC prompt on this path."""
    try:
        _pending_path_file().write_text(json.dumps({"path": str(path.resolve())}), encoding="utf-8")
    except OSError as exc:
        logger.warning("Could not write lock screen pending path: %s", exc)
        return

    result = subprocess.run(["schtasks", "/run", "/tn", _TASK_NAME], capture_output=True, creationflags=_NO_WINDOW)
    if result.returncode != 0:
        logger.warning("Failed to trigger lock screen task: %s", result.stderr.decode(errors="ignore"))


def _read_pending_path() -> Optional[str]:
    pending = _pending_path_file()
    if not pending.exists():
        return None
    try:
        data = json.loads(pending.read_text(encoding="utf-8"))
        return data["path"]
    except (json.JSONDecodeError, OSError, KeyError) as exc:
        logger.error("Invalid lock screen pending file: %s", exc)
        return None


def _write_registry(image_path: str) -> None:
    with winreg.CreateKeyEx(winreg.HKEY_LOCAL_MACHINE, _CSP_KEY, 0, winreg.KEY_SET_VALUE) as key:
        winreg.SetValueEx(key, "LockScreenImagePath", 0, winreg.REG_SZ, image_path)
        winreg.SetValueEx(key, "LockScreenImageUrl", 0, winreg.REG_SZ, image_path)
        winreg.SetValueEx(key, "LockScreenImageStatus", 0, winreg.REG_DWORD, 1)


def apply_pending() -> None:
    """Runs elevated, invoked by the scheduled task. Reads the path written by
    request_update() and writes it into the registry keys Windows reads for
    the lock screen image."""
    image_path = _read_pending_path()
    if image_path is None:
        return
    try:
        _write_registry(image_path)
        logger.info("Lock screen image set to %s", image_path)
    except OSError as exc:
        logger.error("Failed to write lock screen registry keys (are we elevated?): %s", exc)
