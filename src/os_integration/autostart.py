import logging
import os
import sys
from pathlib import Path

if sys.platform == "win32":
    import winreg

logger = logging.getLogger(__name__)

_RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
_VALUE_NAME = "SpotifyWallpaperEngine"


def _main_script() -> Path:
    return Path(__file__).resolve().parents[2] / "main.py"


def _launch_command() -> str:
    pythonw = Path(sys.executable).with_name("pythonw.exe")
    interpreter = str(pythonw) if pythonw.exists() else sys.executable
    return f'"{interpreter}" "{_main_script()}"'


# --- Linux: an XDG autostart entry ------------------------------------------
# uwsm (Omarchy) runs ~/.config/autostart entries as systemd units with
# ExitType=cgroup, in the session's environment: Restart's new instance,
# started before the old one exits, keeps the unit alive.


def desktop_entry_path() -> Path:
    from src.config.paths import xdg_config_home

    return xdg_config_home() / "autostart" / f"{_VALUE_NAME}.desktop"


# The Desktop Entry spec can quote these, but systemd's autostart generator
# (how uwsm starts the entry) then can't run the path: "%" stays doubled, "$"
# keeps its backslash, a quote is "special characters". Checked 2026-10-01.
_PATH_CHARACTERS_SYSTEMD_REFUSES = set("%$'\"`\\")


def _app_command() -> list:
    from src.os_integration.updater import app_python

    command = [app_python(_main_script().parent), str(_main_script())]
    for part in command:
        refused = sorted(_PATH_CHARACTERS_SYSTEMD_REFUSES & set(part))
        if refused:
            raise OSError(
                f"the app's path has {' '.join(refused)}, which the session's autostart can't run: "
                f"move the app to a folder without them ({part})"
            )
    return command


def _exec_argument(argument: str) -> str:
    """One Exec argument, as the Desktop Entry spec wants it: quoted, with
    ", `, $ and \\ escaped inside the quotes, then every \\ doubled because
    Exec is itself a string value, and % doubled (field codes)."""
    quoted = '"' + "".join("\\" + c if c in '"`$\\' else c for c in argument) + '"'
    return quoted.replace("\\", "\\\\").replace("%", "%%")


def desktop_entry() -> str:
    command = " ".join(_exec_argument(part) for part in _app_command())
    return (
        "[Desktop Entry]\n"
        "Type=Application\n"
        "Name=Spotify Wallpaper Engine\n"
        "Comment=The album art of what Spotify plays, as the wallpaper\n"
        f"Exec={command}\n"
        "Terminal=false\n"
        "X-GNOME-Autostart-enabled=true\n"
    )


def _install_desktop_entry() -> None:
    path = desktop_entry_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp")
    temporary.write_text(desktop_entry(), encoding="utf-8")
    os.replace(temporary, path)
    logger.info("Autostart installed: %s", path)


def install_autostart() -> None:
    if sys.platform != "win32":
        _install_desktop_entry()
        return
    command = _launch_command()
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
        winreg.SetValueEx(key, _VALUE_NAME, 0, winreg.REG_SZ, command)
    logger.info("Autostart installed: %s", command)


def uninstall_autostart() -> None:
    if sys.platform != "win32":
        desktop_entry_path().unlink(missing_ok=True)
        logger.info("Autostart removed")
        return
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
            winreg.DeleteValue(key, _VALUE_NAME)
        logger.info("Autostart removed")
    except FileNotFoundError:
        pass


def is_autostart_installed() -> bool:
    if sys.platform != "win32":
        return desktop_entry_path().is_file()
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _RUN_KEY, 0, winreg.KEY_READ) as key:
            winreg.QueryValueEx(key, _VALUE_NAME)
            return True
    except FileNotFoundError:
        return False
