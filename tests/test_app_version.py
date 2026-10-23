import sys
import subprocess
from pathlib import Path

from src.os_integration import app_version
from src.os_integration.app_version import AppVersion, read_version, version_label


def _completed(stdout="", returncode=0):
    return subprocess.CompletedProcess(args=[], returncode=returncode, stdout=stdout, stderr="")


def test_reads_the_short_commit_and_its_date():
    calls = []

    def run(cmd, **kwargs):
        calls.append((cmd, kwargs))
        return _completed("f93217c 2026-09-29\n")

    version = read_version(Path("C:/app"), run=run)

    assert version == AppVersion(sha="f93217c", date="2026-09-29")
    cmd, kwargs = calls[0]
    assert cmd[:3] == ["git", "-C", str(Path("C:/app"))]
    # pythonw has no console: without this every call flashes a terminal.
    assert kwargs["creationflags"] == subprocess.CREATE_NO_WINDOW


def test_git_missing_from_path_is_an_unknown_version():
    def run(cmd, **kwargs):
        raise FileNotFoundError("git")

    assert read_version(Path("C:/app"), run=run) is None


def test_folder_outside_a_repository_is_an_unknown_version():
    assert read_version(Path("C:/app"), run=lambda cmd, **kw: _completed(returncode=128)) is None


def test_git_that_hangs_is_an_unknown_version():
    def run(cmd, **kwargs):
        raise subprocess.TimeoutExpired(cmd, kwargs.get("timeout"))

    assert read_version(Path("C:/app"), run=run) is None


def test_unexpected_output_is_an_unknown_version():
    assert read_version(Path("C:/app"), run=lambda cmd, **kw: _completed("garbage")) is None


def test_label_shows_commit_and_date():
    assert version_label(AppVersion("f93217c", "2026-09-29")) == "f93217c (2026-09-29)"


def test_label_of_an_unknown_version():
    assert version_label(None) == "Unknown version"


def test_this_checkout_has_a_version():
    # The test suite runs from a git clone, so the real call must work here.
    version = read_version(app_version.app_dir())

    assert version is not None and len(version.sha) >= 7


def test_bundled_version_is_read_from_the_file_the_build_wrote(tmp_path):
    info = tmp_path / "build_info.txt"
    info.write_text("f93217c 2026-09-29\n", encoding="utf-8")

    assert app_version.read_bundled_version(info) == AppVersion("f93217c", "2026-09-29")


def test_bundled_version_missing_or_garbled_is_unknown(tmp_path):
    garbled = tmp_path / "build_info.txt"
    garbled.write_text("garbage", encoding="utf-8")

    assert app_version.read_bundled_version(tmp_path / "nope.txt") is None
    assert app_version.read_bundled_version(garbled) is None
    assert app_version.read_bundled_version(None) is None


def test_frozen_current_version_comes_from_the_bundle(monkeypatch, tmp_path):
    (tmp_path / app_version.BUNDLED_VERSION_FILE).write_text("abc1234 2026-10-23", encoding="utf-8")
    monkeypatch.setattr(sys, "frozen", True, raising=False)
    monkeypatch.setattr(sys, "_MEIPASS", str(tmp_path), raising=False)

    assert app_version.current_version() == AppVersion("abc1234", "2026-10-23")
