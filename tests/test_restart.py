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
