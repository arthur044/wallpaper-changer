from src.config.settings import Settings


def test_background_options_default_to_the_current_look():
    settings = Settings()

    assert settings.background_style == "solid"
    assert settings.art_glow is False
    assert settings.text_card == "none"
    assert settings.art_frame == "none"
    assert settings.smooth_transition is False


def test_blurred_art_background_and_frames_are_valid_choices():
    assert Settings.from_dict({"background_style": "blur"}).background_style == "blur"
    assert Settings.from_dict({"art_frame": "single"}).art_frame == "single"
    assert Settings.from_dict({"art_frame": "double"}).art_frame == "double"


def test_the_old_on_off_frame_value_still_reads():
    # config.json files written while the frame was a plain switch.
    assert Settings.from_dict({"art_frame": True}).art_frame == "double"
    assert Settings.from_dict({"art_frame": False}).art_frame == "none"


def test_an_invalid_art_frame_value_resets_to_none():
    assert Settings.from_dict({"art_frame": "triple"}).art_frame == "none"
    assert Settings.from_dict({"art_frame": 1}).art_frame == "none"


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



def test_smooth_transition_is_an_on_off_option():
    assert Settings.from_dict({"smooth_transition": True}).smooth_transition is True
    assert Settings.from_dict({"smooth_transition": "yes"}).smooth_transition is False
