import pytest

from src.config.settings import Settings
from src.settings_window.style_actions import StyleActions


def _actions(**fields):
    settings = Settings(**fields)
    saved, redraws = [], []
    return StyleActions(settings, saved.append, lambda: redraws.append(1)), settings, saved, redraws


@pytest.mark.parametrize(
    "call, field, value",
    [
        (lambda a: a.set_background("mesh"), "background_style", "mesh"),
        (lambda a: a.set_background("blur"), "background_style", "blur"),
        (lambda a: a.set_glow(True), "art_glow", True),
        (lambda a: a.set_blur_strength(60), "blur_strength", 60),
        (lambda a: a.set_frame("single"), "art_frame", "single"),
        (lambda a: a.set_frame("double"), "art_frame", "double"),
        (lambda a: a.set_glass_card(True), "text_card", "glass"),
        (lambda a: a.set_smooth_transition(True), "smooth_transition", True),
    ],
)
def test_a_change_is_saved_and_redraws_now(call, field, value):
    actions, settings, saved, redraws = _actions()

    call(actions)

    assert getattr(settings, field) == value
    assert saved == [settings] and redraws == [1]


@pytest.mark.parametrize(
    "call",
    [
        lambda a: a.set_background("solid"),
        lambda a: a.set_glow(False),
        lambda a: a.set_blur_strength(26),
        lambda a: a.set_frame("none"),
        lambda a: a.set_glass_card(False),
        lambda a: a.set_smooth_transition(False),
    ],
)
def test_picking_the_current_value_again_does_nothing(call):
    actions, _settings, saved, redraws = _actions()

    call(actions)

    assert saved == [] and redraws == []


def test_glass_card_can_be_turned_off_again():
    actions, settings, saved, _ = _actions(text_card="glass")

    actions.set_glass_card(False)

    assert settings.text_card == "none" and len(saved) == 1


def test_an_unknown_background_or_frame_is_ignored():
    actions, settings, saved, _ = _actions()

    actions.set_background("neon")
    actions.set_frame("triple")

    assert (settings.background_style, settings.art_frame) == ("solid", "none") and saved == []
