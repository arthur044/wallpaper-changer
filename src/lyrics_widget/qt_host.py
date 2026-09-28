import logging
import threading
from typing import Callable, Optional

from PySide6.QtCore import QObject, QRectF, Qt, Signal
from PySide6.QtGui import QColor, QPainter, QPainterPath
from PySide6.QtWidgets import QApplication, QWidget

from src.lyrics_widget.geometry import DEFAULT_SIZE, default_position

logger = logging.getLogger(__name__)

_CORNER_RADIUS = 18
_BACKGROUND = QColor(18, 18, 18, 190)


class LyricsWindow(QWidget):
    """The widget's window: frameless, no taskbar button (Qt.Tool), with a
    per-pixel translucent rounded background. Empty for now."""

    def __init__(self) -> None:
        super().__init__(None, Qt.FramelessWindowHint | Qt.Tool)
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.setAttribute(Qt.WA_ShowWithoutActivating)
        self.resize(*DEFAULT_SIZE)

    def move_to_default_position(self) -> None:
        screen = QApplication.primaryScreen()
        if screen is None:
            return
        area = screen.availableGeometry()
        self.move(*default_position((area.x(), area.y(), area.width(), area.height()), (self.width(), self.height())))

    def paintEvent(self, event) -> None:  # noqa: N802 - Qt override
        painter = QPainter(self)
        painter.setRenderHint(QPainter.Antialiasing)
        path = QPainterPath()
        path.addRoundedRect(QRectF(self.rect()), _CORNER_RADIUS, _CORNER_RADIUS)
        painter.fillPath(path, _BACKGROUND)


class _Bridge(QObject):
    # Emitted from the tray thread; the default (auto) connection queues them
    # to the Qt thread, which owns every widget.
    visibility_requested = Signal(bool)
    quit_requested = Signal()


class QtHost:
    """Owns the QApplication on the main thread, where Qt requires it to live.
    The tray runs on its own thread and reaches Qt only through the signals of
    [_Bridge], so no widget is touched off the Qt thread.

    Create it before anything calls SetProcessDpiAwareness (the first render
    does, in layout.py): Qt sets per-monitor v2 awareness on startup and can't
    once the process already has a mode."""

    def __init__(self) -> None:
        self._app = QApplication.instance() or QApplication([])
        # The widget is the only window; hiding it must not end the app.
        self._app.setQuitOnLastWindowClosed(False)
        self._window: Optional[LyricsWindow] = None
        self._visible = False
        self._lock = threading.Lock()
        self._bridge = _Bridge()
        self._bridge.visibility_requested.connect(self._apply_visibility)
        self._bridge.quit_requested.connect(self._app.quit)

    # --- any thread ---------------------------------------------------------

    def is_widget_visible(self) -> bool:
        with self._lock:
            return self._visible

    def toggle_widget(self) -> None:
        with self._lock:
            self._visible = not self._visible
            visible = self._visible
        self._bridge.visibility_requested.emit(visible)

    def request_quit(self) -> None:
        self._bridge.quit_requested.emit()

    def run_tray_in_thread(self, run_tray: Callable[[], None]) -> threading.Thread:
        """Runs the (blocking) tray loop on its own thread; when it returns,
        after Exit or Restart, the Qt loop is asked to end too."""

        def target() -> None:
            try:
                run_tray()
            except Exception:  # noqa: BLE001 - a dead tray must not leave a headless process behind
                logger.exception("Tray loop crashed")
            finally:
                self.request_quit()

        thread = threading.Thread(target=target, name="tray")
        thread.start()
        return thread

    # --- Qt thread ----------------------------------------------------------

    def exec(self) -> int:
        return self._app.exec()

    def _apply_visibility(self, visible: bool) -> None:
        if not visible:
            if self._window is not None:
                self._window.hide()
            return
        if self._window is None:
            self._window = LyricsWindow()
            self._window.move_to_default_position()
        self._window.show()
