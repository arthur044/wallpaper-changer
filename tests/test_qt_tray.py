import os
import threading

# No real tray or window during the tests; must be set before the QApplication exists.
os.environ["QT_QPA_PLATFORM"] = "offscreen"

import pystray  # noqa: E402
import pytest  # noqa: E402
from PIL import Image  # noqa: E402
from PySide6.QtWidgets import QApplication, QSystemTrayIcon  # noqa: E402

from src.config.settings import Settings  # noqa: E402
from src.os_integration.qt_tray import QtTrayIcon, to_qicon  # noqa: E402
from src.os_integration.tray import TrayApp  # noqa: E402
from src.utils.app_state import AppState  # noqa: E402

_GREEN = Image.new("RGBA", (64, 64), (30, 215, 96, 255))


@pytest.fixture(scope="module")
def app():
    return QApplication.instance() or QApplication([])


def _wait_for(condition, timeout=2.0):
    done = threading.Event()
    for _ in range(int(timeout / 0.01)):
        QApplication.processEvents()
        if condition():
            return True
        done.wait(0.01)
    return condition()


def _labels(icon):
    return [action.text() if not action.isSeparator() else "-" for action in icon._qmenu.actions()]


def test_the_pystray_menu_becomes_the_qt_menu(app):
    hidden = {"show": False}
    menu = pystray.Menu(
        pystray.MenuItem("Settings...", lambda icon, item: None, default=True),
        pystray.MenuItem(lambda item: "Pause", lambda icon, item: None),
        pystray.MenuItem("Re-authenticate", lambda icon, item: None, visible=lambda item: hidden["show"]),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("Exit", lambda icon, item: None, enabled=lambda item: False),
    )

    icon = QtTrayIcon("test", _GREEN, "Spotify Wallpaper Engine", menu=menu)

    assert _wait_for(lambda: _labels(icon) == ["Settings...", "Pause", "-", "Exit"])
    assert not icon._qmenu.actions()[-1].isEnabled()
    assert icon._tray.toolTip() == "Spotify Wallpaper Engine"

    hidden["show"] = True
    icon.update_menu()
    assert _wait_for(lambda: "Re-authenticate" in _labels(icon))


def test_a_click_runs_the_item_off_the_qt_thread(app):
    ran = []
    menu = pystray.Menu(pystray.MenuItem("Pause", lambda icon, item: ran.append((icon, threading.current_thread()))))
    icon = QtTrayIcon("test", _GREEN, menu=menu)
    assert _wait_for(lambda: icon._qmenu.actions())

    icon._qmenu.actions()[0].trigger()

    assert _wait_for(lambda: ran)
    assert ran[0][0] is icon and ran[0][1] is not threading.main_thread()


def test_a_left_click_runs_the_default_item(app):
    opened = []
    menu = pystray.Menu(
        pystray.MenuItem("Pause", lambda icon, item: None),
        pystray.MenuItem("Settings...", lambda icon, item: opened.append(1), default=True),
    )
    icon = QtTrayIcon("test", _GREEN, menu=menu)

    icon._activated(QSystemTrayIcon.ActivationReason.Context)  # right click: the menu, nothing else
    icon._activated(QSystemTrayIcon.ActivationReason.Trigger)

    assert _wait_for(lambda: opened == [1])


def test_the_icon_and_visibility_can_change_from_another_thread(app):
    icon = QtTrayIcon("test", _GREEN)
    red = Image.new("RGBA", (64, 64), (220, 50, 50, 255))

    worker = threading.Thread(target=lambda: (setattr(icon, "icon", red), setattr(icon, "visible", True)))
    worker.start()
    worker.join()

    assert icon.icon is red and icon.visible
    assert _wait_for(lambda: icon._tray.isVisible())


def test_run_blocks_until_stop_like_pystrays_loop(app):
    setups = []
    icon = QtTrayIcon("test", _GREEN)
    loop = threading.Thread(target=lambda: icon.run(setup=lambda i: setups.append(i)))
    loop.start()
    assert _wait_for(lambda: setups == [icon])
    assert loop.is_alive()

    icon.stop()
    loop.join(timeout=2.0)

    assert not loop.is_alive() and not icon.visible


def test_the_pil_icon_keeps_its_pixels(app):
    pixmap = to_qicon(_GREEN).pixmap(64, 64)
    color = pixmap.toImage().pixelColor(32, 32)

    assert (color.red(), color.green(), color.blue()) == (30, 215, 96)


def test_the_tray_app_on_qt_shows_its_menu_and_follows_pause(app):
    state = AppState()
    tray = TrayApp(
        state,
        Settings(),
        on_reauthenticate=lambda: None,
        on_exit=lambda: None,
        on_setup=lambda: None,
        icon_factory=QtTrayIcon,
    )
    icon = tray._icon

    assert isinstance(icon, QtTrayIcon)
    assert _wait_for(lambda: _labels(icon) == ["Settings...", "Pause", "-", "Exit"])

    tray.commands().toggle_pause()

    assert _wait_for(lambda: "Resume" in _labels(icon))
