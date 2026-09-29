"""Negative controls for the guards in conftest.py: without them firing, a
lock screen test with a missing fake would change the user's real task."""

import subprocess

import pytest

from src.os_integration import lockscreen


def test_uninstall_without_fakes_is_stopped_before_touching_the_task():
    with pytest.raises(AssertionError, match="real schtasks"):
        lockscreen.uninstall_task()


def test_install_without_fakes_is_stopped_before_the_uac_prompt():
    with pytest.raises(AssertionError, match="real elevation"):
        lockscreen.install_task()


def test_ensure_task_without_fakes_is_stopped():
    with pytest.raises(AssertionError, match="real schtasks"):
        lockscreen.ensure_task()


def test_other_programs_still_run():
    # The updater and version tests need real git.
    result = subprocess.run(["git", "--version"], capture_output=True, text=True)

    assert result.returncode == 0 and "git" in result.stdout
