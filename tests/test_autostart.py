import sys

from src.os_integration import autostart


def test_frozen_autostart_runs_the_exe_itself(monkeypatch):
    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(sys, "executable", "C:/app/SpotifyWallpaperEngine.exe")

    assert autostart._launch_command() == '"C:/app/SpotifyWallpaperEngine.exe"'


def test_autostart_from_source_runs_main_py():
    assert autostart._launch_command().endswith('main.py"')
