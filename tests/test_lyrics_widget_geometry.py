from src.lyrics_widget.geometry import default_position


def test_default_position_is_the_bottom_right_corner_inside_the_margin():
    # 1920x1080 with a 40 px taskbar at the bottom: available height is 1040.
    assert default_position((0, 0, 1920, 1040), (360, 160), margin=24) == (1920 - 360 - 24, 1040 - 160 - 24)


def test_default_position_follows_a_screen_that_does_not_start_at_zero():
    # A primary screen to the right of another one, with a top taskbar.
    assert default_position((1920, 40, 2560, 1400), (360, 160), margin=24) == (1920 + 2560 - 384, 40 + 1400 - 184)


def test_a_window_bigger_than_the_screen_starts_at_its_top_left_not_off_screen():
    assert default_position((100, 50, 300, 120), (360, 160), margin=24) == (100, 50)
