import os

os.environ["QT_QPA_PLATFORM"] = "offscreen"

import pytest
from PySide6.QtWidgets import QApplication, QLabel, QPushButton

from src.config.settings import Settings
from src.os_integration import lockscreen
from src.settings_window.commands import AppCommands
from src.settings_window.style_actions import StyleActions
from src.settings_window.window import SettingsWindow


@pytest.fixture(scope="module")
def app():
    return QApplication.instance() or QApplication([])


class _Lyrics:
    def __init__(self):
        self.visible = self.locked = self.on_top = False
        self.resets = 0

    def toggle_widget(self):
        self.visible = not self.visible

    def is_widget_visible(self):
        return self.visible

    def toggle_locked(self):
        self.locked = not self.locked

    def is_locked(self):
        return self.locked

    def toggle_on_top(self):
        self.on_top = not self.on_top

    def is_on_top(self):
        return self.on_top

    def reset_position(self):
        self.resets += 1


class _App:
    """Records what the window asks of the app; the state it reports is set by the test."""

    def __init__(self):
        self.calls = []
        self.paused = False
        self.lock_busy = False
        self.has_updater = True
        self.label = "Check for updates"
        self.updating = False
        self.reauth = False

    def commands(self):
        def record(name):
            return lambda: self.calls.append(name)

        return AppCommands(
            version="abc123",
            is_paused=lambda: self.paused,
            toggle_pause=record("pause"),
            force_sync=record("sync"),
            lock_sync_busy=lambda: self.lock_busy,
            toggle_lock_sync=record("lock"),
            has_updater=lambda: self.has_updater,
            update_label=lambda: self.label,
            update_click=record("update"),
            updating=lambda: self.updating,
            needs_reauthentication=lambda: self.reauth,
            reauthenticate=record("reauth"),
            setup=record("setup"),
            restart=record("restart"),
            exit=record("exit"),
        )


def _window(app, **fields):
    settings = Settings(**fields)
    saved, redraws = [], []
    style = StyleActions(settings, saved.append, lambda: redraws.append(1))
    lyrics, fake_app = _Lyrics(), _App()
    window = SettingsWindow(settings, style, lyrics, fake_app.commands())
    return window, settings, saved, redraws, lyrics, fake_app


def _button(window, text):
    return next(b for b in window.findChildren(QPushButton) if b.text() == text)


def test_it_shows_the_current_settings(app):
    window, *_ = _window(app, background_style="mesh", art_frame="single", art_glow=True, blur_strength=50)

    assert window._backgrounds["mesh"].isChecked()
    assert window._frames["single"].isChecked()
    assert window._glow.isChecked() and not window._glass.isChecked()
    assert window._blur.currentData() == 50


def test_picking_a_background_saves_and_redraws(app):
    window, settings, saved, redraws, *_ = _window(app)

    window._backgrounds["blur"].click()

    assert settings.background_style == "blur"
    assert saved == [settings] and redraws == [1]


def test_the_checkboxes_and_the_frame_and_blur_write_through(app):
    window, settings, saved, *_ = _window(app)

    window._glow.click()
    window._glass.click()
    window._smooth.click()
    window._frames["double"].click()
    window._blur.setCurrentIndex(3)
    window._blur.activated.emit(3)

    assert (settings.art_glow, settings.text_card, settings.smooth_transition) == (True, "glass", True)
    assert (settings.art_frame, settings.blur_strength) == ("double", 100)
    assert len(saved) == 5


def test_refresh_does_not_count_as_a_user_change(app):
    window, settings, saved, redraws, *_ = _window(app)
    settings.background_style = "mesh"
    settings.art_glow = True

    window.refresh()

    assert window._backgrounds["mesh"].isChecked() and window._glow.isChecked()
    assert saved == [] and redraws == []


def test_a_blur_value_outside_the_presets_stays_selectable(app):
    window, settings, *_ = _window(app, blur_strength=37)

    assert window._blur.currentData() == 37
    settings.blur_strength = 10
    window.refresh()
    assert window._blur.currentData() == 10


