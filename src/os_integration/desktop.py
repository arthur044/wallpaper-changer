"""The OS-specific pieces main.py wires together, picked once by platform.
Each pair has the same interface; Windows keeps exactly what it had."""

import sys

if sys.platform == "win32":
    from src.os_integration.smtc import SmtcWatcher as MediaWatcher
    from src.os_integration.wallpaper import next_output_path, set_wallpaper

    # pystray draws the icon itself.
    tray_icon_factory = None
else:
    from src.os_integration.mpris import MprisWatcher as MediaWatcher
    from src.os_integration.omarchy_wallpaper import next_output_path, set_wallpaper
    from src.os_integration.qt_tray import QtTrayIcon as tray_icon_factory

__all__ = ["MediaWatcher", "next_output_path", "set_wallpaper", "tray_icon_factory"]
