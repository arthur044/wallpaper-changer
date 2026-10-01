import ctypes
import logging
import os
import sys
import time
from ctypes import wintypes
from pathlib import Path
from typing import Optional

logger = logging.getLogger(__name__)

# "Local\" scopes it to this logon session: another user on the same PC keeps
# their own wallpaper engine.
MUTEX_NAME = "Local\\SpotifyWallpaperEngine"
# Restart launches the new instance before the old one exits; the old one
# joins its poller for up to 15 s, so the new one waits at least that long.
RESTART_WAIT_S = 20.0

WAIT_OBJECT_0 = 0x00000000
WAIT_ABANDONED = 0x00000080
WAIT_TIMEOUT = 0x00000102
WAIT_FAILED = 0xFFFFFFFF


def wait_outcome(wait_result: int) -> str:
    """'acquired', 'busy' (another instance holds it) or 'unguarded' (the wait
    itself failed; the app runs anyway rather than never starting)."""
    if wait_result in (WAIT_OBJECT_0, WAIT_ABANDONED):
        return "acquired"
    if wait_result == WAIT_TIMEOUT:
        return "busy"
    return "unguarded"


class _Kernel32:
    def __init__(self) -> None:
        k32 = ctypes.WinDLL("kernel32", use_last_error=True)
        k32.CreateMutexW.restype = wintypes.HANDLE
        k32.CreateMutexW.argtypes = [wintypes.LPVOID, wintypes.BOOL, wintypes.LPCWSTR]
        k32.WaitForSingleObject.restype = wintypes.DWORD
        k32.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
        k32.ReleaseMutex.restype = wintypes.BOOL
        k32.ReleaseMutex.argtypes = [wintypes.HANDLE]
        k32.CloseHandle.restype = wintypes.BOOL
        k32.CloseHandle.argtypes = [wintypes.HANDLE]
        self._k32 = k32

    def create_mutex(self, name: str) -> int:
        return self._k32.CreateMutexW(None, False, name) or 0

    def wait(self, handle: int, timeout_ms: int) -> int:
        return self._k32.WaitForSingleObject(handle, timeout_ms)

    def release(self, handle: int) -> None:
        self._k32.ReleaseMutex(handle)

    def close(self, handle: int) -> None:
        self._k32.CloseHandle(handle)


class _Flock:
    """The same four calls as _Kernel32, with an flock on a file in
    $XDG_RUNTIME_DIR (per user, per boot) off Windows. The kernel drops an
    flock when its holder dies, as Windows hands over an abandoned mutex.
    The file is opened close-on-exec (Python's default), so the instance a
    Restart launches doesn't inherit the old one's lock."""

    _RETRY_S = 0.1

    def __init__(self, directory: Optional[Path] = None) -> None:
        self._directory = directory

    def _lock_path(self, name: str) -> Path:
        directory = self._directory
        if directory is None:
            runtime = os.environ.get("XDG_RUNTIME_DIR")
            if runtime and os.path.isabs(runtime):
                directory = Path(runtime)
            else:
                from src.config.paths import state_dir

                directory = state_dir()
        # "Local\SpotifyWallpaperEngine" -> SpotifyWallpaperEngine.lock
        return directory / (name.rsplit("\\", 1)[-1] + ".lock")

    def create_mutex(self, name: str) -> int:
        try:
            fd = os.open(self._lock_path(name), os.O_RDWR | os.O_CREAT, 0o600)
        except OSError as exc:
            logger.warning("Could not open the single-instance lock file: %s", exc)
            return 0
        if fd == 0:  # 0 reads as "no handle" to acquire(); stdin was closed
            moved = os.dup(fd)
            os.close(fd)
            fd = moved
        return fd

    def wait(self, handle: int, timeout_ms: int) -> int:
        import fcntl

        deadline = time.monotonic() + timeout_ms / 1000
        while True:
            try:
                fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
                return WAIT_OBJECT_0
            except BlockingIOError:
                if time.monotonic() >= deadline:
                    return WAIT_TIMEOUT
                time.sleep(self._RETRY_S)
            except OSError as exc:
                logger.warning("flock on the single-instance lock failed: %s", exc)
                return WAIT_FAILED

    def release(self, handle: int) -> None:
        import fcntl

        fcntl.flock(handle, fcntl.LOCK_UN)

    def close(self, handle: int) -> None:
        os.close(handle)


class InstanceLock:
    """Held for the life of the app. A mutex belongs to the thread that took
    it, so release() has to run on that same thread (main)."""

    def __init__(self, api, handle: int) -> None:
        self._api = api
        self._handle = handle

    def release(self) -> None:
        if not self._handle:
            return
        handle, self._handle = self._handle, 0
        self._api.release(handle)
        self._api.close(handle)


def acquire(name: str = MUTEX_NAME, timeout_s: float = RESTART_WAIT_S, api=None) -> Optional[InstanceLock]:
    """The lock, or None when another instance still holds it after timeout_s."""
    if api is None:
        api = _Kernel32() if sys.platform == "win32" else _Flock()
    handle = api.create_mutex(name)
    if not handle:
        logger.warning("Could not create the single-instance mutex; running unguarded")
        return InstanceLock(api, 0)

    outcome = wait_outcome(api.wait(handle, int(timeout_s * 1000)))
    if outcome == "busy":
        api.close(handle)
        return None
    if outcome == "unguarded":
        logger.warning("Waiting on the single-instance mutex failed; running unguarded")
        api.close(handle)
        return InstanceLock(api, 0)
    return InstanceLock(api, handle)
