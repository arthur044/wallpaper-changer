import os

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

import pytest
from PySide6.QtWidgets import QApplication

from src.config.settings import Settings
from src.settings_window.style_actions import StyleActions
from src.settings_window.window import SettingsWindow


@pytest.fixture(scope="module")
def app():
    return QApplication.instance() or QApplication([])


def _window(app, **fields):
    settings = Settings(**fields)
    saved, redraws = [], []
    style = StyleActions(settings, saved.append, lambda: redraws.append(1))
    return SettingsWindow(settings, style), settings, saved, redraws


def test_it_shows_the_current_settings(app):
    window, *_ = _window(app, background_style="mesh", art_frame="single", art_glow=True, blur_strength=50)

    assert window._backgrounds["mesh"].isChecked()
    assert window._frames["single"].isChecked()
    assert window._glow.isChecked() and not window._glass.isChecked()
    assert window._blur.currentData() == 50


def test_picking_a_background_saves_and_redraws(app):
    window, settings, saved, redraws = _window(app)

    window._backgrounds["blur"].click()

    assert settings.background_style == "blur"
    assert saved == [settings] and redraws == [1]


def test_the_checkboxes_and_the_frame_and_blur_write_through(app):
    window, settings, saved, _ = _window(app)

    window._glow.click()
    window._glass.click()
    window._smooth.click()
    window._frames["double"].click()
    window._blur.setCurrentIndex(3)
    window._blur.activated.emit(3)

    assert (settings.art_glow, settings.text_card, settings.smooth_transition) == (True, "glass", True)
    assert (settings.art_frame, settings.blur_strength) == ("double", 100)
    assert len(saved) == 5


def test_refresh_does_not_count_as_a_user_change(app):
    window, settings, saved, redraws = _window(app)
    settings.background_style = "mesh"
    settings.art_glow = True

    window.refresh()

    assert window._backgrounds["mesh"].isChecked() and window._glow.isChecked()
    assert saved == [] and redraws == []


def test_a_blur_value_outside_the_presets_stays_selectable(app):
    window, settings, *_ = _window(app, blur_strength=37)

    assert window._blur.currentData() == 37
    settings.blur_strength = 10
    window.refresh()
    assert window._blur.currentData() == 10
