import pytest

from src.config.settings import Settings
from src.os_integration.tray import TrayApp
from src.utils.app_state import AppState

# pystray registers a window class named after id(icon) when an icon is built
# and unregisters it only when run() ends. These trays never run, so a freed
# tray's id can come back for a new one and Windows refuses the class with
# "Class already exists" (1410), now and then. Keeping every tray alive for the
# whole session keeps the ids unique. The app itself builds one tray per process.
_ALIVE = []
_build_tray = TrayApp.__init__


@pytest.fixture(autouse=True)
def _keep_trays_alive(monkeypatch):
    def init(self, *args, **kwargs):
        _build_tray(self, *args, **kwargs)
        _ALIVE.append(self)

    monkeypatch.setattr(TrayApp, "__init__", init)


def _tray(**callbacks):
    defaults = {
        "on_reauthenticate": lambda: None,
        "on_exit": lambda: None,
        "on_setup": lambda: None,
    }
    defaults.update(callbacks)
    return TrayApp(AppState(), Settings(), **defaults)


def test_pystray_setup_hook_is_not_shadowed_by_an_injected_callback():
    """Regression: storing the wizard callback as self._on_setup shadowed the
    _on_setup(self, icon) method that pystray invokes via run(setup=...),
    so the icon never became visible and its status colour never updated."""
    tray = _tray()

    # This is exactly how pystray calls the hook: setup(icon).
    tray._on_setup(tray._icon)

    assert tray._icon.visible is True


def test_second_setup_click_is_ignored_while_the_wizard_is_open():
    """Two concurrent Tk() roots on two threads is not a supported tkinter
    configuration, so a second click must not spawn a second wizard."""
    import threading

    release = threading.Event()
    started = []

    def blocking_wizard():
        started.append(1)
        release.wait(timeout=5.0)

    tray = _tray(on_setup=blocking_wizard)
    try:
        tray._setup(tray._icon, None)
        for _ in range(100):  # wait for the first wizard thread to actually start
            if started:
                break
            release.wait(timeout=0.01)

        tray._setup(tray._icon, None)  # second click, wizard still open

        assert started == [1]
    finally:
        release.set()


def test_setup_can_run_again_after_the_wizard_closes():
    calls = []
    tray = _tray(on_setup=lambda: calls.append(1))

    for _ in range(2):
        tray._setup(tray._icon, None)
        if tray._wizard_thread is not None:
            tray._wizard_thread.join(timeout=5.0)

    assert calls == [1, 1]


def test_setup_menu_item_runs_the_injected_callback():
    calls = []
    tray = _tray(on_setup=lambda: calls.append(1))

    tray._setup(tray._icon, None)

    # _setup spawns a daemon thread; give it a moment to run.
    for _ in range(100):
        if calls:
            break
        tray._app_state.stop_event.wait(timeout=0.01)

    assert calls == [1]


def _style_tray(monkeypatch, settings):
    from src.os_integration import tray as tray_module

    saved = []
    monkeypatch.setattr(tray_module, "save_settings", lambda s: saved.append(s))
    tray = TrayApp(AppState(), settings, on_reauthenticate=lambda: None, on_exit=lambda: None, on_setup=lambda: None)
    return tray, saved


def test_background_choice_is_saved_and_redraws_now(monkeypatch):
    settings = Settings()
    tray, saved = _style_tray(monkeypatch, settings)

    tray._set_background("mesh")

    assert settings.background_style == "mesh"
    assert saved == [settings]
    assert tray._app_state.force_sync_event.is_set()


def test_same_background_choice_does_nothing(monkeypatch):
    tray, saved = _style_tray(monkeypatch, Settings())

    tray._set_background("solid")

    assert saved == []
    assert not tray._app_state.force_sync_event.is_set()


def test_glow_and_glass_toggle_and_redraw(monkeypatch):
    settings = Settings()
    tray, saved = _style_tray(monkeypatch, settings)

    tray._toggle_glow(tray._icon, None)
    tray._toggle_glass(tray._icon, None)

    assert settings.art_glow is True
    assert settings.text_card == "glass"
    assert len(saved) == 2
    assert tray._app_state.force_sync_event.is_set()

    tray._toggle_glass(tray._icon, None)
    assert settings.text_card == "none"


class _FakeWidget:
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


def _lyrics_items(tray):
    menu = next(item for item in tray._build_menu().items if item.text == "Lyrics widget")
    return {item.text: item for item in menu.submenu.items}


def test_lyrics_widget_menu_runs_each_control_and_shows_its_state():
    widget = _FakeWidget()
    tray = _tray(lyrics_widget=widget)
    items = _lyrics_items(tray)

    assert [items[name].checked for name in ("Show", "Lock position", "Always on top")] == [False, False, False]
    for name in ("Show", "Lock position", "Always on top", "Reset position"):
        items[name](tray._icon)

    assert (widget.visible, widget.locked, widget.on_top, widget.resets) == (True, True, True, 1)
    assert [items[name].checked for name in ("Show", "Lock position", "Always on top")] == [True, True, True]


def test_without_a_lyrics_widget_its_menu_is_hidden():
    tray = _tray()
    menu = next(item for item in tray._build_menu().items if item.text == "Lyrics widget")
    assert menu.visible is False


def test_restart_runs_the_callback_and_closes_the_tray(monkeypatch):
    calls = []
    tray = TrayApp(
        AppState(),
        Settings(),
        on_reauthenticate=lambda: None,
        on_exit=lambda: None,
        on_setup=lambda: None,
        on_restart=lambda: calls.append("restart"),
    )
    stopped = []
    monkeypatch.setattr(tray._icon, "stop", lambda: stopped.append(1))

    tray._restart(tray._icon, None)

    assert calls == ["restart"]
    assert stopped == [1]



def test_blurred_background_is_a_background_choice(monkeypatch):
    settings = Settings()
    tray, saved = _style_tray(monkeypatch, settings)

    tray._set_background("blur")

    assert settings.background_style == "blur"
    assert saved == [settings]


def test_the_frame_is_a_three_way_choice(monkeypatch):
    settings = Settings()
    tray, saved = _style_tray(monkeypatch, settings)

    tray._set_frame("single")
    assert settings.art_frame == "single"
    assert tray._app_state.force_sync_event.is_set()

    tray._set_frame("double")
    tray._set_frame("none")
    assert settings.art_frame == "none"
    assert len(saved) == 3


def test_picking_the_current_frame_again_does_nothing(monkeypatch):
    tray, saved = _style_tray(monkeypatch, Settings())

    tray._set_frame("none")

    assert saved == []



def test_smooth_transition_toggles_and_redraws(monkeypatch):
    settings = Settings()
    tray, saved = _style_tray(monkeypatch, settings)

    tray._toggle_smooth(tray._icon, None)
    assert settings.smooth_transition is True
    assert tray._app_state.force_sync_event.is_set()

    tray._toggle_smooth(tray._icon, None)
    assert settings.smooth_transition is False
    assert len(saved) == 2



def test_blur_strength_levels_are_saved_and_redrawn(monkeypatch):
    settings = Settings()
    tray, saved = _style_tray(monkeypatch, settings)

    tray._set_blur_strength(60)
    assert settings.blur_strength == 60
    assert tray._app_state.force_sync_event.is_set()

    tray._set_blur_strength(60)
    assert len(saved) == 1, "picking the current level again changes nothing"
