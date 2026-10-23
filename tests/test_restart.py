import os
import sys

from src.os_integration import restart


def test_relaunch_command_reruns_this_script_without_one_shot_flags(monkeypatch):
    monkeypatch.setattr(sys, "argv", ["main.py", "--setup"])

    command = restart.relaunch_command()

    assert command == [sys.executable, os.path.abspath("main.py")]


def test_relaunch_starts_the_command(monkeypatch):
    started = []
    monkeypatch.setattr(restart.subprocess, "Popen", lambda cmd, **kw: started.append((cmd, kw)))

    restart.relaunch()

    assert started and started[0][0] == restart.relaunch_command()


def _freeze(monkeypatch, exe="C:/app/SpotifyWallpaperEngine.exe"):
    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(sys, "executable", exe)


def test_frozen_relaunch_command_is_the_exe_alone(monkeypatch):
    _freeze(monkeypatch)
    monkeypatch.setattr(sys, "argv", ["C:/app/SpotifyWallpaperEngine.exe", "--setup"])

    assert restart.relaunch_command() == ["C:/app/SpotifyWallpaperEngine.exe"]


def test_frozen_relaunch_makes_the_new_exe_unpack_its_own_folder(monkeypatch):
    _freeze(monkeypatch)
    started = []
    monkeypatch.setattr(restart.subprocess, "Popen", lambda cmd, **kw: started.append((cmd, kw)))

    restart.relaunch()

    assert started[0][1]["env"]["PYINSTALLER_RESET_ENVIRONMENT"] == "1"


def test_relaunch_from_source_leaves_the_environment_alone(monkeypatch):
    started = []
    monkeypatch.setattr(restart.subprocess, "Popen", lambda cmd, **kw: started.append((cmd, kw)))

    restart.relaunch()

    assert started[0][1]["env"] is None
