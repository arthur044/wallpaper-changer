import json
import os
import subprocess

# No real window during the tests; must be set before the QApplication exists.
os.environ["QT_QPA_PLATFORM"] = "offscreen"

import pytest  # noqa: E402
from PySide6.QtWidgets import QApplication  # noqa: E402

from src.config.settings import Settings  # noqa: E402
from src.lyrics.lrclib import NotFound  # noqa: E402
from src.lyrics_widget import hyprland_window as hw  # noqa: E402
from src.lyrics_widget.qt_host import QtHost, screen_key  # noqa: E402


# --- the Lua and the hyprctl answers -------------------------------------------------


def test_the_rule_swaps_the_previous_one_and_counts_move_from_the_monitor():
    lua = hw.rule_lua((1380, 700, 420, 160), "DP-1", (1280, 0))

    assert lua.startswith(f"if {hw._RULE_GLOBAL} then {hw._RULE_GLOBAL}:set_enabled(false) end ")
    assert 'match = { title = "^Spotify Wallpaper Engine lyrics$" }' in lua
    assert "float = true, pin = true, no_initial_focus = true" in lua
    assert 'monitor = "DP-1"' in lua
    assert "size = { 420, 160 }" in lua and "move = { 100, 700 }" in lua


def test_strings_are_escaped_for_lua():
    assert hw._lua_string('a"b\\c') == '"a\\"b\\\\c"'


def test_removing_the_rule_switches_it_off_and_forgets_it():
    assert hw.remove_rule_lua() == (
        f"if {hw._RULE_GLOBAL} then {hw._RULE_GLOBAL}:set_enabled(false) {hw._RULE_GLOBAL} = nil end"
    )


def test_a_move_resizes_first_since_a_resize_keeps_the_center():
    lua = hw.move_resize_lua((1500, 800, 400, 150))

    assert lua.index("window.resize") < lua.index("window.move")
    assert "x = 400, y = 150" in lua and "x = 1500, y = 800" in lua


_CLIENTS = [
    {"title": "Spotify Wallpaper Engine", "pid": 42, "at": [0, 0], "size": [400, 600], "monitor": 0},
    {"title": hw.TITLE, "pid": 7, "at": [10, 10], "size": [1, 1], "monitor": 0},  # another copy's
    {"title": hw.TITLE, "pid": 42, "at": [1500, 800], "size": [400, 150], "monitor": 1},
]


def test_the_widget_is_found_by_title_and_this_process():
    assert hw.find_client(_CLIENTS, 42)["at"] == [1500, 800]
    assert hw.find_client(_CLIENTS, 99) is None
    assert hw.client_rect(hw.find_client(_CLIENTS, 42)) == (1500, 800, 400, 150)


class Hyprctl:
    """hyprctl, faked: answers by subcommand and records the calls."""

    def __init__(self, **answers):
        self.answers = answers
        self.calls = []

    def __call__(self, args, **kwargs):
        self.calls.append(args)
        answer = self.answers.get(args[1], "ok")
        if isinstance(answer, Exception):
            raise answer
        returncode, stdout = (answer if isinstance(answer, tuple) else (0, answer))
        return subprocess.CompletedProcess(args, returncode, stdout, "")


def test_window_rect_and_its_monitor_name():
    run = Hyprctl(clients=json.dumps(_CLIENTS), monitors=json.dumps([{"id": 1, "name": "HDMI-A-1"}]))
    window = hw.HyprlandWidgetWindow(run=run, pid=42)

    rect, monitor_id = window.window_rect()

    assert rect == (1500, 800, 400, 150)
    assert window.monitor_name(monitor_id) == "HDMI-A-1"


@pytest.mark.parametrize(
    "fullscreen, hides", [(0, False), (1, False), (2, True), (3, True)], ids=["none", "maximized", "fullscreen", "both"]
)
def test_only_a_full_screen_window_hides_the_widget(fullscreen, hides):
    run = Hyprctl(activewindow=json.dumps({"title": "mpv", "fullscreen": fullscreen}))

    assert hw.HyprlandWidgetWindow(run=run).full_screen_active() is hides


def test_a_lua_error_or_a_missing_hyprctl_is_logged_once_and_ignored(caplog):
    run = Hyprctl(eval=(0, "[string ...]:1: attempt to index a nil value"))
    window = hw.HyprlandWidgetWindow(run=run)

    with caplog.at_level("WARNING"):
        window.remove_rule()
        window.remove_rule()
        run.answers["eval"] = FileNotFoundError("hyprctl")
        window.remove_rule()
        assert window.window_rect() is None
        assert window.full_screen_active() is False

    assert len([r for r in caplog.records if "Hyprland" in r.message]) == 1


# --- the host on Hyprland ---------------------------------------------------------------


class FakeHyprland:
    def __init__(self):
        self.calls = []
        self.rect = None  # where Hyprland says the window is
        self.monitor_id = 0
        self.full_screen = False

    def before_show(self, rect, monitor, origin):
        self.calls.append(("before_show", rect, monitor, origin))

    def remove_rule(self):
        self.calls.append(("remove_rule",))

    def move_resize(self, rect):
        self.calls.append(("move_resize", rect))

    def window_rect(self):
        return None if self.rect is None else (self.rect, self.monitor_id)

    def monitor_name(self, monitor_id):
        return QApplication.primaryScreen().name()

    def full_screen_active(self):
        return self.full_screen


