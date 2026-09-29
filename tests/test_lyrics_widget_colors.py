from src.lyrics_widget.colors import (
    DEFAULT_BACKGROUND,
    MIN_CONTRAST,
    TintHolder,
    contrast_with_white,
    widget_background,
)


def test_no_album_color_yet_means_the_default_background():
    assert widget_background(None) == DEFAULT_BACKGROUND


def test_a_dark_album_keeps_its_own_color():
    navy = (20, 40, 110)
    assert contrast_with_white(navy) >= MIN_CONTRAST
    assert widget_background(navy) == navy


def test_a_light_album_is_darkened_until_white_text_reads_well():
    for light in ((250, 240, 200), (255, 255, 255), (240, 150, 30), (30, 190, 150)):
        background = widget_background(light)
        assert contrast_with_white(background) >= MIN_CONTRAST, light
        # Still the album's hue: darker, never another color.
        assert all(b <= a for a, b in zip(light, background)), light


def test_it_is_darkened_only_as_much_as_needed():
    # One 5% step lighter than the result would already fall short of 4.5:1.
    light = (240, 150, 30)
    background = widget_background(light)
    factor = background[0] / light[0]
    one_step_lighter = tuple(int(v * round(factor + 0.05, 2)) for v in light)
    assert contrast_with_white(one_step_lighter) < MIN_CONTRAST


def test_contrast_matches_the_wcag_formula_at_the_extremes():
    assert round(contrast_with_white((0, 0, 0)), 1) == 21.0
    assert round(contrast_with_white((255, 255, 255)), 1) == 1.0


def test_the_holder_hands_the_last_color_over():
    holder = TintHolder()
    assert holder.get() is None
    holder.set((1, 2, 3))
    holder.set((4, 5, 6))
    assert holder.get() == (4, 5, 6)
