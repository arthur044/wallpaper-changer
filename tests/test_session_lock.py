import subprocess
import sys

import pytest

from src.os_integration import session_lock
from src.os_integration.session_lock import _desktop_name_indicates_locked


def test_default_desktop_is_not_locked():
    assert _desktop_name_indicates_locked("Default") is False


def test_winlogon_desktop_is_locked():
    assert _desktop_name_indicates_locked("Winlogon") is True


def test_unreadable_desktop_name_is_treated_as_locked():
    assert _desktop_name_indicates_locked(None) is True


def _check(returncode=None, error=None):
    calls = []

    def run(args, **kwargs):
        calls.append(args)
        if error is not None:
            raise error
        return subprocess.CompletedProcess(args, returncode, b"", b"")

    run.calls = calls
    return run


@pytest.mark.parametrize("returncode, locked", [(0, True), (1, False), (2, False)], ids=["locked", "unlocked", "undetermined"])
def test_omarchys_check_decides_the_lock(returncode, locked):
    run = _check(returncode)

    assert session_lock.is_omarchy_session_locked(run) is locked
    assert run.calls == [["omarchy-hyprland-session-locked"]]


@pytest.mark.parametrize("error", [FileNotFoundError("no omarchy"), subprocess.TimeoutExpired("x", 2)], ids=["missing", "hung"])
def test_a_check_that_fails_counts_as_unlocked(error):
    assert session_lock.is_omarchy_session_locked(_check(error=error)) is False


def test_a_failing_check_is_logged_once_until_it_works_again(monkeypatch, caplog):
    monkeypatch.setattr(session_lock, "_omarchy_check_failing", False)
    broken = _check(error=FileNotFoundError("no omarchy"))

    with caplog.at_level("WARNING"):
        session_lock.is_omarchy_session_locked(broken)
        session_lock.is_omarchy_session_locked(broken)
        session_lock.is_omarchy_session_locked(_check(1))
        session_lock.is_omarchy_session_locked(broken)

    assert len([r for r in caplog.records if "session lock" in r.message]) == 2


def test_off_windows_the_app_asks_omarchy(monkeypatch):
    monkeypatch.setattr(sys, "platform", "linux")
    monkeypatch.setattr(session_lock, "is_omarchy_session_locked", lambda: True)

    assert session_lock.is_workstation_locked() is True
