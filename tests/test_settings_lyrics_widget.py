from src.config.settings import Settings


def test_the_widget_starts_off_unlocked_behind_the_windows_at_the_default_spot():
    settings = Settings()
    assert settings.lyrics_widget_enabled is False
    assert settings.lyrics_widget_locked is False
    assert settings.lyrics_widget_on_top is False
    assert settings.lyrics_widget_geometry is None


def test_saved_widget_settings_are_read_back():
    geometry = {"monitor": "DISPLAY1|||", "rect": [10, 20, 420, 190]}
    settings = Settings.from_dict(
        {
            "lyrics_widget_enabled": True,
            "lyrics_widget_locked": True,
            "lyrics_widget_on_top": True,
            "lyrics_widget_geometry": geometry,
        }
    )
    assert (settings.lyrics_widget_enabled, settings.lyrics_widget_locked, settings.lyrics_widget_on_top) == (True, True, True)
    assert settings.lyrics_widget_geometry == geometry


def test_an_invalid_switch_falls_back_to_its_default_alone():
    settings = Settings.from_dict({"lyrics_widget_on_top": "yes", "lyrics_widget_enabled": True})
    assert settings.lyrics_widget_on_top is False
    assert settings.lyrics_widget_enabled is True


def test_an_invalid_geometry_falls_back_to_the_default_spot():
    for bad in (
        "top-left",
        {"rect": [0, 0, 420, 190]},
        {"monitor": "DISPLAY1", "rect": [0, 0, 420]},
        {"monitor": "DISPLAY1", "rect": [0, 0, -5, 190]},
        {"monitor": "DISPLAY1", "rect": [0, 0, True, 190]},
        {"monitor": "DISPLAY1", "rect": [0, 0, 420.5, 190]},
    ):
        assert Settings.from_dict({"lyrics_widget_geometry": bad}).lyrics_widget_geometry is None, bad
