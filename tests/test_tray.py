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



def test_running_version_is_in_the_tooltip():
    tray = TrayApp(
        AppState(),
        Settings(),
        on_reauthenticate=lambda: None,
        on_exit=lambda: None,
        on_setup=lambda: None,
        version="f93217c (2026-09-29)",
    )

    assert tray._icon.title == "Spotify Wallpaper Engine - f93217c (2026-09-29)"


def test_without_a_version_the_tray_keeps_its_plain_title():
    tray = _tray()

    assert tray._icon.title == "Spotify Wallpaper Engine"


class _InlineUpdater:
    """Stands in for Updater: one update available, applied on request."""

    def __init__(self):
        from src.os_integration.updater import Action, ApplyResult, Decision

        self._decision = Decision(Action.UPDATE, target="bbbbbbb", commits=2)
        self._result = ApplyResult(self._decision, restart=True)

    def check(self):
        return self._decision

    def apply(self):
        return self._result


def test_lock_sync_stays_on_when_its_task_cannot_be_removed(monkeypatch):
    from src.os_integration import tray as tray_module

    monkeypatch.setattr(tray_module.lockscreen, "uninstall_task", lambda: False)
    saved = []
    monkeypatch.setattr(tray_module, "save_settings", lambda s: saved.append(s))
    settings = Settings(sync_lock_screen=True)
    tray = TrayApp(AppState(), settings, on_reauthenticate=lambda: None, on_exit=lambda: None, on_setup=lambda: None)

    tray._apply_lock_sync_toggle()

    assert settings.sync_lock_screen is True
    assert saved == []


def test_lock_sync_turns_off_once_its_task_is_gone(monkeypatch):
    from src.os_integration import tray as tray_module

    monkeypatch.setattr(tray_module.lockscreen, "uninstall_task", lambda: True)
    monkeypatch.setattr(tray_module, "save_settings", lambda s: None)
    settings = Settings(sync_lock_screen=True)
    tray = TrayApp(AppState(), settings, on_reauthenticate=lambda: None, on_exit=lambda: None, on_setup=lambda: None)

    tray._apply_lock_sync_toggle()

    assert settings.sync_lock_screen is False


def test_a_left_click_on_the_icon_opens_the_settings_window():
    opened = []
    tray = TrayApp(
        AppState(), Settings(), on_reauthenticate=lambda: None, on_exit=lambda: None, on_setup=lambda: None,
        on_open_settings=lambda: opened.append(1),
    )

    item = next(i for i in tray._build_menu().items if i.default)
    item(tray._icon)

    assert item.text == "Settings..." and opened == [1]


def _commands_tray(monkeypatch, **kwargs):
    tray = TrayApp(
        AppState(), Settings(), on_reauthenticate=lambda: None, on_exit=lambda: None, on_setup=lambda: None, **kwargs
    )
    stopped = []
    monkeypatch.setattr(tray._icon, "stop", lambda: stopped.append(1))
    return tray, stopped


def _join_background_threads():
    import threading

    for thread in threading.enumerate():
        if thread.name in ("restart", "exit"):
            thread.join(timeout=5.0)


def test_the_menu_is_only_open_pause_reauthenticate_and_exit():
    tray = _tray()

    shown = [i.text for i in tray._build_menu().items if i.visible and i.text and not i.text.startswith("-")]
    assert shown == ["Settings...", "Pause", "Exit"]


def test_reauthenticate_shows_in_the_menu_only_on_an_error():
    tray = _tray()
    item = next(i for i in tray._build_menu().items if i.text == "Re-authenticate")
    assert item.visible is False

    tray._app_state.set_error("expired")

    assert item.visible is True
    assert tray.commands().needs_reauthentication() is True


def test_commands_pause_and_sync_reach_the_app_state():
    tray = _tray()
    commands = tray.commands()

    commands.toggle_pause()
    assert commands.is_paused() is True
    commands.force_sync()

    assert tray._app_state.force_sync_event.is_set()


def test_commands_restart_runs_off_the_calling_thread_and_closes_the_tray(monkeypatch):
    import threading

    calls = []
    tray, stopped = _commands_tray(monkeypatch, on_restart=lambda: calls.append(threading.current_thread().name))

    tray.commands().restart()
    _join_background_threads()

    assert calls == ["restart"] and stopped == [1]


def test_commands_exit_stops_the_app_and_the_tray(monkeypatch):
    exits = []
    tray, stopped = _commands_tray(monkeypatch)
    tray._on_exit = lambda: exits.append(1)

    tray.commands().exit()
    _join_background_threads()

    assert exits == [1] and stopped == [1] and tray._app_state.stop_event.is_set()


