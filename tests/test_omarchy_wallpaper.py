import os
import subprocess

import pytest

from src.config import paths
from src.os_integration import omarchy_wallpaper


@pytest.fixture
def home(monkeypatch, tmp_path):
    home = tmp_path / "home"
    monkeypatch.setattr(paths.Path, "home", lambda: home)
    monkeypatch.setattr(paths.sys, "platform", "linux")
    monkeypatch.delenv("XDG_STATE_HOME", raising=False)
    (home / ".local" / "state" / "omarchy" / "current").mkdir(parents=True)
    return home


class Shell:
    """omarchy-shell, faked: records each call and answers with [result]."""

    def __init__(self, returncode=0, error=None):
        self.calls = []
        self.returncode = returncode
        self.error = error

    def __call__(self, args, **kwargs):
        self.calls.append(args)
        if self.error is not None:
            raise self.error
        return subprocess.CompletedProcess(args, self.returncode, "", "omarchy-shell is not running")


def _wallpaper(name_hint="x"):
    path = omarchy_wallpaper.next_output_path()
    path.write_bytes(name_hint.encode())
    return path


def _link(home):
    return home / ".local" / "state" / "omarchy" / "current" / "background"


def test_each_wallpaper_gets_a_new_file_in_the_state_dir(home):
    first, second = omarchy_wallpaper.next_output_path(), omarchy_wallpaper.next_output_path()

    assert first != second  # a/b/a would show a stale a: the shell caches by path
    assert first.parent == home / ".local" / "state" / paths.APP_NAME / "wallpaper"


def test_the_link_points_at_the_new_wallpaper_and_the_shell_is_told(home):
    shell = Shell()
    path = _wallpaper()

    omarchy_wallpaper.set_wallpaper(path, run=shell)

    assert os.readlink(_link(home)) == str(path.resolve())
    assert shell.calls == [["omarchy-shell", "background", "setInstant", str(path.resolve())]]


def test_smooth_uses_the_shells_reveal_animation(home):
    shell = Shell()
    path = _wallpaper()

    omarchy_wallpaper.set_wallpaper(path, smooth=True, run=shell)

    assert shell.calls[0][2] == "set"


def test_the_themes_background_link_is_replaced_and_the_theme_file_kept(home):
    theme_file = home / "theme-bg.jpg"
    theme_file.write_bytes(b"jpg")
    _link(home).symlink_to(theme_file)
    path = _wallpaper()

    omarchy_wallpaper.set_wallpaper(path, run=Shell())

    assert os.readlink(_link(home)) == str(path.resolve())
    assert theme_file.exists()
    assert [p.name for p in _link(home).parent.iterdir()] == ["background"]  # no temporary link left behind


@pytest.mark.parametrize(
    "shell",
    [Shell(returncode=1), Shell(error=FileNotFoundError("omarchy-shell")), Shell(error=subprocess.TimeoutExpired("x", 5))],
    ids=["shell not running", "no omarchy-shell", "shell hung"],
)
def test_a_shell_that_cant_be_told_still_leaves_the_link_set(home, shell):
    # The lock screen and the next shell start read the link.
    path = _wallpaper()

    omarchy_wallpaper.set_wallpaper(path, run=shell)

    assert os.readlink(_link(home)) == str(path.resolve())


def test_without_omarchy_nothing_is_touched_and_it_fails(home):
    (home / ".local" / "state" / "omarchy" / "current").rmdir()
    shell = Shell()

    with pytest.raises(OSError, match="Omarchy"):
        omarchy_wallpaper.set_wallpaper(_wallpaper(), run=shell)

    assert shell.calls == []


def test_only_the_new_wallpaper_and_the_one_it_replaces_are_kept(home):
    first = _wallpaper("1")
    omarchy_wallpaper.set_wallpaper(first, run=Shell())
    second = _wallpaper("2")
    omarchy_wallpaper.set_wallpaper(second, run=Shell())
    third = _wallpaper("3")

    omarchy_wallpaper.set_wallpaper(third, run=Shell())

    assert sorted(first.parent.iterdir()) == sorted([second, third])


def test_a_newer_file_not_set_yet_is_never_removed(home):
    current = _wallpaper("1")
    newer = _wallpaper("2")

    omarchy_wallpaper.set_wallpaper(current, run=Shell())

    assert newer.exists()


def test_after_a_theme_background_only_older_files_of_ours_go(home):
    stale = _wallpaper("old")
    theme_file = home / "theme-bg.jpg"
    theme_file.write_bytes(b"jpg")
    _link(home).symlink_to(theme_file)
    path = _wallpaper("new")

    omarchy_wallpaper.set_wallpaper(path, run=Shell())

    assert not stale.exists() and path.exists() and theme_file.exists()
