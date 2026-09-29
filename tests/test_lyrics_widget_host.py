import os
import threading
import time

# No real window during the tests; must be set before the QApplication exists.
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

import pytest  # noqa: E402
from PySide6.QtCore import QTimer, Qt  # noqa: E402
from PySide6.QtWidgets import QApplication  # noqa: E402

from src.lyrics.lrclib import NotFound, SyncedLyrics  # noqa: E402
from src.lyrics.text import TimedLine  # noqa: E402
from src.lyrics_widget.qt_host import QtHost  # noqa: E402
from src.lyrics_widget.view_model import Phase  # noqa: E402
from src.os_integration.smtc import SmtcNowPlaying, TimelineSample  # noqa: E402

_WATCHDOG_MS = 5000


@pytest.fixture
def host():
    return QtHost()


def _exec_with_watchdog(host):
    """Runs the Qt loop, failing instead of hanging if nothing ends it."""
    timed_out = []

    def watchdog():
        timed_out.append(1)
        QApplication.instance().quit()

    timer = QTimer()
    timer.setSingleShot(True)
    timer.timeout.connect(watchdog)
    timer.start(_WATCHDOG_MS)
    started = time.monotonic()
    host.exec()
    timer.stop()
    assert not timed_out, "the Qt loop only ended through the watchdog"
    return time.monotonic() - started


def test_toggle_shows_then_hides_a_frameless_tool_window(host):
    host.toggle_widget()
    QApplication.processEvents()

    window = host._window
    assert host.is_widget_visible() is True
    assert window is not None and window.isVisible()
    assert window.windowFlags() & Qt.FramelessWindowHint
    assert window.windowFlags() & Qt.Tool  # no taskbar button
    assert window.testAttribute(Qt.WA_TranslucentBackground)

    host.toggle_widget()
    QApplication.processEvents()

    assert host.is_widget_visible() is False
    assert not window.isVisible()


def test_hiding_the_only_window_does_not_end_the_app(host):
    assert QApplication.instance().quitOnLastWindowClosed() is False


def test_a_toggle_from_another_thread_reaches_the_window_on_the_qt_thread(host):
    seen = []

    def from_tray_thread():
        host.toggle_widget()
        host.request_quit()

    original = host._apply_visibility

    def recording(visible):
        original(visible)
        # Checked here, not after exec(): Qt 6's quit() closes every window.
        seen.append((threading.current_thread() is threading.main_thread(), host._window.isVisible()))

    host._bridge.visibility_requested.disconnect(host._apply_visibility)
    host._bridge.visibility_requested.connect(recording)
    threading.Thread(target=from_tray_thread).start()

    _exec_with_watchdog(host)

    assert seen == [(True, True)]


def test_the_qt_loop_ends_when_the_tray_loop_returns(host):
    release = threading.Event()

    thread = host.run_tray_in_thread(lambda: release.wait(timeout=5.0))
    QTimer.singleShot(50, release.set)  # "Exit" clicked shortly after startup

    assert _exec_with_watchdog(host) < 2.0
    thread.join(timeout=1.0)
    assert not thread.is_alive()


def test_the_qt_loop_ends_even_if_the_tray_loop_crashes(host):
    def crashing_tray():
        raise RuntimeError("tray died")

    host.run_tray_in_thread(crashing_tray)

    assert _exec_with_watchdog(host) < 2.0


# --- the widget following SMTC -------------------------------------------------

SONG = SmtcNowPlaying(title="Song", artist="Artist", album_title="Album", album_artist="Artist", is_playing=True)


class _FakeWatcher:
    def __init__(self, snapshot=None, timeline=None):
        self.snapshot = snapshot
        self.timeline = timeline

    def get_snapshot(self):
        return self.snapshot

    def get_timeline(self):
        return self.timeline


def _playing_watcher(position_ms=5_000):
    timeline = TimelineSample(
        track_key=SONG.track_key,
        position_ms=position_ms,
        observed_at=time.monotonic(),
        duration_ms=200_000,
        is_playing=True,
        rate=1.0,
        stamp=1.0,
    )
    return _FakeWatcher(SONG, timeline)


def _run_until(host, condition):
    """Runs the Qt loop until [condition] holds, checked every 20 ms."""
    poll = QTimer()
    poll.setInterval(20)
    poll.timeout.connect(lambda: condition() and QApplication.instance().quit())
    poll.start()
    _exec_with_watchdog(host)
    poll.stop()


def test_the_lyrics_reach_the_window_from_a_lookup_off_the_qt_thread():
    on_main_thread = []

    def source(query):
        on_main_thread.append(threading.current_thread() is threading.main_thread())
        return SyncedLyrics((TimedLine(1_000, "Line one"), TimedLine(4_000, "Line two"), TimedLine(9_000, "Line three")))

    host = QtHost(lyrics_source=source)
    host.attach_smtc(_playing_watcher(position_ms=5_000))
    host.toggle_widget()

    _run_until(host, lambda: host._window is not None and host._window.view.phase == Phase.SYNCED)

    assert on_main_thread == [False]
    assert host._window.view.lines == ("Line one", "Line two", "Line three")
    assert host._window.view.current == 1
    host.toggle_widget()


def test_nothing_playing_hides_the_window_while_the_widget_stays_on():
    watcher = _FakeWatcher()
    host = QtHost(lyrics_source=lambda query: NotFound())
    host.attach_smtc(watcher)

    host.toggle_widget()
    QApplication.processEvents()
    assert host.is_widget_visible() is True
    assert not host._window.isVisible()

    watcher.snapshot = SONG
    host._tick()
    assert host._window.isVisible()

    watcher.snapshot = None
    host._tick()
    assert not host._window.isVisible()
    host.toggle_widget()


def test_turning_the_widget_off_stops_following_smtc():
    host = QtHost(lyrics_source=lambda query: NotFound())
    host.attach_smtc(_playing_watcher())
    host.toggle_widget()
    assert host._timer.isActive()

    host.toggle_widget()

    assert not host._timer.isActive(), "off means no ticks, so no lookups"
