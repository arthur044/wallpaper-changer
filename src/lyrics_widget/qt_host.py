import logging
import threading
from typing import Callable, List, Optional

from PySide6.QtCore import QEasingCurve, QObject, QPointF, QRectF, Qt, QTimer, QVariantAnimation, Signal
from PySide6.QtGui import QColor, QFont, QFontMetrics, QPainter, QPainterPath, QTextLayout, QTextOption
from PySide6.QtWidgets import QApplication, QWidget

from src.config.settings import Settings
from src.lyrics.lrclib import Lyrics, LyricsQuery, LyricsUnavailableError, LrclibClient
from src.lyrics.slot import LyricsSlot
from src.lyrics_widget.colors import DEFAULT_BACKGROUND, TintHolder, widget_background
from src.lyrics_widget.controller import FetchRequest, LyricsWidgetController
from src.lyrics_widget.geometry import DEFAULT_SIZE, default_position, edges_at, hide_for_full_screen, restore_rect
from src.lyrics_widget.view_model import (
    BREAK_HEIGHT,
    LINE_SPACING,
    MIN_FONT_PX,
    PADDING_PX,
    Phase,
    View,
    column_tops,
    font_px_for_width,
    min_width,
    scroll_for_progress,
    scroll_to_line,
)
from src.os_integration.fullscreen import notification_state
from src.os_integration.window_layer import set_window_layer

logger = logging.getLogger(__name__)

_CORNER_RADIUS = 18
_BACKGROUND_ALPHA = 200
_TEXT = QColor(255, 255, 255)
_CURRENT_ALPHA = 255
_OTHER_ALPHA = 110
_TEXT_ALPHA = 215  # unsynced words: all alike
_MESSAGE_ALPHA = 170
_FADE = 0.2  # lines fade out over this share of the height at each edge
_VERTICAL_PADDING = 12
_MIN_HEIGHT = 80
_SCROLL_MS = 350
_TICK_MS = 100
_FULL_SCREEN_CHECK_TICKS = 10  # once a second
_SAVE_AFTER_MOVE_MS = 600
# A press that moves nothing (a plain click) stops counting as the user
# moving the window after this long.
_INTERACTION_MS = 1000
_FONT_FAMILY = "Segoe UI"

_QT_EDGES = {"left": Qt.LeftEdge, "right": Qt.RightEdge, "top": Qt.TopEdge, "bottom": Qt.BottomEdge}
_CURSORS = {
    frozenset(): Qt.ArrowCursor,
    frozenset({"left"}): Qt.SizeHorCursor,
    frozenset({"right"}): Qt.SizeHorCursor,
    frozenset({"top"}): Qt.SizeVerCursor,
    frozenset({"bottom"}): Qt.SizeVerCursor,
    frozenset({"left", "top"}): Qt.SizeFDiagCursor,
    frozenset({"right", "bottom"}): Qt.SizeFDiagCursor,
    frozenset({"right", "top"}): Qt.SizeBDiagCursor,
    frozenset({"left", "bottom"}): Qt.SizeBDiagCursor,
}


def _wrap_option() -> QTextOption:
    option = QTextOption(Qt.AlignHCenter)
    # Between words, or inside a word too long for a line on its own.
    option.setWrapMode(QTextOption.WrapAtWordBoundaryOrAnywhere)
    return option


def _wrapped_height(text: str, font: QFont, width: float) -> float:
    layout = QTextLayout(text, font)
    layout.setTextOption(_wrap_option())
    layout.beginLayout()
    height = 0.0
    while True:
        line = layout.createLine()
        if not line.isValid():
            break
        line.setLineWidth(width)
        line.setPosition(QPointF(0, height))
        height += line.height()
    layout.endLayout()
    return height


def screen_key(screen) -> str:
    """Which monitor, as stable as Qt can tell: its name plus whatever the
    EDID reports (often empty on Windows)."""
    return "|".join((screen.name(), screen.manufacturer(), screen.model(), screen.serialNumber()))


def window_flags(locked: bool, on_top: bool) -> Qt.WindowFlags:
    """Frameless, no taskbar button (Qt.Tool), behind the other windows unless
    on top, and letting clicks through while locked."""
    flags = Qt.FramelessWindowHint | Qt.Tool
    flags |= Qt.WindowStaysOnTopHint if on_top else Qt.WindowStaysOnBottomHint
    if locked:
        flags |= Qt.WindowTransparentForInput
    return flags


