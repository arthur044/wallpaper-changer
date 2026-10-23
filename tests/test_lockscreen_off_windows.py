import pytest

from src.os_integration import lockscreen


@pytest.fixture(autouse=True)
def _linux(monkeypatch):
    monkeypatch.setattr(lockscreen.sys, "platform", "linux")


def test_off_windows_the_lock_screen_already_follows_the_wallpaper():
    assert lockscreen.follows_the_wallpaper() is True


def test_no_call_reaches_schtasks_or_uac(monkeypatch, tmp_path):
    # conftest.py turns any real schtasks or elevation into a failure; this
    # makes sure none of them is even attempted, nor the pending file written.
    monkeypatch.setattr(lockscreen, "data_dir", lambda: tmp_path)

    assert lockscreen.ensure_task() is True
    assert lockscreen.install_task() is True
    assert lockscreen.uninstall_task() is True
    assert lockscreen.is_task_installed() is False
    lockscreen.request_update(tmp_path / "wallpaper.png")
    lockscreen.apply_pending()

    assert list(tmp_path.iterdir()) == []


def test_on_windows_it_does_not(monkeypatch):
    monkeypatch.setattr(lockscreen.sys, "platform", "win32")

    assert lockscreen.follows_the_wallpaper() is False
