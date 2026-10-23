import sys

import pytest

from src.os_integration import autostart

EXE = "C:/app/SpotifyWallpaperEngine.exe"


class _FakeRegistry:
    """The Run key as a dict; stands in for winreg."""

    HKEY_CURRENT_USER = KEY_READ = KEY_SET_VALUE = REG_SZ = 0

    def __init__(self, values):
        self.values = values

    def OpenKey(self, *_args):
        return _Key()

    def QueryValueEx(self, _key, name):
        if name not in self.values:
            raise FileNotFoundError(name)
        return self.values[name], 1

    def SetValueEx(self, _key, name, _reserved, _kind, value):
        self.values[name] = value


class _Key:
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


@pytest.fixture
def registry(monkeypatch):
    fake = _FakeRegistry({})
    # raising=False: off Windows the module never imports winreg.
    monkeypatch.setattr(autostart, "winreg", fake, raising=False)
    return fake.values


def _freeze(monkeypatch):
    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(sys, "executable", EXE)


def test_frozen_autostart_runs_the_exe_itself(monkeypatch):
    _freeze(monkeypatch)

    assert autostart._launch_command() == f'"{EXE}"'


def test_autostart_from_source_runs_main_py():
    assert autostart._launch_command().endswith('main.py"')


def test_refresh_follows_an_exe_that_moved(monkeypatch, registry):
    _freeze(monkeypatch)
    registry[autostart._VALUE_NAME] = '"C:/old/SpotifyWallpaperEngine.exe"'

    autostart.refresh_autostart()

    assert registry[autostart._VALUE_NAME] == f'"{EXE}"'


def test_refresh_does_not_turn_autostart_on(monkeypatch, registry):
    _freeze(monkeypatch)

    autostart.refresh_autostart()

    assert registry == {}


def test_refresh_is_a_no_op_from_source(registry):
    registry[autostart._VALUE_NAME] = '"C:/py/pythonw.exe" "C:/clone/main.py"'

    autostart.refresh_autostart()

    assert registry[autostart._VALUE_NAME] == '"C:/py/pythonw.exe" "C:/clone/main.py"'


def test_refresh_leaves_the_python_install_autostart_alone(monkeypatch, registry):
    _freeze(monkeypatch)
    registry[autostart._VALUE_NAME] = '"C:/py/pythonw.exe" "C:/clone/main.py"'

    autostart.refresh_autostart()

    assert registry[autostart._VALUE_NAME] == '"C:/py/pythonw.exe" "C:/clone/main.py"'


def test_refresh_failing_to_write_does_not_stop_the_app(monkeypatch, registry):
    _freeze(monkeypatch)
    registry[autostart._VALUE_NAME] = '"C:/old/SpotifyWallpaperEngine.exe"'

    def denied():
        raise PermissionError("registry")

    monkeypatch.setattr(autostart, "install_autostart", denied)

    autostart.refresh_autostart()  # must not raise
