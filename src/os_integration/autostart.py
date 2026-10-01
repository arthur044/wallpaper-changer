import logging
import sys
from pathlib import Path

if sys.platform == "win32":
    import winreg

logger = logging.getLogger(__name__)

_RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
_VALUE_NAME = "SpotifyWallpaperEngine"


def _launch_command() -> str:
    main_script = Path(__file__).resolve().parents[2] / "main.py"
    pythonw = Path(sys.executable).with_name("pythonw.exe")
    interpreter = str(pythonw) if pythonw.exists() else sys.executable
    return f'"{interpreter}" "{main_script}"'


def _require_windows() -> None:
    # A clean error the wizard can show, not a NameError on winreg. Starting
    # with the session on Linux is its own step of the port.
    if sys.platform != "win32":
        raise OSError("starting with the session is not supported on this platform yet")


def install_autostart() -> None:
    _require_windows()
    command = _launch_command()
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
        winreg.SetValueEx(key, _VALUE_NAME, 0, winreg.REG_SZ, command)
    logger.info("Autostart installed: %s", command)


def uninstall_autostart() -> None:
    _require_windows()
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
            winreg.DeleteValue(key, _VALUE_NAME)
        logger.info("Autostart removed")
    except FileNotFoundError:
        pass


def is_autostart_installed() -> bool:
    if sys.platform != "win32":
        return False
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _RUN_KEY, 0, winreg.KEY_READ) as key:
            winreg.QueryValueEx(key, _VALUE_NAME)
            return True
    except FileNotFoundError:
        return False
