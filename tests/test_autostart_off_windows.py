import configparser
import shlex
import sys

import pytest

from src.config.settings import Settings
from src.onboarding import steps
from src.os_integration import autostart


@pytest.fixture(autouse=True)
def _linux(monkeypatch, tmp_path):
    monkeypatch.setattr(autostart.sys, "platform", "linux")
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "config"))


def _entry_path(tmp_path):
    return tmp_path / "config" / "autostart" / "SpotifyWallpaperEngine.desktop"


def test_install_writes_an_xdg_autostart_entry(tmp_path):
    autostart.install_autostart()

    entry = configparser.ConfigParser(interpolation=None)
    entry.read(_entry_path(tmp_path), encoding="utf-8")
    section = entry["Desktop Entry"]
    assert section["Type"] == "Application"
    assert section["Terminal"] == "false"
    # The venv's own python (not resolved past the venv symlink) and main.py.
    assert shlex.split(section["Exec"]) == [sys.executable, str(autostart._main_script())]
    assert autostart.is_autostart_installed() is True


def test_uninstall_removes_it_and_is_fine_when_already_gone(tmp_path):
    autostart.install_autostart()

    autostart.uninstall_autostart()
    autostart.uninstall_autostart()

    assert not _entry_path(tmp_path).exists()
    assert autostart.is_autostart_installed() is False


def test_the_real_home_is_never_touched_by_the_tests(tmp_path):
    # Guard from conftest: $HOME is a throwaway folder here too.
    assert autostart.desktop_entry_path() == _entry_path(tmp_path)


@pytest.mark.parametrize(
    "argument, expected",
    [
        ("/home/me/.venv/bin/python", '"/home/me/.venv/bin/python"'),
        ("/a b/main.py", '"/a b/main.py"'),
        ('/q"uote', '"/q\\\\"uote"'),  # " escaped by \, then the \ doubled (string value)
        ("/$HOME/`x`", '"/\\\\$HOME/\\\\`x\\\\`"'),
        ("/100%/x", '"/100%%/x"'),
    ],
)
def test_exec_arguments_follow_the_desktop_entry_spec(argument, expected):
    assert autostart._exec_argument(argument) == expected


def test_the_wizard_option_installs_it(monkeypatch, tmp_path):
    monkeypatch.setattr(steps, "save_settings", lambda settings: None)

    result = steps.apply_options(Settings(), autostart=True, sync_lock_screen=False)

    assert result.finished and _entry_path(tmp_path).exists()
