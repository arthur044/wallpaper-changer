from src.config.settings import Settings


def test_background_options_default_to_the_current_look():
    settings = Settings()

    assert settings.background_style == "solid"
    assert settings.art_glow is False
    assert settings.text_card == "none"


def test_from_dict_keeps_valid_background_options():
    settings = Settings.from_dict({"background_style": "mesh", "art_glow": True, "text_card": "glass"})

    assert settings.background_style == "mesh"
    assert settings.art_glow is True
    assert settings.text_card == "glass"


def test_from_dict_replaces_unknown_background_options_with_defaults():
    settings = Settings.from_dict(
        {"background_style": "plasma", "art_glow": "yes", "text_card": 3, "corner_radius": 40}
    )

    assert settings.background_style == "solid"
    assert settings.art_glow is False
    assert settings.text_card == "none"
    # A bad value only resets itself, never its neighbours.
    assert settings.corner_radius == 40


def test_from_dict_ignores_unknown_keys():
    settings = Settings.from_dict({"not_a_setting": 1, "corner_radius": 8})

    assert settings.corner_radius == 8
