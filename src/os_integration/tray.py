import logging
import threading
from typing import Callable, Optional, Tuple

import pystray
from PIL import Image, ImageDraw

from src.config.settings import Settings, save_settings
from src.os_integration import lockscreen
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
    ):
        self._app_state = app_state
        self._settings = settings
        self._on_reauthenticate = on_reauthenticate
        self._on_exit = on_exit
        # Deliberately not named _on_setup: that name belongs to the pystray
        # setup hook below, and an instance attribute would shadow it.
        self._launch_wizard = on_setup
        self._on_restart = on_restart
        self._wizard_thread: Optional[threading.Thread] = None
        self._icon = pystray.Icon(
            "spotify_wallpaper_engine",
            _build_icon_image(_ICON_COLORS[AppStatus.RUNNING]),
            "Spotify Wallpaper Engine",
            menu=self._build_menu(),
        )

    def _build_menu(self) -> pystray.Menu:
        return pystray.Menu(
            pystray.MenuItem(self._pause_label, self._toggle_pause),
            pystray.MenuItem("Force Sync", self._force_sync),
            pystray.MenuItem("Style", self._build_style_menu()),
            pystray.MenuItem(
                "Sync Lock Screen",
                self._toggle_lock_sync,
                checked=lambda item: self._settings.sync_lock_screen,
            ),
            pystray.MenuItem(
                "Re-authenticate",
                self._reauthenticate,
                visible=lambda item: self._app_state.snapshot().status == AppStatus.ERROR,
            ),
            pystray.MenuItem("Setup...", self._setup),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Restart", self._restart),
            pystray.MenuItem("Exit", self._exit),
        )

    def _build_style_menu(self) -> pystray.Menu:
        return pystray.Menu(
            pystray.MenuItem(
                "Solid background",
                lambda icon, item: self._set_background("solid"),
                checked=lambda item: self._settings.background_style == "solid",
                radio=True,
            ),
            pystray.MenuItem(
                "Mesh background",
                lambda icon, item: self._set_background("mesh"),
                checked=lambda item: self._settings.background_style == "mesh",
                radio=True,
            ),
            pystray.MenuItem(
                "Blurred art background",
                lambda icon, item: self._set_background("blur"),
                checked=lambda item: self._settings.background_style == "blur",
                radio=True,
            ),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Art glow", self._toggle_glow, checked=lambda item: self._settings.art_glow),
            pystray.MenuItem("Glass frame", self._toggle_frame, checked=lambda item: self._settings.art_frame),
            pystray.MenuItem("Glass card", self._toggle_glass, checked=lambda item: self._settings.text_card == "glass"),
        )

    def _pause_label(self, item) -> str:
        return "Resume" if self._app_state.is_paused() else "Pause"

    def _toggle_pause(self, icon, item) -> None:
        self._app_state.toggle_pause()
        self._refresh_icon()

    def _force_sync(self, icon, item) -> None:
        self._app_state.force_sync_event.set()

    def _set_background(self, style: str) -> None:
        if self._settings.background_style == style:
            return
        self._settings.background_style = style
        self._apply_style_change()

    def _toggle_glow(self, icon, item) -> None:
        self._settings.art_glow = not self._settings.art_glow
        self._apply_style_change()

    def _toggle_frame(self, icon, item) -> None:
        self._settings.art_frame = not self._settings.art_frame
        self._apply_style_change()

    def _toggle_glass(self, icon, item) -> None:
        self._settings.text_card = "none" if self._settings.text_card == "glass" else "glass"
        self._apply_style_change()

    def _apply_style_change(self) -> None:
        # The render reads this same Settings object, and the base cache key
        # includes the style, so a forced redraw is all it takes to show it.
        save_settings(self._settings)
        self._app_state.force_sync_event.set()

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
        threading.Thread(target=self._apply_lock_sync_toggle, daemon=True).start()

    def _apply_lock_sync_toggle(self) -> None:
        if self._settings.sync_lock_screen:
            lockscreen.uninstall_task()
            self._settings.sync_lock_screen = False
            save_settings(self._settings)
            return

        if lockscreen.is_task_installed() or lockscreen.install_task():
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
        # Blocking call — must run on the main thread (Win32 message loop requirement on Windows).
        self._icon.run(setup=self._on_setup)

    def _on_setup(self, icon: pystray.Icon) -> None:
        icon.visible = True
        self._watch_status()

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
