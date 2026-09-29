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
from src.config.settings import Settings  # noqa: E402
from src.lyrics_widget.geometry import DEFAULT_SIZE, default_position  # noqa: E402
from src.lyrics_widget.qt_host import QtHost, screen_key, window_flags  # noqa: E402
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


# --- position and layer ----------------------------------------------------------


def test_the_window_sits_behind_the_others_unless_on_top_and_lets_clicks_through_when_locked():
    default = window_flags(locked=False, on_top=False)
    assert default & Qt.WindowStaysOnBottomHint and not default & Qt.WindowStaysOnTopHint
    assert not default & Qt.WindowTransparentForInput

    on_top = window_flags(locked=False, on_top=True)
    assert on_top & Qt.WindowStaysOnTopHint and not on_top & Qt.WindowStaysOnBottomHint

    assert window_flags(locked=True, on_top=False) & Qt.WindowTransparentForInput


def test_the_layer_is_set_on_windows_itself_whenever_the_window_shows(monkeypatch):
    """Regression: switching Qt's hint from bottom to top on a live window
    never gave it WS_EX_TOPMOST on Windows, so the layer is set with Win32."""
    from src.lyrics_widget import qt_host

    calls = []
    monkeypatch.setattr(qt_host, "set_window_layer", lambda hwnd, on_top: calls.append(on_top))
    host = QtHost(Settings(), lyrics_source=lambda query: NotFound())

    # Setting the layer again is harmless, and a flag change can show the
    # window more than once: what matters is the last layer asked for.
    host.toggle_widget()
    QApplication.processEvents()
    assert calls and calls[-1] is False

    host.toggle_on_top()
    QApplication.processEvents()
    assert calls[-1] is True

    host.toggle_on_top()
    QApplication.processEvents()
    assert calls[-1] is False
    host.toggle_widget()


def test_the_window_takes_the_album_color_darkened_for_contrast():
    from src.lyrics_widget.colors import TintHolder, widget_background

    tint = TintHolder()
    host = QtHost(Settings(), lyrics_source=lambda query: NotFound(), tint=tint)
    host.toggle_widget()
    QApplication.processEvents()

    tint.set((240, 150, 30))  # a light album
    host._tick()

    expected = widget_background((240, 150, 30))
    color = host._window.background
    assert (color.red(), color.green(), color.blue()) == expected
    host.toggle_widget()


class _Saves:
    def __init__(self):
        self.count = 0

    def __call__(self, settings):
        self.count += 1


def _host_with(settings):
    saves = _Saves()
    return QtHost(settings, save=saves, lyrics_source=lambda query: NotFound()), saves


def test_the_switches_are_applied_to_the_window_and_saved():
    settings = Settings()
    host, saves = _host_with(settings)
    host.toggle_widget()
    QApplication.processEvents()

    host.toggle_locked()
    host.toggle_on_top()
    QApplication.processEvents()

    flags = host._window.windowFlags()
    assert flags & Qt.WindowTransparentForInput and flags & Qt.WindowStaysOnTopHint
    assert host._window.isVisible(), "changing the flags must not leave it hidden"
    assert (settings.lyrics_widget_enabled, settings.lyrics_widget_locked, settings.lyrics_widget_on_top) == (True, True, True)
    assert saves.count == 3
    host.toggle_widget()


def test_the_switches_saved_last_time_are_where_it_starts():
    settings = Settings(lyrics_widget_enabled=True, lyrics_widget_locked=True, lyrics_widget_on_top=True)
    host, _ = _host_with(settings)

    assert (host.is_widget_visible(), host.is_locked(), host.is_on_top()) == (True, True, True)


def _available(screen):
    area = screen.availableGeometry()
    return area.x(), area.y(), area.width(), area.height()


def test_a_saved_spot_on_a_monitor_still_there_is_restored():
    screen = QApplication.primaryScreen()
    x, y, _, _ = _available(screen)
    settings = Settings(lyrics_widget_geometry={"monitor": screen_key(screen), "rect": [x + 30, y + 40, 400, 150]})
    host, _ = _host_with(settings)

    host.toggle_widget()
    QApplication.processEvents()

    geometry = host._window.geometry()
    assert (geometry.x(), geometry.y(), geometry.width(), geometry.height()) == (x + 30, y + 40, 400, 150)
    host.toggle_widget()


