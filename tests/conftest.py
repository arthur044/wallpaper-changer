import subprocess
from pathlib import Path

import pytest

from src.os_integration import lockscreen

_real_run = subprocess.run


def _program(args) -> str:
    """Bare program name: "schtasks" and C:\\Windows\\System32\\schtasks.exe alike."""
    first = args[0] if isinstance(args, (list, tuple)) else str(args).split()[0]
    return Path(str(first).strip('"')).name.lower()


@pytest.fixture(autouse=True)
def _no_real_scheduled_task(monkeypatch):
    """Every test, every file: nothing reaches the user's real lock screen task.

    A test once got to the real elevation through a fake /query that said the
    task survived: a real UAC prompt, and the user's task was deleted. A plain
    `schtasks /delete` or `/create` needs no UAC at all, so both are refused.
    Tests that need them patch subprocess.run / _run_elevated themselves,
    which replaces these guards for that test.
    """

    def refuse_elevation(exe, params):
        raise AssertionError(f"test reached the real elevation: {exe} {params}")

    def guarded_run(args, *more, **kwargs):
        if _program(args).startswith("schtasks"):
            raise AssertionError(f"test reached the real schtasks: {args}")
        return _real_run(args, *more, **kwargs)

    monkeypatch.setattr(lockscreen, "_run_elevated", refuse_elevation)
    monkeypatch.setattr(subprocess, "run", guarded_run)


@pytest.fixture(autouse=True)
def _no_real_home(monkeypatch, tmp_path_factory):
    """Every test, every file: nothing lands in the user's real home.

    A test once wrote the real ~/.config/autostart entry on Linux (it would
    have started a stray copy of the app at the next login). Path.home()
    reads $HOME off Windows, and config/data/cache/state follow the XDG
    variables there, so all of them point into a throwaway folder. Tests
    that need a specific home still patch it themselves."""
    home = tmp_path_factory.mktemp("home")
    monkeypatch.setenv("HOME", str(home))
    for var in ("XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME", "XDG_STATE_HOME"):
        monkeypatch.delenv(var, raising=False)