class LyricsWindow(QWidget):
    """The widget's window, with a per-pixel translucent rounded background.
    Shows the lines of a View, the one being sung highlighted and scrolled to
    with an animation. Unlocked, a press moves it, or resizes it near the
    border; [on_user_moved] runs once the user has let go."""

    def __init__(self, on_user_moved: Callable[[], None] = lambda: None) -> None:
        super().__init__(None, window_flags(locked=False, on_top=False))
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.setAttribute(Qt.WA_ShowWithoutActivating)
        self.setMouseTracking(True)
        self._on_user_moved = on_user_moved
        self._on_top = False
        self._background = QColor(*DEFAULT_BACKGROUND, _BACKGROUND_ALPHA)
        self._interacting = False
        self._save_timer = QTimer(self)
        self._save_timer.setSingleShot(True)
        self._save_timer.setInterval(_SAVE_AFTER_MOVE_MS)
        self._save_timer.timeout.connect(self._user_moved)
        self._interaction_timer = QTimer(self)
        self._interaction_timer.setSingleShot(True)
        self._interaction_timer.setInterval(_INTERACTION_MS)
        self._interaction_timer.timeout.connect(self._interaction_over)
        self._view = View(Phase.LOADING)
        self._font = QFont(_FONT_FAMILY)
        self._bold = QFont(_FONT_FAMILY)
        self._heights: List[float] = []
        self._tops: List[float] = []
        self._scroll = 0.0
        self._animation = QVariantAnimation(self)
        self._animation.setDuration(_SCROLL_MS)
        self._animation.setEasingCurve(QEasingCurve.OutCubic)
        self._animation.valueChanged.connect(self._set_scroll)
        smallest = QFont(_FONT_FAMILY)
        smallest.setPixelSize(MIN_FONT_PX)
        self.setMinimumSize(min_width(QFontMetrics(smallest).averageCharWidth()), _MIN_HEIGHT)
        self.resize(*DEFAULT_SIZE)
        self._update_fonts()

    @property
    def view(self) -> View:
        return self._view

    @property
    def background(self) -> QColor:
        return QColor(self._background)

    def set_background(self, rgb) -> None:
        color = QColor(*rgb, _BACKGROUND_ALPHA)
        if color != self._background:
            self._background = color
            self.update()

    def apply_layer(self, locked: bool, on_top: bool) -> None:
        # setWindowFlags hides the window; it comes back as it was.
        was_visible = self.isVisible()
        self._on_top = on_top
        self.setWindowFlags(window_flags(locked, on_top))
        self.unsetCursor()
        if was_visible:
            self.show()  # showEvent sets the layer

    def showEvent(self, event) -> None:  # noqa: N802 - Qt override
        super().showEvent(event)
        # The flags alone don't move an existing window between the bottom and
        # the top of the stack on Windows; see set_window_layer.
        set_window_layer(int(self.winId()), self._on_top)

    def move_to_default_position(self) -> None:
        screen = QApplication.primaryScreen()
        if screen is None:
            return
        area = screen.availableGeometry()
        self.move(*default_position((area.x(), area.y(), area.width(), area.height()), (self.width(), self.height())))

    def set_view(self, view: View) -> None:
        old = self._view
        if view == old:
            return
        self._view = view
        if view.lines != old.lines or view.phase != old.phase:
            self._relayout()
            self._animation.stop()
            self._scroll = self._target_scroll()
        elif view.phase == Phase.SYNCED and view.current != old.current:
            self._animation.stop()
            self._animation.setStartValue(self._scroll)
            self._animation.setEndValue(self._target_scroll())
            self._animation.start()
        else:
            self._scroll = self._target_scroll()
        self.update()

    # --- moving and resizing (unlocked only: locked, no input reaches here) ---

    def mousePressEvent(self, event) -> None:  # noqa: N802 - Qt override
        if event.button() != Qt.LeftButton or self.windowHandle() is None:
            return
        point = event.position().toPoint()
        edges = edges_at(point.x(), point.y(), self.width(), self.height())
        self._interacting = True
        self._interaction_timer.start()
        if edges:
            qt_edges = Qt.Edges()
            for edge in edges:
                qt_edges |= _QT_EDGES[edge]
            self.windowHandle().startSystemResize(qt_edges)
        else:
            self.windowHandle().startSystemMove()

    def mouseMoveEvent(self, event) -> None:  # noqa: N802 - Qt override
        if event.buttons() == Qt.NoButton:
            point = event.position().toPoint()
            self.setCursor(_CURSORS[edges_at(point.x(), point.y(), self.width(), self.height())])

    def moveEvent(self, event) -> None:  # noqa: N802 - Qt override
        self._moved_by_user()
        super().moveEvent(event)

    def _moved_by_user(self) -> None:
        if self._interacting:
            self._save_timer.start()
            self._interaction_timer.start()

    def _interaction_over(self) -> None:
        if not self._save_timer.isActive():
            self._interacting = False

    def _user_moved(self) -> None:
        self._interacting = False
        self._on_user_moved()

    def cancel_user_move(self) -> None:
        """The code is about to place the window: nothing pending may save it
        as the user's choice (a click just before a Reset position would)."""
        self._save_timer.stop()
        self._interaction_timer.stop()
        self._interacting = False

    # --- layout ---------------------------------------------------------------

    def _update_fonts(self) -> None:
        px = font_px_for_width(self.width())
        self._font.setPixelSize(px)
        self._bold.setPixelSize(px)
        self._bold.setWeight(QFont.DemiBold)

    def _text_width(self) -> float:
        return max(1.0, self.width() - 2 * PADDING_PX)

    def _view_height(self) -> float:
        return max(1.0, self.height() - 2 * _VERTICAL_PADDING)

    def _relayout(self) -> None:
        # Measured in the bold face, the widest: a line keeps its wrapping
        # whether it is the current one or not, so nothing jumps.
        line_height = QFontMetrics(self._bold).height()
        width = self._text_width()
        self._heights = [
            _wrapped_height(text, self._bold, width) if text.strip() else line_height * BREAK_HEIGHT
            for text in self._view.lines
        ]
        self._tops = column_tops(self._heights, line_height * LINE_SPACING)

    def _total_height(self) -> float:
        return self._tops[-1] + self._heights[-1] if self._tops else 0.0

    def _target_scroll(self) -> float:
        if self._view.phase == Phase.SYNCED:
            return scroll_to_line(self._tops, self._heights, self._view.current, self._view_height())
        if self._view.phase == Phase.TEXT:
            return scroll_for_progress(self._total_height(), self._view_height(), self._view.progress)
        return 0.0

    def _set_scroll(self, value) -> None:
        self._scroll = float(value)
        self.update()

    def resizeEvent(self, event) -> None:  # noqa: N802 - Qt override
        self._update_fonts()
        self._relayout()
        self._animation.stop()
        self._scroll = self._target_scroll()
        self._moved_by_user()
        super().resizeEvent(event)

    # --- painting -------------------------------------------------------------

    def paintEvent(self, event) -> None:  # noqa: N802 - Qt override
        painter = QPainter(self)
        painter.setRenderHint(QPainter.Antialiasing)
        painter.setRenderHint(QPainter.TextAntialiasing)
        outline = QPainterPath()
        outline.addRoundedRect(QRectF(self.rect()), _CORNER_RADIUS, _CORNER_RADIUS)
        painter.fillPath(outline, self._background)

        message = self._view.message
        if message is not None:
            self._paint_message(painter, message)
        elif self._view.lines:
            self._paint_lines(painter)

    def _paint_message(self, painter: QPainter, message: str) -> None:
        color = QColor(_TEXT)
        color.setAlpha(_MESSAGE_ALPHA)
        painter.setPen(color)
        painter.setFont(self._font)
        area = QRectF(PADDING_PX, _VERTICAL_PADDING, self._text_width(), self._view_height())
        painter.drawText(area, Qt.AlignCenter | Qt.TextWordWrap, message)

    def _paint_lines(self, painter: QPainter) -> None:
        view_height = self._view_height()
        fade = view_height * _FADE
        option = _wrap_option()
        synced = self._view.phase == Phase.SYNCED
        painter.setClipRect(QRectF(0, _VERTICAL_PADDING, self.width(), view_height))
        for index, text in enumerate(self._view.lines):
            if not text.strip():
                continue
            top = self._tops[index] - self._scroll
            height = self._heights[index]
            if top + height < 0 or top > view_height:
                continue
            current = synced and index == self._view.current
            alpha = _CURRENT_ALPHA if current else (_OTHER_ALPHA if synced else _TEXT_ALPHA)
            # Fade toward the top and bottom edges instead of a hard cut.
            middle = top + height / 2
            edge = min(middle, view_height - middle)
            if edge < fade:
                alpha = int(alpha * max(0.0, edge) / fade)
            color = QColor(_TEXT)
            color.setAlpha(alpha)
            painter.setPen(color)
            painter.setFont(self._bold if current else self._font)
            painter.drawText(QRectF(PADDING_PX, _VERTICAL_PADDING + top, self._text_width(), height), text, option)


