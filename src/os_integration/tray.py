import logging
import os
import sys
import threading
from typing import Callable, Optional, Tuple

if sys.platform != "win32":
    # Off Windows the icon is drawn by Qt (qt_tray.QtTrayIcon) and pystray only
    # describes the menu. Left to choose, it would load a backend at import
    # that needs GTK/AppIndicator or an X display, and fail without them.
    os.environ.setdefault("PYSTRAY_BACKEND", "dummy")

import pystray  # noqa: E402 - after the backend is chosen
from PIL import Image, ImageDraw  # noqa: E402

from src.config.settings import Settings, save_settings
from src.os_integration import lockscreen
from src.os_integration.update_menu import UpdateMenu
from src.os_integration.updater import Updater
from src.settings_window.commands import AppCommands
from src.utils.app_state import AppState, AppStatus

logger = logging.getLogger(__name__)

_ICON_COLORS = {
    AppStatus.RUNNING: (30, 215, 96),
    AppStatus.IDLE: (120, 120, 120),
    AppStatus.PAUSED: (240, 180, 40),
    AppStatus.ERROR: (220, 50, 50),
}


def _build_icon_image(color: Tuple[int, int, int]) -> Image.Image:
    image = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.ellipse((4, 4, 60, 60), fill=color)
    return image


