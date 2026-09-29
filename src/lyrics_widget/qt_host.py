import logging
import threading
import time
from typing import Callable, List, Optional

from PySide6.QtCore import QEasingCurve, QObject, QPointF, QRectF, Qt, QTimer, QVariantAnimation, Signal
from PySide6.QtGui import QColor, QFont, QFontMetrics, QPainter, QPainterPath, QTextLayout, QTextOption
from PySide6.QtWidgets import QApplication, QWidget

from src.lyrics.lrclib import Lyrics, LyricsQuery, LyricsUnavailableError, LrclibClient
from src.lyrics.slot import LyricsSlot
from src.lyrics_widget.controller import FetchRequest, LyricsWidgetController
from src.lyrics_widget.geometry import DEFAULT_SIZE, default_position
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

logger = logging.getLogger(__name__)

_CORNER_RADIUS = 18
_BACKGROUND = QColor(18, 18, 18, 190)
_TEXT = QColor(255, 255, 255)
_CURRENT_ALPHA = 255
_OTHER_ALPHA = 110
_TEXT_ALPHA = 215  # unsynced words: all alike
_MESSAGE_ALPHA = 170
_FADE = 0.2  # lines fade out over this share of the height at each edge
_VERTICAL_PADDING = 12
_SCROLL_MS = 350
_TICK_MS = 100
_FONT_FAMILY = "Segoe UI"


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


class LyricsWindow(QWidget):
    """The widget's window: frameless, no taskbar button (Qt.Tool), with a
    per-pixel translucent rounded background. Shows the lines of a View, the
    one being sung highlighted and scrolled to with an animation."""

    def __init__(self) -> None:
        super().__init__(None, Qt.FramelessWindowHint | Qt.Tool)
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.setAttribute(Qt.WA_ShowWithoutActivating)
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
        self.setMinimumWidth(min_width(QFontMetrics(smallest).averageCharWidth()))
        self.resize(*DEFAULT_SIZE)
        self._update_fonts()

    @property
    def view(self) -> View:
        return self._view

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
        super().resizeEvent(event)

    # --- painting -------------------------------------------------------------

    def paintEvent(self, event) -> None:  # noqa: N802 - Qt override
        painter = QPainter(self)
        painter.setRenderHint(QPainter.Antialiasing)
        painter.setRenderHint(QPainter.TextAntialiasing)
        outline = QPainterPath()
        outline.addRoundedRect(QRectF(self.rect()), _CORNER_RADIUS, _CORNER_RADIUS)
        painter.fillPath(outline, _BACKGROUND)

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
    once the process already has a mode."""

    def __init__(self, lyrics_source: Optional[LyricsSource] = None) -> None:
        self._app = QApplication.instance() or QApplication([])
        # The widget is the only window; hiding it must not end the app.
        self._app.setQuitOnLastWindowClosed(False)
        self._window: Optional[LyricsWindow] = None
        self._placed = False
        self._visible = False  # the tray toggle: on or off
        self._lock = threading.Lock()
        self._watcher = None
        self._controller = LyricsWidgetController(has_source=False)
        self._slot = LyricsSlot(lyrics_source if lyrics_source is not None else LrclibClient().lyrics)
        self._timer = QTimer()
        self._timer.setInterval(_TICK_MS)
        self._timer.timeout.connect(self._tick)
        self._bridge = _Bridge()
        self._bridge.visibility_requested.connect(self._apply_visibility)
        self._bridge.quit_requested.connect(self._app.quit)
        self._bridge.lyrics_ready.connect(self._on_lyrics)
        self._bridge.lyrics_failed.connect(self._on_failure)

    def attach_smtc(self, watcher) -> None:
        """The track and position come from SMTC; without it (use_smtc off)
        the widget says so instead of guessing. Call before exec()."""
        self._watcher = watcher
        self._controller = LyricsWidgetController(has_source=watcher is not None)

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
            # Off: no ticks, so no lookups either.
            self._timer.stop()
            if self._window is not None:
                self._window.hide()
            return
        if self._window is None:
            self._window = LyricsWindow()
        self._timer.start()
        self._tick()

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
        window.set_view(view)
        if view.phase == Phase.HIDDEN:
            window.hide()
        elif not window.isVisible():
            if not self._placed:
                window.move_to_default_position()
                self._placed = True
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

    def _on_lyrics(self, track_key: str, lyrics) -> None:
        self._controller.on_lyrics(track_key, lyrics)
        self._tick()

    def _on_failure(self, track_key: str) -> None:
        self._controller.on_failure(track_key)
        self._tick()
