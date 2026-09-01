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
