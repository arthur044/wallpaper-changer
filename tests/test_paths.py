import sys

import pytest

from src.config import paths


@pytest.fixture
def home(monkeypatch, tmp_path):
    monkeypatch.setattr(paths.Path, "home", lambda: tmp_path / "home")
    for var in ("APPDATA", "LOCALAPPDATA", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME"):
        monkeypatch.delenv(var, raising=False)
    return tmp_path / "home"


@pytest.fixture
def linux(monkeypatch):
    monkeypatch.setattr(sys, "platform", "linux")


@pytest.fixture
def windows(monkeypatch):
    monkeypatch.setattr(sys, "platform", "win32")


def test_linux_defaults_follow_the_xdg_base_directories(home, linux):
    assert paths.config_dir() == home / ".config" / paths.APP_NAME
    assert paths.data_dir() == home / ".local" / "share" / paths.APP_NAME
    assert paths.cache_dir() == home / ".cache" / paths.APP_NAME
    assert paths.config_file() == home / ".config" / paths.APP_NAME / "config.json"


def test_linux_honours_the_xdg_variables(monkeypatch, tmp_path, home, linux):
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "cfg"))
    monkeypatch.setenv("XDG_DATA_HOME", str(tmp_path / "data"))
    monkeypatch.setenv("XDG_CACHE_HOME", str(tmp_path / "cache"))

    assert paths.config_dir() == tmp_path / "cfg" / paths.APP_NAME
    assert paths.data_dir() == tmp_path / "data" / paths.APP_NAME
    assert paths.cache_dir() == tmp_path / "cache" / paths.APP_NAME


def test_linux_ignores_a_relative_xdg_variable(monkeypatch, home, linux):
    # The XDG spec: a relative path is invalid and must be ignored.
    monkeypatch.setenv("XDG_CACHE_HOME", "relative/cache")

    assert paths.cache_dir() == home / ".cache" / paths.APP_NAME


def test_linux_directories_are_created(home, linux):
    for directory in (paths.config_dir(), paths.data_dir(), paths.cache_dir(), paths.logs_dir()):
        assert directory.is_dir()


def test_linux_wallpaper_files_and_album_bases_live_in_the_cache(home, linux):
    assert paths.album_base_path("key").parent == home / ".cache" / paths.APP_NAME / "album_bases"
    assert paths.album_art_dir() == home / ".cache" / paths.APP_NAME / "album_art"
    assert paths.track_index_file() == home / ".local" / "share" / paths.APP_NAME / "track_index.json"


def test_windows_keeps_appdata_and_localappdata(monkeypatch, tmp_path, home, windows):
    monkeypatch.setenv("APPDATA", str(tmp_path / "Roaming"))
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path / "Local"))
    # Ignored on Windows even when set (e.g. by MSYS).
    monkeypatch.setenv("XDG_CACHE_HOME", str(tmp_path / "xdg-cache"))

    assert paths.config_dir() == tmp_path / "Roaming" / paths.APP_NAME
    assert paths.data_dir() == tmp_path / "Local" / paths.APP_NAME
    assert paths.cache_dir() == tmp_path / "Local" / paths.APP_NAME / "cache"
    assert paths.logs_dir() == tmp_path / "Local" / paths.APP_NAME / "logs"


def test_windows_without_appdata_falls_back_to_home(home, windows):
    assert paths.config_dir() == home / paths.APP_NAME
    assert paths.data_dir() == home / paths.APP_NAME
