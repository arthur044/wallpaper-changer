import subprocess
from pathlib import Path

from src.os_integration import app_version
from src.os_integration.app_version import AppVersion, read_version, version_label


def _completed(stdout="", returncode=0):
    return subprocess.CompletedProcess(args=[], returncode=returncode, stdout=stdout, stderr="")


def test_reads_the_short_commit_and_its_date(monkeypatch):
    # A sentinel, not the real flag: outside Windows NO_WINDOW is 0, which a
    # hard-coded creationflags=0 would also match.
    monkeypatch.setattr(app_version, "NO_WINDOW", 0x08000000)
    calls = []

    def run(cmd, **kwargs):
        calls.append((cmd, kwargs))
        return _completed("f93217c 2026-09-29\n")

    version = read_version(Path("C:/app"), run=run)

    assert version == AppVersion(sha="f93217c", date="2026-09-29")
    cmd, kwargs = calls[0]
    assert cmd[:3] == ["git", "-C", str(Path("C:/app"))]
    # pythonw has no console: without this every call flashes a terminal.
    assert kwargs["creationflags"] == 0x08000000


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