def test_the_lyrics_controls_drive_the_widget_and_show_its_state(app, windows):
    window, _s, _sv, _r, lyrics, _app = _window(app)

    for box in (window._lyrics_show, window._lyrics_locked, window._lyrics_on_top):
        box.click()
    _button(window, "Reset position").click()

    assert (lyrics.visible, lyrics.locked, lyrics.on_top, lyrics.resets) == (True, True, True, 1)
    assert all(b.isChecked() for b in (window._lyrics_show, window._lyrics_locked, window._lyrics_on_top))


@pytest.fixture
def windows(monkeypatch):
    # The lock screen toggle (schtasks, UAC) exists on Windows only.
    monkeypatch.setattr(lockscreen.sys, "platform", "win32")


def test_off_windows_the_lock_screen_box_explains_instead_of_offering_a_toggle(app, monkeypatch):
    monkeypatch.setattr(lockscreen.sys, "platform", "linux")
    window, _s, _sv, _r, _l, fake_app = _window(app)

    window.refresh()

    assert window._lock_sync is None
    assert any("already shows the wallpaper" in label.text() for label in window.findChildren(QLabel))
    assert "lock" not in fake_app.calls


def test_the_lock_screen_box_shows_what_happened_not_what_was_clicked(app, windows):
    # The UAC prompt was declined: the app did nothing, so the box goes back off.
    window, settings, _sv, _r, _l, fake_app = _window(app)

    window._lock_sync.click()

    assert fake_app.calls == ["lock"]
    assert settings.sync_lock_screen is False and not window._lock_sync.isChecked()


def test_the_lock_screen_box_is_disabled_while_the_change_is_pending(app, windows):
    window, _s, _sv, _r, _l, fake_app = _window(app)
    fake_app.lock_busy = True

    window.refresh()

    assert not window._lock_sync.isEnabled()


def test_a_lock_screen_change_finished_later_shows_up_on_the_next_refresh(app, windows):
    window, settings, _sv, _r, _l, fake_app = _window(app)
    window._lock_sync.click()  # the UAC prompt is open: nothing has changed yet

    settings.sync_lock_screen = True  # accepted, on the tray's thread
    window.refresh()

    assert window._lock_sync.isChecked()


def test_playback_buttons_and_the_pause_label(app):
    window, *_rest, fake_app = _window(app)
    assert _button(window, "Pause")

    _button(window, "Pause").click()
    _button(window, "Sync now").click()
    fake_app.paused = True
    window.refresh()

    assert fake_app.calls == ["pause", "sync"] and window._pause.text() == "Resume"


def test_the_app_buttons_run_their_commands(app):
    window, *_rest, fake_app = _window(app)

    for text in ("Check for updates", "Re-authenticate with Spotify", "Setup...", "Restart", "Exit"):
        _button(window, text).click()

    assert fake_app.calls == ["update", "reauth", "setup", "restart", "exit"]


def test_the_update_and_reauthenticate_buttons_show_only_when_they_apply(app):
    window, *_rest, fake_app = _window(app)
    window.show()
    assert window._update.isVisible() and not window._reauth.isVisible()

    fake_app.has_updater, fake_app.reauth = False, True
    window.refresh()
    assert not window._update.isVisible() and window._reauth.isVisible()

    fake_app.has_updater, fake_app.label = True, "Updating..."
    window.refresh()
    assert window._update.text() == "Updating..."
    window.hide()


def test_restart_and_exit_are_disabled_while_an_update_is_applied(app):
    window, *_rest, fake_app = _window(app)
    assert window._restart.isEnabled() and window._exit.isEnabled()

    fake_app.updating = True
    window.refresh()

    assert not window._restart.isEnabled() and not window._exit.isEnabled()


def test_the_window_refreshes_itself_only_while_it_is_visible(app):
    window, *_ = _window(app)

    assert not window._timer.isActive()
    window.show()
    assert window._timer.isActive()
    window.hide()
    assert not window._timer.isActive()


def test_on_linux_always_on_top_is_explained_not_offered(app, monkeypatch):
    # Hyprland keeps a floating window above the tiled ones: there is no "behind".
    monkeypatch.setattr(lockscreen.sys, "platform", "linux")
    window, _s, _sv, _r, lyrics, _app = _window(app)

    window._lyrics_show.click()
    window._lyrics_locked.click()
    window.refresh()

    assert window._lyrics_on_top is None
    assert any("can't stay behind" in label.text() for label in window.findChildren(QLabel))
    assert (lyrics.visible, lyrics.locked, lyrics.on_top) == (True, True, False)
