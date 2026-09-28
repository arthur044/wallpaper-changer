import os
import threading
import time

# No real window during the tests; must be set before the QApplication exists.
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

import pytest  # noqa: E402
from PySide6.QtCore import QTimer, Qt  # noqa: E402
from PySide6.QtWidgets import QApplication  # noqa: E402

from src.lyrics_widget.qt_host import QtHost  # noqa: E402

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
