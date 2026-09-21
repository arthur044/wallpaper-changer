from src.config.settings import Settings
from src.os_integration.tray import TrayApp
from src.utils.app_state import AppState


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