def test_commands_carry_the_version_and_whether_there_is_an_updater():
    plain = _tray().commands()
    assert plain.version == "" and plain.has_updater() is False and plain.update_label() == ""

    tray = TrayApp(
        AppState(), Settings(), on_reauthenticate=lambda: None, on_exit=lambda: None, on_setup=lambda: None,
        version="f93217c (2026-09-29)", updater=_InlineUpdater(),
    )
    commands = tray.commands()
    assert commands.version == "f93217c (2026-09-29)"
    assert commands.has_updater() is True and commands.update_label() == "Check for updates"


def test_commands_update_checks_then_applies_through_restart(monkeypatch):
    calls = []
    tray, stopped = _commands_tray(monkeypatch, on_restart=lambda: calls.append("restart"), updater=_InlineUpdater())
    monkeypatch.setattr(tray._update_menu, "_spawn", lambda fn: fn())
    commands = tray.commands()

    commands.update_click()
    assert commands.update_label() == "Update to bbbbbbb (2 commits)" and calls == []
    commands.update_click()

    assert calls == ["restart"] and stopped == [1]


def test_exit_in_the_menu_is_blocked_while_an_update_is_applied(monkeypatch):
    tray, _ = _commands_tray(monkeypatch, updater=_InlineUpdater())
    pending = []
    monkeypatch.setattr(tray._update_menu, "_spawn", pending.append)
    commands = tray.commands()
    exit_item = next(i for i in tray._build_menu().items if i.text == "Exit")

    commands.update_click()
    assert commands.updating() is False and exit_item.enabled is True  # a check is harmless
    pending.pop()()  # the check finds the update

    commands.update_click()
    assert commands.updating() is True and exit_item.enabled is False


def test_a_second_lock_sync_click_is_ignored_while_the_first_is_pending(monkeypatch):
    import threading
    import time

    from src.os_integration import tray as tray_module

    release, started = threading.Event(), []

    def slow_install():
        started.append(1)
        release.wait(timeout=5.0)
        return True

    monkeypatch.setattr(tray_module.lockscreen, "ensure_task", slow_install)
    monkeypatch.setattr(tray_module, "save_settings", lambda s: None)
    tray = TrayApp(AppState(), Settings(), on_reauthenticate=lambda: None, on_exit=lambda: None, on_setup=lambda: None)
    monkeypatch.setattr(tray._icon, "update_menu", lambda: None)
    commands = tray.commands()
    try:
        commands.toggle_lock_sync()
        for _ in range(200):
            if started:
                break
            release.wait(timeout=0.01)
        assert commands.lock_sync_busy() is True

        commands.toggle_lock_sync()  # the UAC prompt is still open

        assert started == [1]
    finally:
        release.set()
    for _ in range(500):
        if not commands.lock_sync_busy():
            break
        time.sleep(0.01)
    assert commands.lock_sync_busy() is False and tray._settings.sync_lock_screen is True


@pytest.mark.parametrize(
    "start, uninstall_ok, ensure_ok",
    [(True, True, None), (True, False, None), (False, None, True), (False, None, False)],
    ids=["turned-off", "removal-declined", "turned-on", "install-declined"],
)
def test_lock_sync_refreshes_the_menu_once_the_slow_toggle_finishes(monkeypatch, start, uninstall_ok, ensure_ok):
    # pystray rebuilds the menu right after the click handler returns. The toggle
    # runs on a thread (UAC), so that rebuild sees the old state and the check
    # mark stays stale until something else refreshes it: the user clicks again
    # and undoes the change.
    from src.os_integration import tray as tray_module

    monkeypatch.setattr(tray_module.lockscreen, "uninstall_task", lambda: uninstall_ok)
    monkeypatch.setattr(tray_module.lockscreen, "ensure_task", lambda: ensure_ok)
    monkeypatch.setattr(tray_module, "save_settings", lambda s: None)
    settings = Settings(sync_lock_screen=start)
    tray = TrayApp(AppState(), settings, on_reauthenticate=lambda: None, on_exit=lambda: None, on_setup=lambda: None)
    seen = []

    class _Icon:
        def update_menu(self):
            seen.append(settings.sync_lock_screen)

    tray._icon = _Icon()

    tray._apply_lock_sync_toggle()

    expected = (not start) if (uninstall_ok or ensure_ok) else start
    assert seen == [expected]
