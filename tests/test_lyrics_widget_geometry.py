from src.lyrics_widget.geometry import (
    clamp_into,
    default_position,
    edges_at,
    hide_for_full_screen,
    restore_rect,
)

PRIMARY = ("DISPLAY1", (0, 0, 1920, 1040))
SECOND = ("DISPLAY2", (1920, 0, 2560, 1400))


def _saved(monitor, rect):
    return {"monitor": monitor, "rect": list(rect)}


def test_a_saved_spot_on_a_monitor_still_there_is_kept():
    assert restore_rect(_saved("DISPLAY2", (2000, 100, 420, 190)), [PRIMARY, SECOND]) == (2000, 100, 420, 190)


def test_nothing_saved_means_the_default_position():
    assert restore_rect(None, [PRIMARY]) is None


def test_a_monitor_that_is_gone_means_the_default_position():
    assert restore_rect(_saved("DISPLAY2", (2000, 100, 420, 190)), [PRIMARY]) is None


def test_a_spot_mostly_off_its_monitor_means_the_default_position():
    # The monitor went from 2560 to 1920 wide (it now ends at x=3840): of the
    # widget's 420 px only 3840-3700 = 140 are left on it, a third.
    shrunk = ("DISPLAY2", (1920, 0, 1920, 1040))
    assert restore_rect(_saved("DISPLAY2", (3700, 100, 420, 190)), [PRIMARY, shrunk]) is None


def test_a_spot_just_under_half_on_its_monitor_means_the_default_position():
    # (1920-1640) x (1040-900) = 280 x 140 of 420 x 190: 49%, under half.
    assert restore_rect(_saved("DISPLAY1", (1640, 900, 420, 190)), [PRIMARY]) is None


def test_a_spot_partly_off_its_monitor_is_pulled_back_inside():
    # (1920-1600) x (1040-880) = 320 x 160 of 420 x 190: 64% still on it, so
    # kept, and moved fully inside the available area.
    assert restore_rect(_saved("DISPLAY1", (1600, 880, 420, 190)), [PRIMARY]) == (1500, 850, 420, 190)


def test_a_widget_bigger_than_its_monitor_is_shrunk_to_fit():
    assert clamp_into((0, 0, 3000, 2000), (0, 0, 1920, 1040)) == (0, 0, 1920, 1040)


def test_a_malformed_save_means_the_default_position():
    assert restore_rect({"monitor": "DISPLAY1"}, [PRIMARY]) is None
    assert restore_rect(_saved("DISPLAY1", (0, 0, 0, 190)), [PRIMARY]) is None


def test_a_press_near_a_border_resizes_and_elsewhere_moves():
    assert edges_at(200, 90, 420, 190) == frozenset()
    assert edges_at(2, 90, 420, 190) == {"left"}
    assert edges_at(415, 90, 420, 190) == {"right"}
    assert edges_at(200, 1, 420, 190) == {"top"}
    assert edges_at(418, 188, 420, 190) == {"right", "bottom"}


def test_only_an_always_on_top_widget_hides_for_a_full_screen_app():
    for busy in (2, 3, 4):  # full-screen app, Direct3D game, presentation
        assert hide_for_full_screen(True, busy) is True
        assert hide_for_full_screen(False, busy) is False
    assert hide_for_full_screen(True, 5) is False  # QUNS_ACCEPTS_NOTIFICATIONS: nothing full screen
    assert hide_for_full_screen(True, None) is False  # could not ask


def test_default_position_is_the_bottom_right_corner_inside_the_margin():
    # 1920x1080 with a 40 px taskbar at the bottom: available height is 1040.
    assert default_position((0, 0, 1920, 1040), (360, 160), margin=24) == (1920 - 360 - 24, 1040 - 160 - 24)


def test_default_position_follows_a_screen_that_does_not_start_at_zero():
    # A primary screen to the right of another one, with a top taskbar.
    assert default_position((1920, 40, 2560, 1400), (360, 160), margin=24) == (1920 + 2560 - 384, 40 + 1400 - 184)


def test_a_window_bigger_than_the_screen_starts_at_its_top_left_not_off_screen():
    assert default_position((100, 50, 300, 120), (360, 160), margin=24) == (100, 50)