def test_a_spot_saved_on_a_monitor_that_is_gone_falls_back_to_the_default_corner_keeping_the_size():
    settings = Settings(lyrics_widget_geometry={"monitor": "a monitor unplugged", "rect": [5000, 40, 400, 150]})
    host, _ = _host_with(settings)

    host.toggle_widget()
    QApplication.processEvents()

    geometry = host._window.geometry()
    assert (geometry.width(), geometry.height()) == (400, 150)
    assert (geometry.x(), geometry.y()) == default_position(_available(QApplication.primaryScreen()), (400, 150))
    host.toggle_widget()


def test_reset_position_forgets_the_saved_spot_and_goes_back_to_the_default():
    screen = QApplication.primaryScreen()
    x, y, _, _ = _available(screen)
    settings = Settings(lyrics_widget_geometry={"monitor": screen_key(screen), "rect": [x + 30, y + 40, 400, 150]})
    host, saves = _host_with(settings)
    host.toggle_widget()
    QApplication.processEvents()
    before = saves.count

    host.reset_position()
    QApplication.processEvents()

    assert settings.lyrics_widget_geometry is None
    assert saves.count == before + 1
    geometry = host._window.geometry()
    assert (geometry.width(), geometry.height()) == DEFAULT_SIZE
    assert (geometry.x(), geometry.y()) == default_position(_available(screen), DEFAULT_SIZE)
    host.toggle_widget()


def test_a_move_by_the_user_is_saved_with_its_monitor():
    settings = Settings()
    host, saves = _host_with(settings)
    host.toggle_widget()
    QApplication.processEvents()
    window = host._window

    window.move(window.x() - 50, window.y() - 20)
    window._user_moved()  # what the timer runs once the user lets go

    saved = settings.lyrics_widget_geometry
    assert saved["monitor"] == screen_key(window.screen())
    assert saved["rect"] == [window.x(), window.y(), window.width(), window.height()]
    assert saves.count >= 1
    host.toggle_widget()


def test_an_answer_arriving_after_the_widget_was_switched_off_does_not_show_it():
    """Review #11: the late answer used to tick, and the tick showed the window."""
    host = QtHost(Settings(), lyrics_source=lambda query: NotFound())
    host.attach_smtc(_playing_watcher())
    host.toggle_widget()
    QApplication.processEvents()
    host.toggle_widget()  # off before LRCLIB answered
    QApplication.processEvents()

    host._on_lyrics(SONG.track_key, NotFound())
    host._on_failure(SONG.track_key)

    assert not host._window.isVisible()


def test_a_click_just_before_reset_position_does_not_undo_it():
    """Review #11: a plain click left the window 'being moved', so the reset's
    own move was saved back 600 ms later as the user's choice."""
    settings = Settings()
    host, _ = _host_with(settings)
    host.toggle_widget()
    QApplication.processEvents()
    window = host._window
    window._interacting = True  # what a press does, even one that moves nothing
    window._interaction_timer.start()

    host.reset_position()
    QApplication.processEvents()

    assert not window._save_timer.isActive()
    assert settings.lyrics_widget_geometry is None
    host.toggle_widget()


def test_a_click_that_moves_nothing_stops_counting_as_a_move():
    host, _ = _host_with(Settings())
    host.toggle_widget()
    QApplication.processEvents()
    window = host._window
    window._interacting = True

    window._interaction_over()  # the timer's end, 1 s after the press

    assert window._interacting is False
    host.toggle_widget()


def test_placing_the_window_by_code_is_not_saved_as_the_users_choice():
    settings = Settings()
    host, _ = _host_with(settings)
    host.toggle_widget()
    QApplication.processEvents()

    host._window.move(host._window.x() - 50, host._window.y())
    QApplication.processEvents()

    assert not host._window._save_timer.isActive()
    assert settings.lyrics_widget_geometry is None
    host.toggle_widget()
