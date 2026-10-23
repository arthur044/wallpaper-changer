import os
import subprocess
import sys

import pytest

from src.config import paths
from src.os_integration import omarchy_wallpaper

# Omarchy is Linux, and symlinks on Windows need Developer Mode or admin.
pytestmark = pytest.mark.skipif(sys.platform == "win32", reason="Omarchy backend: Linux only")


@pytest.fixture
def home(monkeypatch, tmp_path):
    home = tmp_path / "home"
    monkeypatch.setattr(paths.Path, "home", lambda: home)
    monkeypatch.setattr(paths.sys, "platform", "linux")
    monkeypatch.delenv("XDG_STATE_HOME", raising=False)
    (home / ".local" / "state" / "omarchy" / "current").mkdir(parents=True)
    monkeypatch.setattr(omarchy_wallpaper, "_replaced_at", {})
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


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


def _draw_and_set(clock, smooth=False):
    """As the poller does: draw the next wallpaper, then set it."""
    path = _wallpaper()
    omarchy_wallpaper.set_wallpaper(path, smooth=smooth, run=Shell(), now=clock)
    return path


def test_spaced_out_changes_keep_only_the_current_wallpaper_and_the_one_it_replaced(home):
    clock = Clock()
    first = _draw_and_set(clock)
    clock.t += 10
    second = _draw_and_set(clock)
    clock.t += 10
    third = _draw_and_set(clock)

    assert sorted(first.parent.iterdir()) == sorted([second, third])


def test_quick_changes_keep_what_the_shell_may_still_be_fading_from(home):
    # smooth on and three skips inside the 420 ms fade: the shell still draws
    # the first one under the fade, reading it from disk.
    clock = Clock()
    paths_set = []
    for _ in range(4):
        paths_set.append(_draw_and_set(clock, smooth=True))
        clock.t += 0.1

    assert all(path.exists() for path in paths_set)


def test_once_things_settle_the_lingering_wallpapers_go(home):
    clock = Clock()
    quick = []
    for _ in range(3):
        quick.append(_draw_and_set(clock))
        clock.t += 0.1
    clock.t += 10

    latest = _draw_and_set(clock)

    assert sorted(latest.parent.iterdir()) == sorted([quick[-1], latest])


def test_a_missing_file_leaves_the_link_alone(home):
    theme_file = home / "theme-bg.jpg"
    theme_file.write_bytes(b"jpg")
    _link(home).symlink_to(theme_file)
    shell = Shell()

    with pytest.raises(OSError, match="does not exist"):
        omarchy_wallpaper.set_wallpaper(home / "nowhere.png", run=shell)

    assert os.readlink(_link(home)) == str(theme_file)
    assert shell.calls == []


def test_after_a_theme_background_our_old_files_go_and_the_theme_file_stays(home):
    stale = _wallpaper("old")
    theme_file = home / "theme-bg.jpg"
    theme_file.write_bytes(b"jpg")
    _link(home).symlink_to(theme_file)
    path = _wallpaper("new")

    omarchy_wallpaper.set_wallpaper(path, run=Shell())

    assert not stale.exists() and path.exists() and theme_file.exists()


def test_the_file_the_link_points_to_is_never_removed(home, monkeypatch):
    # Say the link was set to one of ours by someone else (or a crash between
    # the link and the cleanup): the lock screen and the boot read it.
    clock = Clock()
    pinned = _draw_and_set(clock)
    clock.t += 10
    other = _wallpaper("other")
    # Before the change the link pointed elsewhere (the theme); at cleanup
    # time it points to [pinned].
    reads = []

    def link_target(link):
        reads.append(link)
        return home / "theme-bg.jpg" if len(reads) == 1 else pinned.resolve()

    monkeypatch.setattr(omarchy_wallpaper, "_link_target", link_target)

    omarchy_wallpaper.set_wallpaper(other, run=Shell(), now=clock)

    assert pinned.exists()