class _Saves:
    def __init__(self):
        self.count = 0

    def __call__(self, settings):
        self.count += 1


@pytest.fixture
def app():
    return QApplication.instance() or QApplication([])


def _host(settings=None):
    hyprland, saves = FakeHyprland(), _Saves()
    host = QtHost(settings or Settings(), save=saves, lyrics_source=lambda query: NotFound(), hyprland=hyprland)
    return host, hyprland, saves


def _area():
    area = QApplication.primaryScreen().availableGeometry()
    return area.x(), area.y(), area.width(), area.height()


def test_offscreen_there_is_no_hyprland_unless_given(app):
    assert QtHost(Settings())._hyprland is None


def test_the_rule_is_set_before_the_window_maps_with_its_saved_place(app):
    screen = QApplication.primaryScreen()
    x, y, _w, _h = _area()
    settings = Settings(lyrics_widget_geometry={"monitor": screen_key(screen), "rect": [x + 30, y + 40, 400, 150]})
    host, hyprland, _ = _host(settings)

    host.toggle_widget()
    QApplication.processEvents()

    assert host._window.windowTitle() == "Spotify Wallpaper Engine lyrics"
    origin = screen.geometry().topLeft()
    assert ("before_show", (x + 30, y + 40, 400, 150), screen.name(), (origin.x(), origin.y())) in hyprland.calls
    host.toggle_widget()


def test_every_show_sets_the_rule_again_since_a_reload_drops_it(app):
    host, hyprland, _ = _host()
    host.toggle_widget()
    QApplication.processEvents()

    host.toggle_locked()  # a flag change hides and shows the window again
    QApplication.processEvents()

    assert [call[0] for call in hyprland.calls].count("before_show") == 2
    host.toggle_widget()


def test_a_place_hyprland_reports_twice_in_a_row_is_saved_as_the_users(app):
    host, hyprland, saves = _host()
    host.toggle_widget()
    QApplication.processEvents()
    x, y, _w, _h = _area()
    hyprland.rect = (x + 50, y + 60, 380, 140)  # the user dragged it

    host._follow_hyprland_moves()  # first sight: maybe still moving
    assert host._settings.lyrics_widget_geometry is None

    host._follow_hyprland_moves()  # held still for a check: saved

    assert host._settings.lyrics_widget_geometry == {
        "monitor": screen_key(QApplication.primaryScreen()),
        "rect": [x + 50, y + 60, 380, 140],
    }
    assert saves.count >= 1
    host._follow_hyprland_moves()  # nothing new: not saved again
    host.toggle_widget()


def test_after_a_move_the_next_show_uses_where_the_user_left_it(app):
    host, hyprland, _ = _host()
    host.toggle_widget()
    QApplication.processEvents()
    x, y, _w, _h = _area()
    hyprland.rect = (x + 50, y + 60, 380, 140)
    host._follow_hyprland_moves()
    host._follow_hyprland_moves()

    host.toggle_locked()
    QApplication.processEvents()

    assert hyprland.calls[-1][0] == "before_show" and hyprland.calls[-1][1] == (x + 50, y + 60, 380, 140)
    host.toggle_widget()


def test_reset_position_moves_the_shown_window_through_hyprland(app):
    host, hyprland, _ = _host()
    host.toggle_widget()
    QApplication.processEvents()

    host.reset_position()
    QApplication.processEvents()

    assert hyprland.calls[-1] == ("move_resize", host._hyprland_rect)
    assert host._settings.lyrics_widget_geometry is None
    host.toggle_widget()


def test_a_full_screen_window_hides_the_widget(app):
    host, hyprland, _ = _host()
    host.toggle_widget()
    QApplication.processEvents()
    host._controller = _ShowingController()

    hyprland.full_screen = True
    host._ticks = 0
    host._tick()

    assert not host._window.isVisible()
    host.toggle_widget()


def test_quitting_switches_the_rule_off(app, monkeypatch):
    host, hyprland, _ = _host()

    host._app.aboutToQuit.emit()

    assert ("remove_rule",) in hyprland.calls


class _ShowingController:
    def tick(self, snapshot, timeline):
        from src.lyrics_widget.view_model import Phase, View

        return View(Phase.LOADING), None


def test_the_once_a_second_reads_go_through_the_socket_not_hyprctl():
    asked, ran = [], []
    window = hw.HyprlandWidgetWindow(pid=42, query=lambda what: asked.append(what) or json.dumps(_CLIENTS))
    window._run = lambda *a, **k: ran.append(a)

    assert window.window_rect() == ((1500, 800, 400, 150), 1)
    assert asked == ["clients"] and ran == []


def test_a_socket_that_fails_reads_as_nothing():
    def broken(what):
        raise TimeoutError("timed out")

    window = hw.HyprlandWidgetWindow(query=broken)

    assert window.window_rect() is None and window.full_screen_active() is False