class _Bridge(QObject):
    # Emitted from other threads; the default (auto) connection queues them to
    # the Qt thread, which owns every widget.
    visibility_requested = Signal(bool)
    layer_requested = Signal()
    reset_requested = Signal()
    quit_requested = Signal()
    lyrics_ready = Signal(str, object)
    lyrics_failed = Signal(str)


LyricsSource = Callable[[LyricsQuery], Lyrics]


class QtHost:
    """Owns the QApplication on the main thread, where Qt requires it to live.
    The tray runs on its own thread and reaches Qt only through the signals of
    [_Bridge], so no widget is touched off the Qt thread.

    Create it before anything calls SetProcessDpiAwareness (the first render
    does, in layout.py): Qt sets per-monitor v2 awareness on startup and can't
    once the process already has a mode.

    The widget's switches and position live in [settings] and are written with
    [save] from the Qt thread; without [save] (tests) nothing is written."""

    def __init__(
        self,
        settings: Optional[Settings] = None,
        save: Optional[Callable[[Settings], None]] = None,
        lyrics_source: Optional[LyricsSource] = None,
        tint: Optional[TintHolder] = None,
    ) -> None:
        self._app = QApplication.instance() or QApplication([])
        # The widget is the only window; hiding it must not end the app.
        self._app.setQuitOnLastWindowClosed(False)
        self._settings = settings if settings is not None else Settings()
        self._save = save if save is not None else (lambda _settings: None)
        self._window: Optional[LyricsWindow] = None
        self._lock = threading.Lock()
        self._visible = self._settings.lyrics_widget_enabled  # the tray's "Show"
        self._locked = self._settings.lyrics_widget_locked
        self._on_top = self._settings.lyrics_widget_on_top
        self._full_screen = False
        self._ticks = 0
        self._tint = tint
        self._watcher = None
        self._controller = LyricsWidgetController(has_source=False)
        self._slot = LyricsSlot(lyrics_source if lyrics_source is not None else LrclibClient().lyrics)
        self._timer = QTimer()
        self._timer.setInterval(_TICK_MS)
        self._timer.timeout.connect(self._tick)
        self._bridge = _Bridge()
        self._bridge.visibility_requested.connect(self._apply_visibility)
        self._bridge.layer_requested.connect(self._apply_layer)
        self._bridge.reset_requested.connect(self._reset_position)
        self._bridge.quit_requested.connect(self._app.quit)
        self._bridge.lyrics_ready.connect(self._on_lyrics)
        self._bridge.lyrics_failed.connect(self._on_failure)
        self._app.screenRemoved.connect(self._on_screen_removed)

    def attach_smtc(self, watcher) -> None:
        """The track and position come from SMTC; without it (use_smtc off)
        the widget says so instead of guessing. Call before exec()."""
        self._watcher = watcher
        self._controller = LyricsWidgetController(has_source=watcher is not None)

    # --- any thread (the tray's LyricsWidgetControls) -------------------------

    def is_widget_visible(self) -> bool:
        with self._lock:
            return self._visible

    def is_locked(self) -> bool:
        with self._lock:
            return self._locked

    def is_on_top(self) -> bool:
        with self._lock:
            return self._on_top

    def toggle_widget(self) -> None:
        with self._lock:
            self._visible = not self._visible
            visible = self._visible
        self._bridge.visibility_requested.emit(visible)

    def toggle_locked(self) -> None:
        with self._lock:
            self._locked = not self._locked
        self._bridge.layer_requested.emit()

    def toggle_on_top(self) -> None:
        with self._lock:
            self._on_top = not self._on_top
        self._bridge.layer_requested.emit()

    def reset_position(self) -> None:
        self._bridge.reset_requested.emit()

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

        # Daemon: a pystray loop stuck after Qt quits must not keep the
        # process alive (main joins it with a timeout first).
        thread = threading.Thread(target=target, name="tray", daemon=True)
        thread.start()
        return thread

    # --- Qt thread ----------------------------------------------------------

    def exec(self) -> int:
        if self.is_widget_visible():  # left on last time
            self._apply_visibility(True)
        return self._app.exec()

    def _persist(self) -> None:
        self._save(self._settings)

    def _apply_visibility(self, visible: bool) -> None:
        if self._settings.lyrics_widget_enabled != visible:
            self._settings.lyrics_widget_enabled = visible
            self._persist()
        if not visible:
            # Off: no ticks, so no lookups either.
            self._timer.stop()
            if self._window is not None:
                self._window.hide()
            return
        self._ensure_window()
        self._timer.start()
        self._tick()

    def _ensure_window(self) -> LyricsWindow:
        if self._window is None:
            self._window = LyricsWindow(on_user_moved=self._save_geometry)
            self._window.apply_layer(self.is_locked(), self.is_on_top())
            self._place_window()
        return self._window

    def _apply_layer(self) -> None:
        locked, on_top = self.is_locked(), self.is_on_top()
        self._settings.lyrics_widget_locked = locked
        self._settings.lyrics_widget_on_top = on_top
        self._persist()
        if self._window is not None:
            self._window.apply_layer(locked, on_top)

    def _screens(self):
        return [
            (screen_key(screen), (area.x(), area.y(), area.width(), area.height()))
            for screen in self._app.screens()
            for area in (screen.availableGeometry(),)
        ]

    def _place_window(self) -> None:
        window = self._window
        if window is None:
            return
        window.cancel_user_move()
        saved = self._settings.lyrics_widget_geometry
        rect = restore_rect(saved, self._screens())
        if rect is not None:
            window.setGeometry(*rect)
            return
        # The default corner, keeping the size the user chose, if any, but
        # never bigger than the primary screen (it may come from a 4K one).
        width, height = saved["rect"][2:] if saved else DEFAULT_SIZE
        screen = self._app.primaryScreen()
        if screen is not None:
            area = screen.availableGeometry()
            width, height = min(width, area.width()), min(height, area.height())
        window.resize(width, height)
        window.move_to_default_position()

    def _save_geometry(self) -> None:
        window = self._window
        if window is None or window.screen() is None:
            return
        geometry = window.geometry()
        self._settings.lyrics_widget_geometry = {
            "monitor": screen_key(window.screen()),
            "rect": [geometry.x(), geometry.y(), geometry.width(), geometry.height()],
        }
        self._persist()

    def _reset_position(self) -> None:
        self._settings.lyrics_widget_geometry = None
        self._persist()
        if self._window is not None:
            self._window.cancel_user_move()
            self._window.resize(*DEFAULT_SIZE)
            self._window.move_to_default_position()

    def _on_screen_removed(self, _screen) -> None:
        # Its monitor may be the one gone: the saved spot or the default.
        self._place_window()

    def _tick(self) -> None:
        watcher = self._watcher
        snapshot = watcher.get_snapshot() if watcher is not None else None
        timeline = watcher.get_timeline() if watcher is not None else None
        view, request = self._controller.tick(snapshot, timeline)
        if request is not None:
            self._start_lookup(request)
        window = self._window
        if window is None:
            return
        if self._ticks % _FULL_SCREEN_CHECK_TICKS == 0:
            self._full_screen = hide_for_full_screen(self.is_on_top(), notification_state())
        self._ticks += 1
        if self._tint is not None:
            window.set_background(widget_background(self._tint.get()))
        window.set_view(view)
        if view.phase == Phase.HIDDEN or self._full_screen:
            window.hide()
        elif not window.isVisible():
            window.show()

    def _start_lookup(self, request: FetchRequest) -> None:
        # A daemon thread, not a pool: exiting never waits on a slow LRCLIB.
        # The slot runs lookups one at a time.
        threading.Thread(target=self._look_up, args=(request,), name="lyrics", daemon=True).start()

    def _look_up(self, request: FetchRequest) -> None:
        try:
            lyrics = self._slot.lyrics_for(request.track_key, request.query)
        except LyricsUnavailableError as exc:
            logger.warning("Lyrics lookup failed, will retry: %s", exc)
            self._bridge.lyrics_failed.emit(request.track_key)
            return
        except Exception:  # noqa: BLE001 - a lookup bug must not kill the widget
            logger.exception("Lyrics lookup crashed")
            self._bridge.lyrics_failed.emit(request.track_key)
            return
        # The kind of answer only: the words never go to the log.
        logger.info("Lyrics lookup for the current track: %s", type(lyrics).__name__)
        self._bridge.lyrics_ready.emit(request.track_key, lyrics)

    # An answer can arrive after the widget was switched off: it is kept, but
    # only a running widget ticks (and shows itself), never a switched-off one.
    def _on_lyrics(self, track_key: str, lyrics) -> None:
        self._controller.on_lyrics(track_key, lyrics)
        if self._timer.isActive():
            self._tick()

    def _on_failure(self, track_key: str) -> None:
        self._controller.on_failure(track_key)
        if self._timer.isActive():
            self._tick()