class TrayApp:
    def __init__(
        self,
        app_state: AppState,
        settings: Settings,
        on_reauthenticate: Callable[[], None],
        on_exit: Callable[[], None],
        on_setup: Callable[[], None],
        on_restart: Callable[[], None] = lambda: None,
        version: str = "",
        updater: Optional[Updater] = None,
        on_open_settings: Callable[[], None] = lambda: None,
        icon_factory: Optional[Callable] = None,
    ):
        self._app_state = app_state
        self._settings = settings
        self._on_reauthenticate = on_reauthenticate
        self._on_exit = on_exit
        # Deliberately not named _on_setup: that name belongs to the pystray
        # setup hook below, and an instance attribute would shadow it.
        self._launch_wizard = on_setup
        self._on_restart = on_restart
        self._open_settings = on_open_settings
        self._lock_sync_busy = False
        self._version = version
        self._wizard_thread: Optional[threading.Thread] = None
        # pystray.Icon, or anything with its interface (qt_tray.QtTrayIcon).
        self._icon = (icon_factory or pystray.Icon)(
            "spotify_wallpaper_engine",
            _build_icon_image(_ICON_COLORS[AppStatus.RUNNING]),
            f"Spotify Wallpaper Engine - {version}" if version else "Spotify Wallpaper Engine",
            menu=self._build_menu(),
        )
        self._update_menu: Optional[UpdateMenu] = None
        if updater is not None:
            self._update_menu = UpdateMenu(
                updater,
                # Same path as the Restart item: stop the poller, relaunch, close the tray.
                on_restart=lambda: self._restart(self._icon, None),
                refresh=self._refresh_icon,
            )

    def _build_menu(self) -> pystray.Menu:
        # The rest of the controls live in the settings window: a menu closes
        # after every click.
        return pystray.Menu(
            # default=True: a left click on the icon opens it too.
            pystray.MenuItem("Settings...", lambda icon, item: self._open_settings(), default=True),
            pystray.MenuItem(self._pause_label, self._toggle_pause),
            pystray.MenuItem(
                "Re-authenticate",
                self._reauthenticate,
                visible=lambda item: self._needs_reauthentication(),
            ),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Exit", self._exit, enabled=lambda item: not self._updating()),
        )

    def commands(self) -> AppCommands:
        """What the settings window can ask of the app, tied to this tray."""
        return AppCommands(
            version=self._version,
            is_paused=self._app_state.is_paused,
            toggle_pause=lambda: self._toggle_pause(self._icon, None),
            force_sync=lambda: self._force_sync(self._icon, None),
            lock_sync_busy=lambda: self._lock_sync_busy,
            toggle_lock_sync=lambda: self._toggle_lock_sync(self._icon, None),
            has_updater=lambda: self._update_menu is not None,
            update_label=lambda: self._update_menu.label() if self._update_menu else "",
            update_click=lambda: self._update_menu.click() if self._update_menu else None,
            updating=self._updating,
            needs_reauthentication=self._needs_reauthentication,
            reauthenticate=lambda: self._reauthenticate(self._icon, None),
            setup=lambda: self._setup(self._icon, None),
            restart=lambda: self._in_background(lambda: self._restart(self._icon, None), "restart"),
            exit=lambda: self._in_background(lambda: self._exit(self._icon, None), "exit"),
        )

    @staticmethod
    def _in_background(fn: Callable[[], None], name: str) -> None:
        # Restart waits for the poller to finish its render: not on the Qt thread.
        threading.Thread(target=fn, daemon=True, name=name).start()

    def _needs_reauthentication(self) -> bool:
        return self._app_state.snapshot().status == AppStatus.ERROR

    def _pause_label(self, item) -> str:
        return "Resume" if self._app_state.is_paused() else "Pause"

    def _toggle_pause(self, icon, item) -> None:
        self._app_state.toggle_pause()
        self._refresh_icon()

    def _force_sync(self, icon, item) -> None:
        self._app_state.force_sync_event.set()

    def _updating(self) -> bool:
        return self._update_menu is not None and self._update_menu.is_applying()

    def _restart(self, icon, item) -> None:
        self._on_restart()
        icon.stop()

    def _reauthenticate(self, icon, item) -> None:
        threading.Thread(target=self._on_reauthenticate, daemon=True).start()

    def _setup(self, icon, item) -> None:
        # Off the tray thread: the wizard owns its own Tk mainloop and would
        # otherwise block the tray's message pump for as long as it's open.
        # One at a time, though - two concurrent Tk() roots on two threads is
        # not a supported tkinter configuration and can take the process down.
        if self._wizard_thread is not None and self._wizard_thread.is_alive():
            logger.info("Setup wizard is already open, ignoring the second request")
            return

        self._wizard_thread = threading.Thread(target=self._launch_wizard, daemon=True, name="wizard")
        self._wizard_thread.start()

    def _toggle_lock_sync(self, icon, item) -> None:
        if self._lock_sync_busy:  # the UAC prompt is still open: one change at a time
            return
        self._lock_sync_busy = True
        threading.Thread(target=self._apply_lock_sync_toggle, daemon=True).start()

    def _apply_lock_sync_toggle(self) -> None:
        try:
            self._flip_lock_sync()
        finally:
            self._lock_sync_busy = False
            # pystray rebuilds the menu when the click handler returns, which
            # here is long before the UAC prompt is answered: without this the
            # check mark shows the old state and the next click undoes the change.
            try:
                self._icon.update_menu()
            except Exception:  # noqa: BLE001 - the icon may be stopped already (Exit during the UAC prompt)
                # Not swallowed silently, and never in place of _flip_lock_sync's own error.
                logger.debug("Could not refresh the tray menu after the lock screen toggle", exc_info=True)

    def _flip_lock_sync(self) -> None:
        if self._settings.sync_lock_screen:
            if not lockscreen.uninstall_task():
                # The task is still registered and runs elevated at logon, so
                # the menu keeps showing it on rather than claiming it's gone.
                logger.warning("Lock screen sync left on: the task could not be removed (UAC declined?)")
                return
            self._settings.sync_lock_screen = False
            save_settings(self._settings)
            return

        if lockscreen.ensure_task():
            self._settings.sync_lock_screen = True
            save_settings(self._settings)
        else:
            logger.warning("Lock screen sync not enabled: task installation was declined or failed")

    def _exit(self, icon, item) -> None:
        self._app_state.stop_event.set()
        self._app_state.force_sync_event.set()
        self._on_exit()
        icon.stop()

    def _refresh_icon(self) -> None:
        status = self._app_state.snapshot().status
        self._icon.icon = _build_icon_image(_ICON_COLORS[status])
        self._icon.update_menu()

    def run(self) -> None:
        # Blocking. On Windows pystray pumps its messages on whichever thread
        # calls this, so it runs on its own thread: the main one belongs to Qt.
        self._icon.run(setup=self._on_setup)

    def stop(self) -> None:
        self._icon.stop()

    def _on_setup(self, icon: pystray.Icon) -> None:
        icon.visible = True
        self._watch_status()
        if self._update_menu is not None:
            self._update_menu.check_silently()

    def _watch_status(self) -> None:
        def loop() -> None:
            last_status = None
            while not self._app_state.stop_event.is_set():
                status = self._app_state.snapshot().status
                if status != last_status:
                    self._refresh_icon()
                    last_status = status
                self._app_state.stop_event.wait(timeout=1.0)

        threading.Thread(target=loop, daemon=True).start()
