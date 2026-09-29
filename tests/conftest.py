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
