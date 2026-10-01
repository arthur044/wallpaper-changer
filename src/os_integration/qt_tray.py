import logging
import queue
import threading
from typing import Callable, Optional

from PIL import Image
from PySide6.QtCore import QObject, QTimer, Signal
from PySide6.QtGui import QIcon, QImage, QPixmap
from PySide6.QtWidgets import QMenu, QSystemTrayIcon

logger = logging.getLogger(__name__)


def to_qicon(image: Image.Image) -> QIcon:
    rgba = image.convert("RGBA")
    data = rgba.tobytes("raw", "RGBA")
    # copy(): QImage doesn't own [data], which Python frees after this call.
    qimage = QImage(data, rgba.width, rgba.height, rgba.width * 4, QImage.Format_RGBA8888).copy()
    return QIcon(QPixmap.fromImage(qimage))


def _is_separator(item) -> bool:
    import pystray

    return item is pystray.Menu.SEPARATOR


class _Bridge(QObject):
    # Emitted from any thread; the default (auto) connection queues them to
    # the Qt thread, which owns the tray icon and its menu.
    image_changed = Signal(object)
    visibility_changed = Signal(bool)
    menu_changed = Signal()


class QtTrayIcon:
    """The tray icon on Linux, where the bar of Omarchy (Quickshell) shows
    StatusNotifierItems: QSystemTrayIcon registers one, pystray's backends
    don't (measured 2026-10-01). It takes pystray.Icon's place in TrayApp
    with the part of its interface TrayApp uses (icon, visible, update_menu,
    run, stop), so the menu stays the very pystray.Menu Windows shows.

    Build it on the Qt thread; the rest may be called from any thread. Menu
    clicks run off the Qt thread, one after another on a single worker, as
    pystray runs them on its loop: Restart waits for the poller and must not
    block Qt, and Restart then Exit must not run at once."""

    def __init__(self, name: str, icon: Image.Image, title: Optional[str] = None, menu=None) -> None:
        self.name = name
        self._menu_spec = menu
        self._stopped = threading.Event()
        self._clicks: queue.Queue = queue.Queue()
        self._worker: Optional[threading.Thread] = None
        if not QSystemTrayIcon.isSystemTrayAvailable():
            # Then nothing shows the icon, and its menu is the only way to Exit.
            logger.warning("No system tray to show the icon in (no StatusNotifierItem host?)")
        self._tray = QSystemTrayIcon()
        if title:
            self._tray.setToolTip(title)
        self._qmenu = QMenu()
        self._qmenu.aboutToShow.connect(self._rebuild_menu)
        self._tray.setContextMenu(self._qmenu)
        self._tray.activated.connect(self._activated)
        self._bridge = _Bridge()
        self._bridge.image_changed.connect(lambda image: self._tray.setIcon(to_qicon(image)))
        self._bridge.visibility_changed.connect(self._tray.setVisible)
        self._bridge.menu_changed.connect(self._rebuild_menu)
        self._icon = icon
        self._visible = False
        self._bridge.image_changed.emit(icon)
        # Not now: like pystray, the menu's callables are read later, and the
        # TrayApp building this icon isn't finished yet.
        QTimer.singleShot(0, self._rebuild_menu)

    # --- pystray.Icon's interface, any thread --------------------------------

    @property
    def icon(self) -> Image.Image:
        return self._icon

    @icon.setter
    def icon(self, image: Image.Image) -> None:
        self._icon = image
        self._bridge.image_changed.emit(image)

    @property
    def visible(self) -> bool:
        return self._visible

    @visible.setter
    def visible(self, value: bool) -> None:
        self._visible = value
        self._bridge.visibility_changed.emit(value)

    def update_menu(self) -> None:
        self._bridge.menu_changed.emit()

    def run(self, setup: Optional[Callable] = None) -> None:
        """Blocks until stop(), like pystray's loop; Qt does the drawing."""
        if setup is not None:
            setup(self)
        else:
            self.visible = True
        self._stopped.wait()

    def stop(self) -> None:
        self.visible = False
        self._stopped.set()

    # --- Qt thread ------------------------------------------------------------

    def _rebuild_menu(self) -> None:
        """From the pystray.Menu as it stands now: labels, visibility and
        enabled state are callables there."""
        self._qmenu.clear()
        if self._menu_spec is None:
            return
        for item in self._menu_spec:  # visible items only
            if _is_separator(item):
                self._qmenu.addSeparator()
                continue
            if item.submenu is not None:
                # Not drawn here yet: say so rather than show an item that does nothing.
                logger.warning("Tray submenu %r is not supported on this platform; left out", item.text)
                continue
            action = self._qmenu.addAction(item.text)
            action.setEnabled(item.enabled)
            if item.checked is not None:
                action.setCheckable(True)
                action.setChecked(item.checked)
            action.triggered.connect(lambda _checked=False, item=item: self._click(item))

    def _activated(self, reason) -> None:
        # A left click runs the default item (Settings...), as on Windows.
        if reason == QSystemTrayIcon.ActivationReason.Trigger and self._menu_spec is not None:
            self._in_background(lambda: self._menu_spec(self))

    def _click(self, item) -> None:
        self._in_background(lambda: item(self))

    def _in_background(self, fn: Callable[[], None]) -> None:
        self._clicks.put(fn)
        if self._worker is None:
            self._worker = threading.Thread(target=self._run_clicks, daemon=True, name="tray-clicks")
            self._worker.start()

    def _run_clicks(self) -> None:
        while True:
            fn = self._clicks.get()
            try:
                fn()
            except Exception:  # noqa: BLE001 - a failing menu action must not go unseen
                logger.exception("Tray menu action failed")
